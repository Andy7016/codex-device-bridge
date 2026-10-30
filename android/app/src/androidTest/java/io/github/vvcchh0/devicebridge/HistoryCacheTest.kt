package io.github.vvcchh0.devicebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vvcchh0.devicebridge.data.HistoryCache
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryCacheTest {
    private fun identity(): String = MessageDigest.getInstance("SHA-256")
        .digest(UUID.randomUUID().toString().toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun encryptedPagesAreIsolatedAndCorruptionIsDisposable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = HistoryCache(context)
        val profile = identity(); val other = identity(); val session = identity()
        val message = "synthetic cache text never stored in plaintext"
        val page = JSONObject().put("sessionKey", session).put("revision", "a".repeat(64))
            .put("messages", JSONArray().put(JSONObject().put("id", "same-id").put("role", "assistant")
                .put("text", message).put("status", "completed")))
            .put("runtimeStatus", "unknown").put("nextBefore", JSONObject.NULL)
        val file = File(context.noBackupFilesDir, "devicebridge-v1/history-$profile.v1.enc")
        try {
            cache.save(profile, session, null, page)
            assertEquals(message, cache.load(profile, session, null)!!.getJSONArray("messages").getJSONObject(0).getString("text"))
            assertNull(cache.load(other, session, null))
            assertNull(cache.load(profile, identity(), null))
            assertNull(cache.load(profile, session, "different-page"))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(message))
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertNull(cache.load(profile, session, null))
            cache.save(profile, session, null, page)
            assertNotNull(cache.load(profile, session, null))
            cache.clear(profile)
            assertFalse(file.exists())
        } finally { cache.clear(profile); cache.clear(other) }
    }
}
