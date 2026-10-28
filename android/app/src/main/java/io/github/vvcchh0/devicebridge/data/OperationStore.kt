package io.github.vvcchh0.devicebridge.data

import android.content.Context
import io.github.vvcchh0.devicebridge.PendingOperation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Encrypted per-profile outbox. Interrupted sends become unknown and are never retried here. */
class OperationStore(context: Context) {
    private val applicationContext = context.applicationContext

    fun load(profileId: String): List<PendingOperation> = synchronized(STORE_LOCK) {
        val vault = vaultFor(profileId)
        if (!vault.exists()) return@synchronized emptyList()
        val stored = readStored(vault)
        val recovered = stored.map { operation ->
            if (operation.state == "sending") operation.copy(state = "unknown") else operation
        }
        if (recovered != stored) writeStored(vault, recovered)
        recovered
    }

    /** Merges caller updates with existing unresolved entries; terminal history is bounded to 32 recent records. */
    fun save(profileId: String, operations: List<PendingOperation>) = synchronized(STORE_LOCK) {
        val vault = vaultFor(profileId)
        val existing = if (vault.exists()) readStored(vault) else emptyList()
        val recoveredExisting = existing.map { operation ->
            if (operation.state == "sending") operation.copy(state = "unknown") else operation
        }
        val requested = validateOperations(operations)
        val requestedById = requested.associateBy { it.id }
        val existingById = recoveredExisting.associateBy { it.id }

        for (old in recoveredExisting) {
            val update = requestedById[old.id]
            if (update != null && !sameImmutableFields(old, update)) {
                throw OperationStoreException("operation_identity_conflict")
            }
        }

        val mergedById = LinkedHashMap<String, PendingOperation>()
        for (old in recoveredExisting) {
            if (!isTerminal(old.state)) mergedById[old.id] = requestedById[old.id] ?: old
        }
        for (operation in requested) mergedById[operation.id] = operation

        val unresolved = mergedById.values.filterNot { isTerminal(it.state) }
        if (unresolved.size > MAX_OPERATIONS) throw OperationStoreException("operation_limit_reached")

        val requiredIds = unresolved.mapTo(HashSet<String>()) { it.id }
        for (operation in requested) {
            if (operation.id !in existingById) requiredIds += operation.id
        }
        if (requiredIds.size > MAX_OPERATIONS) throw OperationStoreException("operation_limit_reached")

        val required = mergedById.values.filter { it.id in requiredIds }
        val slotsForHistory = MAX_OPERATIONS - required.size
        val recentTerminal = mergedById.values
            .filter { isTerminal(it.state) && it.id !in requiredIds }
            .sortedWith(OPERATION_ORDER)
            .takeLast(minOf(MAX_RECENT_TERMINAL, slotsForHistory))
        val result = (required + recentTerminal).sortedWith(OPERATION_ORDER)
        if (result.size > MAX_OPERATIONS) throw OperationStoreException("operation_limit_reached")
        writeStored(vault, result)
    }

    /** Deletes only the selected profile's operation cache. */
    fun clear(profileId: String) = synchronized(STORE_LOCK) {
        try {
            vaultFor(profileId).delete()
        } catch (e: VaultException) {
            throw OperationStoreException(e.code)
        }
    }

