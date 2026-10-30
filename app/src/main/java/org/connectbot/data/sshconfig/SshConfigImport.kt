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

import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import org.connectbot.util.HostConstants
import java.util.UUID

/** What the user chose for one imported block. */
enum class SshConfigImportDecision {
    CREATE,
    UPDATE,
    SKIP,
}

/** Existing host fields that differ from the imported values. */
data class SshConfigFieldChange(
    val label: String,
    val current: String,
    val incoming: String,
)

/** One row in the import preview. */
data class SshConfigPreviewItem(
    val entry: SshConfigHostEntry,
    /** Existing host matched by nickname or by hostname/user/port, if any. */
    val existingHost: Host?,
    val changes: List<SshConfigFieldChange>,
    val defaultDecision: SshConfigImportDecision,
) {
    val isNew: Boolean
        get() = existingHost == null
}

data class SshConfigPreview(
    val document: SshConfigDocument,
    val items: List<SshConfigPreviewItem>,
    val skipped: List<SshConfigSkip>,
    val issues: List<SshConfigIssue>,
)

/** Result of applying an import. */
data class SshConfigApplyResult(
    val createdHosts: Int,
    val updatedHosts: Int,
    val createdForwards: Int,
    val warnings: List<String>,
)

/**
 * Builds a preview and applies it. Kept free of Android types so it can be unit
 * tested on the JVM; callers pass in the current hosts and pubkeys.
 */
object SshConfigImport {
    fun buildPreview(
        text: String,
        existingHosts: List<Host>,
        pubkeyNicknames: Set<String>,
    ): SshConfigPreview {
        val document = SshConfig.parse(text)
        val conversion = SshConfigConversion.convert(document)
        val items = conversion.entries.map { entry ->
            val existing = matchExisting(entry, existingHosts)
            SshConfigPreviewItem(
                entry = entry,
                existingHost = existing,
                changes = if (existing == null) emptyList() else describeChanges(existing, entry, pubkeyNicknames),
                defaultDecision = if (existing == null) SshConfigImportDecision.CREATE else SshConfigImportDecision.UPDATE,
            )
        }
        return SshConfigPreview(
            document = document,
            items = items,
            skipped = conversion.skipped,
            issues = document.issues,
        )
    }

    /** Matches by nickname first, then by hostname/user/port, mirroring OpenSSH's alias-first lookup. */
    fun matchExisting(entry: SshConfigHostEntry, existingHosts: List<Host>): Host? {
        existingHosts.firstOrNull { it.protocol == "ssh" && it.nickname.equals(entry.alias, ignoreCase = true) }
            ?.let { return it }
        return existingHosts.firstOrNull {
            it.protocol == "ssh" &&
                it.hostname.equals(entry.hostname, ignoreCase = true) &&
                it.port == entry.port &&
                it.username == entry.username
        }
    }

    private fun describeChanges(host: Host, entry: SshConfigHostEntry, pubkeyNicknames: Set<String>): List<SshConfigFieldChange> {
        val changes = mutableListOf<SshConfigFieldChange>()
        if (host.hostname != entry.hostname && entry.hostnameExplicit) changes += SshConfigFieldChange("HostName", host.hostname, entry.hostname)
        if (host.username != entry.username && entry.usernameExplicit) changes += SshConfigFieldChange("User", host.username, entry.username)
        if (host.port != entry.port && entry.portExplicit) changes += SshConfigFieldChange("Port", host.port.toString(), entry.port.toString())
        if (host.compression != entry.compression && entry.compressionExplicit) {
            changes += SshConfigFieldChange("Compression", host.compression.toString(), entry.compression.toString())
        }
        val agent = entry.forwardAgent ?: HostConstants.AUTHAGENT_NO
        if ((host.useAuthAgent ?: HostConstants.AUTHAGENT_NO) != agent) {
            changes += SshConfigFieldChange("ForwardAgent", host.useAuthAgent ?: "no", agent)
        }
        val incomingKey = resolveIdentityFiles(entry, pubkeyNicknames).firstOrNull { it.nickname != null }
        if (incomingKey != null) {
            changes += SshConfigFieldChange("IdentityFile", host.pubkeyId.toString(), incomingKey.nickname ?: incomingKey.path)
        }
        return changes
    }

    /** Resolves the first `IdentityFile` that names a known ConnectBot key. */
    fun resolvePubkeyId(
        entry: SshConfigHostEntry,
        pubkeyNicknames: Set<String>,
        resolvePubkey: (String) -> UUID?,
    ): UUID? = resolveIdentityFiles(entry, pubkeyNicknames)
        .asSequence()
        .mapNotNull { it.nickname }
        .mapNotNull { nickname -> resolvePubkey(nickname) }
        .firstOrNull()

