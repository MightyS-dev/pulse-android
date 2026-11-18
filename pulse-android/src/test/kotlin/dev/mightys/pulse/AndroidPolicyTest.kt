package dev.mightys.pulse

import android.app.Activity
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test

class AndroidPolicyTest {
    @Test fun activityStartsStillRefreshMetadataWithoutSendingBackground() {
        val h = AutoEventsAndroidTest.LifecycleRun(); var refreshes = 0
        val hook = PulseLifecycle(h.client) { refreshes++ }
        hook.onActivityStarted(Activity()); hook.onActivityStarted(Activity()); hook.onActivityStopped(Activity())
        h.queue.drain()
        assertEquals(2, refreshes); assertEquals(0, h.count("pulse.app_backgrounded"))
    }
    @Test fun processPauseDoesNotFlushUntilOnStop() {
        val h = AutoEventsAndroidTest.LifecycleRun()
        h.event(Lifecycle.Event.ON_RESUME); h.now += 1000; h.event(Lifecycle.Event.ON_PAUSE)
        assertEquals(0, h.count("pulse.app_backgrounded"))
        h.event(Lifecycle.Event.ON_STOP); assertEquals(1, h.count("pulse.app_backgrounded"))
    }
    @Test fun observingAnAlreadyStoppedProcessDoesNotFabricateBackground() {
        val h = AutoEventsAndroidTest.LifecycleRun()
        h.owner.lifecycle.removeObserver(h.hook)
        h.event(Lifecycle.Event.ON_START); h.event(Lifecycle.Event.ON_STOP)
        h.hook.observe(h.owner.lifecycle); h.queue.drain()
        assertEquals(0, h.count("pulse.app_backgrounded"))
        h.event(Lifecycle.Event.ON_START); h.now += 1000; h.event(Lifecycle.Event.ON_STOP)
        assertEquals(1, h.count("pulse.app_backgrounded"))
    }
    @Test fun osVersionIsNumericAndNeverContainsBuildDescriptions() {
        assertEquals("16", AndroidMetadata.numericRelease("16"))
        assertEquals("15.0.1", AndroidMetadata.numericRelease("15.0.1 beta build abc"))
        assertEquals("8.1", AndroidMetadata.numericRelease("8.1.0"))
        assertEquals("14.2", AndroidMetadata.numericRelease("14.2.0.1"))
        assertEquals("", AndroidMetadata.numericRelease("unknown"))
        assertEquals("", AndroidMetadata.numericRelease(null))
    }
    @Test fun signingDigestMatchesStandardVectorAndHandlesMissingCertificate() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AndroidMetadata.certificateDigest("abc".toByteArray()))
        assertEquals("", AndroidMetadata.certificateDigest(null))
    }
    @Test fun kotlinFacadeSupportsNullsBeforeInitializationWithoutThrowing() {
        Pulse.initialize(null, null)
        Pulse.track(null); Pulse.track("nan", Double.NaN); Pulse.setUser(null)
        Pulse.flush(); assertFalse(Pulse.flush(0))
        assertTrue(Pulse.optOut)
    }
    @Test fun appVersionFallsBackToBuildNumberAndNeverStaysEmpty() {
        assertEquals("1.2.3", AndroidMetadata.appVersion("1.2.3", "45"))
        assertEquals("45", AndroidMetadata.appVersion(null, "45"))
        assertEquals("45", AndroidMetadata.appVersion("", "45"))
        assertEquals("0", AndroidMetadata.appVersion(null, null))
    }
    @Test fun deviceModelAndLocaleAreNeverEmpty() {
        assertEquals("Pixel 9", AndroidMetadata.deviceModel("Pixel 9"))
        assertEquals("Android", AndroidMetadata.deviceModel(null))
        assertEquals("Android", AndroidMetadata.deviceModel(" "))
        assertEquals("und", AndroidMetadata.localeTag(java.util.Locale.ROOT))
        assertEquals("sr-RS", AndroidMetadata.localeTag(java.util.Locale.forLanguageTag("sr-RS")))
    }
}
