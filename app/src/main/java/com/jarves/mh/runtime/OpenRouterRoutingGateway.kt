package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Adds Mobile Harness' saved OpenRouter routing policy to Anthropic Messages requests. */
internal fun applyOpenRouterRouting(source: JSONObject, profile: ProviderProfile): JSONObject {
    val providers = profile.openRouterProviders
    if (providers.isEmpty()) return source
    val routing = JSONObject()
        .put("order", JSONArray(providers))
        .put("allow_fallbacks", profile.openRouterAllowFallbacks)
    return source.put("provider", routing)
}

/** Loopback proxy used because coding-agent CLIs do not expose OpenRouter's provider body field. */
internal class OpenRouterRoutingGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): OpenRouterRoutingGateway = apply {
        Thread({ acceptLoop() }, "mh-openrouter-routing").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-openrouter-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val output = BufferedOutputStream(socket.getOutputStream())
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, errorJson("Request is too large"))
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        if (path.endsWith("/count_tokens")) {
            writeJson(output, 200, JSONObject().put("input_tokens", body.decodeToString().length / 4 + 1).toString())
            return
        }
        if (method != "POST" || !path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("Unsupported OpenRouter endpoint"))
            return
        }

        runCatching {
            val routedBody = applyOpenRouterRouting(JSONObject(body.decodeToString()), profile).toString().toByteArray()
            val endpoint = profile.resolvedBaseUrl.trimEnd('/') + path
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", headers["accept"] ?: "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
                headers["anthropic-beta"]?.let { connection.setRequestProperty("anthropic-beta", it) }
                connection.outputStream.use { it.write(routedBody) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val reason = if (code in 200..299) "OK" else "Error"
                val contentType = connection.contentType ?: "application/json"
                output.write(
                    "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                stream?.use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
                output.flush()
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson(error.message ?: "OpenRouter request failed"))
        }
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        output.write(
            "HTTP/1.1 $code Error\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
        return null
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", "api_error").put("message", message))
        .toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
    }
}
