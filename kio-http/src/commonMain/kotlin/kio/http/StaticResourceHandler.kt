package kio.http

import io.ktor.http.HttpMethod

fun Route.staticResource(uri: String) {
    registerCall(HttpMethod.Get, uri) {

    }
}
