/*
 * Copyright 2026, the kio-async project contributors
 * SPDX-License-Identifier: Zlib
 */
package me.example.asyncechoserver

import io.github.andannn.kio.async.asyncFdRawSink
import io.github.andannn.kio.async.asyncFdRawSource
import kotlinx.cinterop.*
import kotlinx.coroutines.launch
import kotlinx.io.AsyncSource
import kotlinx.io.IOException
import kotlinx.io.buffered
import io.github.andannn.kio.async.runPollEventLoop
import io.github.andannn.kio.async.awaitReadIo
import platform.darwin.inet_addr
import platform.posix.*

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop {
    signal(SIGPIPE, SIG_IGN)
    memScoped {
        val serverFd = socket(AF_INET, SOCK_STREAM, 0)
        if (serverFd < 0) {
            println("ERROR: could not create server socket: ${errnoMessage()}")
            return@memScoped
        }

        val yes = alloc<IntVar> { value = 1 }
        if (setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<IntVar>().toUInt()) < 0
        ) {
            println("ERROR: could not configure server socket: strerror(errno)")
            return@memScoped
        }

        val serverAddr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = htons(PORT.convert())
            sin_addr.s_addr = inet_addr(HOST_IP)
        }

        if (bind(serverFd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
            println("ERROR: could not bind server socket: ${errnoMessage()}")
            return@memScoped
        }

        if (listen(serverFd, 69) < 0) {
            println("ERROR: could not listen to server socket: ${errnoMessage()}")
            return@memScoped
        }

        if (setNonBlocking(serverFd) < 0) {
            fprintf(
                stderr,
                "ERROR: could not set server socket non-blocking: %s\n",
                strerror(errno)
            );
            return@memScoped
        }

        println("INFO: server (${serverFd}) is listening to , $HOST_IP, $PORT")

        while (true) {
            memScoped {
                val clientAddr = alloc<sockaddr_in> {}
                val clientAddrLen = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }

                awaitReadIo(serverFd)
                val clientFd =
                    accept(serverFd, clientAddr.ptr.reinterpret(), clientAddrLen.ptr.reinterpret())

                if (clientFd < 0) {
                    println("ERROR: could not accept connection from client: ${errnoMessage()}")
                    return@memScoped
                }

                if (setNonBlocking(clientFd) < 0) {
                    fprintf(stderr, "ERROR: could not set client socket non-blocking: %s\n", strerror(errno));
                    return@memScoped
                }
                println("INFO: client (${clientFd}) is accepted")

                launch {
                    val source = asyncFdRawSource(clientFd).buffered()
                    val sink = asyncFdRawSink(clientFd).buffered()
                    val buffer = ByteArray(1024)
                    try {
                        while (true) {
                            val read = source.readAtMostTo(buffer)
                            if (read < 0) {
                                // EOF
                                break
                            }
                            println("$read bytes read.")
                            sink.write(buffer, 0, read)
                            sink.flush()
                        }
                    } catch (t: IOException) {
                        println("ioException: $t, ${t.printStackTrace()}")
                        throw t
                    } finally {
                        shutdown(clientFd, SHUT_WR)

                        try {
                            source.drainSourceBuffer()
                        } catch (t: Throwable) {
                            // ignore exception because we are closing
                            println("exception when drainSourceBuffer: $t")
                        } finally {
                            close(clientFd)
                        }

                        close(clientFd)

                        println("client closed $clientFd")
                    }
                }
            }
        }
    }
}

private fun setNonBlocking(sockfd: Int): Int {
    val flags = fcntl(sockfd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(sockfd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

private fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}

private suspend fun AsyncSource.drainSourceBuffer() {
    val buf = ByteArray(1024)
    while (!exhausted()) {
        val read = readAtMostTo(buf)
        if (read == -1) break
    }
}
