/*
 * Copyright 2026, the KWebSocket project contributors
 * SPDX-License-Identifier: Zlib
 */
package me.example.postegreclient

import kio.async.runPollEventLoop
import kio.postegre.types.PgInt4
import kio.postgres.conn.openPgConnection
import kio.postgres.conn.pipeline
import kio.postgres.conn.query
import kio.postgres.conn.receiveAsList
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.err
import platform.posix.signal
import kotlin.uuid.ExperimentalUuidApi

const val HOST_IP = "127.0.0.1"
const val PORT = 55432

@OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
//fun main(): Unit = runPollEventLoop {
//    signal(SIGPIPE, SIG_IGN)
//
//    val conn = openPgConnection(HOST_IP, PORT, user = "postgres")
////    val stmt = conn.prepare<Params>("Select $1, $2", "2")
////    println(conn.exec(stmt, Params(1, 4)))
////    stmt.close()
//
////    conn.prepare("Select 1", "2")
//
//    val result = conn.pipeline {
//        withSync {
//            query("Select $1, $2", Params(1, 3))
////            exec("Select $1, $2")
//        }
//        withSync {
//            query("Select $1, $2", Params(1, 3))
//            query("Select $1, $2", Params(1, 3))
//        }
//
//        val a = consumeSync {
//            receive()
////            receiveAsList<Result>().also { println("result 1") }
//        }
//
//        consumeSync {
//            receiveAsList<Result>().also { println("result 2") }
//            receiveAsList<Result>().also { println("result 3") }
//        }
//    }
//
//    println(result)
//    conn.close()
//
////    try {
////        println(conn.exec("Sasdfasdfelect 1"))
////    } catch (pgException: Exception) {
////        println("$pgException")
////    }
////    println(conn.query<Params, Result>("Select $1, $2", Params(1, 4)).toList<Result>())
////    conn.close()
//}

fun main(): Unit = runBlocking {
    foo()
}

suspend fun foo() = coroutineScope {
    val job1 = async {
        delay(100000)
        1
    }
    job1.invokeOnCompletion {
        println("BBBB $it")
    }

    val job2 = launch {
        delay(1000)
        error("AAAA")
    }
    val res = job1.await()
    println("job1.await() ${res}")
    res
}

@Serializable
private data class Params(
    val a2: PgInt4,
    val b2: PgInt4,
)

@Serializable
private data class Result(
    val a2: PgInt4,
    val b2: PgInt4,
)
