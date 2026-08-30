package io.github.shhhapp.shhh.core

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * Robolectric's [ShadowAudioManager] applies every stream-volume write, so the
 * one situation Android really refuses — moving the ring volume while a zen
 * mode runs without ACCESS_NOTIFICATION_POLICY — can never arise on its own.
 * This shadow reproduces it, throwing the exact exception AudioService throws,
 * for the named streams only.
 */
@Implements(AudioManager::class)
class ShadowRefusingAudioManager : ShadowAudioManager() {

    @Implementation
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        if (streamType in refusedStreams) {
            throw SecurityException("Not allowed to change Do Not Disturb state")
        }
        super.setStreamVolume(streamType, index, flags)
    }

    companion object {
        /** Streams whose writes are refused. Static state — reset per test. */
        var refusedStreams: Set<Int> = emptySet()
    }
}

/**
 * Records every [AudioManager.setRingerMode] call so a test can prove there
 * were none. Shhh must never make one: that is AOSP's *external* ringer path
 * (ZenModeHelper.onSetRingerModeExternal), where a NORMAL or VIBRATE target
 * ends the user's Do Not Disturb and a SILENT target starts one — the exact
 * bug the volume-slider-only design exists to avoid.
 */
@Implements(AudioManager::class)
class ShadowRingerModeRecordingAudioManager : ShadowAudioManager() {

    @Implementation
    override fun setRingerMode(mode: Int) {
        writes += mode
        super.setRingerMode(mode)
    }

    companion object {
        val writes = mutableListOf<Int>()
    }
}

/**
 * Quiet mode is "ring volume 0", read and written on the same slider, and
 * nothing else. Every test here pins one half of that contract: what the
 * controller writes, or what it reports back under each zen mode.
 */
