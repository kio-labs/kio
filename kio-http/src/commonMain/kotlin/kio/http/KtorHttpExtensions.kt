package kio.http

import io.ktor.http.Cookie
import io.ktor.http.HeadersBuilder
import io.ktor.http.renderSetCookieHeader

fun HeadersBuilder.appendCookie(cookie: Cookie) {
    append("Set-Cookie", renderSetCookieHeader(cookie))
}