package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import dev.mightys.pulse.ControlState.Kind

class ControlUiPolicyTest {
    @Test fun everyKindHasOneSurface() {
        val expected = mapOf(
            Kind.Normal to ControlUiPolicy.Surface.None, Kind.UpdateRequired to ControlUiPolicy.Surface.FullScreen,
            Kind.VersionBlocked to ControlUiPolicy.Surface.FullScreen, Kind.Maintenance to ControlUiPolicy.Surface.FullScreen,
            Kind.UpdateSuggested to ControlUiPolicy.Surface.Dialog, Kind.Announcement to ControlUiPolicy.Surface.Card,
            Kind.WhatsNew to ControlUiPolicy.Surface.Card, Kind.MaintenanceUpcoming to ControlUiPolicy.Surface.Bar,
        )
        assertEquals(Kind.values().toSet(), expected.keys)
        for ((kind, surface) in expected) assertEquals(kind.name, surface, ControlUiPolicy.surface(kind))
    }

    @Test fun onlyTheTransparentActivityKindsNeedAnActivity() {
        assertFalse(ControlUiPolicy.needsActivity(Kind.Normal)); assertFalse(ControlUiPolicy.needsActivity(Kind.MaintenanceUpcoming))
        for (kind in listOf(Kind.UpdateRequired, Kind.VersionBlocked, Kind.Maintenance, Kind.UpdateSuggested, Kind.Announcement, Kind.WhatsNew))
            assertTrue(kind.name, ControlUiPolicy.needsActivity(kind))
    }

    @Test fun backSendsLockingScreensToTheBackgroundAndDismissesTheRest() {
        for (kind in listOf(Kind.UpdateRequired, Kind.VersionBlocked, Kind.Maintenance)) assertEquals(ControlUiPolicy.Back.MoveToBackground, ControlUiPolicy.back(kind))
        for (kind in listOf(Kind.UpdateSuggested, Kind.Announcement, Kind.WhatsNew)) assertEquals(ControlUiPolicy.Back.Dismiss, ControlUiPolicy.back(kind))
    }

    @Test fun storeLinkFromTheRulesWinsElseThePlayAppThenThePage() {
        val page = StoreLinks.playPage("com.example.app")
        assertEquals(listOf("https://play.google.com/get"), StoreLinks.candidates("https://play.google.com/get", "com.example.app"))
        assertEquals(listOf("market://details?id=com.example.app", page), StoreLinks.candidates(null, "com.example.app"))
        assertEquals("the built-in page link still tries the Play app first", listOf("market://details?id=com.example.app", page), StoreLinks.candidates(page, "com.example.app"))
        assertEquals(emptyList<String>(), StoreLinks.candidates(null, null)); assertEquals(emptyList<String>(), StoreLinks.candidates("  ", null))
        assertEquals(listOf("https://play.google.com/get"), StoreLinks.candidates("https://play.google.com/get", null))
    }

    @Test fun playFlowOnlyForPlayInstallsWithTheLibrary() {
        val plan = PlayUpdatePlan
        assertEquals(PlayUpdatePlan.Plan.Immediate, plan.plan(Kind.UpdateRequired, true, true))
        assertEquals(PlayUpdatePlan.Plan.Immediate, plan.plan(Kind.VersionBlocked, true, true))
        assertEquals(PlayUpdatePlan.Plan.Flexible, plan.plan(Kind.UpdateSuggested, true, true))
        assertEquals(PlayUpdatePlan.Plan.Page, plan.plan(Kind.UpdateRequired, false, true))
        assertEquals(PlayUpdatePlan.Plan.Page, plan.plan(Kind.UpdateRequired, true, false))
        assertEquals(PlayUpdatePlan.Plan.Page, plan.plan(Kind.Announcement, true, true))
    }

    @Test fun theUpdateKindsCountAsUpdatePresses() {
        assertTrue(ControlUiPolicy.isUpdate(Kind.UpdateRequired)); assertTrue(ControlUiPolicy.isUpdate(Kind.UpdateSuggested)); assertTrue(ControlUiPolicy.isUpdate(Kind.VersionBlocked))
        assertFalse(ControlUiPolicy.isUpdate(Kind.Maintenance)); assertFalse(ControlUiPolicy.isUpdate(Kind.Announcement))
    }

    @Test fun playLibraryIsAbsentInThisTestRun() { assertFalse(PlayLibrary.available()) }
}
