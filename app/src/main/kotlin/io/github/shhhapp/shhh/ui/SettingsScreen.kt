package io.github.shhhapp.shhh.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.shhhapp.shhh.BuildConfig
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.tile.ShhhTileService
import io.github.shhhapp.shhh.update.CheckResult
import io.github.shhhapp.shhh.update.ReleaseInfo
import io.github.shhhapp.shhh.update.UpdateChecker
import io.github.shhhapp.shhh.widget.ShhhWidgetReceiver
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: ShhhSettings,
    canScheduleExact: Boolean,
    onBack: () -> Unit,
    onQuietHoursChanged: () -> Unit,
    onSettingChanged: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onRequestExactAlarmAccess: () -> Unit,
    updateChecker: UpdateChecker = remember { UpdateChecker() }
) {
    var showExactAlarmDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Observable locale read: recomposes when the user changes language.
    val chipLocale: Locale =
        androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

    var restoreMode by remember { mutableStateOf(settings.restoreMode) }
    var fixedPercent by remember { mutableIntStateOf(settings.fixedRestorePercent) }
    var quietEnabled by remember { mutableStateOf(settings.quietHoursEnabled) }
    var quietStart by remember { mutableIntStateOf(settings.quietStartMinutes) }
    var quietEnd by remember { mutableIntStateOf(settings.quietEndMinutes) }
    var quietDays by remember { mutableStateOf(settings.quietDays) }
    var liveCountdown by remember { mutableStateOf(settings.liveCountdownEnabled) }
    var headphones by remember { mutableStateOf(settings.headphonesAutoRestore) }
    var autoUpdate by remember { mutableStateOf(settings.autoUpdateCheckEnabled) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showAutomationSheet by remember { mutableStateOf(false) }

    val updateScope = rememberCoroutineScope()
    var checkState by remember { mutableStateOf(UpdateCheckState.IDLE) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    fun checkForUpdates() {
        // A found release is kept until the dialog handles it; re-tapping the
        // row reopens the dialog instead of re-checking.
        if (availableRelease != null) {
            showUpdateDialog = true
            return
        }
        updateScope.launch {
            checkState = UpdateCheckState.CHECKING
            when (val result = updateChecker.check(BuildConfig.VERSION_NAME)) {
                is CheckResult.UpdateAvailable -> {
                    availableRelease = result.release
                    checkState = UpdateCheckState.AVAILABLE
                    showUpdateDialog = true
                }

                CheckResult.UpToDate -> checkState = UpdateCheckState.NONE
                CheckResult.Error -> checkState = UpdateCheckState.ERROR
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    M3Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---- Hush behavior ----
            SectionHeader(stringResource(R.string.settings_behavior_header))
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    RowTitle(
                        stringResource(R.string.settings_restore_title),
                        stringResource(R.string.settings_restore_hint)
                    )
                    Spacer(Modifier.height(12.dp))
                    ExpressiveToggleGroup(
                        options = listOf(
                            ToggleOption(
                                stringResource(R.string.settings_restore_previous),
                                R.drawable.ic_history
                            ),
                            ToggleOption(
                                if (restoreMode == ShhhSettings.RestoreMode.FIXED) {
                                    stringResource(
                                        R.string.settings_restore_fixed_value, fixedPercent
                                    )
                                } else {
                                    stringResource(R.string.settings_restore_fixed)
                                },
                                R.drawable.ic_tune
                            )
                        ),
                        selectedIndex = restoreMode.ordinal,
                        onSelect = { index ->
                            restoreMode = ShhhSettings.RestoreMode.entries[index]
                            settings.restoreMode = restoreMode
                            onSettingChanged()
                        }
                    )
                    AnimatedVisibility(
                        visible = restoreMode == ShhhSettings.RestoreMode.FIXED,
                        enter = RevealEnter,
                        exit = RevealExit
                    ) {
                        Slider(
                            value = fixedPercent.toFloat(),
                            onValueChange = { fixedPercent = it.toInt() },
                            onValueChangeFinished = {
                                settings.fixedRestorePercent = fixedPercent
                                onSettingChanged()
                            },
                            valueRange = 10f..100f,
                            steps = 8,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // ---- Quiet hours ----
            SectionHeader(stringResource(R.string.quiet_hours_title))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.quiet_hours_title),
                    hint = stringResource(R.string.quiet_hours_hint),
                    checked = quietEnabled,
                    modifier = Modifier.testTag("toggle_quiet")
                ) { checked ->
                    if (checked && !canScheduleExact) {
                        showExactAlarmDialog = true
                        return@SwitchRow
                    }
                    quietEnabled = checked
                    settings.quietHoursEnabled = checked
                    if (checked) onRequestNotificationPermission()
                    onQuietHoursChanged()
                }
                AnimatedVisibility(
                    visible = quietEnabled,
                    enter = RevealEnter,
                    exit = RevealExit
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GroupDivider()
                        QuietHoursDial(
                            startMinutes = quietStart,
                            endMinutes = quietEnd,
                            onChange = { start, end ->
                                quietStart = start
                                quietEnd = end
                            },
                            onChangeFinished = {
                                settings.quietStartMinutes = quietStart
                                settings.quietEndMinutes = quietEnd
                                onQuietHoursChanged()
                            },
                            onStartClick = { showStartPicker = true },
                            onEndClick = { showEndPicker = true },
                            modifier = Modifier.padding(vertical = 16.dp).testTag("quiet_dial")
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(
                                    selected = day in quietDays,
                                    onClick = {
                                        quietDays = if (day in quietDays) {
                                            quietDays - day
                                        } else {
                                            quietDays + day
                                        }
                                        settings.quietDays = quietDays
                                        onQuietHoursChanged()
                                    },
                                    label = {
                                        Text(
                                            day.getDisplayName(TextStyle.NARROW, chipLocale),
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // ---- Notifications ----
            SectionHeader(stringResource(R.string.settings_notifications_header))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_countdown_title),
                    hint = stringResource(R.string.settings_countdown_hint),
                    checked = liveCountdown
                ) { checked ->
                    liveCountdown = checked
                    settings.liveCountdownEnabled = checked
                    if (checked) onRequestNotificationPermission()
                    onSettingChanged()
                }
            }

            // ---- Headphones ----
            SectionHeader(stringResource(R.string.settings_headphones_header))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_headphones_title),
                    hint = stringResource(R.string.settings_headphones_hint),
                    checked = headphones
                ) { checked ->
                    headphones = checked
                    settings.headphonesAutoRestore = checked
                    if (checked) {
                        onRequestBluetoothPermission()
                        onRequestNotificationPermission()
                    }
                    onSettingChanged()
                }
            }

            // ---- Quick access ----
            SectionHeader(stringResource(R.string.settings_quick_access_header))
            SettingsGroup {
                ClickRow(
                    title = stringResource(R.string.setup_add_tile),
                    hint = stringResource(R.string.tile_content_description)
                ) { requestAddTile(context) }
                GroupDivider()
                ClickRow(
                    title = stringResource(R.string.setup_add_widget),
                    hint = stringResource(R.string.widget_description)
                ) { requestPinWidget(context) }
                GroupDivider()
                ClickRow(
                    title = stringResource(R.string.settings_automation_title),
                    hint = stringResource(R.string.settings_automation_hint)
                ) { showAutomationSheet = true }
            }

            // ---- Updates ----
            SectionHeader(stringResource(R.string.settings_updates_header))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_update_title),
                    hint = stringResource(R.string.settings_auto_update_hint),
                    checked = autoUpdate,
                    modifier = Modifier.testTag("toggle_auto_update")
                ) { checked ->
                    autoUpdate = checked
                    settings.autoUpdateCheckEnabled = checked
                    onSettingChanged()
                }
                GroupDivider()
                // if-chain, not an exhaustive when: a when-expression would
                // compile in a dead NoWhenBranchMatched throw the coverage
                // gate counts as a missed line.
                ClickRow(
                    title = stringResource(R.string.settings_check_update_title),
                    hint = if (checkState == UpdateCheckState.CHECKING) {
                        stringResource(R.string.settings_check_update_checking)
                    } else if (checkState == UpdateCheckState.NONE) {
                        stringResource(R.string.settings_check_update_none)
                    } else if (checkState == UpdateCheckState.ERROR) {
                        stringResource(R.string.settings_check_update_error)
                    } else if (checkState == UpdateCheckState.AVAILABLE) {
                        stringResource(
                            R.string.settings_check_update_available,
                            availableRelease?.versionName.orEmpty()
                        )
                    } else {
                        stringResource(R.string.settings_check_update_idle)
                    }
                ) { checkForUpdates() }
            }

            // ---- About ----
            SectionHeader(stringResource(R.string.settings_about_header))
            SettingsGroup {
                ClickRow(
                    title = stringResource(R.string.app_name),
                    hint = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    onClick = null
                )
                GroupDivider()
                ClickRow(
                    title = stringResource(R.string.settings_source_title),
                    hint = stringResource(R.string.settings_source_hint)
                ) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())
                    )
                }
                GroupDivider()
                ClickRow(
                    title = stringResource(R.string.settings_troubleshooting_title),
                    hint = stringResource(R.string.settings_troubleshooting_hint)
                ) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, TROUBLESHOOTING_URL.toUri())
                    )
                }
                GroupDivider()
                val contactSubject = stringResource(
                    R.string.contact_email_subject, BuildConfig.VERSION_NAME
                )
                ClickRow(
                    title = stringResource(R.string.settings_contact_title),
                    hint = stringResource(R.string.settings_contact_hint)
                ) {
                    // No email app is a real possibility; failing silently
                    // beats crashing Settings.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = "mailto:".toUri()
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
                                putExtra(Intent.EXTRA_SUBJECT, contactSubject)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialMinutes = quietStart,
            onDismiss = { showStartPicker = false },
            onConfirm = { minutes ->
                quietStart = minutes
                settings.quietStartMinutes = minutes
                showStartPicker = false
                onQuietHoursChanged()
            }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialMinutes = quietEnd,
            onDismiss = { showEndPicker = false },
            onConfirm = { minutes ->
                quietEnd = minutes
                settings.quietEndMinutes = minutes
                showEndPicker = false
                onQuietHoursChanged()
            }
        )
    }
    if (showAutomationSheet) {
        AutomationSheet(onDismiss = { showAutomationSheet = false })
    }
    val releaseToShow = availableRelease
    if (showUpdateDialog && releaseToShow != null) {
        UpdateDialog(
            release = releaseToShow,
            checker = updateChecker,
            onDismiss = { showUpdateDialog = false }
        )
    }
    if (showExactAlarmDialog) {
        ExactAlarmDialog(
            onGrant = {
                showExactAlarmDialog = false
                onRequestExactAlarmAccess()
            },
            onDismiss = { showExactAlarmDialog = false }
        )
    }
}

// ---- Reusable pieces ----

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun GroupDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun RowTitle(title: String, hint: String?) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) { RowTitle(title, hint) }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickRow(
    title: String,
    hint: String?,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) { RowTitle(title, hint) }
    }
}

@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = { TimePicker(state = state) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.automation_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.automation_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                R.string.automation_action_hush,
                R.string.automation_action_unhush,
                R.string.automation_action_toggle,
                R.string.automation_action_media
            ).forEach { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun requestAddTile(context: Context) {
    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
        ComponentName(context, ShhhTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor
    ) { }
}

private fun requestPinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, ShhhWidgetReceiver::class.java),
            null,
            null
        )
    }
}

private enum class UpdateCheckState { IDLE, CHECKING, NONE, ERROR, AVAILABLE }

private const val SOURCE_URL = "https://github.com/7sStudio/shhh-app"
private const val TROUBLESHOOTING_URL = "https://dontkillmyapp.com"
private const val DEVELOPER_EMAIL = "7sStudio@tutamail.com"
