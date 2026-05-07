/*
 * Copyright 2026, the kio-async project contributors
 * SPDX-License-Identifier: Zlib
 */
package me.example.echoserver

import io.github.andannn.kio.async.fdRawSink
import io.github.andannn.kio.async.fdRawSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import platform.darwin.inet_addr
import platform.posix.AF_INET
import platform.posix.SHUT_WR
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.signal
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit {
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

        println("INFO: server (${serverFd}) is listening to , $HOST_IP, $PORT")

        while (true) {
            memScoped {
                val clientAddr = alloc<sockaddr_in> {}
                val clientAddrLen = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }

                val clientFd =
                    accept(serverFd, clientAddr.ptr.reinterpret(), clientAddrLen.ptr.reinterpret())

                if (clientFd < 0) {
                    println("ERROR: could not accept connection from client: ${errnoMessage()}")
                    return@memScoped
                }

                println("INFO: client (${clientFd}) is accepted")
                val source = fdRawSource(clientFd).buffered()
                val sink = fdRawSink(clientFd).buffered()
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

private fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}

private fun Source.drainSourceBuffer() {
    val buf = ByteArray(1024)
    while (!exhausted()) {
        val read = readAtMostTo(buf)
        if (read == -1) break
    }
}
