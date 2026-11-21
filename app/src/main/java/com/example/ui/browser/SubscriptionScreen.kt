package com.example.ui.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.ProSubscriptionPackage
import com.example.ui.theme.EmphasizedTypography
import com.example.ui.theme.GoogleSansFlexFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubscriptionScreen(
    isProActivated: Boolean,
    isProExpired: Boolean,
    activeKey: String,
    activePackage: ProSubscriptionPackage,
    expiresAtMillis: Long,
    remainingProMillis: Long,
    selectedPackage: ProSubscriptionPackage,
    onSelectPackage: (ProSubscriptionPackage) -> Unit,
    remainingFreePlaysToday: Int,
    dailyPlayLimit: Int,
    promptReason: String?,
    activationError: String?,
    onActivateKey: (String, ProSubscriptionPackage) -> Unit,
    onClearError: () -> Unit,
    onDeactivatePro: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val authorEmail = stringResource(R.string.pro_author_contact_email)
    val chooserTitle = stringResource(R.string.pro_contact_author_chooser_title)
    val emailCopiedLabel = stringResource(R.string.pro_author_email_copied)
    val requestCopiedLabel = stringResource(R.string.pro_request_message_copied)
    val clampedRemaining = remainingFreePlaysToday.coerceIn(0, dailyPlayLimit)
    val usedPlays = (dailyPlayLimit - clampedRemaining).coerceIn(0, dailyPlayLimit)
    var keyInput by remember(isProActivated) { mutableStateOf("") }
    var contactFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var showContactFallbackDialog by remember { mutableStateOf(false) }

    val emailSubject = "Apex Pro ${selectedPackage.displayName} Activation Key Request (${selectedPackage.formattedPrice})"
    val emailBody = "Hi Author,\n\nI would like to request an Apex Pro activation key for the ${selectedPackage.displayName} package (${selectedPackage.formattedPrice} for ${selectedPackage.billingUnitLabel} unlimited automation plays)."

    fun launchAuthorEmailOrFallback(openFallbackOnMissingClient: Boolean) {
        val mailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$authorEmail")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(authorEmail))
            putExtra(Intent.EXTRA_SUBJECT, emailSubject)
            putExtra(Intent.EXTRA_TEXT, emailBody)
        }
        val canResolveMail = runCatching {
            mailIntent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        val launched = if (canResolveMail) {
            runCatching {
                context.startActivity(Intent.createChooser(mailIntent, chooserTitle))
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!launched && openFallbackOnMissingClient) {
            clipboardManager.setText(AnnotatedString(authorEmail))
            contactFeedbackMessage = "$emailCopiedLabel: $authorEmail"
            showContactFallbackDialog = true
        }
    }

    val packagesInOrder = remember {
        listOf(
            ProSubscriptionPackage.MONTH,
            ProSubscriptionPackage.WEEK,
            ProSubscriptionPackage.DAY
        )
    }

    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
            fontFamily = GoogleSansFlexFamily
        )
    ) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("subscription_pro_screen"),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(
                                if (isProActivated) {
                                    R.string.pro_dialog_title_active
                                } else {
                                    R.string.sync_backup_dialog_title
                                }
                            ),
                            style = EmphasizedTypography.headlineHero.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize,
                                lineHeight = MaterialTheme.typography.titleLarge.lineHeight
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("pro_screen_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.pro_cd_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        ) { innerPadding ->
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if ((!promptReason.isNullOrBlank() || isProExpired) && !isProActivated) {
                        val bannerText = promptReason?.takeIf { it.isNotBlank() }
                            ?: "Your ${activePackage.displayName} Apex Pro pass has expired. Pick a package below and activate a new key to continue unlimited automation."
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(
                                0.75.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pro_limit_reason_banner")
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = bannerText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Hero Plan & Quota Status Card
                    val heroGradient = if (isProActivated) {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            if (isProActivated) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pro_status_card")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .background(heroGradient)
                                .padding(18.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isProActivated) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isProActivated) {
                                                Icons.Filled.Verified
                                            } else {
                                                Icons.Outlined.WorkspacePremium
                                            },
                                            contentDescription = null,
                                            tint = if (isProActivated) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (isProActivated) {
                                            stringResource(R.string.pro_plan_active_label)
                                        } else {
                                            stringResource(
                                                R.string.pro_plan_free_format,
                                                clampedRemaining,
                                                dailyPlayLimit
                                            )
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isProActivated) {
                                        Text(
                                            text = "${activePackage.displayName} Pass (${activePackage.formattedPrice} / ${activePackage.billingUnitLabel})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            if (!isProActivated) {
                                LinearProgressIndicator(
                                    progress = {
                                        usedPlays.toFloat() / dailyPlayLimit.coerceAtLeast(1).toFloat()
                                    },
                                    color = if (clampedRemaining <= 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                )
                            }

                            Text(
                                text = stringResource(
                                    if (isProActivated) {
                                        R.string.pro_dialog_subtitle_active
                                    } else {
                                        R.string.sync_backup_dialog_message
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (isProActivated && expiresAtMillis > 0L) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("pro_validity_summary")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Remaining: ${formatRemainingDuration(remainingProMillis)} • Expires ${formatExpiryDate(expiresAtMillis)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isProActivated) {
                        // Package Selection Section: Month ($5.00), Week ($1.17), Day ($0.17)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.pro_packages_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            packagesInOrder.forEach { pkg ->
                                val isSelected = selectedPackage == pkg
                                val titleRes = when (pkg) {
                                    ProSubscriptionPackage.MONTH -> R.string.pro_package_month_title
                                    ProSubscriptionPackage.WEEK -> R.string.pro_package_week_title
                                    ProSubscriptionPackage.DAY -> R.string.pro_package_day_title
                                }
                                val descRes = when (pkg) {
                                    ProSubscriptionPackage.MONTH -> R.string.pro_package_month_desc
                                    ProSubscriptionPackage.WEEK -> R.string.pro_package_week_desc
                                    ProSubscriptionPackage.DAY -> R.string.pro_package_day_desc
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 0.75.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable(role = Role.RadioButton) {
                                            onSelectPackage(pkg)
                                        }
                                        .testTag("pro_package_${pkg.id}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 14.dp)
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { onSelectPackage(pkg) }
                                        )
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(titleRes),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (pkg == ProSubscriptionPackage.MONTH) {
                                                    Surface(
                                                        shape = RoundedCornerShape(999.dp),
                                                        color = MaterialTheme.colorScheme.primary
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.pro_package_best_value),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.padding(
                                                                horizontal = 8.dp,
                                                                vertical = 2.dp
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = stringResource(descRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = pkg.formattedPrice,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            Text(
                                                text = "/ ${pkg.displayName.lowercase()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Contact Author Card & Primary Tonal Action
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                0.75.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pro_contact_author_card")
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.pro_contact_author_card_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = authorEmail,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(authorEmail))
                                            contactFeedbackMessage = "$emailCopiedLabel: $authorEmail"
                                        },
                                        shapes = IconButtonDefaults.shapes(),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("pro_copy_author_email_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = stringResource(R.string.pro_copy_author_email),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { launchAuthorEmailOrFallback(openFallbackOnMissingClient = true) },
                                    shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("pro_contact_author_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Email,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${stringResource(R.string.pro_contact_author_button)} (${selectedPackage.formattedPrice})",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontFamily = GoogleSansFlexFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                AnimatedVisibility(visible = !contactFeedbackMessage.isNullOrBlank()) {
                                    Text(
                                        text = contactFeedbackMessage.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = GoogleSansFlexFamily
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Activation Key Input + 1-Tap Paste
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { updated ->
                                keyInput = updated
                                if (activationError != null) {
                                    onClearError()
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.pro_activation_key_label),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = GoogleSansFlexFamily
                                    )
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "Paste key for ${selectedPackage.displayName} pass (${selectedPackage.billingUnitLabel})",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = GoogleSansFlexFamily
                                    )
                                )
                            },
                            singleLine = true,
                            isError = activationError != null,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val pasted = clipboardManager.getText()?.text.orEmpty().trim()
                                        if (pasted.isNotEmpty()) {
                                            keyInput = pasted
                                            if (activationError != null) {
                                                onClearError()
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("pro_paste_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentPaste,
                                        contentDescription = stringResource(R.string.pro_paste_key_action)
                                    )
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = GoogleSansFlexFamily,
                                fontWeight = FontWeight.Medium
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pro_activation_key_input")
                        )

                        if (!activationError.isNullOrBlank()) {
                            Text(
                                text = activationError,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = GoogleSansFlexFamily
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("pro_activation_error")
                            )
                        }

                        Button(
                            onClick = { onActivateKey(keyInput, selectedPackage) },
                            enabled = keyInput.isNotBlank(),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("pro_activate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Activate ${selectedPackage.displayName} (${selectedPackage.formattedPrice})",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = GoogleSansFlexFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                0.75.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Activated Key",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = activeKey.ifBlank { BrowserPreferences.VALID_PRO_ACTIVATION_KEY },
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            ),
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Selected Package",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${activePackage.displayName} • ${activePackage.formattedPrice} (${activePackage.billingUnitLabel})",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            ),
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    TextButton(
                                        onClick = onDeactivatePro,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("pro_deactivate_button")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.pro_deactivate_button),
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontFamily = GoogleSansFlexFamily
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (showContactFallbackDialog) {
            AlertDialog(
                onDismissRequest = { showContactFallbackDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.pro_contact_author_dialog_title),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = GoogleSansFlexFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.pro_contact_author_dialog_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = GoogleSansFlexFamily
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = authorEmail,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = GoogleSansFlexFamily,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = emailSubject,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = GoogleSansFlexFamily,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = emailBody,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = GoogleSansFlexFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString("To: $authorEmail\nSubject: $emailSubject\n\n$emailBody")
                            )
                            contactFeedbackMessage = requestCopiedLabel
                            showContactFallbackDialog = false
                        },
                        modifier = Modifier.testTag("pro_dialog_copy_request_button")
                    ) {
                        Text(
                            text = stringResource(R.string.pro_copy_request_message),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = GoogleSansFlexFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showContactFallbackDialog = false },
                        modifier = Modifier.testTag("pro_dialog_close_button")
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = GoogleSansFlexFamily
                            )
                        )
                    }
                },
                modifier = Modifier.testTag("pro_contact_author_dialog")
            )
        }
    }
}

internal fun formatRemainingDuration(remainingMillis: Long): String {
    if (remainingMillis <= 0L) return "Expired"
    val totalMinutes = remainingMillis / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "${days}d ${hours}h left"
        hours > 0L -> "${hours}h ${minutes}m left"
        else -> "${minutes.coerceAtLeast(1L)}m left"
    }
}

private fun formatExpiryDate(expiresAtMillis: Long): String {
    return runCatching {
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(expiresAtMillis))
    }.getOrDefault("")
}
