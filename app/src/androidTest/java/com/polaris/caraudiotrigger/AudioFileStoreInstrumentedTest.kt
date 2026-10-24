package com.polaris.caraudiotrigger

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polaris.caraudiotrigger.data.AppSettings
import com.polaris.caraudiotrigger.data.AudioFileStore
import com.polaris.caraudiotrigger.data.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AudioFileStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AudioFileStore(context)

    @Test
    fun corruptImportedAudioIsRejected() {
        val corrupt = File(context.cacheDir, "corrupt-audio.mp3")
        corrupt.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            assertThrows(IOException::class.java) { store.import(Uri.fromFile(corrupt)) }
        } finally {
            corrupt.delete()
        }
    }

    @Test
    fun missingCustomAudioResolvesToBuiltInChime() {
        val resolved = store.resolve(
            AppSettings(
                audioSource = AudioSource.CUSTOM,
                customAudioPath = File(context.filesDir, "does-not-exist.mp3").absolutePath,
            ),
        )
        assertTrue(resolved.isFile)
        assertTrue(resolved.length() > 44L)
        assertEquals("built_in_chime.wav", resolved.name)
    }
}
