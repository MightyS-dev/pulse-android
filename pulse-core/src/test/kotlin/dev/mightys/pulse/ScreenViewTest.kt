package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ScreenViewTest {
    private fun views(h: Harness) = h.allEvents.filter { it["type"] == "pulse.screen_view" }
    private fun props(e: Map<*, *>) = e["props"] as Map<*, *>

    @Test fun screenSendsTheSignalWithNameAndNoPreviousForTheFirst() {
        val h = Harness(); h.client.screen("HomeActivity"); h.flush()
        val e = views(h).single()
        assertEquals("HomeActivity", props(e)["screen"]); assertNull(props(e)["previousScreen"])
    }

    @Test fun nextScreenCarriesPreviousScreen() {
        val h = Harness(); h.client.screen("A"); h.client.screen("B"); h.client.screen("A"); h.flush()
        val p = views(h).map(::props)
        assertEquals(listOf("A", "B", "A"), p.map { it["screen"] })
        assertEquals(listOf(null, "A", "B"), p.map { it["previousScreen"] })
    }

    @Test fun sameScreenAgainWithoutAnotherInBetweenSendsNothing() {
        val h = Harness(); h.client.screen("A"); h.client.screen("A"); h.client.screen(" A "); h.flush()
        assertEquals(1, views(h).size)
    }

    @Test fun namesAreSanitised() {
        val h = Harness()
        listOf("/orders/123/items?x=1#top", "/u/123e4567-e89b-12d3-a456-426614174000", "  ", "", null).forEach(h.client::screen)
        h.client.screen("x".repeat(300)); h.flush()
        val names = views(h).map { props(it)["screen"] as String }
        assertEquals("/orders/:id/items", names[0]); assertEquals("/u/:id", names[1]); assertEquals(3, names.size)
        assertEquals(128, names[2].length)
    }

    @Test fun optOutSuppressesScreenViewsAndResetsDedupe() {
        val h = Harness(); h.client.screen("A"); h.client.optOut = true; h.settle(); h.client.screen("B"); h.flush()
        assertEquals(0, views(h).size)
        h.client.optOut = false; h.settle(); h.client.screen("A"); h.flush()
        val e = views(h).single(); assertNull(props(e)["previousScreen"])
    }

    @Test fun screenBeforeInitializeIsKept() {
        val h = Harness(initialize = false); h.client.screen("Early"); h.initialize(); h.flush()
        assertEquals("Early", props(views(h).single())["screen"])
    }

    @Test fun automaticAndManualScreensBeforeInitializeKeepCallOrderAndChain() {
        val h = Harness(initialize = false)
        h.client.automaticScreen("Main"); h.client.screen("home"); h.client.screen("detail"); h.initialize(); h.flush()
        val p = views(h).map(::props)
        assertEquals(listOf("Main", "home", "detail"), p.map { it["screen"] })
        assertEquals(listOf(null, "Main", "home"), p.map { it["previousScreen"] })
    }

    @Test fun automaticScreenDroppedByTheOptionDoesNotBreakTheChain() {
        val h = Harness(PulseOptions(automaticScreenViews = false), initialize = false)
        h.client.automaticScreen("Main"); h.client.screen("home"); h.client.screen("detail"); h.initialize(); h.flush()
        val p = views(h).map(::props)
        assertEquals(listOf("home", "detail"), p.map { it["screen"] }); assertEquals(listOf(null, "home"), p.map { it["previousScreen"] })
    }

    @Test fun optionDefaultsToOn() {
        assertTrue(PulseOptions().automaticScreenViews); assertFalse(PulseOptions(automaticScreenViews = false).automaticScreenViews)
    }
}
