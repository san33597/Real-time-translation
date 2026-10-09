package com.localfirst.realtimetranslator

import com.localfirst.realtimetranslator.model.AudioSource
import com.localfirst.realtimetranslator.service.RealtimeTranslationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Check the *actual* PendingIntents shown to the user, not only service stop state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureNotificationTest {
    private fun service(): RealtimeTranslationService =
        Robolectric.buildService(RealtimeTranslationService::class.java).create().get()

    @Test fun tappingBodyOpensMainActivityAndDedicatedStopActionTargetsCurrentSession() {
        val notification = service().buildCaptureNotification("capture-one", AudioSource.SYSTEM)

        val open = notification.contentIntent
        assertNotNull("The notification body must be clickable", open)
        assertTrue(open!!.isActivity)
        val openIntent = shadowOf(open).savedIntent
        assertEquals(MainActivity::class.java.name, openIntent.component?.className)

        assertEquals(1, notification.actions.size)
        val stop = notification.actions[0]
        assertEquals("停止采集", stop.title.toString())
        assertTrue("Notification Stop must start the existing foreground service",
            stop.actionIntent.isForegroundService)
        val stopIntent = shadowOf(stop.actionIntent).savedIntent
        assertEquals(RealtimeTranslationService.ACTION_STOP, stopIntent.action)
        assertEquals("capture-one",
            stopIntent.getStringExtra(RealtimeTranslationService.EXTRA_SESSION_ID))
        assertEquals(RealtimeTranslationService::class.java.name, stopIntent.component?.className)
        assertTrue("Stop must be an explicit action distinct from opening the app",
            openIntent.component != stopIntent.component)
        assertFalse(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun oldNotificationActionNeverReusesNewSessionToken() {
        val service = service()
        val old = service.buildCaptureNotification("old", AudioSource.SYSTEM).actions[0].actionIntent
        val current = service.buildCaptureNotification("new", AudioSource.SYSTEM).actions[0].actionIntent
        assertNotEquals(old, current)
        assertEquals("old",
            shadowOf(old).savedIntent.getStringExtra(RealtimeTranslationService.EXTRA_SESSION_ID))
        assertEquals("new",
            shadowOf(current).savedIntent.getStringExtra(RealtimeTranslationService.EXTRA_SESSION_ID))
    }
}
