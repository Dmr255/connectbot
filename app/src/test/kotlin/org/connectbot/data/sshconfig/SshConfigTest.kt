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

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.junit.Test
import java.util.UUID

class SshConfigTest {
    @Test
    fun parse_handlesCommentsQuotesAndEqualsSeparator() {
        val document = SshConfig.parse(
            """
            # leading comment
            Host "work laptop"
                HostName = laptop.example.com   # trailing comment
                User alice
        """.trimIndent(),
        )

        assertThat(document.blocks).hasSize(1)
        val block = document.blocks.single()
        assertThat(block.pattern).isEqualTo("work laptop")
        assertThat(block.directives.map { it.key }).containsExactly("hostname", "user")
        assertThat(block.directives.first().value).isEqualTo("laptop.example.com")
    }

    @Test
    fun convert_mapsSupportedDirectivesAndForwards() {
        val document = SshConfig.parse(
            """
            Host web
                HostName 10.0.0.1
                User deploy
                Port 2222
                Compression yes
                ForwardAgent yes
                IdentityFile ~/.ssh/work
                LocalForward 8080 localhost:80
                RemoteForward 9000 127.0.0.1:9000
                DynamicForward 1080
        """.trimIndent(),
        )

        val entry = SshConfigConversion.convert(document).entries.single()
        assertThat(entry.alias).isEqualTo("web")
        assertThat(entry.hostname).isEqualTo("10.0.0.1")
        assertThat(entry.username).isEqualTo("deploy")
        assertThat(entry.port).isEqualTo(2222)
        assertThat(entry.compression).isTrue()
        assertThat(entry.forwardAgent).isEqualTo("yes")
        assertThat(entry.identityFiles).containsExactly("~/.ssh/work")
        assertThat(entry.forwards).containsExactly(
            SshConfigForward.Local("localhost", 8080, "localhost", 80),
            SshConfigForward.Remote("localhost", 9000, "127.0.0.1", 9000),
            SshConfigForward.Dynamic("localhost", 1080),
        )
    }

    @Test
    fun convert_fallsBackToAliasAndDefaultPort() {
        val document = SshConfig.parse("Host simple\n")

        val entry = SshConfigConversion.convert(document).entries.single()
        assertThat(entry.hostname).isEqualTo("simple")
        assertThat(entry.port).isEqualTo(22)
        assertThat(entry.username).isEmpty()
    }

    @Test
    fun convert_skipsWildcardsMatchesAndGlobalDirectives() {
        val document = SshConfig.parse(
            """
            User global
            Host *.example.com
                User corp
            Match host db
                User admin
        """.trimIndent(),
        )

        val result = SshConfigConversion.convert(document)
        assertThat(result.entries).isEmpty()
        assertThat(result.skipped.map { it.alias }).containsExactly("(global)", "*.example.com", "(match)")
    }

    @Test
    fun convert_reportsUnknownDirectives() {
        val document = SshConfig.parse("Host a\n    ProxyCommand nc %h %p\n")
        assertThat(document.issues).hasSize(1)
        assertThat(document.issues.single().message).contains("ProxyCommand")
    }

    @Test
    fun convert_generatesOneEntryPerConcreteAlias() {
        val document = SshConfig.parse("Host one two *.wild\n    User shared\n")
        val entries = SshConfigConversion.convert(document).entries
        assertThat(entries.map { it.alias }).containsExactly("one", "two")
    }

    @Test
    fun convert_sameAliasInTwoBlocksKeepsFirstValueAndFillsGaps() {
        val document = SshConfig.parse(
            """
            Host work
                HostName first.example.com
                User alice
            Host work
                HostName second.example.com
                Port 2200
                LocalForward 8080 localhost:80
        """.trimIndent(),
        )

        val entry = SshConfigConversion.convert(document).entries.single()
        assertThat(entry.alias).isEqualTo("work")
        assertThat(entry.hostname).isEqualTo("first.example.com")
        assertThat(entry.username).isEqualTo("alice")
        assertThat(entry.port).isEqualTo(2200)
        assertThat(entry.forwards).hasSize(1)
    }

    @Test
    fun apply_resolvesProxyJumpToHostCreatedInSameImport() {
        val preview = SshConfigImport.buildPreview(
            """
            Host bastion
                HostName bastion.example.com
            Host app
                HostName app.internal
                ProxyJump bastion
            """.trimIndent(),
            existingHosts = emptyList(),
            pubkeyNicknames = emptySet(),
        )

        val plan = SshConfigImport.apply(
            preview = preview,
            decisions = emptyMap(),
            existingForwards = emptyList(),
            pubkeyNicknames = emptySet(),
            resolvePubkey = { null },
            resolveJumpHost = { null },
        )

        val bastion = plan.hosts.single { it.nickname == "bastion" }
        val app = plan.hosts.single { it.nickname == "app" }
        assertThat(app.jumpHostId).isEqualTo(bastion.id)
        assertThat(plan.warnings).isEmpty()
    }

