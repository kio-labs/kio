package kio.http

import io.ktor.http.HttpMethod

fun RouteScope.staticResource(uri: String) {
    registerCall(HttpMethod.Get, uri) {

    }
}
