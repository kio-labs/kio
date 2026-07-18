package kio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import kio.async.io.openFileSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

fun Route.staticResource(
    remotePath: String,
    basePackage: String,
    index: String? = "index.html",
) {
    route(remotePath) {
        route("{${pathParameterName}...}") {
            registerCallHandler(HttpMethod.Get) {
                val segments = parameters.getAll(pathParameterName)?.toTypedArray() ?: emptyArray()
                val path = Path(basePackage, *segments)

                val resolvedResult = resolveStaticFile(path, index)
                if (resolvedResult == null) {
                    respondText("404 page not found", status = HttpStatusCode.NotFound)
                    return@registerCallHandler
                }

                val (resolvedFilePath, size) = resolvedResult
                respondFile(resolvedFilePath.toString(), size)
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
}

private const val pathParameterName = "static-content-path-parameter"

private fun resolveStaticFile(
    requestedPath: Path,
    index: String?
): Pair<Path, Long>? {
    val metadata =
        SystemFileSystem.metadataOrNull(requestedPath)
            ?: return null

    return when {
        metadata.isRegularFile -> {
            requestedPath to metadata.size
        }

        metadata.isDirectory -> {
            if (index == null) {
                null
            } else {
                val indexPath = Path(requestedPath, index)
                val indexMetadata =
                    SystemFileSystem.metadataOrNull(indexPath)

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