package com.malik.lmai.feature.mcp

import android.net.Uri
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * One-shot OAuth loopback listener for Peach MCP.
 *
 * Peach's dynamic client registration accepts HTTPS redirect URIs or localhost HTTP redirects,
 * but rejects Android custom schemes. A localhost callback keeps the flow fully local to the
 * device and avoids requiring a hosted callback service.
 */
@Singleton
class PeachMcpLoopbackServer @Inject constructor() {
    private val lock = Any()

    @Volatile
    private var activeSocket: ServerSocket? = null

    fun start(): String {
        stop()

        // Bind explicitly to IPv4 loopback. Some Android builds resolve "localhost" to IPv6
        // first, while the browser later reaches 127.0.0.1. The OAuth redirect URI itself stays
        // as http://localhost:<port>/... because that is the form Peach accepts for native apps.
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val socket = ServerSocket().apply {
            reuseAddress = true
            soTimeout = CALLBACK_TIMEOUT_MILLIS
            bind(InetSocketAddress(loopback, 0), 1)
        }
        synchronized(lock) {
            activeSocket = socket
        }

        thread(
            start = true,
            isDaemon = true,
            name = "H-Peach-MCP-OAuth-Loopback",
        ) {
            serveOnce(socket)
        }

        return "http://localhost:${socket.localPort}$CALLBACK_PATH"
    }

    fun stop() {
        val socket = synchronized(lock) {
            activeSocket.also { activeSocket = null }
        }
        runCatching { socket?.close() }
    }

    private fun serveOnce(socket: ServerSocket) {
        try {
            socket.accept().use { client ->
                client.soTimeout = READ_TIMEOUT_MILLIS
                val requestTarget = readRequestTarget(client)
                val callbackUri = requestTarget?.let { target ->
                    if (target.startsWith("http://") || target.startsWith("https://")) {
                        Uri.parse(target)
                    } else {
                        Uri.parse("http://localhost:${socket.localPort}$target")
                    }
                }

                if (callbackUri?.path == CALLBACK_PATH) {
                    PeachMcpOAuthCallbackBus.publish(callbackUri)
                    writeSuccessPage(client)
                } else {
                    writeNotFound(client)
                }
            }
        } catch (_: SocketTimeoutException) {
            // Authorization was abandoned. The next attempt creates a fresh listener.
        } catch (_: Exception) {
            // UI reports OAuth/registration failures; listener errors should not crash the app.
        } finally {
            synchronized(lock) {
                if (activeSocket === socket) activeSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun readRequestTarget(client: Socket): String? {
        val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = reader.readLine()?.trim().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) return null
        return parts[1]
    }

    private fun writeSuccessPage(client: Socket) {
        val body = """
            <!doctype html>
            <html lang="ar" dir="rtl">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>H</title>
                <style>
                  body{font-family:sans-serif;background:#f7f7fb;color:#17171c;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
                  main{max-width:420px;padding:32px;text-align:center}
                  a{display:inline-block;margin-top:20px;padding:14px 22px;border-radius:14px;background:#2563eb;color:#fff;text-decoration:none}
                </style>
              </head>
              <body>
                <main>
                  <h2>تم استلام موافقة Peach</h2>
                  <p>يمكنك العودة إلى تطبيق H الآن.</p>
                  <a href="lmai://peach-mcp-oauth?return=1">العودة إلى H</a>
                </main>
                <script>setTimeout(function(){location.href='lmai://peach-mcp-oauth?return=1'},700);</script>
              </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        writeResponse(client, "200 OK", "text/html; charset=utf-8", body)
    }

    private fun writeNotFound(client: Socket) {
        val body = "Not found".toByteArray(StandardCharsets.UTF_8)
        writeResponse(client, "404 Not Found", "text/plain; charset=utf-8", body)
    }

    private fun writeResponse(
        client: Socket,
        status: String,
        contentType: String,
        body: ByteArray,
    ) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        client.getOutputStream().use { output ->
            output.write(headers)
            output.write(body)
            output.flush()
        }
    }

    companion object {
        const val CALLBACK_PATH = "/peach-mcp-oauth"
        private const val CALLBACK_TIMEOUT_MILLIS = 10 * 60 * 1000
        private const val READ_TIMEOUT_MILLIS = 10 * 1000
    }
}
