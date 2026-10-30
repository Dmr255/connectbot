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

/**
 * A single `Host` (or `Match`) block of an OpenSSH client configuration file.
 *
 * The pattern is kept as written in the source document so the block can be
 * edited and written back. [isGlobal] is set for directives that appear before
 * the first `Host`/`Match` block; OpenSSH applies those to every connection but
 * they are not tied to a concrete host, so they never produce a ConnectBot host.
 */
data class SshConfigBlock(
    /** `Host` or `Match` keyword, or null for the implicit leading block. */
    val kind: String?,
    /** Raw pattern text after the keyword, e.g. `web1 example.com *.internal !bad`. */
    val pattern: String,
    /** Directives in file order. Keys are normalized to lower case. */
    val directives: List<SshConfigDirective>,
    /** Zero-based index of the first line of the block. */
    val startLine: Int,
    /** Zero-based index one past the last line of the block. */
    val endLine: Int,
) {
    val isGlobal: Boolean
        get() = kind == null
}

/** One `Key Value` line inside a configuration block. */
data class SshConfigDirective(
    val key: String,
    val value: String,
    /** Zero-based line index inside [SshConfigDocument.lines]. */
    val line: Int,
)

/** A parse problem that does not prevent the rest of the document from being used. */
data class SshConfigIssue(
    /** One-based line number for display purposes. */
    val line: Int,
    val message: String,
)

/** Parsed representation of an entire client configuration document. */
data class SshConfigDocument(
    /** Raw lines. Kept verbatim so unmodified parts survive a rewrite. */
    val lines: List<String>,
    val blocks: List<SshConfigBlock>,
    val issues: List<SshConfigIssue> = emptyList(),
)

/**
 * Hand-written parser for the subset of OpenSSH's `ssh_config` syntax that
 * ConnectBot can map onto its own host model.
 *
 * Supported: blank lines, `#` comments, `Key Value` pairs with either spaces or
 * `=` as the separator, quoting with `"`, `Host` and `Match` blocks, and
 * `Include` directives (reported as an issue because the target is unknown on
 * Android). Keyword names are case-insensitive.
 */
object SshConfig {
    /** Directives that create or modify a ConnectBot host. */
    val SUPPORTED_DIRECTIVES = setOf(
        "hostname",
        "user",
        "port",
        "identityfile",
        "proxyjump",
        "compression",
        "forwardagent",
        "localforward",
        "remoteforward",
        "dynamicforward",
        "serveraliveinterval",
        "connecttimeout",
        "identitiesonly",
        "proxycommand",
    )

    /** Directives that are recognized but only produce an informational issue. */
    val UNSUPPORTED_DIRECTIVES = setOf(
        "include",
        "match",
        "hostkeyalias",
        "certificatefile",
        "userknownhostsfile",
        "globalknownhostsfile",
        "canonicalizehostname",
        "remotecommand",
        "requesttty",
    )

    private val lineSplit = Regex("\r\n|\r|\n")

    fun parse(text: String): SshConfigDocument {
        val lines = splitLines(text)
        val issues = mutableListOf<SshConfigIssue>()
        val blocks = mutableListOf<SshConfigBlock>()

        var currentKind: String? = null
        var currentPattern = ""
        var currentDirectives = mutableListOf<SshConfigDirective>()
        var currentStart = 0

        fun flush(endLine: Int) {
            if (currentKind == null && currentDirectives.isEmpty()) {
                return
            }
            blocks += SshConfigBlock(
                kind = currentKind,
                pattern = currentPattern,
                directives = currentDirectives.toList(),
                startLine = currentStart,
                endLine = endLine,
            )
        }

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val raw = lines[lineIndex]
            val logical = stripComment(raw).trim()
            if (logical.isEmpty()) {
                lineIndex++
                continue
            }

            val parsed = splitDirective(logical)
            if (parsed == null) {
                issues += SshConfigIssue(lineIndex + 1, "Cannot parse line: ${raw.trim()}")
                lineIndex++
                continue
            }
            val keyRaw = parsed.first
            val valueRaw = parsed.second

            val key = keyRaw.lowercase()
            if (key == "host" || key == "match") {
                flush(endLine = lineIndex)
                if (valueRaw.isBlank()) {
                    issues += SshConfigIssue(lineIndex + 1, "Missing pattern after ${keyRaw}")
                }
                currentKind = keyRaw
                currentPattern = valueRaw
                currentDirectives = mutableListOf()
                currentStart = lineIndex
                lineIndex++
                continue
            }

            when {
                key in SUPPORTED_DIRECTIVES -> currentDirectives += SshConfigDirective(key, valueRaw, lineIndex)

                key in UNSUPPORTED_DIRECTIVES -> issues += SshConfigIssue(
                    lineIndex + 1,
                    "Directive '${keyRaw}' is not supported and was ignored",
                )

                else -> issues += SshConfigIssue(lineIndex + 1, "Unknown directive '${keyRaw}' was ignored")
            }
            lineIndex++
        }
        flush(endLine = lines.size)

        return SshConfigDocument(lines = lines, blocks = blocks, issues = issues)
    }

    /**
     * Splits a directive into key and value, honoring `=` separators and
     * double quotes. Returns null when the line has no usable keyword.
     */
    private fun splitDirective(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        var index = 0
        while (index < trimmed.length && !trimmed[index].isWhitespace() && trimmed[index] != '=') {
            index++
        }
        if (index == 0) return null
        val key = trimmed.substring(0, index)
        var rest = trimmed.substring(index).trimStart()
        if (rest.startsWith("=")) rest = rest.substring(1).trimStart()
        if (rest.isBlank()) return key to ""
        return key to unquote(rest)
    }

    /** Removes a single pair of surrounding double quotes if present. */
    fun unquote(value: String): String {
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    /** Strips a trailing `#` comment while respecting double quotes. */
    fun stripComment(line: String): String {
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            if (c == '"') inQuotes = !inQuotes
            if (c == '#' && !inQuotes) return line.substring(0, index)
            index++
        }
        return line
    }

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return lineSplit.split(text)
    }
}