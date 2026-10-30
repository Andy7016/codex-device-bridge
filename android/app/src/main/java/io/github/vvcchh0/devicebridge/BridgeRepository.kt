package io.github.vvcchh0.devicebridge

import android.content.Context
import io.github.vvcchh0.devicebridge.data.BridgeFailure
import io.github.vvcchh0.devicebridge.data.BridgeTransport
import io.github.vvcchh0.devicebridge.data.ConnectionProfile
import io.github.vvcchh0.devicebridge.data.OperationStore
import io.github.vvcchh0.devicebridge.data.PairingRepository
import io.github.vvcchh0.devicebridge.data.ProfileStore
import org.json.JSONObject
import java.util.UUID

data class HistoryPage(val messages: List<ChatMessage>, val nextBefore: String?, val runtimeStatus: String)

class BridgeRepository(context: Context, private val transport: BridgeTransport = BridgeTransport()) {
    val profiles = ProfileStore(context)
    val outbox = OperationStore(context)
    val pairing = PairingRepository(context, transport)
    init { transport.relayTicketSink = { id, ticket -> profiles.refreshRelayTicket(id, ticket) } }
    fun cancelTransport() = transport.cancelActive()

    fun catalog(profile: ConnectionProfile): List<SessionInfo> {
        val list = transport.request(profile, "catalog", JSONObject()).getJSONArray("sessions")
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            val allowed = item.optJSONArray("allowedMessages")
            SessionInfo(item.getString("label"), item.getString("title"), item.getString("kind"), item.optBoolean("canSend"),
                allowed?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), item.optBoolean("freeText", false))
        }.filter { it.kind == "codex" }
    }

    fun history(profile: ConnectionProfile, label: String, before: String? = null): HistoryPage {
        val result = transport.request(profile, "history", JSONObject().put("thread", label)
            .put("before", before ?: JSONObject.NULL).put("limit", 6))
        val list = result.getJSONArray("messages")
        val messages = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            ChatMessage(item.getString("id"), item.getString("role"), item.getString("text"), item.optString("status"))
        }
        return HistoryPage(messages, if (result.isNull("nextBefore")) null else result.getString("nextBefore"), result.optString("runtimeStatus", "unknown"))
    }

    @Synchronized
    fun send(profile: ConnectionProfile, session: SessionInfo, text: String, operationId: String = UUID.randomUUID().toString()): PendingOperation {
        if (!session.canSend || text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 4096 ||
            !session.freeText && text !in session.allowedMessages) throw BridgeFailure("message_not_authorized", false)
        val previous = outbox.load(profile.id)
        previous.firstOrNull { it.id == operationId }?.let {
            if (it.session != session.label || it.text != text) throw BridgeFailure("operation_identity_conflict", true)
            return it
        }
        if (previous.any { it.session == session.label && it.text == text && it.state in setOf("sending", "unknown", "accepted", "dispatching") })
            throw BridgeFailure("unresolved_operation_exists", true)
        val attempted = PendingOperation(operationId, session.label, text, "sending")
        outbox.save(profile.id, previous + attempted)
        val updated = try {
            merge(attempted, transport.request(profile, "submit", JSONObject().put("thread", session.label)
                .put("id", operationId).put("payload", JSONObject().put("text", text))))
        } catch (_: Exception) { attempted.copy(state = "unknown") }
        replace(profile, updated)
        return updated
    }

    @Synchronized
    fun reconcile(profile: ConnectionProfile, operationId: String): PendingOperation {
        val operation = outbox.load(profile.id).firstOrNull { it.id == operationId }
            ?: throw BridgeFailure("operation_not_found", false)
        var updated = merge(operation, transport.request(profile, "status", JSONObject().put("id", operationId)))
        if (updated.state in setOf("unknown", "completed"))
            updated = merge(updated, transport.request(profile, "reconcile", JSONObject().put("id", operationId)))
        replace(profile, updated)
        return updated
    }

    private fun replace(profile: ConnectionProfile, operation: PendingOperation) {
        outbox.save(profile.id, outbox.load(profile.id).filterNot { it.id == operation.id } + operation)
    }

    private fun merge(old: PendingOperation, result: JSONObject): PendingOperation {
        if (result.getString("id") != old.id) throw BridgeFailure("unexpected_operation_identity", true)
        val state = result.getString("state")
        if (state !in setOf("accepted", "dispatching", "unknown", "completed", "cancelled")) throw BridgeFailure("unsupported_operation_state", true)
        val evidence = result.optJSONObject("result")
        if (state == "completed" && evidence?.optString("delivery") != "observed") throw BridgeFailure("missing_delivery_evidence", true)
        return old.copy(state = state, turnStatus = evidence?.optString("turnStatus") ?: old.turnStatus)
    }
}
