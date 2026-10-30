package io.github.vvcchh0.devicebridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted, atomic app-private storage shared by the profile and operation stores. */
internal class EncryptedVaultFile(context: Context, private val fileName: String) {
    private val baseFile: File
    private val atomicFile: AtomicFile

    init {
        require(FILE_NAME_PATTERN.matches(fileName)) { "invalid_vault_filename" }
        val directory = File(context.applicationContext.noBackupFilesDir, VAULT_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw VaultException("vault_directory_unavailable")
        }
        val canonicalDirectory = try {
            directory.canonicalFile
        } catch (_: IOException) {
            throw VaultException("vault_directory_unavailable")
        }
        baseFile = File(canonicalDirectory, fileName)
        val canonicalFile = try {
            baseFile.canonicalFile
        } catch (_: IOException) {
            throw VaultException("vault_path_unavailable")
        }
        if (canonicalFile.parentFile != canonicalDirectory) {
            throw VaultException("vault_path_unavailable")
        }
        atomicFile = AtomicFile(canonicalFile)
    }

    fun exists(): Boolean = synchronized(lockFor(baseFile)) {
        existsLocked()
    }

    fun read(maxPlaintextBytes: Int): ByteArray = synchronized(lockFor(baseFile)) {
        require(maxPlaintextBytes in 1..MAX_ALLOWED_PLAINTEXT) { "invalid_vault_limit" }
        if (!existsLocked()) throw VaultException("vault_record_missing")

        try {
            val encrypted = atomicFile.openRead().use { readBounded(it, maxPlaintextBytes + ENVELOPE_OVERHEAD) }
            if (encrypted.size < ENVELOPE_OVERHEAD || !encrypted.copyOfRange(0, HEADER_SIZE).contentEquals(HEADER)) {
                throw VaultException("vault_record_corrupt")
            }
            val nonce = encrypted.copyOfRange(HEADER_SIZE, HEADER_SIZE + GCM_NONCE_BYTES)
            val ciphertext = encrypted.copyOfRange(HEADER_SIZE + GCM_NONCE_BYTES, encrypted.size)
            if (ciphertext.size < GCM_TAG_BYTES || ciphertext.size > maxPlaintextBytes + GCM_TAG_BYTES) {
                throw VaultException("vault_record_corrupt")
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, VaultKey.get(), GCMParameterSpec(128, nonce))
                cipher.updateAAD(fileName.toByteArray(Charsets.UTF_8))
                cipher.doFinal(ciphertext).also {
                    if (it.size > maxPlaintextBytes) {
                        it.fill(0)
                        throw VaultException("vault_record_corrupt")
                    }
                }
            } catch (e: VaultException) {
                throw e
            } catch (_: Exception) {
                throw VaultException("vault_record_authentication_failed")
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
                encrypted.fill(0)
            }
        } catch (e: VaultException) {
            throw e
        } catch (_: Exception) {
            throw VaultException("vault_read_failed")
        }
    }

    fun write(plaintext: ByteArray, maxPlaintextBytes: Int) = synchronized(lockFor(baseFile)) {
        require(maxPlaintextBytes in 1..MAX_ALLOWED_PLAINTEXT) { "invalid_vault_limit" }
        if (plaintext.size > maxPlaintextBytes) throw VaultException("vault_record_too_large")

        val nonce: ByteArray
        val encrypted: ByteArray
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, VaultKey.get())
            cipher.updateAAD(fileName.toByteArray(Charsets.UTF_8))
            nonce = cipher.iv
            if (nonce.size != GCM_NONCE_BYTES) throw VaultException("vault_encryption_failed")
            encrypted = cipher.doFinal(plaintext)
        } catch (e: VaultException) {
            throw e
        } catch (_: Exception) {
            throw VaultException("vault_encryption_failed")
        }

        val stream = try {
            atomicFile.startWrite()
        } catch (_: Exception) {
            nonce.fill(0)
            encrypted.fill(0)
            throw VaultException("vault_write_failed")
        }
        try {
            stream.write(HEADER)
            stream.write(nonce)
            stream.write(encrypted)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (_: Exception) {
            try {
                atomicFile.failWrite(stream)
            } catch (_: Exception) {
                // Preserve the original failure classification; AtomicFile has attempted recovery.
            }
            throw VaultException("vault_write_failed")
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
        }
    }

    fun delete() = synchronized(lockFor(baseFile)) {
        try {
            atomicFile.delete()
        } catch (_: Exception) {
            throw VaultException("vault_delete_failed")
        }
    }

    private fun existsLocked(): Boolean =
        baseFile.exists() || File(baseFile.path + ".bak").exists() || File(baseFile.path + ".new").exists()

    private fun readBounded(input: FileInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(4096)
        var total = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw VaultException("vault_record_too_large")
                output.write(buffer, 0, count)
            }
        } finally {
            buffer.fill(0)
        }
        return output.toByteArray()
    }

    private companion object {
        const val VAULT_DIRECTORY = "devicebridge-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HEADER_SIZE = 8
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val ENVELOPE_OVERHEAD = HEADER_SIZE + GCM_NONCE_BYTES + GCM_TAG_BYTES
        const val MAX_ALLOWED_PLAINTEXT = 2 * 1024 * 1024
        val HEADER = byteArrayOf(0x44, 0x42, 0x56, 0x4c, 0x54, 0x30, 0x30, 0x31) // DBVLT001
        val FILE_NAME_PATTERN = Regex("(?:profile|pending-pairing|(?:operations|history)-[0-9a-f]{64})\\.v1\\.enc")
        val FILE_LOCKS = ConcurrentHashMap<String, Any>()

        fun lockFor(file: File): Any = FILE_LOCKS.getOrPut(file.path) { Any() }
    }
}

internal class VaultException(val code: String) : Exception(code)

private object VaultKey {
    private const val KEY_ALIAS = "io.github.vvcchh0.devicebridge.vault.aes.v1"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private val lock = Any()

    fun get(): SecretKey = synchronized(lock) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
        } catch (_: Exception) {
            throw VaultException("vault_key_unavailable")
        }
    }

    private fun generateKey(): SecretKey {
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (_: Exception) {
            throw VaultException("vault_key_unavailable")
        }
    }
}
