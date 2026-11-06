package net.trajano.cloudmediaproviderproxy.provider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewTransferTest {
    /** Preserve every byte across buffer boundaries. */
    @Test
    fun copyPreservesContentAcrossMultipleBuffers() {
        val content = ByteArray(30_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        copyWithCancellation(ByteArrayInputStream(content), output) {}
        assertArrayEquals(content, output.toByteArray())
    }

    /** Avoid reading data for a request that was already abandoned. */
    @Test
    fun canceledRequestDoesNotReadOrWrite() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        assertThrows(InterruptedIOException::class.java) {
            copyWithCancellation(input, output) { throw InterruptedIOException("canceled") }
        }
        assertEquals(3, input.available())
        assertEquals(0, output.size())
    }

    /** Recheck cancellation when a remote read completes before writing its result. */
    @Test
    fun cancellationDuringReadStopsBeforeWriting() {
        var canceled = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                canceled = true
                return count
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(InterruptedIOException::class.java) {
            copyWithCancellation(input, output) {
                if (canceled) throw InterruptedIOException("canceled")
            }
        }
        assertEquals(0, output.size())
    }
}
