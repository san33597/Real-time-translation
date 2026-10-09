package com.localfirst.realtimetranslator.audio

import org.junit.Test

class CaptureStartGuardTest {
    @Test(expected = IllegalStateException::class)
    fun cannotUseGrantBeforeForeground() { CaptureStartGuard().consumeProjectionGrant() }

    @Test(expected = IllegalStateException::class)
    fun cannotReuseProjectionGrant() {
        CaptureStartGuard().apply { foregroundStarted(); consumeProjectionGrant(); consumeProjectionGrant() }
    }

    @Test fun canConsumeGrantOnceAfterForeground() {
        CaptureStartGuard().apply { foregroundStarted(); consumeProjectionGrant() }
    }
}
