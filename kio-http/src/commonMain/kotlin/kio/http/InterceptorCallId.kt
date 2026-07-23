package kio.http

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

fun CallId(generator: CallContext.() -> String): CallInterceptor = CallInterceptor { context, proceed ->
    withContext(CoroutineCallId(generator(context))) {
        proceed(context)
    }
}

suspend fun currentCallId(): String? {
    return currentCoroutineContext()[CoroutineCallId]?.callId
}

data class CoroutineCallId(
    val callId: String
) : AbstractCoroutineContextElement(CoroutineCallId) {
    companion object Key : CoroutineContext.Key<CoroutineCallId>

    override fun toString(): String = "CoroutineCallId(${callId})"
}