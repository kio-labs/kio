package kio.async.io

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.milliseconds

class ConnectionTest {

    @Test
    fun inMemoryConnectionTest() = runTest {
        val conn = openInMemoryPipe().buffered()
        val readJob = launch {
            conn.source.readInt()
        }
        conn.sink.writeInt(1)
        conn.sink.flush()
        readJob.join()
    }

    @Test
    fun inMemoryConnectionCloseTest() = runTest {
        val conn = openInMemoryPipe().buffered()
        val readJob = launch {
            assertFails {
                conn.source.readInt()
            }
        }
        delay(1.milliseconds)
        conn.sink.close()
        readJob.join()
    }
}