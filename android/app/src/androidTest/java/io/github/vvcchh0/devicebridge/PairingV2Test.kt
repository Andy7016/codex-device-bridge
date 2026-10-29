package io.github.vvcchh0.devicebridge

import android.content.Context
import android.content.ContextWrapper
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.AndroidDeviceIdentity
import io.github.vvcchh0.devicebridge.data.BridgeFailure
import io.github.vvcchh0.devicebridge.data.BridgeTransport
import io.github.vvcchh0.devicebridge.data.ConnectionProfile
import io.github.vvcchh0.devicebridge.data.DirectEndpoint
import io.github.vvcchh0.devicebridge.data.InnerTlsConfig
import io.github.vvcchh0.devicebridge.data.PairingCrypto
import io.github.vvcchh0.devicebridge.data.PairingException
import io.github.vvcchh0.devicebridge.data.PairingInvite
import io.github.vvcchh0.devicebridge.data.PairingPhase
import io.github.vvcchh0.devicebridge.data.PairingRepository
import io.github.vvcchh0.devicebridge.data.PairingRpc
import io.github.vvcchh0.devicebridge.data.PairingStore
import io.github.vvcchh0.devicebridge.data.PairingQr
import io.github.vvcchh0.devicebridge.data.ProfileStore
import io.github.vvcchh0.devicebridge.data.RelayEndpoint
import io.github.vvcchh0.devicebridge.data.RouteExchange
import io.github.vvcchh0.devicebridge.data.TransportRoute
import io.github.vvcchh0.devicebridge.data.parseX509Pem
import io.github.vvcchh0.devicebridge.data.sha256Hex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@RunWith(AndroidJUnit4::class)
class PairingV2Test {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private fun fixture(): PairingFixture {
        val assets = instrumentation.context.assets
        assumeTrue("synthetic pairing lab fixture was not injected", assets.list("")?.contains("pairing-lab.json") == true)
        val json = JSONObject(assets.open("pairing-lab.json").bufferedReader().use { it.readText() })
        val qr = json.getString("qrText")
        val invite = PairingQr.parse(qr)
        assertEquals(invite.hostId, json.getString("hostId"))
        return PairingFixture(qr, invite, json.getString("operationId"), json.getString("message"))
    }

    @Test fun inviteParserChecksHostIdentityAndRejectsMalformedText() {
        val fixture = fixture()
        assertEquals(PairingQr.expectedServerName(fixture.invite.hostId), fixture.invite.serverName)
        assertEquals(64, fixture.invite.hostId.length)
        try {
            PairingQr.parse("dbpair:not-a-valid-invite")
            fail("malformed QR accepted")
        } catch (expected: PairingException) {
            assertEquals("invalid_pairing_qr", expected.code)
        }
    }

    @Test fun androidKeystoreIdentitySignsProofAndTlsKeyManagerIsProfileScoped() {
        val identities = AndroidDeviceIdentity()
        val first = identities.create()
        val second = identities.create()
        try {
            val certificate = parseX509Pem(first.certificatePem)
            val proof = "synthetic-pairing-proof".toByteArray(Charsets.UTF_8)
            val signature = java.util.Base64.getDecoder().decode(identities.sign(first.alias, proof))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update(proof)
            assertTrue(verifier.verify(signature))
            assertNull("Keystore private keys must not be exportable", KeyStore.getInstance("AndroidKeyStore").let { store ->
                store.load(null)
                store.getKey(first.alias, null).encoded
            })

            val managers = identities.keyManagers(first.alias, first.certificateSha256)
            val clientManager = managers.filterIsInstance<javax.net.ssl.X509KeyManager>().single()
            val tlsKey = requireNotNull(clientManager.getPrivateKey(first.alias))
            val rawSigner = Signature.getInstance("NONEwithECDSA")
            rawSigner.initSign(tlsKey)
            rawSigner.update(java.security.MessageDigest.getInstance("SHA-256").digest(proof))
            val tlsSignature = rawSigner.sign()
            verifier.initVerify(certificate.publicKey)
            verifier.update(proof)
            assertTrue("TLS prehashed signing must be authorized", verifier.verify(tlsSignature))
            assertArrayEquals(arrayOf(first.alias), clientManager.getClientAliases("EC", emptyArray()))
            assertFalse(clientManager.getClientAliases("EC", emptyArray()).orEmpty().contains(second.alias))
            assertEquals(first.alias, (clientManager as javax.net.ssl.X509ExtendedKeyManager)
                .chooseEngineClientAlias(arrayOf("EC"), emptyArray(), null))
            proof.fill(0)
            signature.fill(0)
        } finally {
            identities.delete(first.alias)
            identities.delete(second.alias)
        }
    }

