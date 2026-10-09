package com.localfirst.realtimetranslator.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnglishModelInstallerTest {
    @Test fun absentModelIsNotReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        EnglishModelInstaller.directory(context).deleteRecursively()
        assertFalse(EnglishModelInstaller.isReady(context))
    }

    @Test fun exactEnglishStreamingModelSelected() {
        assertTrue(EnglishModelInstaller.MODEL_NAME.endsWith("-en-2023-06-26"))
        assertTrue(EnglishModelInstaller.ENCODER.endsWith(".int8.onnx"))
        assertTrue(EnglishModelInstaller.JOINER.endsWith(".int8.onnx"))
    }
}
