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

/**
 * Query parameters that let a one-line `ssh://` link carry connection options
 * without creating a saved host.
 *
 * Example:
 * ```
 * ssh://damar@host.example/?proxyCommand=cloudflared%20access%20ssh%20--hostname%20%25h
 * ```
 */
object HostUriParams {
    /**
     * `ProxyCommand` for this connection. Empty value explicitly disables the
     * proxy configured on a saved host, which lets a link opt out of a tunnel.
     */
    const val PROXY_COMMAND = "proxyCommand"

    /**
     * Reads the `ProxyCommand` override from [uri], URL-decoding it for the caller.
     *
     * Returns null when the parameter is absent (use the saved host's value),
     * or a [ProxyOverride] carrying the decoded command otherwise.
     */
    fun proxyOverride(uri: Uri): ProxyOverride? {
        if (!uri.queryParameterNames.contains(PROXY_COMMAND)) return null
        return ProxyOverride(uri.getQueryParameter(PROXY_COMMAND).orEmpty())
    }

    /** A `proxyCommand` query parameter, decoded; blank means "no proxy". */
    data class ProxyOverride(val command: String) {
        val value: String?
            get() = command.trim().takeIf { it.isNotEmpty() }
    }
}
