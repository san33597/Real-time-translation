package com.localfirst.realtimetranslator.service

import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.model.SessionIdentity
import com.localfirst.realtimetranslator.model.SessionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSessionTest {
    private val one = SessionRequest(SessionIdentity("one"), AudioSource.SYSTEM)
    private val two = SessionRequest(SessionIdentity("two"), AudioSource.MICROPHONE)

    private class CountingResources : SessionResources {
        var opens = 0
        var closes = 0
        var fail = false
        override suspend fun open(request: SessionRequest) {
            opens++
            if (fail) throw IllegalStateException("fake failure")
        }
        override suspend fun close() { closes++ }
    }

    @Test fun duplicateStartAndStopReleaseOnlyOnce() = runBlocking {
        val resources = CountingResources()
        val session = TranslationSession(resources)
        assertTrue(session.start(one))
        assertFalse(session.start(two))
        assertFalse(session.stop("stale"))
        assertTrue(session.stop("one"))
        assertFalse(session.stop("one"))
        assertEquals(1, resources.opens)
        assertEquals(1, resources.closes)
    }

    @Test fun failedOpenCleansUpAndAllowsRetry() = runBlocking {
        val resources = CountingResources()
        val session = TranslationSession(resources)
        resources.fail = true
        try {
            session.start(one)
            throw AssertionError("expected failure")
        } catch (_: IllegalStateException) {}
        assertEquals(1, resources.closes)
        resources.fail = false
        assertTrue(session.start(two))
        assertTrue(session.stop("two"))
        assertEquals(2, resources.closes)
    }

    @Test fun simultaneousStopsCannotDoubleRelease() = runBlocking {
        val resources = CountingResources()
        val session = TranslationSession(resources)
        session.start(one)
        val stopped = (1..8).map { async { session.stop("one") } }.awaitAll()
        assertEquals(1, stopped.count { it })
        assertEquals(1, resources.closes)
    }
}
