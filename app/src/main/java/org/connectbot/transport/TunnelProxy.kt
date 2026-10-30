/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Minimal HTTP `CONNECT` proxy bound to loopback.
 *
 * The bundled `cloudflared` is a statically linked Go binary whose pure-Go DNS
 * resolver cannot read Android's resolver configuration, so every hostname
 * lookup fails. Pointing the helper at this proxy moves name resolution back
 * into the app, where [InetAddress] uses Android's own resolver. The tunnel hop
 * keeps its end-to-end TLS handshake, so certificate verification is unchanged.
 */
internal class TunnelProxy private constructor(private val server: ServerSocket) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    val port: Int
        get() = server.localPort

    private fun startServing() {
        scope.launch {
            while (isActive && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += client
                scope.launch { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = CONNECT_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readAsciiLine(input) ?: return
            while (true) {
                val header = readAsciiLine(input) ?: return
                if (header.isEmpty()) break
            }

            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeAscii(client.getOutputStream(), "HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                return
            }
            val target = parseAuthority(parts[1])
            if (target == null) {
                writeAscii(client.getOutputStream(), "HTTP/1.1 400 Bad Request\r\n\r\n")
                return
            }

            val resolved = InetAddress.getByName(target.first)
            val socket = Socket()
            sockets += socket
            upstream = socket
            socket.connect(InetSocketAddress(resolved, target.second), CONNECT_TIMEOUT_MS)
            client.soTimeout = 0
            writeAscii(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\n\r\n")

            val upstreamSocket = socket
            val up = Thread({ pump(client.getInputStream(), upstreamSocket.getOutputStream(), upstreamSocket) }, "TunnelProxyUp")
            up.isDaemon = true
            up.start()
            pump(upstreamSocket.getInputStream(), client.getOutputStream(), client)
            up.join(TimeUnit.SECONDS.toMillis(5))
        } catch (e: IOException) {
            Timber.d(e, "Tunnel proxy connection failed")
        } finally {
            runCatching { upstream?.close() }
            sockets -= upstream
            sockets -= client
            runCatching { client.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream, peer: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read == -1) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (ignored: IOException) {
            // Either side closing is the normal way out of a tunnel.
        } finally {
            runCatching { peer.close() }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { server.close() }
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val BUFFER_SIZE = 16 * 1024

        /** Binds a loopback proxy and starts accepting connections. */
        fun start(): TunnelProxy {
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            return TunnelProxy(server).also { it.startServing() }
        }

        /** Splits `host:port`, accepting bracketed IPv6 literals. */
        internal fun parseAuthority(authority: String): Pair<String, Int>? {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return null
            val host: String
            val portText: String
            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end <= 0) return null
                host = trimmed.substring(1, end)
                portText = trimmed.substring(end + 1).removePrefix(":")
            } else {
                val colon = trimmed.lastIndexOf(':')
                if (colon <= 0) return null
                host = trimmed.substring(0, colon)
                portText = trimmed.substring(colon + 1)
            }
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }

        private fun readAsciiLine(input: InputStream): String? {
            val builder = StringBuilder()
            var previous = -1
            while (true) {
                val b = input.read()
                if (b == -1) return if (builder.isEmpty()) null else builder.toString()
                if (b == '\n'.code && previous == '\r'.code) {
                    builder.setLength(builder.length - 1)
                    return builder.toString()
                }
                if (builder.length >= MAX_LINE) return null
                builder.append(b.toChar())
                previous = b
            }
        }

        private fun writeAscii(output: OutputStream, text: String) {
            output.write(text.toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        private const val MAX_LINE = 8 * 1024
    }
}