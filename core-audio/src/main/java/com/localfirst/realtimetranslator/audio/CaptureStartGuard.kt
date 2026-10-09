package com.localfirst.realtimetranslator.audio

/**
 * One-use MediaProjection consent may only be consumed after a foreground-service start.
 * The Activity supplies new consent for every new system capture session.
 */
class CaptureStartGuard {
    private var foregroundReady = false
    private var grantConsumed = false
    fun foregroundStarted() { foregroundReady = true }
    fun consumeProjectionGrant() {
        check(foregroundReady) { "Foreground service must start before projection consumption" }
        check(!grantConsumed) { "Projection grant is one-use" }
        grantConsumed = true
    }
}
