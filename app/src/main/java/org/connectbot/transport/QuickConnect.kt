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

/**
 * Parses the command line a user would type into `ssh`, so a connection can be
 * started from the host list without saving a host or URL-encoding anything.
 *
 * Supported forms, in any combination:
 * ```
 * damar@ssh.example.com
 * damar@ssh.example.com:2222
 * ssh damar@ssh.example.com -p 2222 -o ProxyCommand="cloudflared access ssh --hostname %h"
 * ```
 */
object QuickConnect {
    private const val DEFAULT_PORT = 22

    /** A hostname, IPv4 address, or bracketed IPv6 literal. */
    private val hostnamePattern = Regex(
        "^(?:[0-9a-zA-Z._-]+|\\[[0-9a-fA-F:.%_-]+])$",
    )

    /** An unbracketed IPv6 literal such as `2001:db8::1`. */
    private val ipv6Pattern = Regex("^[0-9a-fA-F:.]+$")

    /** Single-letter flags that consume the following token as their value. */
    private val flagsWithValue: Set<Char> = setOf(
        'b', 'c', 'D', 'E', 'e', 'F', 'I', 'i', 'J', 'L', 'l', 'm', 'O', 'o', 'p', 'Q', 'R', 'S', 'W', 'w',
    )

    /** A parsed connection request. [proxyCommand] is null when not supplied. */
    data class Request(
        val username: String,
        val hostname: String,
        val port: Int,
        val proxyCommand: String?,
    )

    /**
     * Parses [raw], returning null when the input is not a usable destination.
     *
     * The parser is deliberately permissive: options it does not care about are
     * skipped instead of rejected, so pasting a command line from a shell
     * history still works.
     */
    fun parse(raw: String): Request? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // A pasted `ssh://` URI is handled by the URI pipeline, not here.
        if (trimmed.contains("://")) return null

        val tokens = ProxyCommand.tokenize(trimmed) ?: return null
        if (tokens.isEmpty()) return null

        var username = ""
        var port = DEFAULT_PORT
        var proxyCommand: String? = null
        var target: String? = null

        var index = 0
        if (tokens[0].equals("ssh", ignoreCase = true)) index++

        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "--" -> {
                    target = tokens.getOrNull(index + 1)
                    break
                }

                token.startsWith("--") -> {
                    val (name, value) = splitOption(token.substring(2))
                    if (name.equals("proxycommand", ignoreCase = true)) {
                        proxyCommand = value.ifEmpty { tokens.getOrNull(index + 1)?.also { index++ } }
                    }
                }

                token.startsWith("-") && token.length > 1 -> {
                    val flag = token[1]
                    val inlineValue = token.substring(2).ifEmpty { null }
                    val value = inlineValue ?: if (flag in flagsWithValue) {
                        tokens.getOrNull(index + 1)?.also { index++ }
                    } else {
                        null
                    }
                    when (flag) {
                        'p' -> port = value?.toIntOrNull() ?: return null
                        'l' -> username = value.orEmpty()
                        'o' -> {
                            val (name, optionValue) = splitOption(value ?: return null)
                            if (name.equals("proxycommand", ignoreCase = true)) proxyCommand = optionValue
                        }
                    }
                }

                target == null -> target = token

                else -> return null
            }
            index++
        }

        val destination = target ?: return null
        val (targetUser, hostAndPort) = splitUser(destination)
        if (targetUser.isNotEmpty()) username = targetUser

        val (hostname, targetPort) = splitPort(hostAndPort) ?: return null
        if (targetPort != null) port = targetPort

        if (!isValidHostname(hostname)) return null
        if (port !in 1..65535) return null

        return Request(
            username = username,
            hostname = hostname,
            port = port,
            proxyCommand = proxyCommand?.takeIf { it.isNotBlank() },
        )
    }

    private fun isValidHostname(value: String): Boolean {
        if (hostnamePattern.matches(value)) return true
        // Unbracketed IPv6 literals have already been split at the last colon,
        // so any remaining colons are part of the address itself.
        return ':' in value && ipv6Pattern.matches(value)
    }

    private fun splitOption(option: String): Pair<String, String> {
        val separator = option.indexOf('=')
        return if (separator < 0) {
            option to ""
        } else {
            option.substring(0, separator) to option.substring(separator + 1)
        }
    }

    private fun splitUser(destination: String): Pair<String, String> {
        val at = destination.lastIndexOf('@')
        return if (at < 0) {
            "" to destination
        } else {
            destination.substring(0, at) to destination.substring(at + 1)
        }
    }

    /** Splits `host`, `host:port`, `[v6]`, or `[v6]:port`. */
    private fun splitPort(value: String): Pair<String, Int?>? {
        if (value.startsWith("[")) {
            val closing = value.indexOf(']')
            if (closing < 0) return null
            val host = value.substring(0, closing + 1)
            val rest = value.substring(closing + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to (rest.substring(1).toIntOrNull() ?: return null)
                else -> null
            }
        }
        val colon = value.lastIndexOf(':')
        if (colon < 0) return value to null
        // More than one colon without brackets is an unbracketed IPv6 literal.
        if (value.indexOf(':') != colon) return value to null
        return value.substring(0, colon) to (value.substring(colon + 1).toIntOrNull() ?: return null)
    }
}