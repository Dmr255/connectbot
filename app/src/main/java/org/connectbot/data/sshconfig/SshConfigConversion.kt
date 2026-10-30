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

package org.connectbot.data.sshconfig

/** Parsed form of a single forwarding directive. */
sealed interface SshConfigForward {
    data class Local(val sourceAddr: String, val sourcePort: Int, val destAddr: String, val destPort: Int) : SshConfigForward
    data class Remote(val sourceAddr: String, val sourcePort: Int, val destAddr: String, val destPort: Int) : SshConfigForward
    data class Dynamic(val sourceAddr: String, val sourcePort: Int) : SshConfigForward
}

/** A configuration block resolved into the values ConnectBot cares about. */
data class SshConfigHostEntry(
    /** The concrete alias taken from the first token of the `Host` pattern. */
    val alias: String,
    /** Effective host name: `HostName`, or the alias when the directive is absent. */
    val hostname: String,
    val hostnameExplicit: Boolean,
    /** Effective user name, empty when absent. */
    val username: String,
    val usernameExplicit: Boolean,
    /** Effective port, defaulting to 22. */
    val port: Int,
    val portExplicit: Boolean,
    val compression: Boolean,
    val compressionExplicit: Boolean,
    /** `ForwardAgent yes` maps to `"yes"`; absent stays null. */
    val forwardAgent: String?,
    val proxyJump: String?,
    /** Paths from `IdentityFile` lines, in order. */
    val identityFiles: List<String>,
    val forwards: List<SshConfigForward>,
    val serverAliveInterval: Int?,
    val connectTimeout: Int?,
    val identitiesOnly: Boolean?,
    /** Index of the source block inside [SshConfigDocument.blocks]. */
    val blockIndex: Int,
)

/** Why a block could not be turned into a host entry. */
data class SshConfigSkip(
    val alias: String,
    val line: Int,
    val reason: String,
)

data class SshConfigConversionResult(
    val entries: List<SshConfigHostEntry>,
    val skipped: List<SshConfigSkip>,
)

/**
 * Maps parsed [SshConfigBlock]s onto ConnectBot's host model.
 *
 * OpenSSH resolves options first-match-wins: for a given keyword the first value
 * encountered while scanning the matching blocks applies. This converter follows
 * the same rule for scalar options and accumulates forwarding directives, which
 * OpenSSH also treats as additive.
 */
object SshConfigConversion {
    const val DEFAULT_SSH_PORT = 22

    /** Patterns with these characters match more than one concrete host. */
    private val patternCharacters = charArrayOf('*', '?', '!')

    fun convert(document: SshConfigDocument): SshConfigConversionResult {
        val skipped = mutableListOf<SshConfigSkip>()
        // OpenSSH resolves options first-match-wins, so values are accumulated per
        // alias across blocks instead of letting a later block override an earlier one.
        val byAlias = LinkedHashMap<String, ResolvedValues>()
        val aliasOrder = LinkedHashMap<String, Int>()

        document.blocks.forEachIndexed { blockIndex, block ->
            if (block.isGlobal) {
                if (block.directives.isNotEmpty()) {
                    skipped += SshConfigSkip(
                        alias = "(global)",
                        line = block.startLine + 1,
                        reason = "Directives before the first Host block apply to every connection and were ignored",
                    )
                }
                return@forEachIndexed
            }

            if (block.kind.equals("match", ignoreCase = true)) {
                skipped += SshConfigSkip("(match)", block.startLine + 1, "Match blocks are not supported")
                return@forEachIndexed
            }

            val tokens = tokenizePattern(block.pattern)
            val concrete = tokens.filter { token -> patternCharacters.none { it in token } }
            if (concrete.isEmpty()) {
                skipped += SshConfigSkip(
                    alias = block.pattern,
                    line = block.startLine + 1,
                    reason = "Wildcard patterns are not supported",
                )
                return@forEachIndexed
            }

            // A Host line may list several aliases; each one resolves independently.
            concrete.forEach { alias ->
                if (alias !in byAlias) {
                    byAlias[alias] = ResolvedValues()
                    aliasOrder[alias] = blockIndex
                }
            }

            block.directives.forEach { directive ->
                concrete.forEach { alias ->
                    applyDirective(byAlias.getValue(alias), directive)
                }
            }
        }

        val entries = byAlias.map { (alias, values) ->
            SshConfigHostEntry(
                alias = alias,
                hostname = values.hostname ?: alias,
                hostnameExplicit = values.hostname != null,
                username = values.username.orEmpty(),
                usernameExplicit = values.username != null,
                port = values.port ?: DEFAULT_SSH_PORT,
                portExplicit = values.port != null,
                compression = values.compression ?: false,
                compressionExplicit = values.compression != null,
                forwardAgent = values.forwardAgent,
                proxyJump = values.proxyJump,
                identityFiles = values.identityFiles.toList(),
                forwards = values.forwards.toList(),
                serverAliveInterval = values.serverAliveInterval,
                connectTimeout = values.connectTimeout,
                identitiesOnly = values.identitiesOnly,
                blockIndex = aliasOrder.getValue(alias),
            )
        }

        return SshConfigConversionResult(entries = entries, skipped = skipped)
    }

