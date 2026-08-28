package kio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.files.Path

fun Route.staticResource(
    remotePath: String,
    basePackage: String,
    index: String? = "index.html",
) {
    route(remotePath) {
        route("{${pathParameterName}...}") {
            registerCallHandler(HttpMethod.Get) {
                val segments = requestParameters.getAll(pathParameterName)?.toTypedArray() ?: emptyArray()
                val path = Path(basePackage, *segments)
                val io = currentCoroutineContext().poller.io
                val resolvedResult = io.resolveStaticFile(path.toString(), index)
                if (resolvedResult == null) {
                    respondText("404 page not found", status = HttpStatusCode.NotFound)
                    return@registerCallHandler
                }

                val (resolvedFilePath, size) = resolvedResult
                respondFile(resolvedFilePath, size)
            }
        }
    }
}

private suspend fun CallContext.respondFile(filePath: String, size: Long) {
    val source = openFileSource(filePath)

    responseHead.statusCode = HttpStatusCode.OK
    responseHead.headers[HttpHeaders.ContentType] = ContentType.defaultForFilePath(filePath).toString()
    responseHead.headers[HttpHeaders.ContentLength] = size.toString()
    responseSink.transferFrom(source)

    source.close()
}

internal expect suspend fun openFileSource(path: String): AsyncRawSource
internal expect suspend fun SuspendIo.getFileStatus(path: String): FileData?

internal data class FileData(
    val isRegularFile: Boolean = false,
    val isDirectory: Boolean = false,
    val size: Long = 0L
)

private const val pathParameterName = "static-content-path-parameter"

private suspend fun SuspendIo.resolveStaticFile(
    requestedPath: String,
    index: String?
): Pair<String, Long>? {
    val metadata = getFileStatus(requestedPath) ?: return null

    return when {
        metadata.isRegularFile -> {
            requestedPath to metadata.size
        }

        metadata.isDirectory -> {
            if (index == null) {
                null
            } else {
                val indexPath = Path(requestedPath, index).toString()
                val indexMetadata = getFileStatus(indexPath)

                if (indexMetadata?.isRegularFile == true) {
                    indexPath to indexMetadata.size
                } else {
                    null
                }
            }
        }

        else -> {
            null
        }
    }
}