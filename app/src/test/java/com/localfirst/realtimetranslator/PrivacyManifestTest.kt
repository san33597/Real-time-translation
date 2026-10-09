package com.localfirst.realtimetranslator

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivacyManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun installedAppRequestsNoBroadStorageOrLocationAccess() {
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
    }

    @Test fun applicationDoesNotAllowBackup() {
        val flags = context.applicationInfo.flags
        assertTrue(flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
    }

    @Test fun serviceIsNotExported() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
        val foreground = info.services.orEmpty().first {
            it.name == "com.localfirst.realtimetranslator.service.RealtimeTranslationService"
        }
        assertFalse(foreground.exported)
    }
}
