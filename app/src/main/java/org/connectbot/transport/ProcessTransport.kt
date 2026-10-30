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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

/**
 * SSH transport backed by a local helper process.
 *
 * The child process is expected to speak the SSH protocol on its stdin/stdout,
 * which is exactly what `ssh -o ProxyCommand=...` relies on. ConnectBot uses it
 * for `cloudflared access ssh`, whose tunnel client ships inside the APK.
 */
class ProcessTransport private constructor(
    private val process: Process,
    private val stdin: OutputStream,
    private val stdout: InputStream,
) : Transport {
    private val stderrTail = Collections.synchronizedList(ArrayDeque<String>())

    @Volatile
    private var closed = false

    private var onClose: (() -> Unit)? = null

    /** Registers cleanup that runs once when the transport closes. */
    fun onClose(action: () -> Unit) {
        onClose = action
    }

    override val isConnected: Boolean
        get() = !closed && process.isAlive

    override suspend fun read(count: Int): ByteArray = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext ByteArray(0)
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                stdout.read(buffer, offset, count - offset)
            } catch (e: IOException) {
                throw TransportException(describeFailure("read", e), e)
            }
            if (read == -1) {
                throw TransportException(describeFailure("read", null))
            }
            offset += read
        }
        buffer
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            stdin.write(data)
            stdin.flush()
        } catch (e: IOException) {
            throw TransportException(describeFailure("write", e), e)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.IO) {
            runCatching { stdin.close() }
            process.destroy()
            runCatching { stdout.close() }
        }
        onClose?.invoke()
        onClose = null
    }

    /** Last stderr lines from the helper, useful for surfacing login prompts. */
    fun stderrSnapshot(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    private fun describeFailure(operation: String, cause: IOException?): String {
        val exit = runCatching { process.exitValue() }.getOrNull()
        val detail = stderrSnapshot().takeLast(3).joinToString(" | ").takeIf { it.isNotBlank() }
        return buildString {
            append("Tunnel helper failed during $operation")
            if (exit != null) append(" (exit code $exit)")
            if (detail != null) append(": $detail")
        }.also { Timber.w(cause, it) }
    }

    companion object {
        private const val MAX_STDERR_LINES = 40

        /**
         * Starts [executable] with [arguments] and returns a transport wired to
         * its stdio. Throws [TransportException] when the process cannot start.
         */
        fun start(
            executable: File,
            arguments: List<String>,
            environment: Map<String, String> = emptyMap(),
            onStderrLine: ((String) -> Unit)? = null,
        ): ProcessTransport {
            if (!executable.isFile) {
                throw TransportException("Tunnel helper is missing: ${executable.absolutePath}")
            }
            if (!executable.canExecute() && !executable.setExecutable(true, true)) {
                throw TransportException("Tunnel helper is not executable: ${executable.absolutePath}")
            }
            val command = listOf(executable.absolutePath) + arguments
            val builder = ProcessBuilder(command)
            builder.redirectErrorStream(false)
            // cloudflared expects a writable home for its access token cache.
            val home = System.getProperty("user.home")
            if (!home.isNullOrBlank()) {
                builder.environment()["HOME"] = home
            }
            builder.environment().putAll(environment)
            val process = try {
                builder.start()
            } catch (e: IOException) {
                throw TransportException("Could not start tunnel helper: ${e.message}", e)
            }
            val transport = ProcessTransport(process, process.outputStream, process.inputStream)
            transport.drainStderr(process.errorStream, onStderrLine)
            return transport
        }
    }

    private fun drainStderr(stream: InputStream, onLine: ((String) -> Unit)?) {
        Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            synchronized(stderrTail) {
                                if (stderrTail.size >= MAX_STDERR_LINES) stderrTail.removeFirst()
                                stderrTail.addLast(trimmed)
                            }
                            onLine?.invoke(trimmed)
                        }
                    }
                }
            } catch (ignored: IOException) {
                // Process went away; nothing left to drain.
            }
        }, "ProxyCommandStderr").apply {
            isDaemon = true
            start()
        }
    }
}