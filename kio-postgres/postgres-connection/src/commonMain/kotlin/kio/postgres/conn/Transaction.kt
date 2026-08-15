package kio.postgres.conn

import kotlinx.coroutines.flow.Flow

suspend inline fun <reified T> PgConnection.transaction(crossinline block: suspend (TransactionScope) -> T): T {
    this as InternalPgConnection
    return withLock {
        exec("begin", lock = false)

        val scope = TransactionScopeImpl(this)
        try {
            val ret = block(scope)
            exec("commit", lock = false)
            ret
        } catch (t: Throwable) {
            exec("rollback", lock = false)
            throw t
        }
    }
}

interface TransactionScope {
    suspend fun exec(sql: String): String
    suspend fun exec(sql: String, parameters: PgParameterScope.() -> Unit): String
}

inline fun <reified R> TransactionScope.query(sql: String, crossinline parameters: PgParameterScope.() -> Unit): Flow<R> =
    (this as TransactionScopeImpl).conn.query(sql, parameters, lock = false)

inline fun <reified R> TransactionScope.query(sql: String): Flow<R> =
    (this as TransactionScopeImpl).conn.query(sql, Unit, lock = false)

inline fun <reified P, reified R> TransactionScope.query(sql: String, params: P): Flow<R> =
    (this as TransactionScopeImpl).conn.query(sql, params, lock = false)

inline fun <reified R> TransactionScope.query(stmt: PgStatement, crossinline parameters: PgParameterScope.() -> Unit): Flow<R> =
    (this as TransactionScopeImpl).conn.query(stmt, parameters, lock = false)

inline fun <reified P, reified R> TransactionScope.query(stmt: PgStatement, params: P): Flow<R> =
    (this as TransactionScopeImpl).conn.query(stmt, params, lock = false)

suspend inline fun <reified P> TransactionScope.exec(sql: String, params: P): String =
    (this as TransactionScopeImpl).conn.exec(sql, params, lock = false)

suspend inline fun <reified P> TransactionScope.exec(stmt: PgStatement, params: P): String =
    (this as TransactionScopeImpl).conn.exec(stmt, params, lock = false)

suspend fun TransactionScope.exec(stmt: PgStatement, parameters: PgParameterScope.() -> Unit): String =
    (this as TransactionScopeImpl).conn.exec(stmt, parameters, lock = false)

@PublishedApi
internal class TransactionScopeImpl(val conn: InternalPgConnection) : TransactionScope {
    override suspend fun exec(sql: String): String =
        conn.exec(sql, lock = false)

    override suspend fun exec(sql: String, parameters: PgParameterScope.() -> Unit): String =
        conn.exec(sql, parameters, lock = false)
}