package dev.mightys.pulse

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test

class HomeActivity : Activity()
class SettingsActivity : Activity()

class ScreenViewCallbacksTest {
    @Test fun resumedActivitySendsItsSimpleClassName() {
        val seen = mutableListOf<String>()
        val callbacks = ScreenViewCallbacks({ seen.add(it) }, fragmentsAvailable = false)
        callbacks.onActivityResumed(HomeActivity()); callbacks.onActivityResumed(SettingsActivity())
        assertEquals(listOf("HomeActivity", "SettingsActivity"), seen)
    }

    @Test fun anonymousActivityWithoutNameIsSkipped() {
        val seen = mutableListOf<String>()
        ScreenViewCallbacks({ seen.add(it) }, fragmentsAvailable = false).onActivityResumed(object : Activity() {})
        assertTrue(seen.isEmpty())
    }

    @Test fun fragmentWatchingOnlyHappensWhenFragmentsAreAvailable() {
        var watched = 0
        ScreenViewCallbacks({}, fragmentsAvailable = false, watchFragments = { _, _ -> watched++ }).onActivityCreated(HomeActivity(), null)
        assertEquals(0, watched)
        ScreenViewCallbacks({}, fragmentsAvailable = true, watchFragments = { _, _ -> watched++ }).onActivityCreated(HomeActivity(), null)
        assertEquals(1, watched)
    }

    @Test fun failingFragmentHookNeverThrowsIntoTheApp() {
        ScreenViewCallbacks({}, fragmentsAvailable = true, watchFragments = { _, _ -> throw NoClassDefFoundError("x") }).onActivityCreated(HomeActivity(), null)
    }

    @Test fun withoutFragmentOnTheClasspathTheCallbacksStillWork() {
        // androidx.fragment is compile-only, so it is absent here exactly as in an app that does not use it.
        assertFalse(FragmentScreens.available())
        val seen = mutableListOf<String>()
        val callbacks = ScreenViewCallbacks({ seen.add(it) })
        callbacks.onActivityCreated(HomeActivity(), null); callbacks.onActivityResumed(HomeActivity())
        assertEquals(listOf("HomeActivity"), seen)
    }

    @Test fun javaFacadeAcceptsNullAndBeforeInitialization() {
        Pulse.screen(null); Pulse.screen("Manual")
    }
}
