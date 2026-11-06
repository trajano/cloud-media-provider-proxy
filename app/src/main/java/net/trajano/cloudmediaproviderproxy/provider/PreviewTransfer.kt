package net.trajano.cloudmediaproviderproxy.provider

import java.io.InputStream
import java.io.OutputStream

/** Stop abandoned transfers between reads and writes, including after a blocking read. */
internal fun copyWithCancellation(input: InputStream, output: OutputStream, checkCanceled: () -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkCanceled()
        val count = input.read(buffer)
        checkCanceled()
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