    /** Applies one directive with first-match-wins semantics for scalar options. */
    private fun applyDirective(values: ResolvedValues, directive: SshConfigDirective) {
        when (directive.key) {
            "hostname" -> values.hostname = values.hostname ?: directive.value
            "user" -> values.username = values.username ?: directive.value
            "port" -> values.port = values.port ?: directive.value.toIntOrNull()
            "compression" -> values.compression = values.compression ?: parseBoolean(directive.value)
            "forwardagent" -> values.forwardAgent = values.forwardAgent ?: parseAgent(directive.value)
            "proxyjump" -> values.proxyJump = values.proxyJump ?: directive.value
            "identityfile" -> values.identityFiles += directive.value
            "serveraliveinterval" -> values.serverAliveInterval = values.serverAliveInterval ?: directive.value.toIntOrNull()
            "connecttimeout" -> values.connectTimeout = values.connectTimeout ?: directive.value.toIntOrNull()
            "identitiesonly" -> values.identitiesOnly = values.identitiesOnly ?: parseBoolean(directive.value)
            "localforward" -> parseForward(directive.value, remote = false)?.let(values.forwards::add)
            "remoteforward" -> parseForward(directive.value, remote = true)?.let(values.forwards::add)
            "dynamicforward" -> parseDynamicForward(directive.value)?.let(values.forwards::add)
        }
    }

    /** Splits a pattern on whitespace, honoring double quotes. */
    fun tokenizePattern(pattern: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
            index++
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * Parses `[bind_address:]port host:hostport`. Returns null for unsupported
     * forms such as Unix domain sockets, which ConnectBot cannot forward.
     */
    fun parseForward(value: String, remote: Boolean): SshConfigForward? {
        val (bindAddr, portSpec, destination) = splitForward(value) ?: return null
        val sourcePort = portSpec.toIntOrNull() ?: return null
        val destSpec = destination ?: return null
        val (destAddr, destPort) = splitDestination(destSpec) ?: return null
        return if (remote) {
            SshConfigForward.Remote(bindAddr, sourcePort, destAddr, destPort)
        } else {
            SshConfigForward.Local(bindAddr, sourcePort, destAddr, destPort)
        }
    }

    fun parseDynamicForward(value: String): SshConfigForward? {
        val (bindAddr, portSpec, destination) = splitForward(value) ?: return null
        if (destination != null) return null
        val sourcePort = portSpec.toIntOrNull() ?: return null
        return SshConfigForward.Dynamic(bindAddr, sourcePort)
    }

    /** Returns (bindAddress, portSpec, destinationOrNull). */
    private fun splitForward(value: String): Triple<String, String, String?>? {
        val parts = value.trim().split(WhitespaceRegex, limit = 2)
        val addressPart = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val destination = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val colon = addressPart.lastIndexOf(':')
        return if (colon >= 0) {
            Triple(addressPart.substring(0, colon).ifBlank { "localhost" }, addressPart.substring(colon + 1), destination)
        } else {
            Triple("localhost", addressPart, destination)
        }
    }

    private fun splitDestination(destination: String): Pair<String, Int>? {
        if (destination.startsWith('/')) return null
        val colon = destination.lastIndexOf(':')
        if (colon <= 0) return null
        val host = destination.substring(0, colon).removeSurrounding("[", "]")
        val port = destination.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun parseBoolean(value: String): Boolean? = when (value.lowercase()) {
        "yes", "true" -> true
        "no", "false" -> false
        else -> null
    }

    private fun parseAgent(value: String): String? = when (value.lowercase()) {
        "yes", "true" -> "yes"
        "no", "false" -> "no"
        else -> null
    }

    private val WhitespaceRegex = Regex("\\s+")

    private class ResolvedValues {
        var hostname: String? = null
        var username: String? = null
        var port: Int? = null
        var compression: Boolean? = null
        var forwardAgent: String? = null
        var proxyJump: String? = null
        val identityFiles = mutableListOf<String>()
        val forwards = mutableListOf<SshConfigForward>()
        var serverAliveInterval: Int? = null
        var connectTimeout: Int? = null
        var identitiesOnly: Boolean? = null
    }
}