    /**
     * Maps `IdentityFile` paths to ConnectBot key nicknames. Accepts either a
     * known nickname or the file's base name, e.g. `~/.ssh/work_ed25519`.
     */
    fun resolveIdentityFiles(
        entry: SshConfigHostEntry,
        pubkeyNicknames: Set<String>,
    ): List<SshConfigIdentityFile> = entry.identityFiles.map { path ->
        val base = path.substringAfterLast('/').removeSuffix(".pub")
        val nickname = listOf(base, path).firstOrNull { candidate -> candidate in pubkeyNicknames }
        SshConfigIdentityFile(path = path, nickname = nickname)
    }

    /**
     * Applies the selected items. [resolvePubkey] maps a nickname to a key ID and
     * [resolveJumpHost] maps a ProxyJump alias to an existing host ID.
     *
     * Returns the hosts to insert/update and the port forwards to create; the
     * caller performs the database writes inside a transaction.
     */
    fun apply(
        preview: SshConfigPreview,
        decisions: Map<String, SshConfigImportDecision>,
        existingForwards: List<PortForward>,
        pubkeyNicknames: Set<String>,
        resolvePubkey: (String) -> UUID?,
        resolveJumpHost: (String) -> UUID?,
    ): SshConfigApplyPlan {
        val hosts = mutableListOf<Host>()
        val forwards = mutableListOf<PortForward>()
        val warnings = mutableListOf<String>()
        // Aliases created earlier in this run, so ProxyJump can point at them.
        val createdByAlias = mutableMapOf<String, UUID>()
        var created = 0
        var updated = 0
        var createdForwards = 0

        preview.items.forEach { item ->
            val decision = decisions[item.entry.alias] ?: item.defaultDecision
            if (decision == SshConfigImportDecision.SKIP) return@forEach

            val entry = item.entry
            val base = item.existingHost ?: Host(
                id = UUID.randomUUID(),
                repositoryId = IMPORT_REPOSITORY_ID,
                nickname = entry.alias,
                hostname = entry.hostname,
                username = entry.username,
                port = entry.port,
            )

            val pubkeyId = resolvePubkeyId(entry, pubkeyNicknames, resolvePubkey) ?: base.pubkeyId

            val jumpHostId = entry.proxyJump
                ?.substringBefore(',')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { jump ->
                    val resolved = resolveJumpHost(jump) ?: createdByAlias[jump.lowercase()]
                    if (resolved == null) warnings += "ProxyJump target '$jump' for ${entry.alias} was not found"
                    resolved
                }
                ?: base.jumpHostId

            if (entry.proxyCommand != null && jumpHostId != null) {
                warnings += "ProxyJump takes precedence over ProxyCommand for ${entry.alias}"
            }

            val merged = base.copy(
                nickname = if (item.isNew) entry.alias else base.nickname,
                protocol = "ssh",
                // Only overwrite fields the document actually specifies.
                hostname = if (item.isNew || entry.hostnameExplicit) entry.hostname else base.hostname,
                username = if (item.isNew || entry.usernameExplicit) entry.username else base.username,
                port = if (item.isNew || entry.portExplicit) entry.port else base.port,
                compression = if (item.isNew || entry.compressionExplicit) entry.compression else base.compression,
                useAuthAgent = entry.forwardAgent ?: base.useAuthAgent,
                pubkeyId = pubkeyId,
                jumpHostId = jumpHostId,
                proxyCommand = entry.proxyCommand ?: base.proxyCommand,
                repositoryId = base.repositoryId,
                isTemporary = false,
            )
            hosts += merged
            if (item.isNew) created++ else updated++
            createdByAlias[entry.alias.lowercase()] = merged.id

            entry.forwards.forEach { forward ->
                val candidate = toPortForward(merged.id, forward) ?: run {
                    warnings += "Unsupported forward in ${entry.alias}: $forward"
                    return@forEach
                }
                val duplicate = existingForwards.any {
                    it.hostId == merged.id && it.type == candidate.type && it.sourcePort == candidate.sourcePort && it.destAddr == candidate.destAddr && it.destPort == candidate.destPort
                }
                if (!duplicate) {
                    forwards += candidate
                    createdForwards++
                }
            }
        }

        return SshConfigApplyPlan(
            hosts = hosts,
            forwards = forwards,
            createdHosts = created,
            updatedHosts = updated,
            createdForwards = createdForwards,
            warnings = warnings,
        )
    }

