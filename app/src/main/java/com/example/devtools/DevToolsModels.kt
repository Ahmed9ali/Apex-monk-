package com.example.devtools

enum class DevToolsTab {
    CONSOLE,
    ELEMENTS,
    NETWORK,
    APPLICATION
}

enum class DevToolsDockMode {
    COMPACT_BOTTOM,
    SIDE_RIGHT,
    EXPANDED
}

enum class ConsoleLogLevel {
    LOG,
    INFO,
    WARN,
    ERROR,
    DEBUG,
    COMMAND,
    RESULT
}

data class DevToolsConsoleEntry(
    val id: String,
    val tabId: String,
    val level: ConsoleLogLevel,
    val message: String,
    val source: String = "",
    val lineNumber: Int = 0,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DevToolsNetworkEntry(
    val id: String,
    val tabId: String,
    val url: String,
    val method: String = "GET",
    val resourceType: String = "other",
    val statusLabel: String = "200 OK",
    val isBlocked: Boolean = false,
    val isError: Boolean = false,
    val durationMs: Long? = null,
    val transferSizeBytes: Long? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DevToolsDomNode(
    val id: String,
    val tagName: String,
    val idAttr: String = "",
    val classAttr: String = "",
    val selector: String,
    val depth: Int = 0,
    val childCount: Int = 0,
    val textPreview: String = "",
    val attributesSummary: Map<String, String> = emptyMap(),
    val computedStyles: Map<String, String> = emptyMap(),
    val boundingBoxText: String = "",
    val outerHtmlPreview: String = ""
)

data class DevToolsKeyValueEntry(
    val key: String,
    val value: String
)

data class DevToolsStorageSnapshot(
    val pageUrl: String = "",
    val pageTitle: String = "",
    val readyState: String = "",
    val userAgent: String = "",
    val viewportWidthCss: Int = 0,
    val viewportHeightCss: Int = 0,
    val devicePixelRatio: Float = 1f,
    val isDesktopMode: Boolean = false,
    val cookies: List<DevToolsKeyValueEntry> = emptyList(),
    val localStorageEntries: List<DevToolsKeyValueEntry> = emptyList(),
    val sessionStorageEntries: List<DevToolsKeyValueEntry> = emptyList(),
    val lastRefreshedAtMillis: Long = 0L
)

data class DevToolsTabState(
    val consoleLogs: List<DevToolsConsoleEntry> = emptyList(),
    val consoleFilter: ConsoleLogLevel? = null,
    val networkRequests: List<DevToolsNetworkEntry> = emptyList(),
    val networkSearchQuery: String = "",
    val networkOnlyFlagged: Boolean = false,
    val domNodes: List<DevToolsDomNode> = emptyList(),
    val domSearchQuery: String = "",
    val selectedDomSelector: String? = null,
    val selectedNodeDetail: DevToolsDomNode? = null,
    val isDomLoading: Boolean = false,
    val isInspectElementMode: Boolean = false,
    val isLayoutOutlinesEnabled: Boolean = false,
    val storageSnapshot: DevToolsStorageSnapshot = DevToolsStorageSnapshot(),
    val isStorageLoading: Boolean = false,
    val commandHistory: List<String> = emptyList()
)

data class DevToolsUiState(
    val isOpen: Boolean = false,
    val activeTab: DevToolsTab = DevToolsTab.CONSOLE,
    val dockMode: DevToolsDockMode = DevToolsDockMode.COMPACT_BOTTOM,
    val consoleInput: String = "",
    val isEvaluatingJs: Boolean = false,
    val activeTabState: DevToolsTabState = DevToolsTabState()
)
