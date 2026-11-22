package com.example.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BorderAll
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HorizontalSplit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.devtools.ConsoleLogLevel
import com.example.devtools.DevToolsConsoleEntry
import com.example.devtools.DevToolsDockMode
import com.example.devtools.DevToolsDomNode
import com.example.devtools.DevToolsTab
import com.example.devtools.DevToolsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevToolsPanel(
    devToolsState: DevToolsUiState,
    activePageUrl: String,
    isHomePage: Boolean,
    isWideScreen: Boolean,
    isDesktopMode: Boolean,
    onSelectTab: (DevToolsTab) -> Unit,
    onCycleDockMode: () -> Unit,
    onToggleInspectElementMode: () -> Unit,
    onToggleLayoutOutlines: () -> Unit,
    onRefreshCurrentTab: () -> Unit,
    onHardReloadPage: () -> Unit,
    onClose: () -> Unit,
    onConsoleInputChange: (String) -> Unit,
    onRunConsoleExpression: (String) -> Unit,
    onSelectConsoleFilter: (ConsoleLogLevel?) -> Unit,
    onClearConsole: () -> Unit,
    onDomSearchChange: (String) -> Unit,
    onRefreshDom: () -> Unit,
    onSelectDomNode: (String) -> Unit,
    onNetworkSearchChange: (String) -> Unit,
    onToggleNetworkFlaggedOnly: () -> Unit,
    onSyncNetworkTiming: () -> Unit,
    onClearNetwork: () -> Unit,
    onRefreshStorage: () -> Unit,
    onSetStorageItem: (isSession: Boolean, key: String, value: String) -> Unit,
    onDeleteStorageItem: (isSession: Boolean, key: String) -> Unit,
    onClearStorage: (scope: String) -> Unit,
    onClearCookies: () -> Unit,
    onToggleDesktopMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = devToolsState.isOpen) {
        if (devToolsState.activeTabState.isInspectElementMode) {
            onToggleInspectElementMode()
        } else {
            onClose()
        }
    }

    val tabState = devToolsState.activeTabState
    val errorCount = remember(tabState.consoleLogs) {
        tabState.consoleLogs.count { it.level == ConsoleLogLevel.ERROR }
    }
    val warnCount = remember(tabState.consoleLogs) {
        tabState.consoleLogs.count { it.level == ConsoleLogLevel.WARN }
    }
    val hostLabel = remember(activePageUrl, isHomePage) {
        if (isHomePage) "apex://home" else formatCleanAddressBarUrl(activePageUrl)
    }

    Surface(
        shape = when (devToolsState.dockMode) {
            DevToolsDockMode.EXPANDED -> RoundedCornerShape(0.dp)
            DevToolsDockMode.SIDE_RIGHT -> RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
            DevToolsDockMode.COMPACT_BOTTOM -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .testTag("devtools_panel")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top DevTools Toolbar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                IconButton(
                    onClick = onToggleInspectElementMode,
                    enabled = !isHomePage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("devtools_inspect_element_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.TouchApp,
                        contentDescription = stringResource(R.string.devtools_cd_inspect_element),
                        tint = if (tabState.isInspectElementMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.devtools_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (errorCount > 0 || warnCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (errorCount > 0) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                }
                            ) {
                                Text(
                                    text = buildString {
                                        if (errorCount > 0) append("${errorCount}E")
                                        if (errorCount > 0 && warnCount > 0) append(" ")
                                        if (warnCount > 0) append("${warnCount}W")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (errorCount > 0) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = hostLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleLayoutOutlines,
                    enabled = !isHomePage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("devtools_layout_outlines_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BorderAll,
                        contentDescription = "Toggle CSS layout outlines",
                        tint = if (tabState.isLayoutOutlinesEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(
                    onClick = onRefreshCurrentTab,
                    enabled = !isHomePage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("devtools_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.devtools_cd_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onCycleDockMode,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("devtools_dock_toggle_button")
                ) {
                    val dockIcon = when {
                        devToolsState.dockMode == DevToolsDockMode.EXPANDED && isWideScreen -> Icons.Outlined.VerticalSplit
                        devToolsState.dockMode == DevToolsDockMode.EXPANDED -> Icons.Outlined.HorizontalSplit
                        devToolsState.dockMode == DevToolsDockMode.COMPACT_BOTTOM && isWideScreen -> Icons.Outlined.VerticalSplit
                        else -> Icons.Outlined.Fullscreen
                    }
                    Icon(
                        imageVector = dockIcon,
                        contentDescription = stringResource(R.string.devtools_cd_toggle_dock),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("devtools_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.devtools_cd_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (tabState.isInspectElementMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CenterFocusStrong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.devtools_elements_pick_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onToggleInspectElementMode) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }

            // Tab Bar: Console | Elements | Network | Application
            val tabs = DevToolsTab.entries
            PrimaryScrollableTabRow(
                selectedTabIndex = tabs.indexOf(devToolsState.activeTab).coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                edgePadding = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("devtools_tab_row")
            ) {
                tabs.forEach { tab ->
                    val selected = tab == devToolsState.activeTab
                    val title = when (tab) {
                        DevToolsTab.CONSOLE -> stringResource(R.string.devtools_tab_console)
                        DevToolsTab.ELEMENTS -> stringResource(R.string.devtools_tab_elements)
                        DevToolsTab.NETWORK -> stringResource(R.string.devtools_tab_network)
                        DevToolsTab.APPLICATION -> stringResource(R.string.devtools_tab_application)
                    }
                    val icon = when (tab) {
                        DevToolsTab.CONSOLE -> Icons.Outlined.Terminal
                        DevToolsTab.ELEMENTS -> Icons.Filled.Code
                        DevToolsTab.NETWORK -> Icons.Outlined.Wifi
                        DevToolsTab.APPLICATION -> Icons.Outlined.Storage
                    }
                    Tab(
                        selected = selected,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(title, maxLines = 1)
                            }
                        },
                        modifier = Modifier.testTag("devtools_tab_${tab.name.lowercase()}")
                    )
                }
            }

            if (isHomePage) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.devtools_home_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (devToolsState.activeTab) {
                    DevToolsTab.CONSOLE -> DevToolsConsolePane(
                        logs = tabState.consoleLogs,
                        activeFilter = tabState.consoleFilter,
                        consoleInput = devToolsState.consoleInput,
                        commandHistory = tabState.commandHistory,
                        isEvaluating = devToolsState.isEvaluatingJs,
                        isHomePage = isHomePage,
                        onSelectFilter = onSelectConsoleFilter,
                        onClearConsole = onClearConsole,
                        onInputChange = onConsoleInputChange,
                        onRunExpression = onRunConsoleExpression
                    )
                    DevToolsTab.ELEMENTS -> DevToolsElementsPane(
                        nodes = tabState.domNodes,
                        selectedSelector = tabState.selectedDomSelector,
                        selectedDetail = tabState.selectedNodeDetail,
                        searchQuery = tabState.domSearchQuery,
                        isLoading = tabState.isDomLoading,
                        isHomePage = isHomePage,
                        onSearchChange = onDomSearchChange,
                        onRefresh = onRefreshDom,
                        onSelectNode = onSelectDomNode
                    )
                    DevToolsTab.NETWORK -> DevToolsNetworkPane(
                        requests = tabState.networkRequests,
                        searchQuery = tabState.networkSearchQuery,
                        onlyFlagged = tabState.networkOnlyFlagged,
                        isHomePage = isHomePage,
                        onSearchChange = onNetworkSearchChange,
                        onToggleFlaggedOnly = onToggleNetworkFlaggedOnly,
                        onSyncTiming = onSyncNetworkTiming,
                        onHardReload = onHardReloadPage,
                        onClear = onClearNetwork
                    )
                    DevToolsTab.APPLICATION -> DevToolsApplicationPane(
                        snapshot = tabState.storageSnapshot,
                        isLoading = tabState.isStorageLoading,
                        isHomePage = isHomePage,
                        isDesktopMode = isDesktopMode,
                        onRefresh = onRefreshStorage,
                        onSetStorageItem = onSetStorageItem,
                        onDeleteStorageItem = onDeleteStorageItem,
                        onClearStorage = onClearStorage,
                        onClearCookies = onClearCookies,
                        onToggleDesktopMode = onToggleDesktopMode
                    )
                }
            }
        }
    }
}

@Composable
private fun DevToolsConsolePane(
    logs: List<DevToolsConsoleEntry>,
    activeFilter: ConsoleLogLevel?,
    consoleInput: String,
    commandHistory: List<String>,
    isEvaluating: Boolean,
    isHomePage: Boolean,
    onSelectFilter: (ConsoleLogLevel?) -> Unit,
    onClearConsole: () -> Unit,
    onInputChange: (String) -> Unit,
    onRunExpression: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val filteredLogs = remember(logs, activeFilter) {
        if (activeFilter == null) logs else logs.filter { it.level == activeFilter }
    }
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    val quickSnippets = remember {
        listOf(
            "document.title",
            "location.href",
            "document.querySelectorAll('input,button').length",
            "performance.now().toFixed(1)",
            "Object.keys(localStorage)"
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter & Clear bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            FilterChip(
                selected = activeFilter == null,
                onClick = { onSelectFilter(null) },
                label = { Text("All (${logs.size})") },
                modifier = Modifier.testTag("devtools_console_filter_all")
            )
            FilterChip(
                selected = activeFilter == ConsoleLogLevel.ERROR,
                onClick = {
                    onSelectFilter(if (activeFilter == ConsoleLogLevel.ERROR) null else ConsoleLogLevel.ERROR)
                },
                label = { Text("Errors (${logs.count { it.level == ConsoleLogLevel.ERROR }})") },
                modifier = Modifier.testTag("devtools_console_filter_error")
            )
            FilterChip(
                selected = activeFilter == ConsoleLogLevel.WARN,
                onClick = {
                    onSelectFilter(if (activeFilter == ConsoleLogLevel.WARN) null else ConsoleLogLevel.WARN)
                },
                label = { Text("Warns (${logs.count { it.level == ConsoleLogLevel.WARN }})") },
                modifier = Modifier.testTag("devtools_console_filter_warn")
            )
            IconButton(
                onClick = onClearConsole,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("devtools_console_clear_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.devtools_cd_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // Log stream
        if (filteredLogs.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.devtools_console_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("devtools_console_list")
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    ConsoleLogRow(entry = entry)
                }
            }
        }

        // Quick JS Snippets / Command History
        val chipItems = (commandHistory.reversed().take(3) + quickSnippets).distinct().take(6)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            chipItems.forEach { snippet ->
                AssistChip(
                    onClick = { onInputChange(snippet) },
                    label = {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            }
        }

        // REPL input bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = consoleInput,
                onValueChange = onInputChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.devtools_console_hint),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                enabled = !isHomePage,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (consoleInput.isNotBlank() && !isEvaluating) {
                            onRunExpression(consoleInput)
                        }
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("devtools_console_input")
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (!isHomePage && consoleInput.isNotBlank()) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !isHomePage && consoleInput.isNotBlank() && !isEvaluating) {
                        onRunExpression(consoleInput)
                    }
                    .testTag("devtools_console_run_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isEvaluating) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.devtools_console_run),
                            tint = if (!isHomePage && consoleInput.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleLogRow(entry: DevToolsConsoleEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val bgColor = when (entry.level) {
        ConsoleLogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        ConsoleLogLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        ConsoleLogLevel.COMMAND -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val fgColor = when (entry.level) {
        ConsoleLogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ConsoleLogLevel.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        ConsoleLogLevel.COMMAND -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = when (entry.level) {
        ConsoleLogLevel.COMMAND -> ">"
        ConsoleLogLevel.RESULT -> "<"
        ConsoleLogLevel.ERROR -> "ERR"
        ConsoleLogLevel.WARN -> "WRN"
        ConsoleLogLevel.INFO -> "INF"
        ConsoleLogLevel.DEBUG -> "DBG"
        ConsoleLogLevel.LOG -> "LOG"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (entry.level == ConsoleLogLevel.ERROR) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(15.dp).padding(top = 2.dp)
                )
            } else if (entry.level == ConsoleLogLevel.WARN) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(15.dp).padding(top = 2.dp)
                )
            } else {
                Text(
                    text = prefix,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = fgColor
                )
                if (entry.source.isNotBlank() || entry.lineNumber > 0) {
                    val shortSource = entry.source.substringAfterLast('/').ifBlank { entry.source }
                    Text(
                        text = "$shortSource:${entry.lineNumber} • ${timeFormat.format(Date(entry.timestampMillis))}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DevToolsElementsPane(
    nodes: List<DevToolsDomNode>,
    selectedSelector: String?,
    selectedDetail: DevToolsDomNode?,
    searchQuery: String,
    isLoading: Boolean,
    isHomePage: Boolean,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectNode: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.devtools_elements_search_hint),
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
                enabled = !isHomePage,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onRefresh() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("devtools_elements_search_input")
            )

            IconButton(
                onClick = onRefresh,
                enabled = !isHomePage && !isLoading,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("devtools_elements_refresh_button")
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        if (nodes.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.devtools_elements_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .weight(0.56f)
                    .fillMaxWidth()
                    .testTag("devtools_elements_tree")
            ) {
                items(nodes, key = { it.id }) { node ->
                    val isSelected = node.selector == selectedSelector
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectNode(node.selector) }
                            .testTag("devtools_dom_node")
                    ) {
                        val indentDp = (node.depth.coerceAtMost(8) * 10).dp
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 36.dp)
                                .padding(start = 8.dp + indentDp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append("<")
                                    append(node.tagName)
                                    if (node.idAttr.isNotBlank()) append("#${node.idAttr}")
                                    if (node.classAttr.isNotBlank()) {
                                        val firstCls = node.classAttr.split(Regex("\\s+")).take(2).joinToString(".")
                                        if (firstCls.isNotBlank()) append(".$firstCls")
                                    }
                                    append(">")
                                    if (node.textPreview.isNotBlank()) {
                                        append(" \"${node.textPreview}\"")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (selectedDetail != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxWidth()
                        .testTag("devtools_element_inspector_detail")
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = selectedDetail.selector,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(selectedDetail.selector))
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.action_copy),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (selectedDetail.boundingBoxText.isNotBlank()) {
                            Text(
                                text = "Box: ${selectedDetail.boundingBoxText}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (selectedDetail.computedStyles.isNotEmpty()) {
                            Text(
                                text = "Computed Styles",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            selectedDetail.computedStyles.entries.forEach { (k, v) ->
                                Text(
                                    text = "  $k: $v",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (selectedDetail.attributesSummary.isNotEmpty()) {
                            Text(
                                text = "Attributes",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            selectedDetail.attributesSummary.entries.forEach { (k, v) ->
                                Text(
                                    text = "  $k=\"$v\"",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (selectedDetail.outerHtmlPreview.isNotBlank()) {
                            Text(
                                text = "HTML Preview",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = selectedDetail.outerHtmlPreview,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
