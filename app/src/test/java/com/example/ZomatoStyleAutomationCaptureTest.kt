package com.example

import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.AutomationActionType
import com.example.automation.AutomationEngine
import com.example.automation.AutomationErrorCode
import com.example.automation.AutomationHudMode
import com.example.automation.AutomationStep
import com.example.automation.DomScanStatus
import com.example.automation.RecordedDomEvent
import com.example.automation.RecordingStatus
import com.example.automation.UniversalActionScripts
import com.example.automation.UniversalDomScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.example.data.local.ApexDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.engine.PrivacyManager
import com.example.ui.viewmodel.BrowserViewModel
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZomatoStyleAutomationCaptureTest {

    private class FakeEvalWebView(
        context: Context,
        var fakeUrl: String = "https://www.zomato.com/login"
    ) : WebView(context) {
        val evaluatedScripts = mutableListOf<String>()

        override fun getUrl(): String = fakeUrl

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            evaluatedScripts.add(script)
            val sidMatch = Regex("""sessionId:\s*("(?:[^"\\]|\\.)*")""").find(script)
            val sid = sidMatch?.groupValues?.getOrNull(1)?.let { JSONObject("{\"v\":$it}").optString("v") } ?: ""
            val payload = if (script.contains("__apexRecorderInstance = inst")) {
                JSONObject().apply {
                    put("ok", true)
                    put("sessionId", sid)
                }.toString()
            } else {
                JSONObject().apply {
                    put("ok", true)
                    put("removed", true)
                }.toString()
            }
            resultCallback?.onReceiveValue(JSONObject.quote(payload))
        }
    }

    private fun idleMainUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        mainLooper.idle()
        while (!predicate() && System.currentTimeMillis() < deadline) {
            mainLooper.idleFor(25L, TimeUnit.MILLISECONDS)
        }
        mainLooper.idle()
        assertTrue("Timed out waiting for condition", predicate())
    }

    private fun createVmHarness(engine: AutomationEngine = AutomationEngine()): Triple<ApexDatabase, ViewModelStore, BrowserViewModel> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val prefs = UserPreferencesRepository(context)
        val sp = context.getSharedPreferences("zomato_capture_${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val repo = BrowserRepository(db.apexDao(), prefs, sp)
        val dl = FastDownloadEngine(context, repo)
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BrowserViewModel(
                        repository = repo,
                        fastDownloadEngine = dl,
                        automationEngine = engine
                    ) as T
                }
            }
        )[BrowserViewModel::class.java]
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return Triple(db, store, vm)
    }

    @Test
    fun extractRegistrableSite_and_areSameSiteOrigins_enforceRegistrableSiteSchemeAndPortBoundaries() {
        val engine = AutomationEngine()

        assertEquals("zomato.com", engine.extractRegistrableSite("https://www.zomato.com/login"))
        assertEquals("zomato.com", engine.extractRegistrableSite("https://accounts.zomato.com/v2/auth"))
        assertEquals("example.co.in", engine.extractRegistrableSite("https://auth.example.co.in/signin"))

        assertTrue(engine.areSameSiteOrigins("https://www.zomato.com/login", "https://accounts.zomato.com/oauth"))
        assertTrue(engine.areSameSiteOrigins("https://app.example.co.in", "https://auth.example.co.in:443"))

        assertFalse(engine.areSameSiteOrigins("https://www.zomato.com", "https://accounts.evil.com"))
        assertFalse(engine.areSameSiteOrigins("https://www.zomato.com", "http://accounts.zomato.com"))
        assertFalse(engine.areSameSiteOrigins("https://www.zomato.com:8443", "https://accounts.zomato.com"))
    }

    @Test
    fun processRecordedDomEvent_acceptsSameSiteSubframeEvents_whileRejectingCrossSiteAndTopLevelMismatch() {
        val engine = AutomationEngine()
        val (db, store, vm) = createVmHarness(engine)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val wv = FakeEvalWebView(context, fakeUrl = "https://www.zomato.com/login")
            val activeTab = vm.uiState.value.activeTab
            vm.registerWebView(activeTab.id, wv)
            vm.onWebViewPageFinished(
                tabId = activeTab.id,
                url = "https://www.zomato.com/login",
                title = "Zomato",
                canGoBack = false,
                canGoForward = false
            )
            idleMainUntil {
                vm.uiState.value.activeTab.url == "https://www.zomato.com/login"
            }

            vm.startRecording()
            idleMainUntil {
                vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING
            }
            val session = vm.uiState.value.recordingSession

            val seq1 = vm.allocateRecordingSequence(session.sessionId)
            val seq2 = vm.allocateRecordingSequence(session.sessionId)
            assertEquals(1L, seq1)
            assertEquals(2L, seq2)

            // Same-site cross-origin modal iframe (accounts.zomato.com inside www.zomato.com)
            val subframePhoneResult = vm.processRecordedDomEvent(
                event = RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = activeTab.id,
                    origin = "https://accounts.zomato.com:443",
                    sequenceNumber = seq1,
                    eventType = "INPUT",
                    targetLabel = "Phone",
                    selector = "iframe#auth-iframe >> input[placeholder=\"Phone\"]",
                    value = "9876543210",
                    isDestructive = false,
                    framePath = listOf("iframe#auth-iframe"),
                    isSubframe = true
                ),
                sourceTabId = activeTab.id,
                sourceWebView = wv,
                sourceWebViewUrl = "https://www.zomato.com/login"
            )
            assertTrue(subframePhoneResult.success)
            assertEquals(RecordingStatus.RECORDING, vm.uiState.value.recordingSession.status)
            assertEquals(1, vm.uiState.value.recordingSession.steps.size)
            assertEquals("Phone", vm.uiState.value.recordingSession.steps.first().targetLabel)
            assertEquals(listOf("iframe#auth-iframe"), vm.uiState.value.recordingSession.steps.first().framePath)

            // Cross-site iframe must still be strictly rejected
            val crossSiteResult = vm.processRecordedDomEvent(
                event = RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = activeTab.id,
                    origin = "https://evil.example.org:443",
                    sequenceNumber = seq2,
                    eventType = "CLICK",
                    targetLabel = "Evil button",
                    selector = "iframe >> button",
                    value = "",
                    isDestructive = false,
                    framePath = listOf("iframe"),
                    isSubframe = true
                ),
                sourceTabId = activeTab.id,
                sourceWebView = wv,
                sourceWebViewUrl = "https://www.zomato.com/login"
            )
            assertFalse(crossSiteResult.success)
            assertEquals(AutomationErrorCode.RECORDING_ORIGIN_CHANGED, vm.lastRecordingRejectionCode)

            // Top-level navigation to accounts.zomato.com must still stop recording on origin change
            vm.onWebViewPageStarted(activeTab.id, "https://accounts.zomato.com/login")
            idleMainUntil {
                vm.uiState.value.recordingSession.status == RecordingStatus.STOPPED
            }
            assertEquals(
                AutomationErrorCode.RECORDING_ORIGIN_CHANGED,
                vm.uiState.value.recordingSession.recordingError?.code
            )
        } finally {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun buildRecorderInstallScript_and_multiFrameBridge_includeZomatoModalAndImeHooks() {
        val engine = AutomationEngine()
        val recorderScript = engine.buildRecorderInstallScript(
            sessionId = "rec_zomato_1",
            tabId = "tab_zomato",
            expectedOrigin = "https://www.zomato.com:443",
            initialSequenceNumber = 0L
        )
        val bridgeScript = engine.buildMultiFrameDocumentStartScript()
        val inputScript = UniversalActionScripts.buildStepExecutionScript(
            AutomationStep(
                id = "step_phone",
                order = 1,
                actionType = AutomationActionType.INPUT,
                targetLabel = "Phone",
                selector = "iframe#auth-iframe >> input[role=\"combobox\"]",
                value = "9876543210",
                description = "Enter Phone"
            )
        )

        // IME, page lifecycle flush, nextElementSibling dial-code skip, and subframe hooks
        assertTrue(recorderScript.contains("document.addEventListener('compositionend'"))
        assertTrue(recorderScript.contains("document.removeEventListener('compositionend'"))
        assertTrue(recorderScript.contains("window.addEventListener('pagehide'"))
        assertTrue(recorderScript.contains("document.addEventListener('visibilitychange'"))
        assertTrue(recorderScript.contains("nextElementSibling"))
        assertTrue(recorderScript.contains("hookIframeElement"))
        assertTrue(recorderScript.contains("ApexAutomationBridge.allocateRecordedSequence"))
        assertTrue(recorderScript.contains("isExplicitPickerInput"))

        // Document-start multi-frame bridge script
        assertTrue(bridgeScript.contains("APEX_FRAME_HELLO"))
        assertTrue(bridgeScript.contains("APEX_RECORDER_SYNC"))
        assertTrue(bridgeScript.contains("APEX_FRAME_SCAN_REQ"))
        assertTrue(bridgeScript.contains("APEX_FRAME_SCAN_RES"))
        assertTrue(bridgeScript.contains("APEX_FRAME_EXEC_REQ"))
        assertTrue(bridgeScript.contains("APEX_FRAME_EXEC_RES"))
        assertTrue(bridgeScript.contains("areSameSite"))

        // Action scripts preserve editable combobox typing and support bridged iframe execution
        assertTrue(inputScript.contains("PENDING_FRAME_EXEC"))
        assertTrue(inputScript.contains("APEX_FRAME_EXEC_REQ"))
        assertTrue(inputScript.contains("hasMatchingComboboxOption"))
    }

    @Test
    fun privacyManager_doesNotFalsePositiveFirstPartyQueryParams_andReturnsJsonForBlockedFetch() {
        val pm = PrivacyManager()

        // First-party URL carrying tracker host substring only inside query param must NOT be blocked
        val firstPartyLogin = pm.evaluateRequest(
            requestUrl = "https://www.zomato.com/api/login?next=turn.com",
            pageUrl = "https://www.zomato.com/",
            shieldsEnabled = true
        )
        assertFalse(firstPartyLogin.blocked)

        // Actual tracker domain must still be blocked
        val actualTracker = pm.evaluateRequest(
            requestUrl = "https://pixel.turn.com/t?id=123",
            pageUrl = "https://www.zomato.com/",
            shieldsEnabled = true
        )
        assertTrue(actualTracker.blocked)

        // Blocked fetch/XHR (Sec-Fetch-Dest: empty) receives valid JSON "{}" to avoid JSON.parse uncaught errors
        val jsonSurrogate = pm.stealthBlockedResponse(
            requestUrl = "https://analytics.example-tracker.com/collect",
            requestHeaders = mapOf("Sec-Fetch-Dest" to "empty")
        )
        assertEquals("application/json", jsonSurrogate.mimeType)
        assertEquals("{}", jsonSurrogate.data.bufferedReader().readText())
    }

    @Test
    fun universalDomScanner_acceptsSameSiteSubframeMutationBatch_andRejectsCrossSiteBatch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine()
        val scanner = engine.universalScanner
        val url = "https://www.zomato.com/login"
        val webView = WebView(context)
        UniversalDomScanner.attachHtmlFixture(
            webView = webView,
            url = url,
            html = """
                <html>
                <body>
                    <input id="initial" placeholder="Initial" />
                </body>
                </html>
            """.trimIndent()
        )
        try {
            scanner.scanPage(webView, tabId = "tab_z", currentUrl = url)
            val session = scanner.getActiveSession("tab_z")
            assertNotNull(session)

            val sameSiteBatch = JSONObject().apply {
                put("sessionId", session!!.scanSessionId)
                put("tabId", "tab_z")
                put("origin", "https://accounts.zomato.com:443")
                put("isSubframe", true)
                put("removedSelectors", JSONArray())
                put("updatedElements", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "input")
                        put("inputType", "tel")
                        put("label", "Phone")
                        put("selector", "iframe#auth >> input[type=\"tel\"]")
                        put("visible", true)
                        put("interactable", true)
                    })
                })
            }.toString()

            val acceptedState = scanner.processDynamicMutationBatch(
                rawJson = sameSiteBatch,
                sourceTabId = "tab_z",
                sourceWebView = webView,
                liveWebViewUrl = url
            )
            assertNotNull(acceptedState)
            assertEquals(2, acceptedState?.elements?.size)
            assertEquals("Phone", acceptedState?.elements?.firstOrNull { it.label == "Phone" }?.label)

            val crossSiteBatch = JSONObject().apply {
                put("sessionId", session!!.scanSessionId)
                put("tabId", "tab_z")
                put("origin", "https://evil.example.org:443")
                put("isSubframe", true)
                put("removedSelectors", JSONArray())
                put("updatedElements", JSONArray())
            }.toString()

            val rejectedState = scanner.processDynamicMutationBatch(
                rawJson = crossSiteBatch,
                sourceTabId = "tab_z",
                sourceWebView = webView,
                liveWebViewUrl = url
            )
            assertNull(rejectedState)
        } finally {
            UniversalDomScanner.clearAttachedFixture(webView)
        }
    }

    @Test
    fun startRecording_remembersExactLiveSpaUrl_andSavePreservesRememberedSourceUrl() {
        val engine = AutomationEngine()
        val (db, store, vm) = createVmHarness(engine)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val baseTabUrl = "https://www.zomato.com/ncr"
            val spaLiveUrl = "https://www.zomato.com/ncr/dine-out?city=delhi"
            val wv = FakeEvalWebView(context, fakeUrl = spaLiveUrl)
            val activeTab = vm.uiState.value.activeTab
            vm.registerWebView(activeTab.id, wv)
            vm.onWebViewPageFinished(
                tabId = activeTab.id,
                url = baseTabUrl,
                title = "Zomato",
                canGoBack = false,
                canGoForward = false
            )
            idleMainUntil { vm.uiState.value.activeTab.url == baseTabUrl }

            vm.startRecording()
            idleMainUntil { vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING }

            val session = vm.uiState.value.recordingSession
            assertEquals(spaLiveUrl, session.targetUrl)

            val seq1 = vm.allocateRecordingSequence(session.sessionId)
            val selectRes = vm.processRecordedDomEvent(
                event = RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = activeTab.id,
                    origin = "https://www.zomato.com:443",
                    sequenceNumber = seq1,
                    eventType = "SELECT",
                    targetLabel = "City",
                    selector = "select#city-select",
                    value = "Delhi NCR",
                    isDestructive = false
                ),
                sourceTabId = activeTab.id,
                sourceWebView = wv,
                sourceWebViewUrl = spaLiveUrl
            )
            assertTrue(selectRes.success)

            vm.stopRecording()
            idleMainUntil { vm.uiState.value.recordingSession.status == RecordingStatus.STOPPED }
            vm.saveRecordingWorkflow("Zomato Dine Out")
            idleMainUntil { vm.uiState.value.automationHudMode == AutomationHudMode.SAVED }

            val saved = runBlocking { db.apexDao().observeAutomationWorkflows().first().first { it.name == "Zomato Dine Out" } }
            assertEquals(spaLiveUrl, saved.sourceUrl)
        } finally {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun recordingSelectionsAndCombobox_rememberSelection_andExecuteFromHomeWithoutVaultExhaustion() {
        val engine = AutomationEngine(
            jsEvalTimeoutMs = 1_500L,
            navTimeoutMs = 2_000L,
            domReadyTimeoutMs = 1_500L,
            domStabilityPollMs = 20L
        )
        val (db, store, vm) = createVmHarness(engine)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val targetUrl = "https://www.zomato.com/login?mode=otp"
            val executedScripts = mutableListOf<String>()

            val wv = object : WebView(context) {
                var curUrl: String = targetUrl
                override fun getUrl(): String = curUrl
                override fun loadUrl(url: String) {
                    curUrl = url
                }
                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    val sidMatch = Regex("""sessionId:\s*("(?:[^"\\]|\\.)*")""").find(script)
                    val sid = sidMatch?.groupValues?.getOrNull(1)?.let { JSONObject("{\"v\":$it}").optString("v") } ?: ""
                    val payload = when {
                        script.contains("__apexRecorderInstance = inst") ->
                            JSONObject().put("ok", true).put("sessionId", sid).toString()
                        script.contains("__apexDomScannerV1") ->
                            JSONObject().put("ok", true).put("elements", JSONArray()).toString()
                        script.contains("readyState") && script.contains("targetReady") ->
                            JSONObject()
                                .put("readyState", "complete")
                                .put("url", curUrl)
                                .put("shape", "4:120")
                                .put("targetReady", true)
                                .toString()
                        script.contains("window.__apexRecorder") ->
                            JSONObject().put("ok", true).put("removed", true).toString()
                        else -> {
                            executedScripts.add(script)
                            JSONObject()
                                .put("ok", true)
                                .put("code", "NONE")
                                .put("verified", true)
                                .put("resolvedSelector", "#sel")
                                .put("msg", "Executed cleanly")
                                .toString()
                        }
                    }
                    resultCallback?.onReceiveValue(JSONObject.quote(payload))
                }
            }

            val recorderScript = engine.buildRecorderInstallScript(
                sessionId = "rec_sel_1",
                tabId = "tab_sel",
                expectedOrigin = "https://www.zomato.com:443"
            )
            assertTrue(recorderScript.contains("selOpt.text || selOpt.label"))
            assertTrue(recorderScript.contains("isSelectOrComboRole"))

            val emptyComboboxSelectScript = UniversalActionScripts.buildStepExecutionScript(
                AutomationStep(
                    id = "s_combo",
                    order = 1,
                    actionType = AutomationActionType.SELECT,
                    targetLabel = "Country Dropdown field",
                    selector = "div[role=\"combobox\"]",
                    value = "",
                    description = "Select Country Dropdown"
                )
            )
            assertTrue(emptyComboboxSelectScript.contains("isExplicitOptionEl"))
            assertTrue(emptyComboboxSelectScript.contains("!wantedRaw && !isExplicitOptionEl"))

            // Start recording on targetUrl
            val tabId = vm.uiState.value.activeTab.id
            vm.registerWebView(tabId, wv)
            vm.onWebViewPageFinished(tabId = tabId, title = "Zomato Login", url = targetUrl, canGoBack = false, canGoForward = false)
            idleMainUntil { vm.uiState.value.activeTab.url == targetUrl }

            vm.startRecording()
            idleMainUntil { vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING }
            val session = vm.uiState.value.recordingSession

            // 1) Empty CUSTOM combobox trigger
            val seq1 = vm.allocateRecordingSequence(session.sessionId)
            assertTrue(
                vm.processRecordedDomEvent(
                    event = RecordedDomEvent(
                        sessionId = session.sessionId,
                        tabId = tabId,
                        origin = "https://www.zomato.com:443",
                        sequenceNumber = seq1,
                        eventType = "SELECT",
                        targetLabel = "Picker Trigger field",
                        selector = "div.custom-picker[role=\"combobox\"]",
                        value = "",
                        isDestructive = false
                    ),
                    sourceTabId = tabId,
                    sourceWebView = wv,
                    sourceWebViewUrl = targetUrl
                ).success
            )
            // 2) Option selected inside picker
            val seq2 = vm.allocateRecordingSequence(session.sessionId)
            assertTrue(
                vm.processRecordedDomEvent(
                    event = RecordedDomEvent(
                        sessionId = session.sessionId,
                        tabId = tabId,
                        origin = "https://www.zomato.com:443",
                        sequenceNumber = seq2,
                        eventType = "CLICK",
                        targetLabel = "+1 United States",
                        selector = "div[role=\"option\"][data-dial=\"+1\"]",
                        value = "",
                        isDestructive = false
                    ),
                    sourceTabId = tabId,
                    sourceWebView = wv,
                    sourceWebViewUrl = targetUrl
                ).success
            )
            // 3) Native <select> remembered value
            val seq3 = vm.allocateRecordingSequence(session.sessionId)
            assertTrue(
                vm.processRecordedDomEvent(
                    event = RecordedDomEvent(
                        sessionId = session.sessionId,
                        tabId = tabId,
                        origin = "https://www.zomato.com:443",
                        sequenceNumber = seq3,
                        eventType = "SELECT",
                        targetLabel = "Order Type",
                        selector = "select#order-type",
                        value = "Delivery",
                        isDestructive = false
                    ),
                    sourceTabId = tabId,
                    sourceWebView = wv,
                    sourceWebViewUrl = targetUrl
                ).success
            )

            val step1AfterRecord = vm.uiState.value.recordingSession.steps.first()
            assertFalse(
                "Empty CUSTOM select trigger must not auto-bind to Vault Pool",
                step1AfterRecord.useVaultPool
            )

            vm.stopRecording()
            idleMainUntil { vm.uiState.value.recordingSession.status == RecordingStatus.STOPPED }
            // Ensure no empty CUSTOM Vault candidate causes VAULT_VALUES_EXHAUSTED
            assertFalse(
                vm.uiState.value.recordingVaultCandidates.any {
                    it.capturedValue.isEmpty() && it.isSelected
                }
            )

            vm.saveRecordingWorkflow("Zomato Selection Flow")
            idleMainUntil { vm.uiState.value.automationHudMode == AutomationHudMode.SAVED }
            val savedWf = runBlocking { db.apexDao().observeAutomationWorkflows().first().first { it.name == "Zomato Selection Flow" } }
            assertEquals(targetUrl, savedWf.sourceUrl)

            // Move user to apex://home (unmounting WebView momentarily) before launching saved automation
            vm.unregisterWebView(tabId)
            vm.onBottomHomePressed()
            idleMainUntil { vm.uiState.value.activeTab.url == "apex://home" }

            vm.runWorkflow(savedWf.id)
            idleMainUntil {
                vm.uiState.value.activeTab.url == targetUrl &&
                    vm.uiState.value.activeTab.isLoading
            }
            // Simulate Compose mounting WebView after navigating away from Home and finishing load
            vm.registerWebView(tabId, wv)
            vm.onWebViewPageStarted(tabId, targetUrl)
            vm.onWebViewPageFinished(tabId = tabId, title = "Zomato Login", url = targetUrl, canGoBack = false, canGoForward = false)

            idleMainUntil(timeoutMs = 5_000L) {
                val st = vm.uiState.value.executionState?.status
                st == "COMPLETED" || st == "FAILED"
            }
            val exec = vm.uiState.value.executionState
            assertNotNull(exec)
            assertEquals("Expected replay from Home to complete cleanly: ${exec?.verificationMessage}", "COMPLETED", exec?.status)
            assertEquals(3, exec?.completedSteps)
            assertEquals(3, exec?.totalSteps)
            assertTrue(executedScripts.isNotEmpty())
        } finally {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }
}
