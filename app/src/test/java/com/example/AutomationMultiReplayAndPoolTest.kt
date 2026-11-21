package com.example

import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.ActiveRecordingSession
import com.example.automation.AutomationActionType
import com.example.automation.AutomationEngine
import com.example.automation.AutomationErrorCode
import com.example.automation.AutomationHudMode
import com.example.automation.AutomationStep
import com.example.automation.AutomationStepCodec
import com.example.automation.AutomationType
import com.example.automation.AutomationVaultCategory
import com.example.automation.AutomationVaultManager
import com.example.automation.PageInspectionState
import com.example.automation.RecordedDomEvent
import com.example.automation.RecordingStatus
import com.example.automation.VaultExhaustionPromptState
import com.example.automation.WorkflowDocument
import com.example.automation.WorkflowDocumentMetadata
import com.example.automation.WorkflowPlayOptions
import com.example.data.local.ApexDatabase
import com.example.data.local.AutomationWorkflowEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.ui.browser.AutomationDetailsScreenOverlay
import com.example.ui.browser.AutomationHudSheet
import com.example.ui.browser.VaultExhaustionPromptDialog
import com.example.ui.browser.WEBVIEW_VIEWPORT_ATTACHED_TAG
import com.example.ui.theme.ApexBrowserTheme
import com.example.ui.viewmodel.BrowserViewModel
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomationMultiReplayAndPoolTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeWebView(
        context: Context,
        var fakeUrl: String = "https://portal.example.com/signup",
        private val evaluator: (script: String, callback: ValueCallback<String>?) -> Unit
    ) : WebView(context) {
        override fun getUrl(): String = fakeUrl

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            evaluator(script, resultCallback)
        }
    }

    private class Harness(
        val db: ApexDatabase,
        val repo: BrowserRepository,
        val vm: BrowserViewModel,
        val context: Context,
        private val store: ViewModelStore
    ) {
        fun close() {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    private fun createHarness(
        engine: AutomationEngine = AutomationEngine(
            jsEvalTimeoutMs = 1_500L,
            navTimeoutMs = 1_500L,
            domReadyTimeoutMs = 450L,
            domStabilityPollMs = 20L
        )
    ): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val sharedPrefs = context.getSharedPreferences(
            "automation_multi_replay_${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        val prefs = UserPreferencesRepository.createInMemoryForTesting(sharedPrefs)
        val repo = BrowserRepository(db.apexDao(), prefs, sharedPrefs)
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
        return Harness(db = db, repo = repo, vm = vm, context = context, store = store)
    }

    private fun idleMainUntil(timeoutMs: Long = 6_000L, predicate: () -> Boolean) {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        mainLooper.idle()
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
            mainLooper.idleFor(15L, TimeUnit.MILLISECONDS)
        }
        mainLooper.idle()
        assertTrue("Timed out waiting for condition", predicate())
    }

    @Test
    fun fieldOnlyRecording_focusWithoutTyping_bindsToVaultPoolWithEmptyValue() {
        val h = createHarness()
        try {
            val engine = AutomationEngine()
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/signup"
            val recorderScript = engine.buildRecorderInstallScript(
                sessionId = "rec-token",
                tabId = tabId,
                expectedOrigin = "https://portal.example.com"
            )
            assertTrue(recorderScript.contains("onFocusIn"))
            assertTrue(recorderScript.contains("focusin"))

            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)
            val wv = FakeWebView(h.context, targetUrl) { script, cb ->
                val sidMatch = Regex("""sessionId:\s*("(?:[^"\\]|\\.)*")""").find(script)
                val sid = sidMatch?.groupValues?.getOrNull(1)?.let { JSONObject("{\"v\":$it}").optString("v") } ?: ""
                val payload = if (script.contains("__apexRecorderInstance = inst")) {
                    JSONObject().put("ok", true).put("sessionId", sid).toString()
                } else {
                    JSONObject().put("ok", true).toString()
                }
                cb?.onReceiveValue(JSONObject.quote(payload))
            }
            h.vm.registerWebView(tabId, wv)
            h.vm.startRecording()
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING }

            val session = h.vm.uiState.value.recordingSession
            val emailFocusEvent = RecordedDomEvent(
                sessionId = session.sessionId,
                tabId = tabId,
                origin = "https://portal.example.com",
                sequenceNumber = 1L,
                eventType = "INPUT",
                targetLabel = "Email",
                selector = "input[name=\"email\"]",
                value = "",
                isDestructive = false
            )
            val passFocusEvent = RecordedDomEvent(
                sessionId = session.sessionId,
                tabId = tabId,
                origin = "https://portal.example.com",
                sequenceNumber = 2L,
                eventType = "INPUT",
                targetLabel = "Password",
                selector = "input[type=\"password\"]",
                value = "",
                isDestructive = false,
                isPasswordField = true
            )
            h.vm.onRecordedDomEvent(emailFocusEvent, tabId, wv, targetUrl)
            h.vm.onRecordedDomEvent(passFocusEvent, tabId, wv, targetUrl)
            idleMainUntil { h.vm.uiState.value.recordingSession.steps.size == 2 }

            h.vm.stopRecording()
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.STOPPED }

            assertEquals(2, h.vm.uiState.value.recordingVaultCandidates.size)
            assertTrue(h.vm.uiState.value.recordingVaultCandidates.all { it.isSelected && it.capturedValue.isEmpty() })

            h.vm.saveRecordingWorkflow("Signup Pool Flow")
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.IDLE }

            val saved = runBlocking { h.db.apexDao().observeAutomationWorkflows().first().first { it.name == "Signup Pool Flow" } }
            val decoded = AutomationStepCodec.decode(saved.stepsJson)
            assertEquals(2, decoded.size)
            assertTrue(decoded.all { it.useVaultPool })
            assertTrue(decoded.all { it.value.isEmpty() })
            assertFalse(decoded.any { it.requiresCredential })
            assertEquals(AutomationVaultCategory.EMAIL.id, decoded[0].vaultCategory)
            assertEquals(AutomationVaultCategory.PASSWORD.id, decoded[1].vaultCategory)
        } finally {
            h.close()
        }
    }

    @Test
    fun multiRunReplay_withVaultPoolAndAutoClearData_rotatesValuesAndClearsAfterEveryRun() {
        val h = createHarness()
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/signup"
            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)

            val injectedScripts = mutableListOf<String>()
            val wv = FakeWebView(h.context, targetUrl) { script, cb ->
                injectedScripts.add(script)
                val reply = if (script.contains("apexAutomationPageProbe")) {
                    JSONObject()
                        .put("readyState", "complete")
                        .put("url", targetUrl)
                        .put("shape", "1:12")
                        .put("targetReady", true)
                        .toString()
                } else {
                    JSONObject()
                        .put("ok", true)
                        .put("msg", "Step executed")
                        .put("resolvedSelector", "input[name=\"email\"]")
                        .put("verified", true)
                        .toString()
                }
                cb?.onReceiveValue(JSONObject.quote(reply))
            }
            h.vm.registerWebView(tabId, wv)

            val workflowId = runBlocking {
                listOf("one@example.com", "two@example.com", "three@example.com").forEachIndexed { idx, email ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.EMAIL,
                        label = "Email #${idx + 1}",
                        plainValue = email,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                h.repo.saveAutomationWorkflow(
                    name = "Multi Pool Signup",
                    domain = "portal.example.com",
                    sourceUrl = targetUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "step_email",
                            order = 1,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Email",
                            selector = "input[name=\"email\"]",
                            value = "",
                            description = "Fill Email from Vault Pool",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.EMAIL.id,
                            vaultFolderId = AutomationVaultManager.defaultFolderIdForCategory(AutomationVaultCategory.EMAIL)
                        ),
                        AutomationStep(
                            id = "step_clear",
                            order = 2,
                            actionType = AutomationActionType.CLEAR_DATA,
                            targetLabel = "Browser Data",
                            selector = "",
                            value = "",
                            description = "Clear browser data"
                        )
                    )
                )
            }

            h.vm.replayWorkflow(
                workflowId = workflowId,
                options = WorkflowPlayOptions(repeatCount = 3, clearDataAfterRun = true)
            )
            idleMainUntil {
                val st = h.vm.uiState.value.executionState?.status
                st == "COMPLETED" || st == "FAILED"
            }
            val execSnapshot = h.vm.uiState.value.executionState!!
            assertEquals("Expected COMPLETED, message=${execSnapshot.verificationMessage}", "COMPLETED", execSnapshot.status)

            assertNull(h.vm.uiState.value.vaultExhaustionPromptState)
            val exec = h.vm.uiState.value.executionState!!
            assertEquals(3, exec.currentRunIndex)
            assertEquals(3, exec.totalRuns)
            assertTrue(exec.clearDataAfterRun)
            assertTrue(exec.verificationMessage.contains("3/3"))

            assertTrue(injectedScripts.any { it.contains("one@example.com") })
            assertTrue(injectedScripts.any { it.contains("two@example.com") })
            assertTrue(injectedScripts.any { it.contains("three@example.com") })
            val clearScriptCalls = injectedScripts.count { it.contains("window.localStorage.clear()") }
            assertTrue("Expected at least 3 browser clear invocations, got $clearScriptCalls", clearScriptCalls >= 3)
        } finally {
            h.close()
        }
    }

    @Test
    fun vaultPoolExhaustion_stopsReplayAndShowsPrompt_thenGenerateAndResumeCompletes() {
        val h = createHarness()
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/register"
            h.vm.onWebViewPageFinished(tabId, "Register", targetUrl, canGoBack = false, canGoForward = false)

            val wv = FakeWebView(h.context, targetUrl) { script, cb ->
                val reply = if (script.contains("apexAutomationPageProbe")) {
                    JSONObject()
                        .put("readyState", "complete")
                        .put("url", targetUrl)
                        .put("shape", "1:12")
                        .put("targetReady", true)
                        .toString()
                } else {
                    JSONObject()
                        .put("ok", true)
                        .put("msg", "Filled input")
                        .put("resolvedSelector", "input[name=\"email\"]")
                        .put("verified", true)
                        .toString()
                }
                cb?.onReceiveValue(JSONObject.quote(reply))
            }
            h.vm.registerWebView(tabId, wv)

            val workflowId = runBlocking {
                h.repo.activateProSubscription("sk_12345")
                listOf("alpha@example.com", "beta@example.com").forEachIndexed { idx, email ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.EMAIL,
                        label = "Email #${idx + 1}",
                        plainValue = email,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                h.repo.saveAutomationWorkflow(
                    name = "Exhaustion Flow",
                    domain = "portal.example.com",
                    sourceUrl = targetUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "step_email",
                            order = 1,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Email",
                            selector = "input[name=\"email\"]",
                            value = "",
                            description = "Fill Email from Vault Pool",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.EMAIL.id,
                            vaultFolderId = AutomationVaultManager.defaultFolderIdForCategory(AutomationVaultCategory.EMAIL)
                        )
                    )
                )
            }

            // Ask for 5 runs when only 2 emails exist -> Run 3 should exhaust the Vault pool
            h.vm.replayWorkflow(
                workflowId = workflowId,
                options = WorkflowPlayOptions(repeatCount = 5, clearDataAfterRun = true)
            )
            idleMainUntil {
                h.vm.uiState.value.executionState?.status == "FAILED" &&
                    h.vm.uiState.value.vaultExhaustionPromptState != null
            }

            val failedExec = h.vm.uiState.value.executionState!!
            assertEquals(AutomationErrorCode.VAULT_VALUES_EXHAUSTED, failedExec.errorCode)
            val prompt = h.vm.uiState.value.vaultExhaustionPromptState!!
            assertEquals(3, prompt.failedRunIndex)
            assertEquals(5, prompt.totalRuns)
            assertEquals(AutomationVaultCategory.EMAIL, prompt.category)

            // Generate 3 more values and automatically resume remaining 3 runs
            h.vm.generateValuesForExhaustedPrompt(count = 3, autoReplayAfter = true)
            idleMainUntil { h.vm.uiState.value.executionState?.status == "COMPLETED" }

            val resumedExec = h.vm.uiState.value.executionState!!
            assertEquals(3, resumedExec.totalRuns)
            assertTrue(resumedExec.verificationMessage.contains("3/3"))
            assertNull(h.vm.uiState.value.vaultExhaustionPromptState)
        } finally {
            h.close()
        }
    }

    @Test
    fun prePlayDialog_configuresRepeatCountAndClearDataBeforeStarting() {
        val doc = WorkflowDocument(
            metadata = WorkflowDocumentMetadata(repeatCount = 1, clearDataAfterRun = false),
            steps = listOf(
                AutomationStep(
                    id = "s1",
                    order = 1,
                    actionType = AutomationActionType.CLICK,
                    targetLabel = "Next",
                    description = "Click Next"
                )
            )
        )
        val workflow = AutomationWorkflowEntity(
            id = 42L,
            name = "Signup Flow",
            domain = "example.com",
            sourceUrl = "https://example.com",
            type = AutomationType.RECORDED.id,
            stepsJson = AutomationStepCodec.encodeDocument(doc),
            stepCount = 1,
            createdAt = 1L,
            updatedAt = 1L
        )
        var capturedWorkflowId = -1L
        var capturedOptions: WorkflowPlayOptions? = null

        composeTestRule.setContent {
            ApexBrowserTheme {
                AutomationHudSheet(
                    hudMode = AutomationHudMode.SAVED,
                    pageInspection = PageInspectionState(domain = "example.com"),
                    recordingSession = ActiveRecordingSession(),
                    instructionDraftText = "",
                    instructionDraftSteps = emptyList(),
                    executionState = null,
                    workflows = listOf(workflow),
                    executions = emptyList(),
                    onSelectMode = {},
                    onScanPage = {},
                    onStartRecording = {},
                    onPauseRecording = {},
                    onResumeRecording = {},
                    onStopRecording = {},
                    onMinimizeRecording = {},
                    onRenameRecordingDraft = {},
                    onEditRecordingStep = { _, _, _ -> },
                    onDeleteRecordingStep = {},
                    onSaveRecording = {},
                    onDiscardRecording = {},
                    onInstructionTextChange = {},
                    onAddInstructions = {},
                    onClearInstructions = {},
                    onReviewInstructionSteps = {},
                    onEditInstructionStep = { _, _, _, _ -> },
                    onDeleteInstructionStep = {},
                    onMoveInstructionStepUp = {},
                    onMoveInstructionStepDown = {},
                    onSaveInstructionsAsWorkflow = {},
                    onExecuteInstructions = {},
                    onRunWorkflow = {},
                    onPlayWorkflowWithOptions = { id, opts ->
                        capturedWorkflowId = id
                        capturedOptions = opts
                    },
                    onOpenWorkflowDetails = {},
                    onOpenWorkspace = {},
                    onOpenNanoRetrieverPanel = {},
                    onConfirmDestructiveExecution = {},
                    onCancelDestructiveExecution = {},
                    onClearExecutionBanner = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Signup Flow").assertIsDisplayed()
        composeTestRule.onNodeWithTag("automation_hud_sheet").assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Run")).performClick()

        composeTestRule.onNodeWithTag("pre_play_automation_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pre_play_repeat_chip_20").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("pre_play_clear_data_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("pre_play_start_button").performClick()

        assertEquals(42L, capturedWorkflowId)
        assertNotNull(capturedOptions)
        assertEquals(20, capturedOptions?.repeatCount)
        assertEquals(true, capturedOptions?.clearDataAfterRun)
    }

    @Test
    fun editWorkflowScreen_updatesReplaySettings_andAddsPrebuiltClearDataStep() {
        val doc = WorkflowDocument(
            metadata = WorkflowDocumentMetadata(repeatCount = 1, clearDataAfterRun = false),
            steps = listOf(
                AutomationStep(
                    id = "s1",
                    order = 1,
                    actionType = AutomationActionType.INPUT,
                    targetLabel = "Email",
                    description = "Fill Email Pool",
                    useVaultPool = true,
                    vaultCategory = AutomationVaultCategory.EMAIL.id
                )
            )
        )
        val workflow = AutomationWorkflowEntity(
            id = 77L,
            name = "Pool Flow",
            domain = "example.com",
            sourceUrl = "https://example.com",
            type = AutomationType.RECORDED.id,
            stepsJson = AutomationStepCodec.encodeDocument(doc),
            stepCount = 1,
            createdAt = 1L,
            updatedAt = 1L
        )
        var savedRepeat = 0
        var savedClear = false
        var addedClearStepType: AutomationActionType? = null

        composeTestRule.setContent {
            ApexBrowserTheme {
                AutomationDetailsScreenOverlay(
                    workflow = workflow,
                    executions = emptyList(),
                    executionState = null,
                    onRunWorkflow = {},
                    onRenameWorkflow = { _, _ -> },
                    onUpdateSteps = { _, _ -> },
                    onAddStep = { _, type, _, _, _ -> addedClearStepType = type },
                    onConfirmDestructiveExecution = {},
                    onCancelDestructiveExecution = {},
                    onClearExecutionBanner = {},
                    onUpdateReplaySettings = { _, repeat, clear ->
                        savedRepeat = repeat
                        savedClear = clear
                    },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("automation_details_list")
            .performScrollToNode(hasTestTag("details_replay_settings_card"))
        composeTestRule.onNodeWithTag("details_replay_settings_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("details_repeat_chip_10").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("details_clear_data_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("details_save_replay_settings_button").performScrollTo().performClick()

        assertEquals(10, savedRepeat)
        assertTrue(savedClear)

        composeTestRule.onNodeWithTag("automation_details_list")
            .performScrollToNode(hasTestTag("details_add_clear_data_button"))
        composeTestRule.onNodeWithTag("details_add_clear_data_button").performClick()
        assertEquals(AutomationActionType.CLEAR_DATA, addedClearStepType)
    }

    @Test
    fun vaultExhaustionDialog_triggersGenerateAndOpenVaultActions() {
        var generatedCount = 0
        var openedVault = false
        val prompt = VaultExhaustionPromptState(
            workflowId = 99L,
            workflowName = "Account Creator",
            folderId = AutomationVaultManager.defaultFolderIdForCategory(AutomationVaultCategory.EMAIL),
            folderName = "Email",
            category = AutomationVaultCategory.EMAIL,
            targetLabel = "Email",
            failedRunIndex = 4,
            totalRuns = 10,
            message = "Automation Vault ran out of Email values."
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                VaultExhaustionPromptDialog(
                    prompt = prompt,
                    onGenerateAndResume = { count -> generatedCount = count },
                    onOpenVault = { openedVault = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("vault_exhaustion_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_exhaustion_count_10").performClick()
        composeTestRule.onNodeWithTag("vault_exhaustion_generate_button").performClick()
        assertEquals(10, generatedCount)

        composeTestRule.onNodeWithTag("vault_exhaustion_open_vault_button").performClick()
        assertTrue(openedVault)
    }

    @Test
    fun detectCategory_andVaultGenerator_supportCountryCodeDobAndGender() {
        assertEquals(
            AutomationVaultCategory.COUNTRY_CODE,
            AutomationVaultManager.detectCategory("Phone Country Code", "select[name=\"countryCode\"]")
        )
        assertEquals(
            AutomationVaultCategory.COUNTRY_CODE,
            AutomationVaultManager.detectCategory("", "#tel-country-code")
        )
        assertEquals(
            AutomationVaultCategory.PHONE,
            AutomationVaultManager.detectCategory("Phone Number", "input[name=\"phoneNumber\"]")
        )
        assertEquals(
            AutomationVaultCategory.DATE_OF_BIRTH,
            AutomationVaultManager.detectCategory("Birth Day", "input[name=\"birthYear\"]")
        )
        assertEquals(
            AutomationVaultCategory.DATE_OF_BIRTH,
            AutomationVaultManager.detectCategory("Date of Birth", "#dob")
        )
        assertEquals(
            AutomationVaultCategory.GENDER,
            AutomationVaultManager.detectCategory("Gender", "select[name=\"gender\"]")
        )
        assertEquals(
            AutomationVaultCategory.GENDER,
            AutomationVaultManager.detectCategory("Female option", "input[type=\"radio\"][name=\"gender\"]")
        )

        val defaultIds = AutomationVaultManager.DEFAULT_FOLDERS.map { it.folderId }
        assertTrue(defaultIds.contains(AutomationVaultManager.FOLDER_ID_COUNTRY_CODE))
        assertTrue(defaultIds.contains(AutomationVaultManager.FOLDER_ID_DATE_OF_BIRTH))
        assertTrue(defaultIds.contains(AutomationVaultManager.FOLDER_ID_GENDER))

        val ccFolder = AutomationVaultManager.DEFAULT_FOLDERS.first { it.folderId == AutomationVaultManager.FOLDER_ID_COUNTRY_CODE }
        val (_, generatedCc) = com.example.automation.VaultValueGenerator.generateValue(ccFolder)
        assertTrue("Expected dial code like +1/+91, got $generatedCc", generatedCc.startsWith("+"))

        val genderFolder = AutomationVaultManager.DEFAULT_FOLDERS.first { it.folderId == AutomationVaultManager.FOLDER_ID_GENDER }
        val (_, generatedGender) = com.example.automation.VaultValueGenerator.generateValue(genderFolder)
        assertTrue(generatedGender in setOf("Female", "Male", "Non-binary"))
    }

    @Test
    fun fieldOnlySelectRecording_bindsCountryCodeAndGenderSelectControlsToVaultPool() {
        val h = createHarness()
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/signup"
            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)
            val wv = FakeWebView(h.context, targetUrl) { script, cb ->
                val sidMatch = Regex("""sessionId:\s*("(?:[^"\\]|\\.)*")""").find(script)
                val sid = sidMatch?.groupValues?.getOrNull(1)?.let { JSONObject("{\"v\":$it}").optString("v") } ?: ""
                val payload = if (script.contains("__apexRecorderInstance = inst")) {
                    JSONObject().put("ok", true).put("sessionId", sid).toString()
                } else {
                    JSONObject().put("ok", true).toString()
                }
                cb?.onReceiveValue(JSONObject.quote(payload))
            }
            h.vm.registerWebView(tabId, wv)
            h.vm.startRecording()
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING }

            val session = h.vm.uiState.value.recordingSession
            h.vm.onRecordedDomEvent(
                RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = tabId,
                    origin = "https://portal.example.com",
                    sequenceNumber = 1L,
                    eventType = "SELECT",
                    targetLabel = "Country Code field",
                    selector = "select[name=\"countryCode\"]",
                    value = "",
                    isDestructive = false
                ),
                tabId,
                wv,
                targetUrl
            )
            h.vm.onRecordedDomEvent(
                RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = tabId,
                    origin = "https://portal.example.com",
                    sequenceNumber = 2L,
                    eventType = "SELECT",
                    targetLabel = "Gender field",
                    selector = "select[name=\"gender\"]",
                    value = "",
                    isDestructive = false
                ),
                tabId,
                wv,
                targetUrl
            )
            idleMainUntil { h.vm.uiState.value.recordingSession.steps.size == 2 }

            h.vm.stopRecording()
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.STOPPED }
            h.vm.saveRecordingWorkflow("Select Pool Flow")
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.IDLE }

            val saved = runBlocking { h.db.apexDao().observeAutomationWorkflows().first().first { it.name == "Select Pool Flow" } }
            val decoded = AutomationStepCodec.decode(saved.stepsJson)
            assertEquals(2, decoded.size)
            assertEquals(AutomationVaultCategory.COUNTRY_CODE.id, decoded[0].vaultCategory)
            assertEquals(AutomationVaultManager.FOLDER_ID_COUNTRY_CODE, decoded[0].vaultFolderId)
            assertTrue(decoded[0].useVaultPool)
            assertEquals(AutomationVaultCategory.GENDER.id, decoded[1].vaultCategory)
            assertEquals(AutomationVaultManager.FOLDER_ID_GENDER, decoded[1].vaultFolderId)
            assertTrue(decoded[1].useVaultPool)
        } finally {
            h.close()
        }
    }

    @Test
    fun splitDobAndGenderToggleReplay_reusesDobPerRunAcrossDayMonthYear_andAdvancesNextRun() {
        val h = createHarness()
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/profile"
            h.vm.onWebViewPageFinished(tabId, "Profile", targetUrl, canGoBack = false, canGoForward = false)

            val runPayloads = mutableListOf<String>()
            val wv = FakeWebView(h.context, targetUrl) { script, cb ->
                runPayloads.add(script)
                val reply = if (script.contains("apexAutomationPageProbe")) {
                    JSONObject()
                        .put("readyState", "complete")
                        .put("url", targetUrl)
                        .put("shape", "1:12")
                        .put("targetReady", true)
                        .toString()
                } else {
                    JSONObject()
                        .put("ok", true)
                        .put("msg", "Step executed")
                        .put("resolvedSelector", "#field")
                        .put("verified", true)
                        .toString()
                }
                cb?.onReceiveValue(JSONObject.quote(reply))
            }
            h.vm.registerWebView(tabId, wv)

            val workflowId = runBlocking {
                listOf("01/03/2002", "15/08/1998").forEachIndexed { idx, dob ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.DATE_OF_BIRTH,
                        label = "DOB #${idx + 1}",
                        plainValue = dob,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                listOf("Female", "Male").forEachIndexed { idx, gender ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.GENDER,
                        label = "Gender #${idx + 1}",
                        plainValue = gender,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                h.repo.saveAutomationWorkflow(
                    name = "Split DOB + Gender Flow",
                    domain = "portal.example.com",
                    sourceUrl = targetUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "s_day",
                            order = 1,
                            actionType = AutomationActionType.SELECT,
                            targetLabel = "Birth Day",
                            selector = "#birth-day",
                            value = "",
                            description = "Fill Date of Birth (Vault Pool) into Birth Day",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.DATE_OF_BIRTH.id,
                            vaultFolderId = AutomationVaultManager.FOLDER_ID_DATE_OF_BIRTH
                        ),
                        AutomationStep(
                            id = "s_month",
                            order = 2,
                            actionType = AutomationActionType.SELECT,
                            targetLabel = "Birth Month",
                            selector = "#birth-month",
                            value = "",
                            description = "Fill Date of Birth (Vault Pool) into Birth Month",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.DATE_OF_BIRTH.id,
                            vaultFolderId = AutomationVaultManager.FOLDER_ID_DATE_OF_BIRTH
                        ),
                        AutomationStep(
                            id = "s_year",
                            order = 3,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Birth Year",
                            selector = "#birth-year",
                            value = "",
                            description = "Fill Date of Birth (Vault Pool) into Birth Year",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.DATE_OF_BIRTH.id,
                            vaultFolderId = AutomationVaultManager.FOLDER_ID_DATE_OF_BIRTH
                        ),
                        AutomationStep(
                            id = "s_gender",
                            order = 4,
                            actionType = AutomationActionType.TOGGLE,
                            targetLabel = "Gender",
                            selector = "input[name=\"gender\"]",
                            value = "",
                            description = "Fill Gender (Vault Pool) into Gender",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.GENDER.id,
                            vaultFolderId = AutomationVaultManager.FOLDER_ID_GENDER
                        )
                    )
                )
            }

            h.vm.replayWorkflow(
                workflowId = workflowId,
                options = WorkflowPlayOptions(repeatCount = 2, clearDataAfterRun = false)
            )
            idleMainUntil {
                val st = h.vm.uiState.value.executionState?.status
                st == "COMPLETED" || st == "FAILED"
            }

            val exec = h.vm.uiState.value.executionState!!
            assertEquals("Expected COMPLETED, msg=${exec.verificationMessage}", "COMPLETED", exec.status)
            assertNull(h.vm.uiState.value.vaultExhaustionPromptState)

            // All 3 DOB steps of Run 1 got "01/03/2002" and Run 2 got "15/08/1998" (never exhausted!)
            assertEquals(3, runPayloads.count { it.contains("01/03/2002") || it.contains("01\\/03\\/2002") })
            assertEquals(3, runPayloads.count { it.contains("15/08/1998") || it.contains("15\\/08\\/1998") })
            assertTrue(runPayloads.any { it.contains("\"Female\"") })
            assertTrue(runPayloads.any { it.contains("\"Male\"") })
        } finally {
            h.close()
        }
    }

    @Test
    fun universalActionScripts_emitsSmartPhoneDobCountryCodeAndGenderLogic() {
        val dobYearScript = com.example.automation.UniversalActionScripts.buildStepExecutionScript(
            AutomationStep(
                id = "dob_year",
                order = 1,
                actionType = AutomationActionType.INPUT,
                targetLabel = "Birth Day",
                selector = "input[name=\"birthYear\"]",
                value = "01/03/2002",
                description = "Enter DOB"
            )
        )
        assertTrue(dobYearScript.contains("apexParseDateParts"))
        assertTrue(dobYearScript.contains("apexDetectDateFieldRole"))
        assertTrue(dobYearScript.contains("apexAdaptDateValueForTarget"))
        assertTrue(dobYearScript.contains("apexTokenizeSig"))

        val phoneScript = com.example.automation.UniversalActionScripts.buildStepExecutionScript(
            AutomationStep(
                id = "phone_step",
                order = 2,
                actionType = AutomationActionType.INPUT,
                targetLabel = "Phone Number",
                selector = "input[name=\"phone\"]",
                value = "+1 (201) 555-1234",
                description = "Enter Phone"
            )
        )
        assertTrue(phoneScript.contains("apexAdaptPhoneForInput"))
        assertTrue(phoneScript.contains("apexExtractPhoneParts"))
        assertTrue(phoneScript.contains("apexHasSiblingCountryCode"))
        assertTrue(phoneScript.contains("doesRetainedMatch"))

        val genderRadioScript = com.example.automation.UniversalActionScripts.buildStepExecutionScript(
            AutomationStep(
                id = "gender_radio",
                order = 3,
                actionType = AutomationActionType.TOGGLE,
                targetLabel = "Gender",
                selector = "input[type=\"radio\"][name=\"gender\"]",
                value = "Female",
                description = "Select Gender"
            )
        )
        assertTrue(genderRadioScript.contains("gAliasMap"))
        assertTrue(genderRadioScript.contains("\"Female\""))
    }

    @Test
    fun repeatReplay_sameUrl_withClearDataOn_clearsDataBeforeRefreshingAndWaitsForEveryLoad() {
        val h = createHarness(
            engine = AutomationEngine(
                jsEvalTimeoutMs = 2_000L,
                navTimeoutMs = 3_000L,
                domReadyTimeoutMs = 1_500L,
                domStabilityPollMs = 20L
            )
        )
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://portal.example.com/signup"
            h.vm.submitNavigation(targetUrl, tabId)
            idleMainUntil { h.vm.uiState.value.activeTab.url == targetUrl }
            val initialLoadReqId = h.vm.uiState.value.activeTab.loadRequestId

            val timeline = mutableListOf<String>()
            var activeRunTracker = 0
            val wv = object : WebView(h.context) {
                init {
                    setTag(WEBVIEW_VIEWPORT_ATTACHED_TAG, true)
                }

                override fun getUrl(): String = targetUrl

                override fun clearCache(includeDiskFiles: Boolean) {
                    timeline.add("CLEAR_NATIVE_CACHE")
                }

                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    when {
                        script.contains("window.localStorage.clear()") -> {
                            timeline.add("CLEAR_DOM_DATA")
                            resultCallback?.onReceiveValue(JSONObject.quote(JSONObject().put("ok", true).toString()))
                        }
                        script.contains("readyState") -> {
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("readyState", "complete")
                                        .put("url", targetUrl)
                                        .put("shape", "2:24")
                                        .put("targetReady", true)
                                        .toString()
                                )
                            )
                        }
                        script.contains("first@example.com") -> {
                            timeline.add("STEP_INPUT_RUN_1")
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("ok", true)
                                        .put("msg", "Filled email 1")
                                        .put("resolvedSelector", "input[name=\"email\"]")
                                        .put("verified", true)
                                        .toString()
                                )
                            )
                        }
                        script.contains("second@example.com") -> {
                            timeline.add("STEP_INPUT_RUN_2")
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("ok", true)
                                        .put("msg", "Filled email 2")
                                        .put("resolvedSelector", "input[name=\"email\"]")
                                        .put("verified", true)
                                        .toString()
                                )
                            )
                        }
                        else -> {
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("ok", true)
                                        .put("elements", org.json.JSONArray())
                                        .toString()
                                )
                            )
                        }
                    }
                }
            }
            h.vm.registerWebView(tabId, wv)
            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)

            val workflowId = runBlocking {
                listOf("first@example.com", "second@example.com").forEachIndexed { idx, email ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.EMAIL,
                        label = "Email #${idx + 1}",
                        plainValue = email,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                h.repo.saveAutomationWorkflow(
                    name = "Same URL Refresh Replay",
                    domain = "portal.example.com",
                    sourceUrl = targetUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "step_email",
                            order = 1,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Email",
                            selector = "input[name=\"email\"]",
                            value = "",
                            description = "Fill Email from Vault Pool",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.EMAIL.id,
                            vaultFolderId = AutomationVaultManager.defaultFolderIdForCategory(AutomationVaultCategory.EMAIL)
                        )
                    )
                )
            }

            h.vm.replayWorkflow(
                workflowId = workflowId,
                options = WorkflowPlayOptions(repeatCount = 2, clearDataAfterRun = true)
            )

            // Wait for Run 1 to initiate a fresh page refresh (even though already on targetUrl)
            idleMainUntil {
                h.vm.uiState.value.activeTab.loadRequestId == initialLoadReqId + 1L &&
                    h.vm.uiState.value.activeTab.isLoading
            }
            timeline.add("REFRESH_TRIGGERED_RUN_1")
            // Step 1 must NOT run while Run 1's refresh is pending
            assertFalse("Step 1 must wait for Run 1 page refresh to finish", timeline.contains("STEP_INPUT_RUN_1"))

            h.vm.onWebViewPageStarted(tabId, targetUrl)
            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)
            timeline.add("PAGE_FINISHED_RUN_1")

            // After Run 1 finishes, Clear Data must execute BEFORE Run 2's page refresh
            idleMainUntil {
                timeline.contains("STEP_INPUT_RUN_1") &&
                    timeline.contains("CLEAR_DOM_DATA") &&
                    h.vm.uiState.value.activeTab.loadRequestId == initialLoadReqId + 2L &&
                    h.vm.uiState.value.activeTab.isLoading
            }
            activeRunTracker = 2
            timeline.add("REFRESH_TRIGGERED_RUN_2")
            assertFalse("Step 1 of Run 2 must wait for Run 2 page refresh to finish", timeline.contains("STEP_INPUT_RUN_2"))

            timeline.add("PAGE_FINISHED_RUN_2")
            h.vm.onWebViewPageStarted(tabId, targetUrl)
            h.vm.onWebViewPageFinished(tabId, "Signup", targetUrl, canGoBack = false, canGoForward = false)

            idleMainUntil {
                val st = h.vm.uiState.value.executionState?.status
                st == "COMPLETED" || st == "FAILED"
            }

            val exec = h.vm.uiState.value.executionState!!
            assertEquals("Expected COMPLETED, msg=${exec.verificationMessage}", "COMPLETED", exec.status)
            assertEquals(2, exec.currentRunIndex)
            assertEquals(2, exec.totalRuns)
            assertEquals(2, activeRunTracker)

            val run1StepIdx = timeline.indexOf("STEP_INPUT_RUN_1")
            val clear1Idx = timeline.indexOf("CLEAR_DOM_DATA")
            val refresh2Idx = timeline.indexOf("REFRESH_TRIGGERED_RUN_2")
            val finish2Idx = timeline.indexOf("PAGE_FINISHED_RUN_2")
            val run2StepIdx = timeline.indexOf("STEP_INPUT_RUN_2")
            val clear2Idx = timeline.lastIndexOf("CLEAR_DOM_DATA")

            assertTrue("Run 1 must clear data after finishing: $timeline", clear1Idx > run1StepIdx)
            assertTrue("Run 2 refresh must occur AFTER Clear Data: $timeline", refresh2Idx > clear1Idx)
            assertTrue("Run 2 step must wait until Run 2 page finish: $timeline", run2StepIdx > finish2Idx)
            assertTrue("Final Clear Data must run after Run 2 finishes: $timeline", clear2Idx > run2StepIdx)
        } finally {
            h.close()
        }
    }

    @Test
    fun repeatReplay_whenRun1NavigatesAway_clearsDataThenRefreshesBackToStartUrlForRun2() {
        val h = createHarness(
            engine = AutomationEngine(
                jsEvalTimeoutMs = 2_000L,
                navTimeoutMs = 3_000L,
                domReadyTimeoutMs = 1_500L,
                domStabilityPollMs = 20L
            )
        )
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val startUrl = "https://portal.example.com/signup"
            val postSubmitUrl = "https://portal.example.com/welcome"
            var currentPageUrl = startUrl

            h.vm.submitNavigation(startUrl, tabId)
            idleMainUntil { h.vm.uiState.value.activeTab.url == startUrl }

            var run1Done = false
            val timeline = mutableListOf<String>()
            val wv = object : WebView(h.context) {
                init {
                    setTag(WEBVIEW_VIEWPORT_ATTACHED_TAG, true)
                }

                override fun getUrl(): String = currentPageUrl

                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    when {
                        script.contains("window.localStorage.clear()") -> {
                            timeline.add("CLEARED_DOM_DATA")
                            resultCallback?.onReceiveValue(JSONObject.quote(JSONObject().put("ok", true).toString()))
                        }
                        script.contains("readyState") -> {
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("readyState", "complete")
                                        .put("url", currentPageUrl)
                                        .put("shape", "2:24")
                                        .put("targetReady", true)
                                        .toString()
                                )
                            )
                        }
                        script.contains("user1@example.com") || script.contains("user2@example.com") -> {
                            val usedEmail = if (script.contains("user2@example.com")) "user2@example.com" else "user1@example.com"
                            timeline.add("EMAIL:$usedEmail")
                            if (!run1Done) {
                                // Simulate Run 1 form submission navigating to /welcome
                                run1Done = true
                                currentPageUrl = postSubmitUrl
                                h.vm.onWebViewPageFinished(tabId, "Welcome", postSubmitUrl, canGoBack = true, canGoForward = false)
                            }
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject()
                                        .put("ok", true)
                                        .put("msg", "Filled $usedEmail on $currentPageUrl")
                                        .put("resolvedSelector", "input[name=\"email\"]")
                                        .put("verified", true)
                                        .toString()
                                )
                            )
                        }
                        else -> {
                            resultCallback?.onReceiveValue(
                                JSONObject.quote(
                                    JSONObject().put("ok", true).put("elements", org.json.JSONArray()).toString()
                                )
                            )
                        }
                    }
                }
            }
            h.vm.registerWebView(tabId, wv)
            h.vm.onWebViewPageFinished(tabId, "Signup", startUrl, canGoBack = false, canGoForward = false)

            val workflowId = runBlocking {
                listOf("user1@example.com", "user2@example.com").forEachIndexed { idx, email ->
                    h.repo.automationVaultManager.saveOrUpdateEntry(
                        category = AutomationVaultCategory.EMAIL,
                        label = "Email #${idx + 1}",
                        plainValue = email,
                        websiteOrigin = "https://portal.example.com",
                        isSensitive = false
                    ).getOrThrow()
                }
                h.repo.saveAutomationWorkflow(
                    name = "Post-Submit Redirect Replay",
                    domain = "portal.example.com",
                    sourceUrl = startUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "step_email",
                            order = 1,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Email",
                            selector = "input[name=\"email\"]",
                            value = "",
                            description = "Fill Email from Vault Pool",
                            useVaultPool = true,
                            vaultCategory = AutomationVaultCategory.EMAIL.id,
                            vaultFolderId = AutomationVaultManager.defaultFolderIdForCategory(AutomationVaultCategory.EMAIL)
                        )
                    )
                )
            }

            h.vm.replayWorkflow(
                workflowId = workflowId,
                options = WorkflowPlayOptions(repeatCount = 2, clearDataAfterRun = true)
            )

            // Finish Run 1's initial page refresh
            idleMainUntil { h.vm.uiState.value.activeTab.isLoading }
            h.vm.onWebViewPageStarted(tabId, startUrl)
            h.vm.onWebViewPageFinished(tabId, "Signup", startUrl, canGoBack = false, canGoForward = false)

            // Wait until Run 1 finished on /welcome, cleared data, and Run 2 navigated back to /signup
            idleMainUntil(timeoutMs = 6_000L) {
                run1Done &&
                    h.vm.uiState.value.activeTab.url == startUrl &&
                    h.vm.uiState.value.activeTab.isLoading &&
                    h.vm.uiState.value.executionState?.currentRunIndex == 2
            }

            // Simulate WebView loading /signup again for Run 2
            currentPageUrl = startUrl
            timeline.add("RUN2_PAGE_FINISHED")
            h.vm.onWebViewPageStarted(tabId, startUrl)
            h.vm.onWebViewPageFinished(tabId, "Signup", startUrl, canGoBack = true, canGoForward = false)

            idleMainUntil {
                val st = h.vm.uiState.value.executionState?.status
                st == "COMPLETED" || st == "FAILED"
            }
            val exec = h.vm.uiState.value.executionState!!
            assertEquals("Expected COMPLETED, msg=${exec.verificationMessage}", "COMPLETED", exec.status)
            assertEquals(startUrl, h.vm.uiState.value.activeTab.url)
            val clearBeforeRun2Idx = timeline.indexOf("CLEARED_DOM_DATA")
            val run2PageFinishedIdx = timeline.indexOf("RUN2_PAGE_FINISHED")
            val email1Idx = timeline.indexOf("EMAIL:user1@example.com")
            val email2Idx = timeline.indexOf("EMAIL:user2@example.com")
            assertTrue("Expected email1 -> clear -> run2 page finished -> email2 in timeline: $timeline", email1Idx in 0 until clearBeforeRun2Idx)
            assertTrue("Expected clear before run2 page finished: $timeline", clearBeforeRun2Idx in 0 until run2PageFinishedIdx)
            assertTrue("Expected email2 after run2 page finished: $timeline", email2Idx > run2PageFinishedIdx)
        } finally {
            h.close()
        }
    }
}
