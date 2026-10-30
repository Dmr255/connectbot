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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.transport.TransportFactory
import java.io.File

/**
 * Locates and launches the Cloudflare Tunnel client bundled as `libcloudflared.so`.
 *
 * Android refuses to `execve()` binaries stored in the app's data directory, so
 * the client must ship inside the APK and be read from `nativeLibraryDir` — the
 * same mechanism ConnectBot already uses for `mosh-client`.
 */
object CloudflaredTunnel {
    private const val LIBRARY_NAME = "libcloudflared.so"

    /** Returns the bundled tunnel client, or null when this APK does not contain it. */
    fun resolveExecutable(context: Context): File? {
        val candidate = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
        if (!candidate.isFile) return null
        if (!candidate.canExecute() && !candidate.setExecutable(true, true)) return null
        return candidate
    }

    /** A [TransportFactory] that runs the tunnel client for one connection. */
    fun transportFactory(executable: File, spec: ProxyCommandSpec, onStderrLine: ((String) -> Unit)? = null): TransportFactory = TransportFactory {
        withContext(Dispatchers.IO) {
            ProcessTransport.start(executable, spec.arguments, onStderrLine)
        }
    }
}