    @Test fun lostBeginAndProveAcknowledgementsReuseEncryptedRequestIdAndKeystoreAlias() {
        val fixture = fixture()
        val (context, root) = isolatedContext()
        val identities = AndroidDeviceIdentity()
        val rpc = LoseFirstBeginAck(fixture.invite)
        val repository = PairingRepository(context, rpc, identities)
        val store = PairingStore(context)
        try {
            try {
                repository.startOrResume(fixture.qr)
                fail("first begin acknowledgement should be lost")
            } catch (expected: BridgeFailure) {
                assertEquals("transport_timeout", expected.code)
            }
            val beforeRetry = requireNotNull(store.load())
            assertNull(beforeRetry.challenge)
            try {
                repository.startOrResume(fixture.qr)
                fail("first prove acknowledgement should be lost")
            } catch (expected: BridgeFailure) {
                assertEquals("transport_timeout", expected.code)
            }
            val proofBeforeRetry = requireNotNull(store.load())
            assertNotNull(proofBeforeRetry.challenge)
            assertNotNull(proofBeforeRetry.signatureBase64)
            val progress = repository.checkStatus()
            val afterRetry = requireNotNull(store.load())
            assertEquals(PairingPhase.WAITING_FOR_APPROVAL, progress.phase)
            assertEquals(beforeRetry.requestId, afterRetry.requestId)
            assertEquals(beforeRetry.keyAlias, afterRetry.keyAlias)
            assertEquals(2, rpc.beginCount)
            assertEquals(2, rpc.proveCount)
            assertEquals(rpc.firstRequestId, rpc.secondRequestId)
            assertEquals(rpc.firstCertificatePem, rpc.secondCertificatePem)
            assertEquals(64, afterRetry.challenge?.length)
            assertNotNull(afterRetry.signatureBase64)
            assertTrue(afterRetry.proofSubmitted)
        } finally {
            val alias = try { store.load()?.keyAlias } catch (_: Exception) { null }
            try { store.clear() } catch (_: Exception) { }
            alias?.let { try { identities.delete(it) } catch (_: Exception) { } }
            removeIsolated(root)
        }
    }

    @Test fun pairingTransportDoesNotPresentPhoneCertificateBeforeApproval() {
        val fixture = fixture()
        val (context, root) = isolatedContext()
        val identity = AndroidDeviceIdentity()
        val exchange = NoClientIdentityPairingExchange(fixture.invite)
        val repository = PairingRepository(context, BridgeTransport(exchange), identity)
        try {
            val progress = repository.startOrResume(fixture.qr)
            assertEquals(PairingPhase.WAITING_FOR_APPROVAL, progress.phase)
            assertEquals(listOf("pair.begin", "pair.prove"), exchange.methods)
            assertTrue(exchange.callsHadNoClientKeys.all { it })
        } finally {
            val alias = try { PairingStore(context).load()?.keyAlias } catch (_: Exception) { null }
            try { PairingStore(context).clear() } catch (_: Exception) { }
            alias?.let { try { identity.delete(it) } catch (_: Exception) { } }
            removeIsolated(root)
        }
    }

