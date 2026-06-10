package com.orator.feature.audiobooks.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoverStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `saves bytes and returns a readable path`() {
        val store = CoverStore(context)
        val bytes = byteArrayOf(1, 2, 3)

        val path = store.save("book1", bytes)!!

        assertArrayEquals(bytes, File(path).readBytes())
    }

    @Test
    fun `null or empty bytes yield no path`() {
        val store = CoverStore(context)
        assertNull(store.save("book1", null))
        assertNull(store.save("book1", ByteArray(0)))
    }
}
