package me.example.httpserver

import kio.async.io.ServerSocket
import kio.async.readString
import kio.http.CallId
import kio.http.CallInterceptor
import kio.http.CoroutineLogger
import kio.http.DefaultExceptionHandler
import kio.http.RequestBodyDecode
import kio.http.RespondedBodyEncode
import kio.http.currentCallId
import kio.http.currentLogger
import kio.http.currentLoggingBackend
import kio.http.trace
import kio.http.get
import kio.http.httpServer
import kio.http.newLogger
import kio.http.post
import kio.http.respondText
import kio.http.route
import kio.http.sendBinary
import kio.http.sendText
import kio.http.websocket
import kio.websocket.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

private fun IndexedCallIdInterceptor(): CallInterceptor {
    var index: Int = 0
    return CallId {
        (index++).toString()
    }
}

private fun LoggerInterceptor(): CallInterceptor = CallInterceptor { context, proceed ->
    val newLogger = currentLoggingBackend().newLogger("Call", mapOf("CallId" to currentCallId()))
    withContext(CoroutineLogger(newLogger)) {
        proceed(context)
    }
}

suspend fun simpleServer(
    serverSocket: ServerSocket,
) {
    httpServer(serverSocket) {
        inject(
            IndexedCallIdInterceptor(),
            LoggerInterceptor(),
            DefaultExceptionHandler,
            RequestBodyDecode,
            RespondedBodyEncode
        ) {
            get("/") { call ->
                call.respondText("hello back; foo value is [${call.parameters["foo"]}]")
            }

            get("/a/{bbb...}") { call ->
                call.respondText("hello back; tail card is ${call.parameters.getAll("bbb")}")
            }

            get("/pre-{id}-suf") { call ->
                call.respondText("hello back; route value is [${call.parameters["id"]}]")
            }

            post("/hello") { call ->
                call.respondText("hello back ${call.requestBody?.readString()}")
            }

            route("/p/foo/bar") {
                post("/hello/a/b") { call ->
                    val requestBody = call.requestBody?.readString() ?: "no data"
                    call.respondText(
                        "hello back. requestBody $requestBody \n",
                        configTrailers = { this["A"] = "B" }
                    )
                }
            }

            post("/chunk") { call ->
                call.respondText("response: ${call.requestBody?.readString()}")
            }

            websocket("/ws") { webSocket ->
                for (msg in webSocket.incoming) {
                    when (msg) {
                        is WebSocketEvent.Binary -> webSocket.sendBinary(buffer = msg.buffer)
                        is WebSocketEvent.Text -> webSocket.sendText(msg.text)
                    }
                }
            }
        }
    }
}