    @Test fun v2TransportSwitchesFromDirectToRelayOnlyBeforeRequestWrite() {
        val fixture = fixture()
        val identity = AndroidDeviceIdentity()
        val phone = identity.create()
        try {
            val direct = DirectEndpoint("192.168.1.222", 12345)
            val profile = fixture.invite.asProfile(phone.alias, phone.certificateSha256, direct)
            val routes = mutableListOf<TransportRoute>()
            val exchange = object : RouteExchange {
                override fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject {
                    routes += route
                    if (routes.size == 1) throw BridgeFailure("direct_route_unavailable", deliveryUnknown = false, requestWritten = false)
                    return JSONObject().put("sessions", org.json.JSONArray())
                }
            }
            val result = BridgeTransport(exchange).request(profile, "catalog", JSONObject())
            assertEquals(0, result.getJSONArray("sessions").length())
            assertEquals(2, routes.size)
            assertTrue(routes[0] is TransportRoute.Direct)
            assertTrue(routes[1] is TransportRoute.Relay)

            val writtenRoutes = mutableListOf<TransportRoute>()
            val noReplay = object : RouteExchange {
                override fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject {
                    writtenRoutes += route
                    throw BridgeFailure("transport_timeout", deliveryUnknown = method == "submit", requestWritten = true)
                }
            }
            try {
                BridgeTransport(noReplay).request(profile, "submit", JSONObject()
                    .put("thread", "synthetic-session")
                    .put("id", "synthetic-operation")
                    .put("payload", JSONObject().put("text", "synthetic message")))
                fail("written request should fail")
            } catch (expected: BridgeFailure) {
                assertEquals("transport_timeout", expected.code)
                assertTrue(expected.deliveryUnknown)
            }
            assertEquals(1, writtenRoutes.size)
        } finally {
            identity.delete(phone.alias)
        }
    }

    @Test fun relayPairingAndUnknownSubmitReconcileTheSameOperationId() {
        val fixture = fixture()
        val (context, root) = isolatedContext()
        val repository = BridgeRepository(context)
        var pairedProfile: ConnectionProfile? = null
        var alias: String? = null
        try {
            val pairing = repository.pairing.startOrResume(fixture.qr)
            val deadline = System.currentTimeMillis() + 55_000
            var progress = pairing
            while (progress.phase == PairingPhase.WAITING_FOR_APPROVAL && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                progress = repository.pairing.checkStatus()
            }
            assertEquals("synthetic lab must approve only this test identity", PairingPhase.APPROVED, progress.phase)
            val profile = requireNotNull(progress.profile)
            pairedProfile = profile
            alias = profile.clientKeyAlias

            val session = repository.catalog(profile).single { it.canSend }
            assertTrue(session.freeText || fixture.message in session.allowedMessages)
            val submitted = repository.send(profile, session, fixture.message, fixture.operationId)
            assertEquals(fixture.operationId, submitted.id)
            assertEquals("unknown", submitted.state)

            val restarted = BridgeRepository(context)
            val saved = restarted.outbox.load(profile.id).single { it.id == fixture.operationId }
            assertEquals("unknown", saved.state)
            val reconciled = restarted.reconcile(profile, fixture.operationId)
            assertEquals(fixture.operationId, reconciled.id)
            assertEquals("completed", reconciled.state)
            val existing = restarted.send(profile, session, fixture.message, fixture.operationId)
            assertEquals(fixture.operationId, existing.id)
            assertEquals("completed", existing.state)
            assertEquals(1, restarted.outbox.load(profile.id).count { it.text == fixture.message })
        } finally {
            try { pairedProfile?.let { repository.outbox.clear(it.id) } } catch (_: Exception) { }
            try { repository.profiles.forget() } catch (_: Exception) { }
            if (alias == null) alias = try { PairingStore(context).load()?.keyAlias } catch (_: Exception) { null }
            try { PairingStore(context).clear() } catch (_: Exception) { }
            alias?.let { try { AndroidDeviceIdentity().delete(it) } catch (_: Exception) { } }
            removeIsolated(root)
        }
    }

