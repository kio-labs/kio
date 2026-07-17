package kio.http

import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.parametersOf
import io.ktor.http.parseQueryString

fun Route.route(path: String, block: Route.() -> Unit) {
    createRouteFromPath(path).apply(block)
}

class Route internal constructor(
    internal val segment: PathSegment,
    internal val httpCallInterceptors: ArrayDeque<CallInterceptor>,
){
    internal val httpCallHandlers = mutableMapOf<HttpMethod, CallHandler>()

    internal val childRoutes: MutableList<Route> = mutableListOf()

    internal fun registerCallHandler(httpMethod: HttpMethod, handler: CallHandler) {
        if (httpCallHandlers.contains(httpMethod)) error("already exist handler of ${httpMethod}")
        httpCallHandlers[httpMethod] = handler
    }

    fun inject(interceptor: CallInterceptor, block: () -> Unit) {
        httpCallInterceptors.addLast(interceptor)
        block()
        httpCallInterceptors.removeLast()
    }
}

internal val RootSegment = PathSegment.Const("/")
internal sealed interface PathSegment {
    data class Const(val name: String) : PathSegment {
        override fun match(segments: List<String>): MatchResult {
            if (segments.isEmpty()) return MatchResult.Failed
            val segment = segments[0]
            return if (name == segment) {
                MatchResult.Success(1)
            } else {
                MatchResult.Failed
            }
        }
    }

    data class Tailcard(
        val name: String = "",
        val prefix: String = ""
    ): PathSegment {
        override fun match(segments: List<String>): MatchResult {
            if (prefix.isNotEmpty()) {
                val segmentText = segments.getOrNull(0)
                if (segmentText == null || !segmentText.startsWith(prefix)) {
                    return MatchResult.Failed
                }
            }

            val values = when {
                name.isEmpty() -> parametersOf()
                else -> parametersOf(
                    name,
                    segments.mapIndexed { index, segment ->
                        if (index == 0) {
                            segment.drop(prefix.length)
                        } else {
                            segment
                        }
                    }
                )
            }

            return MatchResult.Success(
                segments.size,
                values,
            )
        }
    }

    data class Parameter(
        val name: String,
        val prefix: String? = null,
        val suffix: String? = null
    ): PathSegment {
        override fun match(segments: List<String>): MatchResult {
            if (segments.isEmpty()) return MatchResult.Failed
            val segment = segments[0]
            val prefixChecked = when {
                prefix == null -> segment
                segment.startsWith(prefix) -> segment.drop(prefix.length)
                else -> return MatchResult.Failed
            }

            val suffixChecked = when {
                suffix == null -> prefixChecked
                prefixChecked.endsWith(suffix) -> prefixChecked.dropLast(suffix.length)
                else -> return MatchResult.Failed
            }

            return MatchResult.Success(1, parametersOf(name, suffixChecked))
        }
    }

    fun match(segments: List<String>): MatchResult

    companion object {
        fun parse(value: String): PathSegment {
            val prefixIndex = value.indexOf('{')
            val suffixIndex = value.lastIndexOf('}')

            if (prefixIndex == -1 || suffixIndex == -1) {
                // const segment
                return Const(value)
            }

            val prefix = if (prefixIndex == 0) null else value.substring(0, prefixIndex)
            val suffix = if (suffixIndex == value.length - 1) null else value.substring(suffixIndex + 1)
            val signature = value.substring(prefixIndex + 1, suffixIndex)

            return when {
                signature.endsWith("...") -> {
                    if (!suffix.isNullOrEmpty()) {
                        throw IllegalArgumentException("Suffix after tailcard is not supported")
                    }
                    Tailcard(signature.dropLast(3), prefix ?: "")
                }
                else -> Parameter(signature, prefix, suffix)
            }
        }
    }
}

internal sealed interface MatchResult {
    data class Success(val consumeSegmentCount: Int, val parameters: Parameters = Parameters.Empty): MatchResult
    data object Failed: MatchResult
}

internal fun Route.registerCall(
    method: HttpMethod,
    path: String,
    block: CallHandler
) {
    val foldedCallHandler = foldCallInterceptor(httpCallInterceptors, block)
    createRouteFromPath(path).registerCallHandler(method, foldedCallHandler)
}

private fun Route.createRouteFromPath(path: String): Route {
    val segments = path.splitToSequence("/").filter { it.isNotEmpty() }
        .map { segmentStr ->
            PathSegment.parse(segmentStr)
        }
    var last: Route = this
    for (segment in segments) {
        last = last.newOrExistRoute(segment)
    }
    return last
}

private fun Route.newOrExistRoute(segment: PathSegment): Route {
    val existingRoute = childRoutes.firstOrNull { it.segment == segment }
    if (existingRoute == null) {
        val route = Route(segment, httpCallInterceptors)
        childRoutes.add(route)
        return route
    }
    return existingRoute
}

internal fun Route.resolveHandler(uri: String, method: HttpMethod): Pair<Parameters, CallHandler?> {
    val separatorIndex = uri.indexOfFirst { it == '?' }
    val (path, params) = if (separatorIndex == -1) {
        uri to Parameters.Empty
    } else {
        val path = uri.substring(0, separatorIndex)
        val parameters = parseQueryString(uri, separatorIndex + 1)
        path to parameters
    }

    val (route, routeParams) = this.resolveRouteAndParameters(path) ?: return params to null

    val builder = ParametersBuilder()
    builder.appendAll(params)
    builder.appendAll(routeParams)
    return builder.build() to route.httpCallHandlers[method]
}

private fun Route.resolveRouteAndParameters(path: String): Pair<Route, Parameters>? {
    if (path.isEmpty()) return null
    if (path == "/" && this.segment == RootSegment) return this to Parameters.Empty
    if (path[0] == '/') {
        return this.takeIf { this.segment == RootSegment }?.resolveChildRoute(path.substring(1))
    }

    return resolveChildRoute(path)
}

private fun Route.resolveChildRoute(path: String): Pair<Route, Parameters>? {
    val segments = path.split("/").filter { it.isNotEmpty() }.toMutableList()
    var currentRoute = this
    val parametersBuilder = ParametersBuilder()
    while (true) {
        val (route,  matchResult) = currentRoute.findMatchedRoute(segments)
        when (matchResult) {
            MatchResult.Failed -> break
            is MatchResult.Success -> {
                currentRoute = route!!

                repeat(matchResult.consumeSegmentCount) { segments.removeFirst() }
                parametersBuilder.appendAll(matchResult.parameters)
            }
        }
    }

    if (segments.isNotEmpty()) {
        return null
    }

    return currentRoute to parametersBuilder.build()
}

private fun Route.findMatchedRoute(segments: List<String>): Pair<Route?, MatchResult> {
    for (child in childRoutes) {
        when (val result = child.segment.match(segments)) {
            is MatchResult.Success -> {
                return child to result
            }
            MatchResult.Failed -> continue
        }
    }

    return null to MatchResult.Failed
}