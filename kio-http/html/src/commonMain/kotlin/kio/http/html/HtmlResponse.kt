package kio.http.html

import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import kio.http.CallContext
import kio.http.respondText
import kotlinx.html.TagConsumer
import kotlinx.html.stream.createHTML

suspend fun CallContext.respondHtml(
    status: HttpStatusCode? = null,
    configHeaders: HeadersBuilder.() -> Unit = {},
    configTrailers: HeadersBuilder.() -> Unit = {},
    block: TagConsumer<*>.() -> Unit,
) {
    val html = createHTML()
    html.block()
    val htmlStr = html.finalize()

    respondText(
        htmlStr,
        ContentType.Text.Html,
        status,
        configHeaders,
        configTrailers
    )
}
