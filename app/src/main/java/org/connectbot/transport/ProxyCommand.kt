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

/** How a `ProxyCommand` can be run on Android. */
enum class ProxyCommandKind {
    /** `cloudflared access ssh`, executed with the bundled tunnel client. */
    CLOUDFLARED,

    /** Any other program: Android cannot execute arbitrary downloaded binaries. */
    UNSUPPORTED,
}

/** A parsed `ProxyCommand` with `%h`, `%p`, `%r`, and `%%` already expanded. */
data class ProxyCommandSpec(
    val executable: String,
    val arguments: List<String>,
    val kind: ProxyCommandKind,
) {
    /** Reconstructed command line, used for diagnostics. */
    val commandLine: String
        get() = (listOf(executable) + arguments).joinToString(" ")
}

/**
 * Parses OpenSSH `ProxyCommand` values.
 *
 * Android only permits executing binaries that ship inside the APK, so the sole
 * supported program is the bundled Cloudflare Tunnel client. Everything else is
 * reported as [ProxyCommandKind.UNSUPPORTED] rather than silently ignored.
 */
object ProxyCommand {
    private val cloudflaredNames = setOf("cloudflared", "cloudflared.exe")

    /** Tokenizes a command line, honoring single and double quotes. */
    fun tokenize(raw: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var started = false
        for (c in raw) {
            when {
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    started = true
                }

                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    started = true
                }

                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (started) {
                        tokens += current.toString()
                        current.clear()
                        started = false
                    }
                }

                else -> {
                    current.append(c)
                    started = true
                }
            }
        }
        if (inSingle || inDouble) return null
        if (started) tokens += current.toString()
        return tokens
    }

    /**
     * Parses [raw] and expands the placeholders OpenSSH supports in
     * `ProxyCommand`. Returns null when the value cannot be tokenized.
     */
    fun parse(raw: String, hostname: String, port: Int, username: String): ProxyCommandSpec? {
        val tokens = tokenize(raw) ?: return null
        if (tokens.isEmpty()) return null
        val expanded = tokens.map { expand(it, hostname, port, username) }
        val executable = expanded.first()
        val arguments = expanded.drop(1)
        val name = executable.substringAfterLast('/').lowercase()
        val kind = if (name in cloudflaredNames && isAccessSsh(arguments)) {
            ProxyCommandKind.CLOUDFLARED
        } else {
            ProxyCommandKind.UNSUPPORTED
        }
        return ProxyCommandSpec(executable = executable, arguments = arguments, kind = kind)
    }

    private fun isAccessSsh(arguments: List<String>): Boolean =
        arguments.size >= 2 &&
            arguments[0].equals("access", ignoreCase = true) &&
            arguments[1].equals("ssh", ignoreCase = true)

    private fun expand(token: String, hostname: String, port: Int, username: String): String {
        if ('%' !in token) return token
        val builder = StringBuilder(token.length + 16)
        var index = 0
        while (index < token.length) {
            val c = token[index]
            if (c == '%' && index + 1 < token.length) {
                when (token[index + 1]) {
                    'h' -> {
                        builder.append(hostname)
                        index += 2
                        continue
                    }

                    'p' -> {
                        builder.append(port)
                        index += 2
                        continue
                    }

                    'r' -> {
                        builder.append(username)
                        index += 2
                        continue
                    }

                    '%' -> {
                        builder.append('%')
                        index += 2
                        continue
                    }
                }
            }
            builder.append(c)
            index++
        }
        return builder.toString()
    }
}