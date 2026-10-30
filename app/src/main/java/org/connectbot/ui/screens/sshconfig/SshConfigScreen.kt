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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.data.sshconfig.SshConfigImportDecision
import org.connectbot.data.sshconfig.SshConfigPreviewItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SshConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val loadedMessage = stringResource(R.string.ssh_config_loaded_current)

    LaunchedEffect(uiState.error) {
        uiState.error?.let { reason ->
            snackbarHostState.showSnackbar(context.getString(R.string.ssh_config_error, reason))
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.loadedCurrent) {
        if (uiState.loadedCurrent) {
            snackbarHostState.showSnackbar(loadedMessage)
            viewModel.clearLoadedCurrent()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (uiState.step) {
                SshConfigStep.EDIT -> SshConfigEditorPane(
                    uiState = uiState,
                    onTextChange = viewModel::updateText,
                    onPreview = viewModel::preview,
                    onLoadCurrent = viewModel::loadCurrentHosts,
                    onLoadExample = viewModel::loadExample,
                )

                SshConfigStep.PREVIEW -> SshConfigPreviewPane(
                    uiState = uiState,
                    onDecision = viewModel::setDecision,
                    onApply = viewModel::apply,
                    onBack = viewModel::backToEdit,
                    onDismissResult = viewModel::dismissResult,
                )
            }
        }
    }
}

@Composable
private fun SshConfigEditorPane(
    uiState: SshConfigUiState,
    onTextChange: (String) -> Unit,
    onPreview: () -> Unit,
    onLoadCurrent: () -> Unit,
    onLoadExample: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLoadCurrent, enabled = !uiState.isWorking) {
                Text(stringResource(R.string.ssh_config_load_current))
            }
            OutlinedButton(onClick = onLoadExample, enabled = !uiState.isWorking) {
                Text(stringResource(R.string.ssh_config_load_example))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text(stringResource(R.string.ssh_config_editor_label)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onPreview,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isWorking && uiState.text.isNotBlank(),
        ) {
            Text(stringResource(R.string.ssh_config_preview))
        }
    }
}

@Composable
private fun SshConfigPreviewPane(
    uiState: SshConfigUiState,
    onDecision: (String, SshConfigImportDecision) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val preview = uiState.preview ?: return
    val result = uiState.result

    if (result != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(
                    R.string.ssh_config_import_result,
                    result.createdHosts,
                    result.updatedHosts,
                    result.createdForwards,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            result.warnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ssh_config_back_to_edit))
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (preview.items.isEmpty()) {
            Text(
                stringResource(R.string.ssh_config_no_hosts),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(preview.items, key = { it.entry.alias }) { item ->
                PreviewRow(
                    item = item,
                    decision = uiState.decisions[item.entry.alias] ?: item.defaultDecision,
                    onDecision = { decision -> onDecision(item.entry.alias, decision) },
                )
                HorizontalDivider()
            }
            if (preview.skipped.isNotEmpty() || preview.issues.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.ssh_config_preview_problems),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(preview.skipped) { skip ->
                    ListItem(
                        headlineContent = { Text(skip.alias) },
                        supportingContent = { Text("${skip.reason} (line ${skip.line})") },
                    )
                }
                items(preview.issues) { issue ->
                    ListItem(
                        headlineContent = { Text("line ${issue.line}") },
                        supportingContent = { Text(issue.message) },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ssh_config_back_to_edit))
            }
            Button(onClick = onApply, modifier = Modifier.weight(1f), enabled = uiState.canImport && !uiState.isWorking) {
                Text(
                    stringResource(
                        R.string.ssh_config_import_confirm,
                        preview.items.count { (uiState.decisions[it.entry.alias] ?: it.defaultDecision) != SshConfigImportDecision.SKIP },
                    ),
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(
    item: SshConfigPreviewItem,
    decision: SshConfigImportDecision,
    onDecision: (SshConfigImportDecision) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row {
            Text(item.entry.alias, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        stringResource(
                            when {
                                decision == SshConfigImportDecision.SKIP -> R.string.ssh_config_badge_skip
                                item.isNew -> R.string.ssh_config_badge_new
                                else -> R.string.ssh_config_badge_update
                            },
                        ),
                    )
                },
            )
        }
        Text(
            "${item.entry.hostname}:${item.entry.port}" + if (item.entry.username.isNotEmpty()) " (${item.entry.username})" else "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.changes.forEach { change ->
            Text(
                stringResource(R.string.ssh_config_change, change.label, change.current, change.incoming),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshConfigImportDecision.entries.forEach { option ->
                FilterChip(
                    selected = decision == option,
                    onClick = { onDecision(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    SshConfigImportDecision.CREATE -> R.string.ssh_config_badge_new
                                    SshConfigImportDecision.UPDATE -> R.string.ssh_config_badge_update
                                    SshConfigImportDecision.SKIP -> R.string.ssh_config_badge_skip
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}