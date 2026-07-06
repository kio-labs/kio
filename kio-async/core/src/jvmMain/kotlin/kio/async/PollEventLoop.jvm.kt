package kio.async

import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectableChannel

data class SelectionKeyWrapper constructor(
    val channel: SelectableChannel,
)

internal actual fun nowMillis(): Long {
    return System.nanoTime() / 1_000_000L
}

internal actual fun wakeupPipe(): WakeupPipe {
    val pipe = Pipe.open()

    val readChannel = pipe.source()
    val writeChannel = pipe.sink()

    readChannel.configureBlocking(false)
    writeChannel.configureBlocking(false)

    return object : WakeupPipe {
        override val wakeupReadFD = SelectionKeyWrapper(readChannel)

        override fun drainWakeup() {
            val buffer = ByteBuffer.allocate(64)

            while (true) {
                buffer.clear()

                val n = readChannel.read(buffer)

                if (n > 0) {
                    continue
                }

                break
            }
        }

        override fun wakeup() {
            val buffer = ByteBuffer.allocate(1)
            buffer.put(1)
            buffer.flip()

            while (buffer.hasRemaining()) {
                val n = writeChannel.write(buffer)

                if (n > 0) {
                    return
                }

                if (n == 0) {
                    return
                }
            }
        }

        override fun close() {
            readChannel.close()
            writeChannel.close()
        }
    }
}