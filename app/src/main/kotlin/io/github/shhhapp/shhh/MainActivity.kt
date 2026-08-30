package io.github.shhhapp.shhh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.schedule.HushAlarms
import io.github.shhhapp.shhh.ui.HomeScreen
import io.github.shhhapp.shhh.ui.RevealEnter
import io.github.shhhapp.shhh.ui.RevealExit
import io.github.shhhapp.shhh.ui.SettingsScreen
import io.github.shhhapp.shhh.ui.UpdateDialog
import io.github.shhhapp.shhh.ui.theme.ShhhTheme
import io.github.shhhapp.shhh.update.CheckResult
import io.github.shhhapp.shhh.update.ReleaseInfo
import io.github.shhhapp.shhh.update.UpdateChecker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShhhTheme {
                ShhhApp()
            }
        }
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
fun ShhhApp(updateChecker: UpdateChecker = remember { UpdateChecker() }) {
    val context = LocalContext.current
    val manager = remember { HushManager(context) }
    val settings = remember { ShhhSettings(context) }

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var quiet by remember { mutableStateOf(manager.isQuiet) }
    var hasDndAccess by remember { mutableStateOf(manager.hasDndAccess) }
    var timerEnd by remember { mutableLongStateOf(manager.activeTimerEnd) }
    var canScheduleExact by remember { mutableStateOf(HushAlarms.canScheduleExact(context)) }
    // Bumped whenever persisted settings change so summaries recompose.
    var settingsRevision by remember { mutableIntStateOf(0) }

    fun refresh() {
        quiet = manager.isQuiet
        hasDndAccess = manager.hasDndAccess
        timerEnd = manager.activeTimerEnd
        canScheduleExact = HushAlarms.canScheduleExact(context)
        settingsRevision++
    }

    // Refresh when returning from Settings (permission grant) or recents.
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    // Opt-in update check: at most once a day, prompting once per new version.
    var foundUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (settings.autoUpdateCheckEnabled &&
            UpdateChecker.isCheckDue(settings.lastUpdateCheckMillis, now)
        ) {
            // Stamped at attempt time so a failing network isn't hammered.
            settings.lastUpdateCheckMillis = now
            val result = updateChecker.check(BuildConfig.VERSION_NAME)
            if (result is CheckResult.UpdateAvailable &&
                result.release.versionName != settings.lastPromptedUpdateVersion
            ) {
                settings.lastPromptedUpdateVersion = result.release.versionName
                foundUpdate = result.release
            }
        }
    }
    foundUpdate?.let { release ->
        UpdateDialog(
            release = release,
            checker = updateChecker,
            onDismiss = { foundUpdate = null }
        )
    }

    // Stay in sync with ringer and Do Not Disturb changes made anywhere else.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = refresh()
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun ensureNotificationPermission() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun ensureBluetoothPermission() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun requestExactAlarmAccess() {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        )
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    AnimatedContent(
        targetState = screen,
        // App rule (see ui/Transitions.kt): no edge slides — fade in place.
        transitionSpec = { RevealEnter togetherWith RevealExit },
        label = "screen"
    ) { current ->
        when (current) {
            Screen.HOME -> HomeScreen(
                quiet = quiet,
                hasDndAccess = hasDndAccess,
                canScheduleExact = canScheduleExact,
                timerEndMillis = timerEnd,
                settings = settings,
                settingsRevision = settingsRevision,
                onToggle = {
                    if (manager.toggle() == QuietModeController.Result.NeedsDndAccess) {
                        hasDndAccess = false
                    }
                    refresh()
                },
                onHushFor = { minutes ->
                    ensureNotificationPermission()
                    manager.hush(durationMinutes = minutes)
                    refresh()
                },
                onEndTimer = {
                    manager.unhush()
                    refresh()
                },
                onQuietHoursToggled = { enabled ->
                    settings.quietHoursEnabled = enabled
                    onQuietHoursChanged(context, manager)
                    refresh()
                },
                onRequestExactAlarmAccess = { requestExactAlarmAccess() },
                onOpenSettings = { screen = Screen.SETTINGS }
            )

            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                canScheduleExact = canScheduleExact,
                onBack = { screen = Screen.HOME },
                onQuietHoursChanged = {
                    onQuietHoursChanged(context, manager)
                    refresh()
                },
                onSettingChanged = { settingsRevision++ },
                onRequestNotificationPermission = { ensureNotificationPermission() },
                onRequestBluetoothPermission = { ensureBluetoothPermission() },
                onRequestExactAlarmAccess = { requestExactAlarmAccess() },
                updateChecker = updateChecker
            )
        }
    }
}

/** Re-syncs the alarm and hushes immediately when enabling mid-window. */
private fun onQuietHoursChanged(context: Context, manager: HushManager) {
    HushAlarms.syncQuietHoursAlarm(context)
    val settings = ShhhSettings(context)
    if (settings.quietHoursEnabled && !manager.isQuiet) {
        val schedule = io.github.shhhapp.shhh.core.QuietHours.fromSettings(settings)
        io.github.shhhapp.shhh.core.QuietHours
            .activeWindowEnd(java.time.LocalDateTime.now(), schedule)
            ?.let { end -> manager.hushUntil(end) }
    }
}
