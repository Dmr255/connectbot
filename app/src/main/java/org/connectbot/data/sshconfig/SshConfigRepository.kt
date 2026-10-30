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

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.dao.HostDao
import org.connectbot.data.dao.PortForwardDao
import org.connectbot.data.dao.PubkeyDao
import org.connectbot.di.CoroutineDispatchers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges `ssh_config` documents and the Room-backed host list.
 *
 * The draft document is stored as plain text in the app's private storage so the
 * editor can be reopened with the last text the user worked on.
 */
@Singleton
class SshConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ConnectBotDatabase,
    private val hostDao: HostDao,
    private val portForwardDao: PortForwardDao,
    private val pubkeyDao: PubkeyDao,
    private val dispatchers: CoroutineDispatchers,
) {
    private val draftFile: File
        get() = File(context.filesDir, DRAFT_FILE_NAME)

    fun loadDraft(): String = withSyncIo {
        draftFile.takeIf { it.isFile }?.readText().orEmpty()
    }

    fun saveDraft(text: String) = withSyncIo {
        draftFile.parentFile?.mkdirs()
        draftFile.writeText(text)
    }

    suspend fun buildPreview(text: String): SshConfigPreview = withContext(dispatchers.io) {
        SshConfigImport.buildPreview(
            text = text,
            existingHosts = hostDao.getAll(),
            pubkeyNicknames = pubkeyDao.getAll().map { it.nickname }.toSet(),
        )
    }

    suspend fun apply(
        preview: SshConfigPreview,
        decisions: Map<String, SshConfigImportDecision>,
    ): SshConfigApplyResult = withContext(dispatchers.io) {
        val pubkeys = pubkeyDao.getAll()
        val nicknames = pubkeys.map { it.nickname }.toSet()
        val hostsByNickname = hostDao.getAll().associateBy { it.nickname.lowercase() }
        val existingForwards = portForwardDao.getAll()

        val plan = SshConfigImport.apply(
            preview = preview,
            decisions = decisions,
            existingForwards = existingForwards,
            pubkeyNicknames = nicknames,
            resolvePubkey = { nickname -> pubkeys.firstOrNull { it.nickname == nickname }?.id },
            resolveJumpHost = { alias -> hostsByNickname[alias.lowercase()]?.id },
        )

        database.withTransaction {
            plan.hosts.forEach { host ->
                if (hostDao.getById(host.id) == null) hostDao.insertRow(host) else hostDao.update(host)
            }
            plan.forwards.forEach { portForwardDao.insert(it) }
        }

        SshConfigApplyResult(
            createdHosts = plan.createdHosts,
            updatedHosts = plan.updatedHosts,
            createdForwards = plan.createdForwards,
            warnings = plan.warnings,
        )
    }

    suspend fun exportCurrentHosts(): String = withContext(dispatchers.io) {
        val hosts = hostDao.getAll().filter { it.repositoryId == EntityIds.LOCAL }
        val forwards = hosts.associate { it.id to portForwardDao.getByHost(it.id) }
        val names = pubkeyDao.getAll().associate { it.id to it.nickname }
        SshConfigImport.export(hosts, forwards, names)
    }

    private inline fun <T> withSyncIo(block: () -> T): T {
        draftFile.parentFile?.mkdirs()
        return block()
    }

    companion object {
        private const val DRAFT_FILE_NAME = "ssh_config_draft"
    }
}