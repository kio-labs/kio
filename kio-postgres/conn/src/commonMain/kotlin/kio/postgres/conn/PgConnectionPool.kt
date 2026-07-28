package kio.postgres.conn

import kotlinx.coroutines.sync.Semaphore

fun PgConnectionPool(
    capacity: Int,
    newConnection: suspend () -> PgConnection
): PgConnectionPool {
    return InternalPgConnectionPool(capacity, newConnection)
}

interface PgConnectionPool {
    suspend fun acquire(): PgConnection

    fun recycle(connection: PgConnection)

    suspend fun close()
}

suspend fun <T> PgConnectionPool.useConnection(block: suspend (PgConnection) -> T): T {
    this as InternalPgConnectionPool
    val connection = acquire()
    return try {
        block(connection)
    } finally {
        recycle(connection)
    }
}

@PublishedApi
internal class InternalPgConnectionPool(
    private val capacity: Int,
    private val newConnection: suspend () -> PgConnection
): PgConnectionPool {
    var isClosed = false
        private set

    private var size = 0
    private val connectionPermits = Semaphore(permits = capacity)
    private val availableConnections = ArrayDeque<PgConnection>(capacity)
    private val connections = arrayOfNulls<PgConnection>(capacity)

    override suspend fun acquire(): PgConnection {
        connectionPermits.acquire()

        try {
            if (isClosed) {
                throw IllegalStateException("Connection pool is closed")
            }

            if (availableConnections.isNotEmpty()) {
                return availableConnections.removeLast()
            }
            check(size < capacity)

            val newConnection = newConnection()
            if (isClosed) {
                // Pool was closed in-between opening a new connection, close it and throw.
                newConnection.close()
                throw IllegalStateException("Connection pool is closed")
            }
            connections[size++] = newConnection
            return newConnection
        } catch (t: Throwable) {
            connectionPermits.release()
            throw t
        }
    }

    override fun recycle(connection: PgConnection) {
        availableConnections.addLast(connection)
        connectionPermits.release()
    }

    override suspend fun close() {
        isClosed = true
        connections.forEach { it?.close() }
    }
}