    @Test
    fun preview_matchesExistingHostByNicknameAndReportsChanges() {
        val existing = Host(
            id = UUID.randomUUID(),
            nickname = "web",
            hostname = "old.example.com",
            username = "root",
            port = 22,
        )

        val preview = SshConfigImport.buildPreview(
            "Host web\n    HostName new.example.com\n    User root\n",
            existingHosts = listOf(existing),
            pubkeyNicknames = emptySet(),
        )

        val item = preview.items.single()
        assertThat(item.isNew).isFalse()
        assertThat(item.existingHost?.id).isEqualTo(existing.id)
        assertThat(item.defaultDecision).isEqualTo(SshConfigImportDecision.UPDATE)
        assertThat(item.changes.map { it.label }).containsExactly("HostName")
    }

    @Test
    fun preview_marksUnknownHostAsNew() {
        val preview = SshConfigImport.buildPreview(
            "Host fresh\n    HostName fresh.example.com\n",
            existingHosts = emptyList(),
            pubkeyNicknames = emptySet(),
        )

        assertThat(preview.items.single().isNew).isTrue()
        assertThat(preview.items.single().defaultDecision).isEqualTo(SshConfigImportDecision.CREATE)
    }

    @Test
    fun apply_createsHostsAndForwardsAndResolvesKeys() {
        val keyId = UUID.randomUUID()
        val preview = SshConfigImport.buildPreview(
            """
            Host web
                HostName web.example.com
                User deploy
                IdentityFile ~/.ssh/work
                LocalForward 8080 localhost:80
            """.trimIndent(),
            existingHosts = emptyList(),
            pubkeyNicknames = setOf("work"),
        )

        val plan = SshConfigImport.apply(
            preview = preview,
            decisions = emptyMap(),
            existingForwards = emptyList(),
            pubkeyNicknames = setOf("work"),
            resolvePubkey = { nickname -> keyId.takeIf { nickname == "work" } },
            resolveJumpHost = { null },
        )

        assertThat(plan.createdHosts).isEqualTo(1)
        assertThat(plan.updatedHosts).isZero()
        assertThat(plan.createdForwards).isEqualTo(1)
        val host = plan.hosts.single()
        assertThat(host.nickname).isEqualTo("web")
        assertThat(host.pubkeyId).isEqualTo(keyId)
        assertThat(host.repositoryId).isEqualTo(EntityIds.LOCAL)
        assertThat(plan.forwards.single().type).isEqualTo("local")
        assertThat(plan.forwards.single().hostId).isEqualTo(host.id)
    }

    @Test
    fun apply_respectsSkipDecision() {
        val preview = SshConfigImport.buildPreview(
            "Host skipped\n",
            existingHosts = emptyList(),
            pubkeyNicknames = emptySet(),
        )

        val plan = SshConfigImport.apply(
            preview = preview,
            decisions = mapOf("skipped" to SshConfigImportDecision.SKIP),
            existingForwards = emptyList(),
            pubkeyNicknames = emptySet(),
            resolvePubkey = { null },
            resolveJumpHost = { null },
        )

        assertThat(plan.hosts).isEmpty()
        assertThat(plan.createdHosts).isZero()
    }

    @Test
    fun apply_warnsWhenJumpHostIsUnknown() {
        val preview = SshConfigImport.buildPreview(
            "Host app\n    ProxyJump bastion\n",
            existingHosts = emptyList(),
            pubkeyNicknames = emptySet(),
        )

        val plan = SshConfigImport.apply(
            preview = preview,
            decisions = emptyMap(),
            existingForwards = emptyList(),
            pubkeyNicknames = emptySet(),
            resolvePubkey = { null },
            resolveJumpHost = { null },
        )

        assertThat(plan.warnings).hasSize(1)
        assertThat(plan.warnings.single()).contains("bastion")
    }

    @Test
    fun export_roundTripsThroughParser() {
        val host = Host(
            id = UUID.randomUUID(),
            nickname = "web",
            hostname = "web.example.com",
            username = "deploy",
            port = 2222,
            compression = true,
        )

        val text = SshConfigImport.export(
            hosts = listOf(host),
            forwardsByHost = emptyMap(),
            pubkeyNames = emptyMap(),
        )
        val entry = SshConfigConversion.convert(SshConfig.parse(text)).entries.single()

        assertThat(entry.alias).isEqualTo("web")
        assertThat(entry.hostname).isEqualTo("web.example.com")
        assertThat(entry.username).isEqualTo("deploy")
        assertThat(entry.port).isEqualTo(2222)
        assertThat(entry.compression).isTrue()
    }

    @Test
    fun parseForward_rejectsUnixSocketsAndMissingPorts() {
        assertThat(SshConfigConversion.parseForward("/tmp/sock host:80", remote = false)).isNull()
        assertThat(SshConfigConversion.parseForward("8080 host", remote = false)).isNull()
        assertThat(SshConfigConversion.parseForward("8080 host:80", remote = false))
            .isEqualTo(SshConfigForward.Local("localhost", 8080, "host", 80))
    }
}