    @Test fun approvedProfileCannotRetainDemoDataWhenItsOutboxIsCorrupt() {
        val fixture = fixture()
        val identities = AndroidDeviceIdentity()
        val identity = identities.create()
        val profile = fixture.invite.asProfile(identity.alias, identity.certificateSha256, DirectEndpoint("127.0.0.1", 1))
        val operations = io.github.vvcchh0.devicebridge.data.OperationStore(targetContext)
        val store = ViewModelStore()
        try {
            operations.save(profile.id, listOf(PendingOperation("synthetic-corrupt", "codex", "synthetic text", "unknown")))
            val encrypted = File(targetContext.noBackupFilesDir, "devicebridge-v1/operations-${profile.id}.v1.enc")
            val data = encrypted.readBytes()
            data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
            encrypted.writeBytes(data)
            lateinit var model: BridgeViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(targetContext.applicationContext as Application))[BridgeViewModel::class.java]
            }
            val deadline = System.currentTimeMillis()+10_000
            while (model.ui.value.busy && System.currentTimeMillis() < deadline) Thread.sleep(20)
            instrumentation.runOnMainSync { model.showDemo() }
            var rejected = false
            runBlocking { withContext(Dispatchers.Main) {
                try {
                    model.applyPairingProgress(io.github.vvcchh0.devicebridge.data.PairingProgress(
                        PairingPhase.APPROVED, profile.displayName, profile = profile))
                } catch (_: Exception) { rejected = true }
            } }
            assertTrue("corrupted new outbox must be rejected", rejected)
            assertEquals(profile.displayName, model.ui.value.profileName)
            assertFalse(model.ui.value.demo)
            assertEquals(ConnectionStatus.DISCONNECTED, model.ui.value.connection)
            assertTrue(model.ui.value.messages.isEmpty())
            assertTrue(model.ui.value.operations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            operations.clear(profile.id)
            identities.delete(identity.alias)
        }
    }

    @Test fun discardAfterProfileCommitDoesNotDeleteTheRetainedIdentity() {
        val fixture = fixture()
        val (context, root) = isolatedContext()
        val identities = AndroidDeviceIdentity()
        val identity = identities.create()
        try {
            PairingStore(context).save(io.github.vvcchh0.devicebridge.data.PendingPairing(
                fixture.qr, identity.alias, UUID.randomUUID().toString(), "Synthetic phone",
                identity.certificatePem, identity.certificateSha256))
            ProfileStore(context).completePairing(fixture.invite, identity.alias, identity.certificateSha256)
            ProfileStore(context).forget() // A committed alias may still own unresolved operations.
            PairingRepository(context).discardPending()
            assertNull(PairingStore(context).load())
            assertEquals(identity.certificateSha256, identities.load(identity.alias).certificateSha256)
        } finally {
            identities.delete(identity.alias)
            removeIsolated(root)
        }
    }

    @Test fun ipv6LoopbackIsAcceptedButUnspecifiedAndLinkLocalAreRejected() {
        assertTrue(java.net.InetAddress.getByName(PairingQr.validateDirectHost("::1")).isLoopbackAddress)
        for (host in listOf("::", "169.254.1.2", "fe80::1")) {
            var denied = false
            try { PairingQr.validateDirectHost(host) } catch (_: PairingException) { denied = true }
            assertTrue("unexpected direct endpoint accepted", denied)
        }
    }

    @Test fun slowTrickleCannotExtendTheAbsoluteFrameDeadline() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val worker = Thread {
            try { server.accept().use { peer -> repeat(30) { peer.getOutputStream().write(123); Thread.sleep(40) } } }
            catch (_: Exception) { }
        }
        worker.start()
        val socket = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            var stopped = false
            try {
                io.github.vvcchh0.devicebridge.data.SocketDeadline(socket, 180).use { deadline ->
                    while (true) { deadline.armRead(); if (socket.getInputStream().read() < 0) break }
                }
            } catch (_: java.io.IOException) { stopped = true }
            assertTrue(stopped)
            assertTrue(android.os.SystemClock.elapsedRealtime()-started < 1500)
        } finally { socket.close(); server.close(); worker.join(1500) }
    }

    @Test fun qrImageDecodesTheSameInvitation() {
        val fixture = fixture()
        val file = File(targetContext.cacheDir, "pairing-image-${UUID.randomUUID()}.png")
        try {
            instrumentation.context.assets.open("pairing-lab.png").use { input -> file.outputStream().use { input.copyTo(it) } }
            val decoded = io.github.vvcchh0.devicebridge.data.PairingQrImageDecoder.decode(targetContext.contentResolver, android.net.Uri.fromFile(file))
            assertTrue("decoded QR payload differs", decoded == fixture.qr)
        } finally { file.delete() }
    }

    private fun isolatedContext(): Pair<Context, File> {
        val base = targetContext.cacheDir.canonicalFile
        val directory = File(base, "devicebridge-pairing-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        check(directory.canonicalFile.parentFile == base)
        val context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        return context to directory
    }

    private fun removeIsolated(directory: File) {
        val base = targetContext.cacheDir.canonicalFile
        val resolved = directory.canonicalFile
        check(resolved.parentFile == base && resolved.name.startsWith("devicebridge-pairing-test-"))
        resolved.deleteRecursively()
    }

    private data class PairingFixture(val qr: String, val invite: PairingInvite, val operationId: String, val message: String)

    private class LoseFirstBeginAck(private val expectedInvite: PairingInvite) : PairingRpc {
        var beginCount = 0
        var proveCount = 0
        var firstRequestId: String? = null
        var secondRequestId: String? = null
        var firstCertificatePem: String? = null
        var secondCertificatePem: String? = null
        private var expectedCode = ""
        private val challenge = "a".repeat(64)

        override fun requestPairing(invite: PairingInvite, method: String, params: JSONObject): JSONObject = when (method) {
            "pair.begin" -> {
                assertEquals(expectedInvite.inviteId, invite.inviteId)
                beginCount++
                val requestId = params.getString("requestId")
                val certificatePem = params.getString("clientCertificatePem")
                if (beginCount == 1) {
                    firstRequestId = requestId
                    firstCertificatePem = certificatePem
                    throw BridgeFailure("transport_timeout", deliveryUnknown = false, requestWritten = true)
                }
                secondRequestId = requestId
                secondCertificatePem = certificatePem
                val fingerprint = sha256Hex(parseX509Pem(certificatePem).encoded)
                val proof = PairingCrypto.proofBytes(invite.inviteId, requestId, invite.hostId, challenge, fingerprint)
                expectedCode = try { PairingCrypto.confirmationCode(proof) } finally { proof.fill(0) }
                JSONObject().put("state", "challenge").put("challenge", challenge)
                    .put("confirmationCode", expectedCode).put("expiresAt", invite.expiresAt)
            }
            "pair.prove" -> {
                proveCount++
                assertEquals(firstRequestId, params.getString("requestId"))
                if (proveCount == 1) throw BridgeFailure("transport_timeout", deliveryUnknown = false, requestWritten = false)
                JSONObject().put("state", "pending").put("confirmationCode", expectedCode).put("expiresAt", invite.expiresAt)
            }
            else -> error("unexpected pairing method")
        }
    }

    private class NoClientIdentityPairingExchange(private val invite: PairingInvite) : RouteExchange {
        val methods = mutableListOf<String>()
        val callsHadNoClientKeys = mutableListOf<Boolean>()
        private var confirmationCode = ""
        private val challenge = "b".repeat(64)

        override fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject {
            assertEquals(invite.hostId, hostId)
            callsHadNoClientKeys += tls.clientKeyManagers.isEmpty()
            val envelope = JSONObject(frame.toString(Charsets.UTF_8))
            assertEquals(method, envelope.getString("method"))
            val params = envelope.getJSONObject("params")
            methods += method
            return when (method) {
                "pair.begin" -> {
                    val certificatePem = params.getString("clientCertificatePem")
                    val fingerprint = sha256Hex(parseX509Pem(certificatePem).encoded)
                    val proof = PairingCrypto.proofBytes(invite.inviteId, params.getString("requestId"), invite.hostId, challenge, fingerprint)
                    confirmationCode = try { PairingCrypto.confirmationCode(proof) } finally { proof.fill(0) }
                    JSONObject().put("state", "challenge").put("challenge", challenge)
                        .put("confirmationCode", confirmationCode).put("expiresAt", invite.expiresAt)
                }
                "pair.prove" -> JSONObject().put("state", "pending")
                    .put("confirmationCode", confirmationCode).put("expiresAt", invite.expiresAt)
                else -> error("unexpected method $method")
            }
        }
    }

    private fun PairingInvite.asProfile(alias: String, fingerprint: String, direct: DirectEndpoint): ConnectionProfile {
        val idBytes = "$hostId\n$fingerprint".toByteArray(Charsets.UTF_8)
        val id = try { sha256Hex(idBytes) } finally { idBytes.fill(0) }
        return ConnectionProfile(
            id = id,
            displayName = displayName,
            schemaVersion = 2,
            host = relay.host,
            port = relay.port,
            serverCertificatePem = serverCertificatePem,
            serverCertificateSha256 = serverCertificateSha256,
            hostId = hostId,
            serverName = serverName,
            relay = relay,
            directEndpoints = listOf(direct),
            clientKeyAlias = alias,
            clientCertificateSha256 = fingerprint,
        )
    }
}
