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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class QuickConnectTest {
    @Test
    fun plainDestinationUsesDefaults() {
        val request = QuickConnect.parse("ssh.example.com")

        assertThat(request).isNotNull
        assertThat(request!!.username).isEmpty()
        assertThat(request.hostname).isEqualTo("ssh.example.com")
        assertThat(request.port).isEqualTo(22)
        assertThat(request.proxyCommand).isNull()
    }

    @Test
    fun userAndPortAreParsed() {
        val request = QuickConnect.parse("damar@ssh.example.com:2222")

        assertThat(request!!.username).isEqualTo("damar")
        assertThat(request.hostname).isEqualTo("ssh.example.com")
        assertThat(request.port).isEqualTo(2222)
    }

    @Test
    fun leadingSshKeywordAndPortFlagAreAccepted() {
        val request = QuickConnect.parse("ssh -p 2022 damar@ssh.example.com")

        assertThat(request!!.username).isEqualTo("damar")
        assertThat(request.port).isEqualTo(2022)
    }

    @Test
    fun proxyCommandWithQuotesIsParsed() {
        val request = QuickConnect.parse(
            """ssh damar@ssh.example.com -o ProxyCommand="cloudflared access ssh --hostname %h"""",
        )

        assertThat(request!!.proxyCommand).isEqualTo("cloudflared access ssh --hostname %h")
    }

    @Test
    fun proxyCommandWithEqualsAndSingleQuotesIsParsed() {
        val request = QuickConnect.parse(
            "damar@ssh.example.com -oProxyCommand='cloudflared access ssh --hostname %h'",
        )

        assertThat(request!!.proxyCommand).isEqualTo("cloudflared access ssh --hostname %h")
    }

    @Test
    fun usernameFlagIsAccepted() {
        val request = QuickConnect.parse("ssh -l damar ssh.example.com")

        assertThat(request!!.username).isEqualTo("damar")
    }

    @Test
    fun optionsWithArgumentsDoNotBecomeTheDestination() {
        val request = QuickConnect.parse("ssh -i ~/.ssh/id_ed25519 damar@ssh.example.com")

        assertThat(request!!.hostname).isEqualTo("ssh.example.com")
        assertThat(request.username).isEqualTo("damar")
    }

    @Test
    fun userInDestinationWinsOverUserFlag() {
        val request = QuickConnect.parse("ssh -l root damar@ssh.example.com")

        assertThat(request!!.username).isEqualTo("damar")
    }

    @Test
    fun bracketedIpv6WithPortIsParsed() {
        val request = QuickConnect.parse("damar@[2001:db8::1]:2222")

        assertThat(request!!.hostname).isEqualTo("[2001:db8::1]")
        assertThat(request.port).isEqualTo(2222)
    }

    @Test
    fun unbracketedIpv6KeepsDefaultPort() {
        val request = QuickConnect.parse("damar@2001:db8::1")

        assertThat(request!!.hostname).isEqualTo("2001:db8::1")
        assertThat(request.port).isEqualTo(22)
    }

    @Test
    fun emptyInputIsRejected() {
        assertThat(QuickConnect.parse("   ")).isNull()
    }

    @Test
    fun uriIsRejectedSoTheUriPipelineHandlesIt() {
        assertThat(QuickConnect.parse("ssh://damar@ssh.example.com")).isNull()
    }

    @Test
    fun badPortIsRejected() {
        assertThat(QuickConnect.parse("damar@ssh.example.com:99999")).isNull()
        assertThat(QuickConnect.parse("damar@ssh.example.com:abc")).isNull()
    }

    @Test
    fun unmatchedQuoteIsRejected() {
        assertThat(QuickConnect.parse("""damar@ssh.example.com -o ProxyCommand="unterminated""")).isNull()
    }

    @Test
    fun twoDestinationsAreRejected() {
        assertThat(QuickConnect.parse("one.example.com two.example.com")).isNull()
    }
}