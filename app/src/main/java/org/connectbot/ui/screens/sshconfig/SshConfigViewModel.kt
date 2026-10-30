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

package org.connectbot.ui.screens.sshconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.sshconfig.SshConfigApplyResult
import org.connectbot.data.sshconfig.SshConfigImportDecision
import org.connectbot.data.sshconfig.SshConfigPreview
import org.connectbot.data.sshconfig.SshConfigRepository
import org.connectbot.di.CoroutineDispatchers
import javax.inject.Inject

/** Which pane the editor is showing. */
enum class SshConfigStep {
    EDIT,
    PREVIEW,
}

data class SshConfigUiState(
    val text: String = "",
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val step: SshConfigStep = SshConfigStep.EDIT,
    val preview: SshConfigPreview? = null,
    val decisions: Map<String, SshConfigImportDecision> = emptyMap(),
    val result: SshConfigApplyResult? = null,
    val error: String? = null,
    /** Set after the current hosts were copied into the editor. */
    val loadedCurrent: Boolean = false,
) {
    val canImport: Boolean
        get() = preview != null && preview.items.any { item ->
            (decisions[item.entry.alias] ?: item.defaultDecision) != SshConfigImportDecision.SKIP
        }
}

@HiltViewModel
class SshConfigViewModel @Inject constructor(
    private val repository: SshConfigRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshConfigUiState())
    val uiState: StateFlow<SshConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val draft = withContext(dispatchers.io) { repository.loadDraft() }
            _uiState.update { it.copy(text = draft, isLoading = false) }
        }
    }

    fun updateText(text: String) {
        _uiState.update { it.copy(text = text, error = null) }
    }

    fun loadExample() {
        _uiState.update { it.copy(text = EXAMPLE_CONFIG, error = null) }
    }

    fun loadCurrentHosts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            try {
                val exported = withContext(dispatchers.io) { repository.exportCurrentHosts() }
                _uiState.update { it.copy(text = exported, isWorking = false, loadedCurrent = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isWorking = false, error = e.message ?: "Failed to export hosts") }
            }
        }
    }

    fun preview() {
        val text = _uiState.value.text
        if (text.isBlank()) {
            _uiState.update { it.copy(error = "The configuration is empty") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            try {
                val preview = withContext(dispatchers.io) { repository.buildPreview(text) }
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        step = SshConfigStep.PREVIEW,
                        preview = preview,
                        decisions = emptyMap(),
                        result = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isWorking = false, error = e.message ?: "Failed to parse configuration") }
            }
        }
    }

    fun setDecision(alias: String, decision: SshConfigImportDecision) {
        _uiState.update { it.copy(decisions = it.decisions + (alias to decision)) }
    }

    fun backToEdit() {
        _uiState.update { it.copy(step = SshConfigStep.EDIT, preview = null, decisions = emptyMap()) }
    }

    fun apply() {
        val preview = _uiState.value.preview ?: return
        val decisions = _uiState.value.decisions
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, error = null) }
            try {
                withContext(dispatchers.io) { repository.saveDraft(_uiState.value.text) }
                val result = withContext(dispatchers.io) { repository.apply(preview, decisions) }
                _uiState.update {
                    it.copy(isWorking = false, result = result)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isWorking = false, error = e.message ?: "Failed to import hosts") }
            }
        }
    }

    fun dismissResult() {
        _uiState.update {
            it.copy(step = SshConfigStep.EDIT, preview = null, decisions = emptyMap(), result = null)
        }
    }

    fun clearLoadedCurrent() {
        _uiState.update { it.copy(loadedCurrent = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        private val EXAMPLE_CONFIG = """
            # Example OpenSSH client configuration
            Host web1
                HostName web1.example.com
                User deploy
                Port 2222
                IdentityFile ~/.ssh/id_ed25519

            Host db
                HostName db.example.com
                User admin
                LocalForward 5432 localhost:5432
        """.trimIndent()
    }
}