    private fun readStored(vault: EncryptedVaultFile): List<PendingOperation> {
        val bytes = try {
            vault.read(MAX_STORE_BYTES)
        } catch (e: VaultException) {
            throw OperationStoreException(e.code)
        }
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val json = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            return parseOperations(json)
        } catch (e: OperationStoreException) {
            throw e
        } catch (_: Exception) {
            throw OperationStoreException("operation_store_corrupt")
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseOperations(json: String): List<PendingOperation> {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw OperationStoreException("operation_store_corrupt")
        }
        if (root.length() != 2 || root.optInt("schemaVersion", -1) != 1 || !root.has("operations")) {
            throw OperationStoreException("operation_store_corrupt")
        }
        val array = root.opt("operations") as? JSONArray ?: throw OperationStoreException("operation_store_corrupt")
        if (array.length() > MAX_OPERATIONS) throw OperationStoreException("operation_store_corrupt")
        val result = ArrayList<PendingOperation>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val entry = array.opt(index) as? JSONObject ?: throw OperationStoreException("operation_store_corrupt")
            if (entry.length() != OPERATION_KEYS.size || entry.keys().asSequence().toSet() != OPERATION_KEYS) {
                throw OperationStoreException("operation_store_corrupt")
            }
            val operation = parseOperation(entry)
            if (!ids.add(operation.id)) throw OperationStoreException("operation_store_corrupt")
            result += operation
        }
        if (result.count { !isTerminal(it.state) } > MAX_OPERATIONS) {
            throw OperationStoreException("operation_store_corrupt")
        }
        return result
    }

    private fun parseOperation(entry: JSONObject): PendingOperation {
        val id = boundedString(entry, "id", 1, MAX_ID_CHARS)
        val session = boundedString(entry, "session", 1, MAX_SESSION_CHARS)
        val text = boundedString(entry, "text", 0, MAX_TEXT_CHARS)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_UTF8_BYTES) {
            throw OperationStoreException("operation_store_corrupt")
        }
        val state = boundedString(entry, "state", 1, MAX_STATE_CHARS)
        if (state !in ALLOWED_STATES) throw OperationStoreException("operation_store_corrupt")
        val turnStatus = boundedString(entry, "turnStatus", 0, MAX_TURN_STATUS_CHARS)
        val createdAt = exactLong(entry.opt("createdAt")) ?: throw OperationStoreException("operation_store_corrupt")
        if (createdAt <= 0) throw OperationStoreException("operation_store_corrupt")
        return PendingOperation(id, session, text, state, turnStatus, createdAt)
    }

    private fun validateOperations(operations: List<PendingOperation>): List<PendingOperation> {
        if (operations.size > MAX_OPERATIONS + MAX_RECENT_TERMINAL) {
            throw OperationStoreException("operation_limit_reached")
        }
        val ids = HashSet<String>()
        for (operation in operations) {
            if (operation.id.length !in 1..MAX_ID_CHARS ||
                operation.session.length !in 1..MAX_SESSION_CHARS ||
                operation.text.length > MAX_TEXT_CHARS ||
                operation.text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_UTF8_BYTES ||
                operation.turnStatus.length > MAX_TURN_STATUS_CHARS ||
                operation.state !in ALLOWED_STATES ||
                operation.createdAt <= 0 ||
                !ids.add(operation.id)
            ) {
                throw OperationStoreException("invalid_operation")
            }
        }
        return operations.toList()
    }

    private fun writeStored(vault: EncryptedVaultFile, operations: List<PendingOperation>) {
        val root = JSONObject().put("schemaVersion", 1)
        val array = JSONArray()
        for (operation in operations) {
            array.put(
                JSONObject()
                    .put("id", operation.id)
                    .put("session", operation.session)
                    .put("text", operation.text)
                    .put("state", operation.state)
                    .put("turnStatus", operation.turnStatus)
                    .put("createdAt", operation.createdAt),
            )
        }
        val bytes = root.put("operations", array).toString().toByteArray(Charsets.UTF_8)
        try {
            vault.write(bytes, MAX_STORE_BYTES)
        } catch (e: VaultException) {
            throw OperationStoreException(e.code)
        } finally {
            bytes.fill(0)
        }
    }

    private fun vaultFor(profileId: String): EncryptedVaultFile {
        if (!PROFILE_ID_PATTERN.matches(profileId)) throw OperationStoreException("invalid_profile_id")
        return try {
            EncryptedVaultFile(applicationContext, "operations-$profileId.v1.enc")
        } catch (e: VaultException) {
            throw OperationStoreException(e.code)
        }
    }

    private fun boundedString(root: JSONObject, name: String, min: Int, max: Int): String {
        val value = root.opt(name)
        if (value !is String || value.length !in min..max) throw OperationStoreException("operation_store_corrupt")
        return value
    }

    private fun exactLong(value: Any?): Long? {
        if (value !is Number) return null
        return value.toString().toLongOrNull()
    }

    private fun sameImmutableFields(first: PendingOperation, second: PendingOperation): Boolean =
        first.id == second.id && first.session == second.session && first.text == second.text && first.createdAt == second.createdAt

    private fun isTerminal(state: String): Boolean = state == "completed" || state == "cancelled"

    private companion object {
        const val MAX_OPERATIONS = 200
        const val MAX_RECENT_TERMINAL = 32
        const val MAX_STORE_BYTES = 2 * 1024 * 1024
        const val MAX_ID_CHARS = 128
        const val MAX_SESSION_CHARS = 128
        const val MAX_TEXT_CHARS = 8192
        const val MAX_TEXT_UTF8_BYTES = 8192
        const val MAX_STATE_CHARS = 32
        const val MAX_TURN_STATUS_CHARS = 128
        val STORE_LOCK = Any()
        val PROFILE_ID_PATTERN = Regex("[0-9a-f]{64}")
        val ALLOWED_STATES = setOf("accepted", "dispatching", "sending", "unknown", "completed", "cancelled")
        val OPERATION_KEYS = setOf("id", "session", "text", "state", "turnStatus", "createdAt")
        val OPERATION_ORDER = compareBy<PendingOperation> { it.createdAt }.thenBy { it.id.lowercase(Locale.ROOT) }
    }
}

class OperationStoreException(val code: String) : Exception(code)
