package io.github.hagbard235.ringnotes.symcon

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange with the Symcon AI hook. Blocking; call from a background thread. */
class SymconClient {
    sealed interface Result {
        data class Http(val code: Int, val body: String?, val retryAfterMs: Long?) : Result
        data class Transport(val message: String) : Result
    }

    fun post(url: String, key: String, body: String): Result = exchange(url, key, body)

    fun get(url: String, key: String): Result = exchange(url, key, null)

    private fun exchange(url: String, key: String, body: String?): Result {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Result.Transport("Ungültige URL")
        }
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // Never follow redirects: the access key must not travel to another origin.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                conn.requestMethod = "GET"
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            Result.Http(code, text, retryAfterMs(conn.getHeaderField("Retry-After")))
        } catch (e: IOException) {
            // Deliberately without the URL or headers, so the key never ends up in a log.
            Result.Transport(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }
}
