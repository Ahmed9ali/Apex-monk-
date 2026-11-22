package com.example

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.devtools.ConsoleLogLevel
import com.example.devtools.DevToolsCoordinator
import com.example.devtools.DevToolsDockMode
import com.example.devtools.DevToolsScripts
import com.example.devtools.DevToolsTab
import com.example.devtools.DevToolsUiState
import com.example.ui.browser.BrowserMenuBottomSheet
import com.example.ui.browser.DevToolsPanel
import com.example.ui.browser.ExpressiveHorizontalTabStrip
import com.example.ui.theme.ApexBrowserTheme
import com.example.ui.viewmodel.BrowserTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DevToolsCoordinatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `console buffer records logs command history filters and clears`() {
        val coordinator = DevToolsCoordinator()
        val tabId = "tab_1"

        coordinator.appendConsoleEntry(tabId, ConsoleLogLevel.LOG, "Booted page", "app.js", 12)
        coordinator.appendConsoleEntry(tabId, ConsoleLogLevel.WARN, "Deprecated API", "app.js", 24)
        coordinator.appendConsoleEntry(tabId, ConsoleLogLevel.ERROR, "TypeError: undefined", "main.js", 88)
        coordinator.appendConsoleEntry(tabId, ConsoleLogLevel.COMMAND, "document.title")
        val state = coordinator.appendConsoleEntry(tabId, ConsoleLogLevel.RESULT, "Example Domain")

        assertEquals(5, state.consoleLogs.size)
        assertEquals(listOf("document.title"), state.commandHistory)

        val filtered = coordinator.setConsoleFilter(tabId, ConsoleLogLevel.ERROR)
        assertEquals(ConsoleLogLevel.ERROR, filtered.consoleFilter)

        val cleared = coordinator.clearConsole(tabId)
        assertTrue(cleared.consoleLogs.isEmpty())
        assertEquals(listOf("document.title"), cleared.commandHistory)
    }

    @Test
    fun `network capture records requests marks errors and merges performance timing`() {
        val coordinator = DevToolsCoordinator()
        val tabId = "tab_net"

        coordinator.recordNetworkRequest(
            tabId = tabId,
            url = "https://example.com/app.js",
            method = "GET",
            resourceType = "script",
            statusLabel = "200",
            isBlocked = false,
            isError = false
        )
        coordinator.recordNetworkRequest(
            tabId = tabId,
            url = "https://ads.tracker.test/pixel.js",
            method = "GET",
            resourceType = "script",
            statusLabel = "Blocked (Tracker)",
            isBlocked = true,
            isError = false
        )
        coordinator.markNetworkError(tabId, "https://example.com/api/login", "HTTP 500")

        val merged = coordinator.mergePerformanceResources(
            tabId = tabId,
            resources = DevToolsScripts.parsePerformanceResources(
                """[{"url":"https://example.com/app.js","initiatorType":"script","durationMs":42,"transferSize":18432}]"""
            )
        )

        assertEquals(3, merged.networkRequests.size)
        val appJs = merged.networkRequests.first { it.url == "https://example.com/app.js" }
        assertEquals(42L, appJs.durationMs)
        assertEquals(18432L, appJs.transferSizeBytes)

        val blocked = merged.networkRequests.first { it.isBlocked }
        assertTrue(blocked.statusLabel.contains("Blocked"))

        val failed = merged.networkRequests.first { it.isError }
        assertEquals("HTTP 500", failed.statusLabel)
    }

    @Test
    fun `dom node parser and storage snapshot parse WebView JSON responses`() {
        val domRaw = """
            "{\"nodes\":[{\"id\":\"n1\",\"tagName\":\"input\",\"idAttr\":\"phone\",\"classAttr\":\"field primary\",\"selector\":\"#phone\",\"depth\":2,\"childCount\":0,\"textPreview\":\"\",\"attributes\":{\"type\":\"tel\",\"placeholder\":\"Phone\"},\"styles\":{\"display\":\"block\",\"color\":\"rgb(0,0,0)\"},\"bbox\":\"16,220 • 328×48\",\"outerHtml\":\"<input id=\\\"phone\\\">\"}]}"
        """.trimIndent()
        val nodes = DevToolsScripts.parseDomNodesResponse(domRaw)
        assertEquals(1, nodes.size)
        assertEquals("#phone", nodes.first().selector)
        assertEquals("tel", nodes.first().attributesSummary["type"])

        val coordinator = DevToolsCoordinator()
        val afterNodes = coordinator.setDomNodes("tab_dom", nodes)
        assertEquals("#phone", afterNodes.selectedDomSelector)
        assertFalse(afterNodes.isDomLoading)

        val storageRaw = """
            {"title":"Zomato","readyState":"complete","userAgent":"Mozilla/5.0","innerWidth":1280,"innerHeight":800,"dpr":2.0,"localStorage":[{"key":"theme","value":"dark"}],"sessionStorage":[{"key":"step","value":"otp"}]}
        """.trimIndent()
        val storageState = coordinator.updateStorageSnapshot(
            tabId = "tab_dom",
            pageUrl = "https://www.zomato.com",
            isDesktopMode = true,
            rawJson = storageRaw,
            cookieHeader = "session=abc123; locale=en-IN"
        )
        val snap = storageState.storageSnapshot
        assertEquals(1280, snap.viewportWidthCss)
        assertEquals(800, snap.viewportHeightCss)
        assertEquals(2, snap.cookies.size)
        assertEquals("session", snap.cookies[0].key)
        assertEquals("abc123", snap.cookies[0].value)
        assertEquals(1, snap.localStorageEntries.size)
        assertEquals(1, snap.sessionStorageEntries.size)
    }

    @Test
    fun `devtools entry points and tabs render and switch cleanly`() {
        var openedFromMenu = 0
        var toggledFromStrip = 0
        var activeDevToolsTab by mutableStateOf(DevToolsTab.CONSOLE)
        var showMenu by mutableStateOf(true)

        composeTestRule.setContent {
            ApexBrowserTheme {
                if (showMenu) {
                    BrowserMenuBottomSheet(
                        isHomeActive = false,
                        desktopViewportEnabled = true,
                        onOpenSettings = {},
                        onNewTabClick = {},
                        onReloadPageClick = {},
                        onToggleDesktopMode = {},
                        onOpenDevToolsClick = {
                            openedFromMenu++
                            showMenu = false
                        },
                        onClearBrowsingData = {},
                        onDismiss = {}
                    )
                } else {
                    androidx.compose.foundation.layout.Column {
                        ExpressiveHorizontalTabStrip(
                            tabs = listOf(BrowserTab(id = "t1", title = "Docs", url = "https://example.com")),
                            activeTabId = "t1",
                            onSelectTab = {},
                            onCloseTab = {},
                            onNewTab = {},
                            onOpenDevTools = { toggledFromStrip++ },
                            isDevToolsOpen = true
                        )
                        DevToolsPanel(
                            devToolsState = DevToolsUiState(
                                isOpen = true,
                                activeTab = activeDevToolsTab,
                                dockMode = DevToolsDockMode.SIDE_RIGHT
                            ),
                            activePageUrl = "https://example.com",
                            isHomePage = false,
                            isWideScreen = true,
                            isDesktopMode = true,
                            onSelectTab = { activeDevToolsTab = it },
                            onCycleDockMode = {},
                            onToggleInspectElementMode = {},
                            onToggleLayoutOutlines = {},
                            onRefreshCurrentTab = {},
                            onHardReloadPage = {},
                            onClose = {},
                            onConsoleInputChange = {},
                            onRunConsoleExpression = {},
                            onSelectConsoleFilter = {},
                            onClearConsole = {},
                            onDomSearchChange = {},
                            onRefreshDom = {},
                            onSelectDomNode = {},
                            onNetworkSearchChange = {},
                            onToggleNetworkFlaggedOnly = {},
                            onSyncNetworkTiming = {},
                            onClearNetwork = {},
                            onRefreshStorage = {},
                            onSetStorageItem = { _, _, _ -> },
                            onDeleteStorageItem = { _, _ -> },
                            onClearStorage = {},
                            onClearCookies = {},
                            onToggleDesktopMode = {},
                            modifier = androidx.compose.ui.Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Verify Menu sheet entry point
        composeTestRule.onNodeWithTag("menu_sheet_developer_tools").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_sheet_developer_tools").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, openedFromMenu)

        // Verify wide Tab Strip quick toggle & DevTools Panel
        composeTestRule.onNodeWithTag("tab_strip_devtools_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, toggledFromStrip)
        composeTestRule.onNodeWithTag("devtools_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("devtools_console_input").assertIsDisplayed()

        // Switch across Elements, Network, and Application tabs
        composeTestRule.onNodeWithTag("devtools_tab_elements").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devtools_elements_search_input").assertIsDisplayed()

        composeTestRule.onNodeWithTag("devtools_tab_network").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devtools_network_search_input").assertIsDisplayed()

        composeTestRule.onNodeWithTag("devtools_tab_application").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devtools_desktop_ua_switch").assertIsDisplayed()
    }
}
