package com.example.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.devtools.DevToolsKeyValueEntry
import com.example.devtools.DevToolsNetworkEntry
import com.example.devtools.DevToolsStorageSnapshot

@Composable
fun DevToolsNetworkPane(
    requests: List<DevToolsNetworkEntry>,
    searchQuery: String,
    onlyFlagged: Boolean,
    isHomePage: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleFlaggedOnly: () -> Unit,
    onSyncTiming: () -> Unit,
    onHardReload: () -> Unit,
    onClear: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val filteredRequests = remember(requests, searchQuery, onlyFlagged) {
        val q = searchQuery.trim().lowercase()
        requests.asReversed().filter { entry ->
            val flagOk = !onlyFlagged || entry.isBlocked || entry.isError
            val queryOk = q.isEmpty() ||
                entry.url.lowercase().contains(q) ||
                entry.resourceType.lowercase().contains(q) ||
                entry.statusLabel.lowercase().contains(q)
            flagOk && queryOk
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.devtools_network_search_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("devtools_network_search_input")
            )

            IconButton(
                onClick = onHardReload,
                enabled = !isHomePage,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("devtools_network_reload_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.devtools_cd_hard_reload)
                )
            }

            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("devtools_network_clear_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.devtools_cd_clear)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${requests.size} reqs",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterChip(
                selected = onlyFlagged,
                onClick = onToggleFlaggedOnly,
                label = {
                    val flaggedCount = requests.count { it.isBlocked || it.isError }
                    Text("Blocked / Failed ($flaggedCount)")
                },
                modifier = Modifier.testTag("devtools_network_flagged_chip")
            )
            OutlinedButton(
                onClick = onSyncTiming,
                enabled = !isHomePage,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("devtools_network_sync_timing_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.devtools_network_sync_timing),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        if (filteredRequests.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.devtools_network_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("devtools_network_list")
            ) {
                items(filteredRequests, key = { it.id }) { req ->
                    NetworkRequestRow(
                        entry = req,
                        onCopyUrl = { clipboard.setText(AnnotatedString(req.url)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkRequestRow(
    entry: DevToolsNetworkEntry,
    onCopyUrl: () -> Unit
) {
    val bgColor = when {
        entry.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        entry.isBlocked -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val statusColor = when {
        entry.isError -> MaterialTheme.colorScheme.error
        entry.isBlocked -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopyUrl)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = entry.method,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = entry.statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = statusColor
                )
                Text(
                    text = entry.resourceType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (entry.durationMs != null) {
                    Text(
                        text = "${entry.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.transferSizeBytes != null && entry.transferSizeBytes > 0L) {
                    val kb = entry.transferSizeBytes / 1024.0
                    Text(
                        text = if (kb >= 1.0) String.format("%.1f KB", kb) else "${entry.transferSizeBytes} B",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DevToolsApplicationPane(
    snapshot: DevToolsStorageSnapshot,
    isLoading: Boolean,
    isHomePage: Boolean,
    isDesktopMode: Boolean,
    onRefresh: () -> Unit,
    onSetStorageItem: (isSession: Boolean, key: String, value: String) -> Unit,
    onDeleteStorageItem: (isSession: Boolean, key: String) -> Unit,
    onClearStorage: (scope: String) -> Unit,
    onClearCookies: () -> Unit,
    onToggleDesktopMode: (Boolean) -> Unit
) {
    var addDialogIsSession by remember { mutableStateOf<Boolean?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("devtools_application_pane")
    ) {
        // Device & Document Metrics Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Viewport & Document",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = !isHomePage && !isLoading,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("devtools_storage_refresh_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.devtools_cd_refresh)
                            )
                        }
                    }
                }

                if (snapshot.viewportWidthCss > 0) {
                    Text(
                        text = "CSS Viewport: ${snapshot.viewportWidthCss} × ${snapshot.viewportHeightCss} px (DPR ${snapshot.devicePixelRatio})",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snapshot.readyState.isNotBlank()) {
                    Text(
                        text = "ReadyState: ${snapshot.readyState}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snapshot.userAgent.isNotBlank()) {
                    Text(
                        text = "UA: ${snapshot.userAgent}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DesktopWindows,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.menu_desktop_site),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Switch(
                        checked = isDesktopMode,
                        onCheckedChange = onToggleDesktopMode,
                        modifier = Modifier.testTag("devtools_desktop_ua_switch")
                    )
                }
            }
        }

        // LocalStorage Section
        StorageSectionCard(
            title = "localStorage (${snapshot.localStorageEntries.size})",
            entries = snapshot.localStorageEntries,
            canAddOrDelete = !isHomePage,
            onAddClick = { addDialogIsSession = false },
            onDeleteEntry = { key -> onDeleteStorageItem(false, key) },
            onClearAll = { onClearStorage("local") }
        )

        // SessionStorage Section
        StorageSectionCard(
            title = "sessionStorage (${snapshot.sessionStorageEntries.size})",
            entries = snapshot.sessionStorageEntries,
            canAddOrDelete = !isHomePage,
            onAddClick = { addDialogIsSession = true },
            onDeleteEntry = { key -> onDeleteStorageItem(true, key) },
            onClearAll = { onClearStorage("session") }
        )

        // Cookies Section
        StorageSectionCard(
            title = "Cookies (${snapshot.cookies.size})",
            entries = snapshot.cookies,
            canAddOrDelete = false,
            onAddClick = null,
            onDeleteEntry = null,
            onClearAll = onClearCookies
        )
    }

    val dialogSessionFlag = addDialogIsSession
    if (dialogSessionFlag != null) {
        var keyInput by remember { mutableStateOf("") }
        var valueInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialogIsSession = null },
            title = { Text(stringResource(R.string.devtools_storage_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(stringResource(R.string.devtools_storage_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = valueInput,
                        onValueChange = { valueInput = it },
                        label = { Text(stringResource(R.string.devtools_storage_value_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            onSetStorageItem(dialogSessionFlag, keyInput.trim(), valueInput)
                            addDialogIsSession = null
                        }
                    },
                    enabled = keyInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialogIsSession = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun StorageSectionCard(
    title: String,
    entries: List<DevToolsKeyValueEntry>,
    canAddOrDelete: Boolean,
    onAddClick: (() -> Unit)?,
    onDeleteEntry: ((String) -> Unit)?,
    onClearAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (canAddOrDelete && onAddClick != null) {
                    IconButton(
                        onClick = onAddClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.devtools_storage_add_title),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text(stringResource(R.string.devtools_cd_clear))
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.devtools_storage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEach { entry ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.key,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.value,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (canAddOrDelete && onDeleteEntry != null) {
                                IconButton(
                                    onClick = { onDeleteEntry(entry.key) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.remove),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
