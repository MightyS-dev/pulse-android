package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class AndroidPolicyTest {
    @Test fun twoStartedActivitiesDoNotFlushUntilLastStops() {
        var backgrounds = 0
        val counter = ActivityCounter { backgrounds++ }
        counter.started(); counter.started(); counter.stopped(false)
        assertEquals(0, backgrounds); counter.stopped(false); assertEquals(1, backgrounds)
    }
    @Test fun stopOfActivityStartedBeforeRegistrationStillFlushes() {
        var backgrounds = 0
        val counter = ActivityCounter { backgrounds++ }
        counter.stopped(true); assertEquals(0, backgrounds)
        counter.stopped(false); assertEquals(1, backgrounds)
    }
    @Test fun configurationChangeDoesNotCreateFalseBackground() {
        var backgrounds = 0
        val counter = ActivityCounter { backgrounds++ }
        counter.started(); counter.stopped(true); counter.started()
        assertEquals(0, backgrounds); counter.stopped(false); assertEquals(1, backgrounds)
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
