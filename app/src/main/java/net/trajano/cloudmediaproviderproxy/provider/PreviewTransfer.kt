package net.trajano.cloudmediaproviderproxy.provider

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** Stop abandoned transfers between reads and writes, including after a blocking read. */
internal fun copyWithCancellation(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = Long.MAX_VALUE,
    checkCanceled: () -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var written = 0L
    while (true) {
        checkCanceled()
        val count = input.read(buffer)
        checkCanceled()
        if (count < 0) return
        if (count > maxBytes - written) throw TransferLimitExceededException()
        output.write(buffer, 0, count)
        written += count
    }
}

internal class TransferLimitExceededException : IOException("Original exceeds the cache size limit")
