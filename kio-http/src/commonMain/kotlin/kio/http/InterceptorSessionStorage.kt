package kio.http

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

fun SessionStorage(
    sessionStorage: SessionStorage
): CallInterceptor = CallInterceptor { context, proceed ->
    withContext(CoroutineSessionStorage(sessionStorage)) {
        proceed(context)
    }
}

interface SessionStorage {
    /**
     * Writes a session [value] for [id].
     */
    suspend fun write(id: String, value: String)

    /**
     * Invalidates a session with the [id] identifier.
     * This method prevents a session [id] from being accessible after this call.
     */
    suspend fun invalidate(id: String)

    /**
     * Reads a session with the [id] identifier.
     */
    suspend fun read(id: String): String?
}

suspend fun currentSessionStorage(): SessionStorage? {
    return currentCoroutineContext()[CoroutineSessionStorage]?.sessionStorage
}

private data class CoroutineSessionStorage(
    val sessionStorage: SessionStorage
) : AbstractCoroutineContextElement(CoroutineSessionStorage) {
    companion object Key : CoroutineContext.Key<CoroutineSessionStorage>

    override fun toString(): String = "CoroutineSessionStorage(${sessionStorage})"
}