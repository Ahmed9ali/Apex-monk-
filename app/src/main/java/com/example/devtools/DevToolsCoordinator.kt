package com.example.devtools

import android.webkit.CookieManager
import android.webkit.WebView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class DevToolsCoordinator {
    private val stateByTab = ConcurrentHashMap<String, DevToolsTabState>()

    fun getTabState(tabId: String): DevToolsTabState {
        return stateByTab[tabId] ?: DevToolsTabState()
    }

    fun clearTab(tabId: String) {
        stateByTab.remove(tabId)
    }

    fun clearAll() {
        stateByTab.clear()
    }

    private inline fun updateTab(tabId: String, crossinline transform: (DevToolsTabState) -> DevToolsTabState): DevToolsTabState {
        return stateByTab.compute(tabId) { _, existing ->
            transform(existing ?: DevToolsTabState())
        } ?: DevToolsTabState()
    }

    fun appendConsoleEntry(
        tabId: String,
        level: ConsoleLogLevel,
        message: String,
        source: String = "",
        lineNumber: Int = 0
    ): DevToolsTabState {
        val cleanMsg = message.trimEnd()
        if (cleanMsg.isBlank()) return getTabState(tabId)
        return updateTab(tabId) { current ->
            val entry = DevToolsConsoleEntry(
                id = UUID.randomUUID().toString(),
                tabId = tabId,
                level = level,
                message = cleanMsg,
                source = source,
                lineNumber = lineNumber,
                timestampMillis = System.currentTimeMillis()
            )
            val updatedLogs = (current.consoleLogs + entry).let { list ->
                if (list.size > MAX_CONSOLE_ENTRIES) list.takeLast(MAX_CONSOLE_ENTRIES) else list
            }
            val updatedHistory = if (level == ConsoleLogLevel.COMMAND) {
                (current.commandHistory.filterNot { it == cleanMsg } + cleanMsg).takeLast(MAX_COMMAND_HISTORY)
            } else {
                current.commandHistory
            }
            current.copy(
                consoleLogs = updatedLogs,
                commandHistory = updatedHistory
            )
        }
    }

    fun setConsoleFilter(tabId: String, level: ConsoleLogLevel?): DevToolsTabState {
        return updateTab(tabId) { it.copy(consoleFilter = level) }
    }

    fun clearConsole(tabId: String): DevToolsTabState {
        return updateTab(tabId) { it.copy(consoleLogs = emptyList()) }
    }

    fun recordNetworkRequest(
        tabId: String,
        url: String,
        method: String,
        resourceType: String,
        statusLabel: String,
        isBlocked: Boolean,
        isError: Boolean
    ): DevToolsTabState {
        if (url.isBlank() || url.startsWith("data:", ignoreCase = true) || url.startsWith("about:", ignoreCase = true)) {
            return getTabState(tabId)
        }
        return updateTab(tabId) { current ->
            val entry = DevToolsNetworkEntry(
                id = UUID.randomUUID().toString(),
                tabId = tabId,
                url = url,
                method = method.ifBlank { "GET" },
                resourceType = resourceType.ifBlank { inferResourceType(url) },
                statusLabel = statusLabel,
                isBlocked = isBlocked,
                isError = isError,
                timestampMillis = System.currentTimeMillis()
            )
            val updated = (current.networkRequests + entry).let { list ->
                if (list.size > MAX_NETWORK_ENTRIES) list.takeLast(MAX_NETWORK_ENTRIES) else list
            }
            current.copy(networkRequests = updated)
        }
    }

    fun markNetworkError(tabId: String, url: String, statusLabel: String): DevToolsTabState {
        if (url.isBlank()) return getTabState(tabId)
        return updateTab(tabId) { current ->
            val idx = current.networkRequests.indexOfLast { it.url == url }
            if (idx >= 0) {
                val mutable = current.networkRequests.toMutableList()
                val existing = mutable[idx]
                mutable[idx] = existing.copy(
                    statusLabel = statusLabel,
                    isError = true
                )
                current.copy(networkRequests = mutable)
            } else {
                val entry = DevToolsNetworkEntry(
                    id = UUID.randomUUID().toString(),
                    tabId = tabId,
                    url = url,
                    method = "GET",
                    resourceType = inferResourceType(url),
                    statusLabel = statusLabel,
                    isBlocked = false,
                    isError = true,
                    timestampMillis = System.currentTimeMillis()
                )
                current.copy(networkRequests = (current.networkRequests + entry).takeLast(MAX_NETWORK_ENTRIES))
            }
        }
    }

    fun mergePerformanceResources(
        tabId: String,
        resources: List<Triple<String, String, Pair<Long, Long?>>>
    ): DevToolsTabState {
        if (resources.isEmpty()) return getTabState(tabId)
        return updateTab(tabId) { current ->
            val existingByUrl = current.networkRequests.associateBy { it.url }.toMutableMap()
            val updatedList = current.networkRequests.toMutableList()
            resources.forEach { (url, initiatorType, metrics) ->
                val (durationMs, sizeBytes) = metrics
                val existing = existingByUrl[url]
                if (existing != null) {
                    val idx = updatedList.indexOfLast { it.id == existing.id }
                    if (idx >= 0) {
                        updatedList[idx] = existing.copy(
                            resourceType = if (existing.resourceType == "other" || existing.resourceType.isBlank()) {
                                initiatorType
                            } else {
                                existing.resourceType
                            },
                            durationMs = durationMs,
                            transferSizeBytes = sizeBytes ?: existing.transferSizeBytes
                        )
                    }
                } else {
                    val newEntry = DevToolsNetworkEntry(
                        id = UUID.randomUUID().toString(),
                        tabId = tabId,
                        url = url,
                        method = "GET",
                        resourceType = initiatorType,
                        statusLabel = "200 (perf)",
                        durationMs = durationMs,
                        transferSizeBytes = sizeBytes,
                        timestampMillis = System.currentTimeMillis()
                    )
                    updatedList.add(newEntry)
                    existingByUrl[url] = newEntry
                }
            }
            current.copy(networkRequests = updatedList.takeLast(MAX_NETWORK_ENTRIES))
        }
    }

    fun setNetworkFilter(tabId: String, query: String, onlyFlagged: Boolean): DevToolsTabState {
        return updateTab(tabId) {
            it.copy(networkSearchQuery = query, networkOnlyFlagged = onlyFlagged)
        }
    }

    fun clearNetwork(tabId: String): DevToolsTabState {
        return updateTab(tabId) { it.copy(networkRequests = emptyList()) }
    }

    fun setDomLoading(tabId: String, loading: Boolean): DevToolsTabState {
        return updateTab(tabId) { it.copy(isDomLoading = loading) }
    }

    fun setDomSearchQuery(tabId: String, query: String): DevToolsTabState {
        return updateTab(tabId) { it.copy(domSearchQuery = query) }
    }

    fun setDomNodes(tabId: String, nodes: List<DevToolsDomNode>): DevToolsTabState {
        return updateTab(tabId) { current ->
            val selected = current.selectedDomSelector
            val matchingSelected = if (!selected.isNullOrBlank()) {
                nodes.firstOrNull { it.selector == selected } ?: current.selectedNodeDetail
            } else {
                nodes.firstOrNull()
            }
            current.copy(
                domNodes = nodes,
                isDomLoading = false,
                selectedDomSelector = matchingSelected?.selector ?: selected,
                selectedNodeDetail = matchingSelected
            )
        }
    }

    fun selectDomNode(tabId: String, selector: String): DevToolsTabState {
        return updateTab(tabId) { current ->
            val matched = current.domNodes.firstOrNull { it.selector == selector }
            current.copy(
                selectedDomSelector = selector,
                selectedNodeDetail = matched ?: current.selectedNodeDetail,
                isInspectElementMode = false
            )
        }
    }

    fun setInspectElementMode(tabId: String, enabled: Boolean): DevToolsTabState {
        return updateTab(tabId) { it.copy(isInspectElementMode = enabled) }
    }

    fun setLayoutOutlinesEnabled(tabId: String, enabled: Boolean): DevToolsTabState {
        return updateTab(tabId) { it.copy(isLayoutOutlinesEnabled = enabled) }
    }

    fun setStorageLoading(tabId: String, loading: Boolean): DevToolsTabState {
        return updateTab(tabId) { it.copy(isStorageLoading = loading) }
    }

    fun updateStorageSnapshot(
        tabId: String,
        pageUrl: String,
        isDesktopMode: Boolean,
        rawJson: String?,
        cookieHeader: String?
    ): DevToolsTabState {
        val unquoted = DevToolsScripts.parseJsonUnquoted(rawJson)
        val parsed = runCatching { JSONObject(unquoted) }.getOrNull()
        val localEntries = buildList {
            val arr = parsed?.optJSONArray("localStorage")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(DevToolsKeyValueEntry(obj.optString("key", ""), obj.optString("value", "")))
                }
            }
        }
        val sessionEntries = buildList {
            val arr = parsed?.optJSONArray("sessionStorage")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(DevToolsKeyValueEntry(obj.optString("key", ""), obj.optString("value", "")))
                }
            }
        }
        val cookies = DevToolsScripts.parseCookiesHeader(cookieHeader)
        val snapshot = DevToolsStorageSnapshot(
            pageUrl = pageUrl,
            pageTitle = parsed?.optString("title", "").orEmpty(),
            readyState = parsed?.optString("readyState", "").orEmpty(),
            userAgent = parsed?.optString("userAgent", "").orEmpty(),
            viewportWidthCss = parsed?.optInt("innerWidth", 0) ?: 0,
            viewportHeightCss = parsed?.optInt("innerHeight", 0) ?: 0,
            devicePixelRatio = parsed?.optDouble("dpr", 1.0)?.toFloat() ?: 1f,
            isDesktopMode = isDesktopMode,
            cookies = cookies,
            localStorageEntries = localEntries,
            sessionStorageEntries = sessionEntries,
            lastRefreshedAtMillis = System.currentTimeMillis()
        )
        return updateTab(tabId) {
            it.copy(storageSnapshot = snapshot, isStorageLoading = false)
        }
    }

    fun readCookiesSafely(url: String): String? {
        return runCatching {
            CookieManager.getInstance().getCookie(url)
        }.getOrNull()
    }

    fun evaluateConsoleExpression(
        webView: WebView?,
        tabId: String,
        expression: String,
        onResult: (DevToolsTabState) -> Unit
    ) {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return
        appendConsoleEntry(tabId, ConsoleLogLevel.COMMAND, trimmed)
        if (webView == null) {
            val updated = appendConsoleEntry(
                tabId,
                ConsoleLogLevel.ERROR,
                "Active tab has no attached webpage WebView. Open a webpage first."
            )
            onResult(updated)
            return
        }
        val script = DevToolsScripts.buildConsoleEvalScript(trimmed)
        webView.evaluateJavascript(script) { raw ->
            val unquoted = DevToolsScripts.parseJsonUnquoted(raw)
            val obj = runCatching { JSONObject(unquoted) }.getOrNull()
            val ok = obj?.optBoolean("ok", true) ?: true
            val formatted = obj?.optString("formatted", "").orEmpty()
                .ifBlank { unquoted.ifBlank { "undefined" } }
            val level = if (ok) ConsoleLogLevel.RESULT else ConsoleLogLevel.ERROR
            val updated = appendConsoleEntry(tabId, level, formatted)
            onResult(updated)
        }
    }

    companion object {
        private const val MAX_CONSOLE_ENTRIES = 300
        private const val MAX_NETWORK_ENTRIES = 300
        private const val MAX_COMMAND_HISTORY = 30

        fun inferResourceType(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> "script"
                path.endsWith(".css") -> "stylesheet"
                path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") ||
                    path.endsWith(".ico") -> "image"
                path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> "font"
                path.endsWith(".json") || path.contains("/api/") || path.contains("/graphql") -> "fetch/xhr"
                path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") -> "media"
                else -> "other"
            }
        }
    }
}