    fun toPortForward(hostId: UUID, forward: SshConfigForward): PortForward? = when (forward) {
        is SshConfigForward.Local -> PortForward(
            hostId = hostId,
            nickname = "L ${forward.sourcePort}→${forward.destAddr}:${forward.destPort}",
            type = HostConstants.PORTFORWARD_LOCAL,
            sourceAddr = forward.sourceAddr,
            sourcePort = forward.sourcePort,
            destAddr = forward.destAddr,
            destPort = forward.destPort,
        )

        is SshConfigForward.Remote -> PortForward(
            hostId = hostId,
            nickname = "R ${forward.sourcePort}→${forward.destAddr}:${forward.destPort}",
            type = HostConstants.PORTFORWARD_REMOTE,
            sourceAddr = forward.sourceAddr,
            sourcePort = forward.sourcePort,
            destAddr = forward.destAddr,
            destPort = forward.destPort,
        )

        is SshConfigForward.Dynamic -> PortForward(
            hostId = hostId,
            nickname = "D ${forward.sourcePort}",
            type = HostConstants.PORTFORWARD_DYNAMIC5,
            sourceAddr = forward.sourceAddr,
            sourcePort = forward.sourcePort,
            destAddr = null,
            destPort = 0,
        )
    }

    /**
     * Renders hosts back into an `ssh_config` document, one block per host, so
     * the editor can round-trip what ConnectBot has stored.
     */
    fun export(hosts: List<Host>, forwardsByHost: Map<UUID, List<PortForward>>, pubkeyNames: Map<UUID, String>): String {
        val builder = StringBuilder()
        hosts.filter { it.protocol == "ssh" && !it.isTemporary }
            .sortedBy { it.nickname.lowercase() }
            .forEach { host ->
                builder.append("Host ").append(quoteIfNeeded(host.nickname)).append('\n')
                builder.append("    HostName ").append(quoteIfNeeded(host.hostname)).append('\n')
                if (host.username.isNotEmpty()) builder.append("    User ").append(quoteIfNeeded(host.username)).append('\n')
                if (host.port != SshConfigConversion.DEFAULT_SSH_PORT) builder.append("    Port ").append(host.port).append('\n')
                host.proxyCommand?.takeIf { it.isNotBlank() }?.let { command ->
                    builder.append("    ProxyCommand ").append(command).append('\n')
                }
                if (host.compression) builder.append("    Compression yes\n")
                host.useAuthAgent?.takeIf { it != HostConstants.AUTHAGENT_NO }?.let {
                    builder.append("    ForwardAgent ").append(if (it == HostConstants.AUTHAGENT_YES) "yes" else "no").append('\n')
                }
                pubkeyNames[host.pubkeyId]?.let { name ->
                    builder.append("    IdentityFile ~/.ssh/").append(name).append('\n')
                }
                forwardsByHost[host.id].orEmpty().forEach { forward ->
                    when (forward.type) {
                        HostConstants.PORTFORWARD_LOCAL -> builder.append("    LocalForward ")
                            .append(forward.sourceAddr).append(':').append(forward.sourcePort)
                            .append(' ').append(forward.destAddr).append(':').append(forward.destPort).append('\n')

                        HostConstants.PORTFORWARD_REMOTE -> builder.append("    RemoteForward ")
                            .append(forward.sourceAddr).append(':').append(forward.sourcePort)
                            .append(' ').append(forward.destAddr).append(':').append(forward.destPort).append('\n')

                        HostConstants.PORTFORWARD_DYNAMIC4,
                        HostConstants.PORTFORWARD_DYNAMIC5,
                        -> builder.append("    DynamicForward ")
                            .append(forward.sourceAddr).append(':').append(forward.sourcePort).append('\n')
                    }
                }
                builder.append('\n')
            }
        return builder.toString().trimEnd() + "\n"
    }

    private fun quoteIfNeeded(value: String): String = if (value.any { it.isWhitespace() }) "\"$value\"" else value
}

/** An `IdentityFile` directive with the ConnectBot key it resolves to, if any. */
data class SshConfigIdentityFile(
    val path: String,
    val nickname: String?,
)

/** Database-neutral output of [SshConfigImport.apply]. */
data class SshConfigApplyPlan(
    val hosts: List<Host>,
    val forwards: List<PortForward>,
    val createdHosts: Int,
    val updatedHosts: Int,
    val createdForwards: Int,
    val warnings: List<String>,
)

/** Local-only hosts are the only repository an import can target. */
internal val IMPORT_REPOSITORY_ID: UUID = EntityIds.LOCAL