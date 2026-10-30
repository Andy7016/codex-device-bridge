package io.github.vvcchh0.devicebridge.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Disposable projected pages; never stores permissions or delivery evidence. */
internal class HistoryCache(private val context: Context) {
    private fun vault(profile: String): EncryptedVaultFile {
        require(Regex("[0-9a-f]{64}").matches(profile))
        return EncryptedVaultFile(context, "history-$profile.v1.enc")
    }

    @Synchronized
    fun load(profile: String, session: String, before: String?): JSONObject? = try {
        val entries = read(profile)
        (0 until entries.length()).map { entries.getJSONObject(it) }.lastOrNull {
            it.optString("sessionKey") == session && it.optString("before") == (before ?: "")
        }?.getJSONObject("page")
    } catch (_: Exception) { null }

    @Synchronized
    fun save(profile: String, session: String, before: String?, page: JSONObject) {
        try {
            if (page.optString("sessionKey") != session || !Regex("[0-9a-f]{64}").matches(page.optString("revision"))) return
            val entries = read(profile)
            val retained = (0 until entries.length()).map { entries.getJSONObject(it) }.filterNot {
                it.optString("sessionKey") == session && it.optString("before") == (before ?: "")
            }.toMutableList()
            retained.add(JSONObject().put("sessionKey", session).put("before", before ?: "")
                .put("page", page).put("verifiedAt", System.currentTimeMillis()))
            while (retained.size > 64) retained.removeAt(0)
            var bytes = JSONObject().put("version", 1).put("pages", JSONArray(retained)).toString().toByteArray(Charsets.UTF_8)
            while (bytes.size > LIMIT && retained.size > 1) {
                bytes.fill(0); retained.removeAt(0)
                bytes = JSONObject().put("version", 1).put("pages", JSONArray(retained)).toString().toByteArray(Charsets.UTF_8)
            }
            try { vault(profile).write(bytes, LIMIT) } finally { bytes.fill(0) }
        } catch (_: Exception) { /* A cache failure must not discard a fresh reply. */ }
    }

    @Synchronized
    fun clear(profile: String) { vault(profile).delete() }

    private fun read(profile: String): JSONArray {
        try {
            val record = vault(profile)
            if (!record.exists()) return JSONArray()
            val bytes = record.read(LIMIT)
            return try {
                val value = JSONObject(bytes.toString(Charsets.UTF_8))
                if (value.optInt("version") != 1) JSONArray() else value.getJSONArray("pages").also {
                    require(it.length() <= 64)
                }
            } finally { bytes.fill(0) }
        } catch (_: Exception) { return JSONArray() }
    }

    companion object { private const val LIMIT = 2 * 1024 * 1024 }
}
