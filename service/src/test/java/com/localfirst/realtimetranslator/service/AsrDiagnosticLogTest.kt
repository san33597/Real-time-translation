package com.localfirst.realtimetranslator.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AsrDiagnosticLogTest {
    @Test fun savesAllTextStagesAndSupportsDeletion() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AsrDiagnosticLog.clear(ctx)
        val log = requireNotNull(AsrDiagnosticLog.start(ctx))
        log.record("parakeet_window", "Here you are. Thank you.",
            mapOf("index" to 3, "stitchedText" to "Thank you.", "removedWords" to 3))
        log.record("asr_update", "Thank you.", mapOf("isFinal" to true))
        log.record("caption_state", "Thank you.")
        log.finish()
        val events = requireNotNull(AsrDiagnosticLog.latest(ctx)).readLines().map { JSONObject(it) }
        assertEquals("parakeet_window", events.first().getString("stage"))
        assertEquals("Here you are. Thank you.", events.first().getString("text"))
        assertEquals("Thank you.", events.first().getJSONObject("metadata").getString("stitchedText"))
        assertTrue(events.any { it.getString("stage") == "asr_update" })
        assertTrue(events.any { it.getString("stage") == "caption_state" })
        assertTrue(AsrDiagnosticLog.clear(ctx) > 0)
        assertNull(AsrDiagnosticLog.latest(ctx))
    }
}
