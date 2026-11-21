package com.example

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ApexDatabase
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.DownloadLocationMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.DownloadStorageHelper
import com.example.engine.FastDownloadEngine
import com.example.ui.browser.DownloadsScreenOverlay
import com.example.ui.browser.SettingsScreenOverlay
import com.example.ui.theme.ApexBrowserTheme
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MobileDownloadLocationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: ApexDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var repository: BrowserRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sharedPrefs = context.getSharedPreferences("test_mobile_download_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()
        prefsRepo = UserPreferencesRepository(context)
        repository = BrowserRepository(database.apexDao(), prefsRepo, sharedPrefs)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `mobile Downloads summary resolves to public Download directory and formats readable SAF paths`() {
        val publicDownloads = DownloadStorageHelper.resolvePublicDownloadsDirectory()
        val displayPath = DownloadStorageHelper.getMobileDownloadsDisplayPath(context)
        val stagingDir = DownloadStorageHelper.resolveDirectory(context, DownloadLocationMode.APP_EXTERNAL)

        assertEquals(publicDownloads.absolutePath, displayPath)
        assertNotEquals(stagingDir.absolutePath, displayPath)

        val (label, path) = DownloadStorageHelper.formatLocationSummary(
            context = context,
            mode = DownloadLocationMode.APP_EXTERNAL,
            customTreeUri = "",
            customLabel = ""
        )
        assertEquals("Downloads", label)
        assertEquals(publicDownloads.absolutePath, path)

        val safUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FInvoices"
        assertEquals("Download/Invoices", DownloadStorageHelper.extractFolderLabelFromUri(safUri))
        assertEquals(
            "Internal storage › Download › Invoices",
            DownloadStorageHelper.formatSafDisplayPath(safUri)
        )

        val (customLabel, customPath) = DownloadStorageHelper.formatLocationSummary(
            context = context,
            mode = DownloadLocationMode.CUSTOM_SAF,
            customTreeUri = safUri,
            customLabel = ""
        )
        assertEquals("Download/Invoices", customLabel)
        assertEquals("Internal storage › Download › Invoices", customPath)

        val safDocUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FInvoices/document/primary%3ADownload%2FInvoices%2Finvoice.pdf"
        assertEquals(
            "Internal storage › Download › Invoices › invoice.pdf",
            DownloadStorageHelper.formatSavedItemLocationLabel(context, safDocUri, "invoice.pdf")
        )

        val rawDownloadsUri = "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload"
        assertEquals("Download", DownloadStorageHelper.extractFolderLabelFromUri(rawDownloadsUri))
        assertEquals("/storage/emulated/0/Download", DownloadStorageHelper.formatSafDisplayPath(rawDownloadsUri))

        val homeDocsUri = "content://com.android.externalstorage.documents/tree/home%3AInvoices"
        assertEquals("Documents/Invoices", DownloadStorageHelper.extractFolderLabelFromUri(homeDocsUri))
        assertEquals("Internal storage › Documents › Invoices", DownloadStorageHelper.formatSafDisplayPath(homeDocsUri))
    }

    @Test
    fun `publishFileToPublicDownloads moves staging file into public Download folder and removes staging copy`() {
        val stagingDir = DownloadStorageHelper.resolveDirectory(context, DownloadLocationMode.APP_EXTERNAL)
        val stagingFile = File(stagingDir, "mobile_doc_test.pdf")
        val content = "PDF-MOBILE-DOWNLOAD-TEST".toByteArray()
        stagingFile.writeBytes(content)

        val published = DownloadStorageHelper.publishFileToPublicDownloads(
            context = context,
            sourceFile = stagingFile,
            mimeType = "application/pdf",
            fileName = "mobile_doc_test.pdf"
        )

        assertNotNull("Expected file to be published into mobile public Downloads", published)
        assertFalse("Staging copy should be removed after publishing", stagingFile.exists())

        val publishedFile = File(published!!.locationPathOrUri)
        val publicDir = DownloadStorageHelper.resolvePublicDownloadsDirectory()
        assertTrue(publishedFile.exists())
        assertTrue(
            "Published file (${publishedFile.absolutePath}) should live inside public Download (${publicDir.absolutePath})",
            publishedFile.absolutePath.startsWith(publicDir.absolutePath)
        )
        assertArrayEquals(content, publishedFile.readBytes())

        val viewIntent = DownloadStorageHelper.buildOpenFileIntent(
            context = context,
            localFilePath = publishedFile.absolutePath,
            fileName = published.resolvedFileName,
            mimeType = "application/pdf",
            userInitiated = true
        )
        assertNotNull(viewIntent)

        DownloadStorageHelper.deleteDownloadArtifact(context, published.locationPathOrUri)
        assertFalse(publishedFile.exists())
    }

    @Test
    fun `FastDownloadEngine in default APP_EXTERNAL mode saves completed download to mobile public Download folder`() {
        runBlocking {
            repository.setDownloadLocation(DownloadLocationMode.APP_EXTERNAL, "", "")
            repository.setAskWhereToSaveFiles(false)
            repository.setParallelDownloadingEnabled(false)
        }
        val engine = FastDownloadEngine(context, repository)
        val payload = ByteArray(96 * 1024) { ((it * 17) % 241).toByte() }

        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Content-Type: application/pdf\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(payload)
                        out.flush()
                    }
                }
            }
        }

        var completedPathToClean = ""
        try {
            val startedLatch = CountDownLatch(1)
            var downloadId = -1L
            engine.enqueueDownload(
                url = "http://127.0.0.1:$port/invoice.pdf",
                suggestedFileName = "invoice.pdf"
            ) { entity ->
                downloadId = entity.id
                startedLatch.countDown()
            }

            val startDeadline = System.currentTimeMillis() + 5_000L
            while (startedLatch.count > 0 && System.currentTimeMillis() < startDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20L)
            }
            assertEquals(0L, startedLatch.count)

            var finalEntity = runBlocking { database.apexDao().getDownloadById(downloadId) }
            val completeDeadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < completeDeadline) {
                finalEntity = runBlocking { database.apexDao().getDownloadById(downloadId) }
                if (finalEntity?.status == FastDownloadEngine.STATUS_COMPLETED) break
                Thread.sleep(40L)
            }

            assertEquals(FastDownloadEngine.STATUS_COMPLETED, finalEntity?.status)
            completedPathToClean = finalEntity!!.localFilePath
            val publicDir = DownloadStorageHelper.resolvePublicDownloadsDirectory()
            assertTrue(
                "Expected completed file in mobile Download folder (${publicDir.absolutePath}) but was $completedPathToClean",
                completedPathToClean.startsWith(publicDir.absolutePath)
            )
            assertArrayEquals(payload, File(completedPathToClean).readBytes())
        } finally {
            runCatching { server.close() }
            serverThread.interrupt()
            if (completedPathToClean.isNotBlank()) {
                DownloadStorageHelper.deleteDownloadArtifact(context, completedPathToClean)
            }
        }
    }

    @Test
    fun `Downloads settings picker shows mobile Downloads folder and readable custom mobile path`() {
        val customSafUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FStudy"
        var prefs by mutableStateOf(
            BrowserPreferences(
                downloadLocationMode = DownloadLocationMode.APP_EXTERNAL,
                customDownloadTreeUri = customSafUri,
                customDownloadFolderLabel = "Download/Study"
            )
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                SettingsScreenOverlay(
                    preferences = prefs,
                    onSelectSearchEngine = {},
                    onToggleShields = {},
                    onToggleHttpsUpgrade = {},
                    onToggleScriptGuard = {},
                    onToggleSmartPreload = {},
                    onToggleDesktopViewport = {},
                    onToggleDynamicColor = {},
                    onSelectSeedPalette = {},
                    onClearBrowsingData = {},
                    onSelectDownloadLocation = { mode, uri, label ->
                        prefs = prefs.copy(
                            downloadLocationMode = mode,
                            customDownloadTreeUri = uri,
                            customDownloadFolderLabel = label
                        )
                    },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_downloads_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val expectedPublicPath = DownloadStorageHelper.getMobileDownloadsDisplayPath(context)
        composeTestRule.onNodeWithText(expectedPublicPath, substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_download_location_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Device Downloads folder • $expectedPublicPath").assertIsDisplayed()
        composeTestRule.onNodeWithText("Internal storage › Download › Study").assertIsDisplayed()
    }

    @Test
    fun `manifest declares zero broad storage permissions for mobile Downloads access`() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(requested.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse(requested.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        assertFalse(requested.contains(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertFalse(requested.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(requested.contains("android.permission.READ_MEDIA_VIDEO"))
    }

    @Test
    fun `DownloadsScreenOverlay displays active mobile Downloads folder banner`() {
        val expectedPublicPath = DownloadStorageHelper.getMobileDownloadsDisplayPath(context)
        var settingsOpened = false

        composeTestRule.setContent {
            ApexBrowserTheme {
                DownloadsScreenOverlay(
                    downloads = emptyList(),
                    downloadLocationMode = DownloadLocationMode.APP_EXTERNAL,
                    onStartNewDownload = { _, _ -> },
                    onPauseDownload = {},
                    onResumeDownload = {},
                    onRetryDownload = {},
                    onCancelDownload = {},
                    onDeleteDownload = {},
                    onClearCompletedDownloads = {},
                    onClearAllDownloads = {},
                    onOpenUrlInBrowser = {},
                    onOpenDownloadLocationSettings = { settingsOpened = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("downloads_location_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedPublicPath, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("downloads_location_banner").performClick()
        assertTrue(settingsOpened)
    }
}
