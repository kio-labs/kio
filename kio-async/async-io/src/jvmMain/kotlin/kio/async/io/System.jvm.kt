package kio.async.io

actual fun getEnv(key: String): String? {
    return System.getenv(key)
}