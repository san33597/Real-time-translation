package com.localfirst.realtimetranslator.service

import com.localfirst.realtimetranslator.model.SessionRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one resource set. Start/stop cannot run concurrently or release resources twice. */
interface SessionResources {
    suspend fun open(request: SessionRequest)
    suspend fun close()
}

class TranslationSession(private val resources: SessionResources) {
    private val mutex = Mutex()
    private var active: SessionRequest? = null

    suspend fun start(request: SessionRequest): Boolean = mutex.withLock {
        if (active != null) return@withLock false
        try {
            resources.open(request)
            active = request
            true
        } catch (failure: Exception) {
            // Even a partially opened adapter must be closed.
            try {
                resources.close()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    suspend fun stop(expectedSessionId: String? = null): Boolean = mutex.withLock {
        val current = active ?: return@withLock false
        if (expectedSessionId != null && expectedSessionId != current.identity.sessionId) {
            return@withLock false
        }
        // Keep ownership until cleanup finishes; prevents a simultaneous start.
        try {
            resources.close()
        } finally {
            active = null
        }
        true
    }
}

/** R1 ONLY. This fake never accesses audio, ASR, translation or any user content. */
class NoOpSessionResources : SessionResources {
    override suspend fun open(request: SessionRequest) = Unit
    override suspend fun close() = Unit
}