@RunWith(AndroidJUnit4::class)
class QuietModeControllerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    /** Every zen state a phone can be in, including "no zen at all". */
    private val allFilters = intArrayOf(
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        // Shadow statics outlive one test too.
        ShadowRefusingAudioManager.refusedStreams = emptySet()
        ShadowRingerModeRecordingAudioManager.writes.clear()

        settings = ShhhSettings(context)
        controller = QuietModeController(context, settings)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        ring = 3
        media = 5
    }

    private var ring: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    private var media: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)

    private val maxRing: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
    private val maxMedia: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val filter: Int get() = notificationManager.currentInterruptionFilter

    /**
     * Puts the phone in [zenFilter], reproducing what that zen does to the
     * volume sliders every app can read (measured on Android 17 / Pixel with
     * shhh off): plain Do Not Disturb and Bedtime (priority) leave both
     * sliders truthful, "alarms only" hands back a ring volume of 0, and
     * "total silence" zeroes the media volume as well. The real levels come
     * back untouched when the zen ends.
     */
    private fun enterZen(zenFilter: Int) {
        notificationManager.setInterruptionFilter(zenFilter)
        when (zenFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> ring = 0
            NotificationManager.INTERRUPTION_FILTER_NONE -> {
                ring = 0
                media = 0
            }
        }
    }

    private fun revokeDndAccess() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
    }

    private fun describe(zenFilter: Int) = when (zenFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "no zen"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority dnd"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
        else -> "total silence"
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun `goQuiet zeroes the ring and media sliders`() {
        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)
        assertEquals(0, media)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `restoreSound brings both sliders back to where they were`() {
        controller.goQuiet()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(3, ring)
        assertEquals(5, media)
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `toggle flips on the ring volume the phone actually reports`() {
        var result = controller.toggle()
        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)

        result = controller.toggle()
        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(3, ring)
        assertEquals(5, media)
    }

    @Test
    fun `toggle reacts to a hush made with the volume keys`() {
        // The user silenced the ringer themselves; the next tap must restore.
        ring = 0

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertTrue(ring > 0)
    }

    @Test
    fun `isQuiet follows the ring slider and ignores the media slider`() {
        media = 0
        assertFalse("muted media alone is not a hush", controller.isQuiet)

        ring = 0
        assertTrue(controller.isQuiet)
    }

    // ------------------------------------------------- the ringer-mode guard

    @Test
    @Config(shadows = [ShadowRingerModeRecordingAudioManager::class])
    fun `no operation ever calls setRingerMode`() {
        // Self-check: an empty recording only means something once the trap has
        // been shown to fire, so make one write of our own and see it land.
        ShadowRingerModeRecordingAudioManager.writes.clear()
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertEquals(
            "the recording shadow must be active for this test to prove anything",
            listOf(AudioManager.RINGER_MODE_NORMAL),
            ShadowRingerModeRecordingAudioManager.writes
        )
        ShadowRingerModeRecordingAudioManager.writes.clear()

        for (zenFilter in allFilters) {
            enterZen(zenFilter)
            controller.goQuiet()
            controller.restoreSound()
            controller.toggle()
            controller.toggle()
            controller.restoreMediaVolumeOnly()
        }

        assertEquals(
            "setRingerMode is AOSP's external ringer path: it ends the user's " +
                "Do Not Disturb on a NORMAL/VIBRATE target and starts one on SILENT",
            emptyList<Int>(),
            ShadowRingerModeRecordingAudioManager.writes
        )
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun `a hush and un-hush cycle leaves the ringer mode untouched`() {
        // Volume writes take AOSP's internal path, which never re-derives the
        // ringer mode for us — the legacy mode stays exactly as it was.
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        controller.goQuiet()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        controller.restoreSound()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    // ------------------------------------------------ previous ring volume

    @Test
    fun `goQuiet saves the ring volume it found and restoreSound returns to it`() {
        ring = 6

        controller.goQuiet()

        assertEquals(6, settings.previousRingVolume)
        controller.restoreSound()
        assertEquals(6, ring)
    }

    @Test
    fun `hushing an already silent ringer must not save the zero`() {
        ring = 4
        controller.goQuiet()
        controller.restoreSound()

        // The user silences the ringer with the volume keys, then taps shhh:
        // saving that 0 would make every later restore land on silence.
        ring = 0
        controller.goQuiet()

        assertEquals(4, settings.previousRingVolume)
        controller.restoreSound()
        assertEquals(4, ring)
    }

    @Test
    fun `restoreSound falls back to half of max when no ring volume was saved`() {
        assertEquals(ShhhSettings.NO_SAVED_VOLUME, settings.previousRingVolume)
        ring = 0

        controller.restoreSound()

        assertEquals((maxRing / 2).coerceAtLeast(1), ring)
    }

    @Test
    fun `a saved ring volume above the maximum falls back to half of max`() {
        // Restored from a backup taken on a phone with a longer ring slider.
        settings.previousRingVolume = maxRing + 5
        ring = 0

        controller.restoreSound()

        assertEquals((maxRing / 2).coerceAtLeast(1), ring)
    }

    @Test
    fun `previousRingVolume round-trips through a full hush cycle`() {
        for (level in 1..maxRing) {
            ring = level
            controller.goQuiet()
            assertEquals(level, settings.previousRingVolume)
            assertEquals(0, ring)
            controller.restoreSound()
            assertEquals("level $level must come back", level, ring)
        }
    }

    // ---------------------------------------------------------- media volume

    @Test
    fun `goQuiet when media is already muted keeps the earlier saved volume`() {
        media = 5
        controller.goQuiet()
        controller.restoreSound()

        media = 0
        ring = 3
        controller.goQuiet()
        controller.restoreSound()

        assertEquals(5, media)
    }

    @Test
    fun `fixed restore mode restores the configured percentage`() {
        settings.restoreMode = ShhhSettings.RestoreMode.FIXED
        settings.fixedRestorePercent = 80

        controller.goQuiet()
        controller.restoreSound()

        assertEquals((maxMedia * 80 / 100).coerceIn(1, maxMedia), media)
    }

    @Test
    fun `previous restore mode with no saved media volume falls back to half of max`() {
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        media = 0
        ring = 0

        controller.restoreSound()

        assertEquals((maxMedia / 2).coerceAtLeast(1), media)
    }

    @Test
    fun `a saved media volume above the maximum falls back to half of max`() {
        settings.previousMediaVolume = maxMedia + 5
        media = 0
        ring = 0

        controller.restoreSound()

        assertEquals((maxMedia / 2).coerceAtLeast(1), media)
    }

    @Test
    fun `restoreMediaVolumeOnly leaves the ring slider hushed`() {
        media = 6
        controller.goQuiet()

        assertTrue(controller.restoreMediaVolumeOnly())

        assertEquals(6, media)
        assertEquals(0, ring)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `restoreMediaVolumeOnly reports failure when the volume stays at zero`() {
        // A max volume of 0 makes the write land on 0 — the shape of an
        // Android 16+ hardening drop, where the call returns but nothing moves.
        controller.goQuiet()
        shadowOf(audioManager).setStreamMaxVolume(0)

        assertFalse(controller.restoreMediaVolumeOnly())
        assertEquals(0, media)
    }

    // ------------------------------------------------------- permission gates

    @Test
    fun `hasDndAccess mirrors the notification policy grant`() {
        assertTrue(controller.hasDndAccess)

        revokeDndAccess()

        assertFalse(controller.hasDndAccess)
    }

    @Test
    fun `isDndActive is true for every zen filter and false otherwise`() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertFalse(controller.isDndActive)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        assertFalse("a filter the system cannot report must not read as dnd", controller.isDndActive)

        for (zenFilter in intArrayOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE
        )) {
            notificationManager.setInterruptionFilter(zenFilter)
            assertTrue(describe(zenFilter), controller.isDndActive)
        }
    }

    @Test
    fun `canChangeSound is false only when a zen runs without dnd access`() {
        assertTrue("granted, no zen", controller.canChangeSound)

        enterZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertTrue("granted, zen running", controller.canChangeSound)

        revokeDndAccess()
        assertFalse("revoked, zen running", controller.canChangeSound)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertTrue("revoked, no zen — shhh needs no permission here", controller.canChangeSound)
    }

    @Test
    fun `without dnd access and no zen running a hush still goes through`() {
        // The old contract refused up front. Volume writes with no zen active
        // need no permission at all (verified on Android 17 / Pixel).
        revokeDndAccess()

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)
        assertEquals(0, media)
    }

    // ------------------------------------------------------ isQuiet under zen

    @Test
    fun `a zen mode alone never reads as a hush`() {
        for (zenFilter in allFilters) {
            setUpFreshPhone()
            enterZen(zenFilter)

            assertFalse(
                "${describe(zenFilter)} with shhh off must read as sound-on",
                controller.isQuiet
            )
        }
    }

    @Test
    fun `a hush engaged before a zen stays reported through it`() {
        for (zenFilter in allFilters) {
            setUpFreshPhone()
            controller.goQuiet()

            enterZen(zenFilter)

            assertTrue("${describe(zenFilter)} must not lose the hush", controller.isQuiet)
        }
    }

    @Test
    fun `priority dnd leaves the ring volume truthful so no fallback is needed`() {
        // Plain Do Not Disturb and Bedtime mask only the legacy ringer *mode*,
        // which shhh no longer reads: the slider still reports its real value.
        enterZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertEquals(3, ring)

        // A stale remembered state must not win here — the slider does.
        settings.lastKnownQuiet = true
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `under alarms only and total silence the remembered state decides`() {
        for (zenFilter in intArrayOf(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE
        )) {
            setUpFreshPhone()
            enterZen(zenFilter)
            // The platform zeroed the readable ring volume; taken at face value
            // that would report a hush the user never asked for.
            assertEquals(0, ring)

            settings.lastKnownQuiet = false
            assertFalse(describe(zenFilter), controller.isQuiet)

            settings.lastKnownQuiet = true
            assertTrue(describe(zenFilter), controller.isQuiet)
        }
    }

    @Test
    fun `an unknown interruption filter trusts the ring slider`() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        ring = 0

        assertTrue(controller.isQuiet)
    }

    @Test
    fun `reading isQuiet keeps the remembered state in sync outside the masking zens`() {
        for (zenFilter in intArrayOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        )) {
            setUpFreshPhone()
            notificationManager.setInterruptionFilter(zenFilter)

            settings.lastKnownQuiet = true
            assertFalse(describe(zenFilter), controller.isQuiet)
            assertFalse(
                "a readable loud slider must correct the memory",
                settings.lastKnownQuiet
            )

            ring = 0
            assertTrue(describe(zenFilter), controller.isQuiet)
            assertTrue("a readable silent slider must record the hush", settings.lastKnownQuiet)
        }
    }

    @Test
    fun `a read under alarms only never overwrites the remembered state`() {
        settings.lastKnownQuiet = true
        enterZen(NotificationManager.INTERRUPTION_FILTER_ALARMS)

        assertTrue(controller.isQuiet)

        assertTrue(settings.lastKnownQuiet)
    }

    // ------------------------------------------------ hush / restore under zen

    @Test
    fun `hushing during priority dnd really zeroes the ring volume`() {
        // The old contract skipped the ringer entirely under DND, leaving the
        // phone audible; the volume path is safe to take, so it is taken.
        enterZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        ring = 4
        media = 6

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)
        assertEquals(0, media)
        assertEquals(4, settings.previousRingVolume)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `hushing works under every zen filter`() {
        for (zenFilter in allFilters) {
            setUpFreshPhone()
            enterZen(zenFilter)

            val result = controller.goQuiet()

            assertEquals(describe(zenFilter), QuietModeController.Result.Success(true), result)
            assertEquals(describe(zenFilter), 0, ring)
            assertTrue(describe(zenFilter), settings.lastKnownQuiet)
            assertTrue(describe(zenFilter), controller.isQuiet)
        }
    }

    @Test
    fun `restoring works under every zen filter and clears the remembered hush`() {
        for (zenFilter in allFilters) {
            setUpFreshPhone()
            controller.goQuiet()
            enterZen(zenFilter)

            val result = controller.restoreSound()

            assertEquals(describe(zenFilter), QuietModeController.Result.Success(false), result)
            assertFalse(describe(zenFilter), settings.lastKnownQuiet)
            assertFalse(describe(zenFilter), controller.isQuiet)
        }
    }

    @Test
    fun `no operation changes the interruption filter`() {
        // The user's Do Not Disturb is theirs: shhh moves sliders under it and
        // leaves the zen exactly as it found it. (The mechanism that could
        // break this is setRingerMode; the guard for that is the trap shadow
        // in `no operation ever calls setRingerMode`.)
        for (zenFilter in allFilters) {
            setUpFreshPhone()
            enterZen(zenFilter)
            val before = filter

            controller.goQuiet()
            assertEquals("${describe(zenFilter)} after goQuiet", before, filter)

            controller.restoreSound()
            assertEquals("${describe(zenFilter)} after restoreSound", before, filter)

            controller.toggle()
            assertEquals("${describe(zenFilter)} after toggle", before, filter)
        }
    }

    @Test
    fun `toggling during alarms only hushes instead of restoring sound`() {
        // The platform's zeroed ring volume used to read as "shhh is on", so a
        // tap here restored sound the user never asked to come back.
        enterZen(NotificationManager.INTERRUPTION_FILTER_ALARMS)

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `a hush during total silence survives the zen ending`() {
        // Total silence hands the real sliders back when it ends, so the
        // restore has to hit the level saved before the hush, not the 0 the
        // platform was reporting.
        ring = 4
        media = 6
        controller.goQuiet()
        enterZen(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertTrue(controller.isQuiet)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        controller.restoreSound()

        assertEquals(4, ring)
        assertEquals(6, media)
        assertFalse(controller.isQuiet)
    }

    // --------------------------------------------------- refused volume writes

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused ring write reports NeedsDndAccess and changes nothing`() {
        enterZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        revokeDndAccess()
        ring = 3
        media = 5
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertEquals("the sliders must be exactly where they were", 3, ring)
        assertEquals(5, media)
        assertFalse("a refused hush must not be remembered as one", settings.lastKnownQuiet)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused ring write during a restore reports NeedsDndAccess`() {
        controller.goQuiet()
        enterZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        revokeDndAccess()
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertEquals(0, ring)
        assertEquals(0, media)
        assertTrue("the phone is still hushed, so the memory must say so", settings.lastKnownQuiet)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a hush whose media write alone is refused still counts as applied`() {
        // The ring volume is what isQuiet reads, so the transition really
        // happened. Reporting failure would tell HushManager nothing changed,
        // stranding the timer and the tile/widget on the old state.
        ShadowRefusingAudioManager.refusedStreams = setOf(AudioManager.STREAM_MUSIC)

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)
        assertEquals("the media volume could not move", 5, media)
        assertTrue(settings.lastKnownQuiet)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a restore whose media write alone is refused still counts as applied`() {
        controller.goQuiet()
        ShadowRefusingAudioManager.refusedStreams = setOf(AudioManager.STREAM_MUSIC)

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(3, ring)
        assertEquals(0, media)
        assertFalse(settings.lastKnownQuiet)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `restoreMediaVolumeOnly reports failure when the write is refused`() {
        controller.goQuiet()
        ShadowRefusingAudioManager.refusedStreams = setOf(AudioManager.STREAM_MUSIC)

        assertFalse(controller.restoreMediaVolumeOnly())
        assertEquals("the ringer must stay hushed", 0, ring)
    }

    // ------------------------------------------------------- the settle wait

    @Test
    fun `the settle wait is bounded when the ring volume never reaches the target`() {
        // Max volume 0 pins every read at 0, so a restore can never see its
        // target. The wait must give up instead of hanging the caller.
        controller.goQuiet()
        shadowOf(audioManager).setStreamMaxVolume(0)

        val before = SystemClock.uptimeMillis()
        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertTrue(
            "the wait must be bounded, not open-ended",
            SystemClock.uptimeMillis() - before <= 1_000
        )
    }

    @Test
    fun `the settle wait is skipped under a ring-masking zen`() {
        // "Alarms only" pins the readable ring volume at 0, so a restore
        // target can never appear there; waiting for it would burn the whole
        // timeout for nothing, and isQuiet uses the remembered state anyway.
        // A max volume of 0 gives the same never-matching read here.
        controller.goQuiet()
        shadowOf(audioManager).setStreamMaxVolume(0)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val unmasked = elapsedMillisOf { controller.restoreSound() }

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val masked = elapsedMillisOf { controller.restoreSound() }

        assertTrue(
            "with a readable slider the wait runs its full course",
            unmasked >= 500
        )
        assertEquals("under a masking zen no time may be spent waiting", 0L, masked)
    }

    /** Simulated milliseconds burned by [block]; Robolectric's clock only
     *  advances on SystemClock.sleep, which is what the settle wait uses. */
    private fun elapsedMillisOf(block: () -> Unit): Long {
        val before = SystemClock.uptimeMillis()
        block()
        return SystemClock.uptimeMillis() - before
    }

    /**
     * Robolectric keeps SharedPreferences and the audio state alive for the
     * whole test method, so loops over the zen filters re-arm the phone
     * between iterations instead of leaking one filter's state into the next.
     */
    private fun setUpFreshPhone() {
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ring = 3
        media = 5
    }
}
