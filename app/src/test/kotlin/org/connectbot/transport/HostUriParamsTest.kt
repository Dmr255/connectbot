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

import android.net.Uri
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostUriParamsTest {
    @Test
    fun proxyOverride_decodesUrlEncodedCommand() {
        val uri = Uri.parse(
            "ssh://damar@ssh.example.com/?proxyCommand=cloudflared%20access%20ssh%20--hostname%20%25h",
        )

        val override = HostUriParams.proxyOverride(uri)

        assertThat(override).isNotNull
        assertThat(override!!.value).isEqualTo("cloudflared access ssh --hostname %h")
    }

    @Test
    fun proxyOverride_isNullWhenParameterMissing() {
        val uri = Uri.parse("ssh://damar@ssh.example.com/")

        assertThat(HostUriParams.proxyOverride(uri)).isNull()
    }

    @Test
    fun proxyOverride_blankValueMeansNoProxy() {
        val uri = Uri.parse("ssh://damar@ssh.example.com/?proxyCommand=")

        val override = HostUriParams.proxyOverride(uri)

        assertThat(override).isNotNull
        assertThat(override!!.value).isNull()
    }

    @Test
    fun proxyOverride_ignoresSurroundingWhitespace() {
        val uri = Uri.parse("ssh://damar@ssh.example.com/?proxyCommand=%20none%20")

        assertThat(HostUriParams.proxyOverride(uri)!!.value).isEqualTo("none")
    }

    @Test
    fun createHost_carriesProxyCommandFromUri() {
        val uri = Uri.parse(
            "ssh://damar@ssh.example.com:2222/?proxyCommand=cloudflared%20access%20ssh%20--hostname%20%25h",
        )

        val host = SSH(null, null, null).createHost(uri)

        assertThat(host.hostname).isEqualTo("ssh.example.com")
        assertThat(host.username).isEqualTo("damar")
        assertThat(host.port).isEqualTo(2222)
        assertThat(host.proxyCommand).isEqualTo("cloudflared access ssh --hostname %h")
    }

    @Test
    fun createHost_withoutParameterLeavesProxyCommandNull() {
        val host = SSH(null, null, null).createHost(Uri.parse("ssh://damar@ssh.example.com/"))

        assertThat(host.proxyCommand).isNull()
    }

    @Test
    fun hostUri_stillRoundTripsWithoutProxyCommand() {
        val host = org.connectbot.data.entity.Host(
            nickname = "web",
            hostname = "web.example.com",
            username = "deploy",
            port = 2222,
            isTemporary = true,
        )

        assertThat(host.getUri().toString()).isEqualTo("ssh://deploy@web.example.com:2222#web")
    }

    @Test
    fun temporaryHostUri_carriesProxyCommandForReconnect() {
        val host = org.connectbot.data.entity.Host(
            nickname = "tunnel",
            hostname = "ssh.example.com",
            username = "damar",
            isTemporary = true,
            proxyCommand = "cloudflared access ssh --hostname %h",
        )

        val reparsed = SSH(null, null, null).createHost(host.getUri())

        assertThat(reparsed.proxyCommand).isEqualTo("cloudflared access ssh --hostname %h")
    }

    @Test
    fun savedHostUri_doesNotEmbedProxyCommand() {
        val host = org.connectbot.data.entity.Host(
            nickname = "saved",
            hostname = "ssh.example.com",
            username = "damar",
            proxyCommand = "cloudflared access ssh --hostname %h",
        )

        assertThat(host.getUri().getQueryParameter("proxyCommand")).isNull()
    }
}
