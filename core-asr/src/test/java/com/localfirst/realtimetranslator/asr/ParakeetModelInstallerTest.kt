package com.localfirst.realtimetranslator.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParakeetModelInstallerTest {
    @Test fun unavailableLargeModelDoesNotAdvertiseReadiness() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        ParakeetModelInstaller.directory(c).deleteRecursively()
        assertFalse(ParakeetModelInstaller.isReady(c))
    }

    @Test fun followsOfficialNemoTransducerFilesWithoutOverwritingZipformer() {
        assertEquals("encoder.int8.onnx", ParakeetModelInstaller.ENCODER)
        assertEquals("decoder.int8.onnx", ParakeetModelInstaller.DECODER)
        assertEquals("joiner.int8.onnx", ParakeetModelInstaller.JOINER)
        assertEquals("tokens.txt", ParakeetModelInstaller.TOKENS)
        val c = ApplicationProvider.getApplicationContext<Context>()
        assertNotEquals(EnglishModelInstaller.directory(c), ParakeetModelInstaller.directory(c))
    }
}
