package dev.mightys.pulse

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test

/** Android heatmap logic that needs no device: tap detection, hit test rules, hooks and picture sizing. */
class HeatmapAndroidTest {
    private val down = TapDetector.ACTION_DOWN; private val up = TapDetector.ACTION_UP; private val move = TapDetector.ACTION_MOVE
    private val cancel = TapDetector.ACTION_CANCEL; private val pointerDown = TapDetector.ACTION_POINTER_DOWN

    // ---- tap detection ----

    @Test fun constantsMatchMotionEvent() {
        assertEquals(android.view.MotionEvent.ACTION_DOWN, down); assertEquals(android.view.MotionEvent.ACTION_UP, up)
        assertEquals(android.view.MotionEvent.ACTION_MOVE, move); assertEquals(android.view.MotionEvent.ACTION_CANCEL, cancel)
        assertEquals(android.view.MotionEvent.ACTION_POINTER_DOWN, pointerDown)
    }

    @Test fun aSinglePointerTapWithinSlopIsATap() {
        val d = TapDetector(8f)
        assertFalse(d.onEvent(down, 1, 100f, 100f)); assertFalse(d.onEvent(move, 1, 104f, 103f))
        assertTrue(d.onEvent(up, 1, 105f, 104f))
    }

    @Test fun dragBeyondSlopIsNotATapEvenIfTheFingerComesBack() {
        val d = TapDetector(8f)
        d.onEvent(down, 1, 100f, 100f); d.onEvent(move, 1, 160f, 100f); d.onEvent(move, 1, 101f, 100f)
        assertFalse(d.onEvent(up, 1, 101f, 100f))
    }

    @Test fun upFarFromDownIsNotATap() {
        val d = TapDetector(8f)
        d.onEvent(down, 1, 0f, 0f)
        assertFalse(d.onEvent(up, 1, 50f, 0f))
    }

    @Test fun multiTouchAndCancelAndStrayUpsAreNotTaps() {
        val d = TapDetector(8f)
        d.onEvent(down, 1, 10f, 10f); d.onEvent(pointerDown, 2, 50f, 50f)
        assertFalse(d.onEvent(up, 1, 10f, 10f))
        d.onEvent(down, 2, 10f, 10f); assertFalse(d.onEvent(up, 1, 10f, 10f))
        d.onEvent(down, 1, 10f, 10f); d.onEvent(cancel, 1, 10f, 10f); assertFalse(d.onEvent(up, 1, 10f, 10f))
        assertFalse(d.onEvent(up, 1, 10f, 10f))
        d.onEvent(down, 1, 10f, 10f); assertTrue(d.onEvent(up, 1, 10f, 10f)) // a clean tap still works afterwards
    }

    // ---- the touch hook never changes the event ----

    @Test fun handleReturnsTheOriginalAnswerAndAlwaysDispatchesOnce() {
        listOf(true, false).forEach { answer ->
            var dispatched = 0; val reported = mutableListOf<Pair<Float, Float>>()
            val t = HeatmapTouch(8f, { true }) { x, y -> reported.add(x to y) }
            assertEquals(answer, t.handle(down, 1, 5f, 6f) { dispatched++; answer })
            assertEquals(answer, t.handle(up, 1, 5f, 6f) { dispatched++; answer })
            assertEquals(2, dispatched); assertEquals(listOf(5f to 6f), reported)
        }
    }

    @Test fun inactiveMeansNoReportButStillDispatches() {
        var reported = 0; var dispatched = 0
        val t = HeatmapTouch(8f, { false }) { _, _ -> reported++ }
        t.handle(down, 1, 5f, 6f) { dispatched++; false }; t.handle(up, 1, 5f, 6f) { dispatched++; false }
        assertEquals(0, reported); assertEquals(2, dispatched)
    }

    @Test fun failuresInsideTheHookNeverStopTheDispatch() {
        var dispatched = 0
        val t = HeatmapTouch(8f, { throw IllegalStateException("active") }) { _, _ -> throw IllegalStateException("report") }
        t.handle(down, 1, 1f, 1f) { dispatched++; true }
        assertTrue(t.handle(up, 1, 1f, 1f) { dispatched++; true })
        val u = HeatmapTouch(8f, { true }) { _, _ -> throw IllegalStateException("report") }
        u.handle(down, 1, 1f, 1f) { dispatched++; true }
        assertTrue(u.handle(up, 1, 1f, 1f) { dispatched++; true })
        assertEquals(4, dispatched)
    }

    @Test fun anExceptionFromTheAppDispatchStillPropagates() {
        val t = HeatmapTouch(8f, { true }) { _, _ -> }
        try { t.handle(down, 1, 1f, 1f) { throw IllegalArgumentException("app") }; fail() } catch (e: IllegalArgumentException) { assertEquals("app", e.message) }
    }

    @Test fun theCallbackWrapperDelegatesEveryWindowCallbackMethod() {
        val wrapperMethods = HeatmapWindowCallback::class.java.declaredMethods.map { it.name + it.parameterTypes.joinToString { p -> p.name } }.toSet()
        android.view.Window.Callback::class.java.methods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
            assertTrue(it.toString(), (it.name + it.parameterTypes.joinToString { p -> p.name }) in wrapperMethods)
        }
    }

    // ---- hit test ----

    private class Node(val name: String, val input: Boolean = false, val ignored: Boolean = false, vararg kids: Node) {
        val children = kids.toList(); var shown = true; var left = 0f; var top = 0f; var right = 1000f; var bottom = 1000f
        fun at(l: Float, t: Float, r: Float, b: Float): Node { left = l; top = t; right = r; bottom = b; return this }
    }
    private val tree = object : HitTree<Node> {
        override fun children(node: Node) = node.children
        override fun enter(parent: Node, child: Node, x: Float, y: Float): FloatArray? =
            if (child.shown && x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) floatArrayOf(x, y) else null
        override fun isInput(node: Node) = node.input
        override fun isIgnored(node: Node) = node.ignored
    }
    private fun probe(root: Node, x: Float = 10f, y: Float = 10f) = HitTest.probe(root, x, y, tree)

    @Test fun plainViewsAreNotInputsOrIgnored() {
        val r = probe(Node("root", false, false, Node("a", false, false, Node("b"))))
        assertFalse(r.input); assertFalse(r.ignored)
    }

    @Test fun anInputUnderThePointIsFoundAnywhereInTheTree() {
        val edit = Node("edit", input = true).at(0f, 0f, 100f, 50f)
        val r = probe(Node("root", false, false, Node("form", false, false, Node("row", false, false, edit))))
        assertTrue(r.input)
    }

    @Test fun anInputElsewhereOnTheScreenDoesNotMatter() {
        val edit = Node("edit", input = true).at(500f, 500f, 600f, 600f)
        assertFalse(probe(Node("root", false, false, edit)).input)
    }

    @Test fun anInputBehindATransparentOverlayStillCounts() {
        val edit = Node("edit", input = true).at(0f, 0f, 100f, 100f)
        val overlay = Node("overlay").at(0f, 0f, 100f, 100f)
        assertTrue(probe(Node("root", false, false, edit, overlay)).input)
    }

    @Test fun hiddenInputsDoNotCount() {
        val edit = Node("edit", input = true).apply { shown = false }
        assertFalse(probe(Node("root", false, false, edit)).input)
    }

    @Test fun everythingInsideAnIgnoredViewIsIgnoredAndTheViewItself() {
        val inner = Node("inner").at(0f, 0f, 50f, 50f)
        val marked = Node("marked", ignored = true, kids = arrayOf(inner)).at(0f, 0f, 200f, 200f)
        assertTrue(probe(Node("root", false, false, marked)).ignored)
        assertTrue(probe(Node("root", false, false, marked), 300f, 300f).ignored.not())
    }

    @Test fun anInputInsideAnIgnoredViewReportsAtTheFirstMark() {
        val r = probe(Node("root", false, false, Node("m", ignored = true, kids = arrayOf(Node("edit", input = true)))))
        assertTrue(r.ignored)
    }

    @Test fun aRootThatIsItselfAnInputOrIgnoredIsCaught() {
        assertTrue(probe(Node("root", input = true)).input); assertTrue(probe(Node("root", ignored = true)).ignored)
    }

    @Test fun absurdDepthIsTreatedAsUnknownAndNotCaptured() {
        var node = Node("leaf")
        repeat(150) { node = Node("n$it", false, false, node) }
        assertTrue(probe(node).input)
    }

    // ---- rules ----

    @Test fun passwordInputTypes() {
        assertTrue(HeatmapRules.isPasswordInputType(0x81)); assertTrue(HeatmapRules.isPasswordInputType(0x91))
        assertTrue(HeatmapRules.isPasswordInputType(0xe1)); assertTrue(HeatmapRules.isPasswordInputType(0x12))
        assertFalse(HeatmapRules.isPasswordInputType(0)); assertFalse(HeatmapRules.isPasswordInputType(1)); assertFalse(HeatmapRules.isPasswordInputType(2))
        assertFalse(HeatmapRules.isPasswordInputType(0x21)) // email variation
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, 0x80); assertEquals(android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0x10)
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0xe0); assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0x90)
    }

    @Test fun keyboardHeightHeuristic() {
        assertTrue(HeatmapRules.keyboardByHeight(2400, 1400)); assertFalse(HeatmapRules.keyboardByHeight(2400, 2300))
        assertFalse(HeatmapRules.keyboardByHeight(2400, 2400)); assertFalse(HeatmapRules.keyboardByHeight(0, 0))
    }

    @Test fun pictureSizeKeepsTheAspectAndLongestSideAt2048() {
        assertEquals(922 to 2048, ShotEncoding.scaled(1080, 2400))
        assertEquals(2048 to 922, ShotEncoding.scaled(2400, 1080))
        assertEquals(1000 to 2000, ShotEncoding.scaled(1000, 2000)); assertEquals(1 to 2048, ShotEncoding.scaled(1, 99999))
    }

    // ---- hooks ----

    private class Rig(var active: Boolean = true, debug: Boolean = true) {
        val installed = mutableListOf<Activity>(); val shots = mutableListOf<Activity>(); val posted = mutableListOf<Pair<Long, Runnable>>()
        val hooks = HeatmapHooks({ active }, { installed.add(it) }, { }, { shots.add(it) }, { delay, action -> posted.add(delay to action) })
            .also { it.debuggable = debug }
        fun fire() { val all = posted.toList(); posted.clear(); all.forEach { it.second.run() } }
    }
    private class Home : Activity()

    @Test fun theControlActivityIsNeverInstalledNorPhotographedAndTheCurrentScreenStays() {
        val r = Rig(); val app = Home(); r.hooks.callbacks.onActivityResumed(app); r.fire()
        r.shots.clear(); r.installed.clear()
        r.hooks.callbacks.onActivityPaused(app)
        r.hooks.callbacks.onActivityResumed(PulseControlActivity()); r.hooks.initializedFrom(PulseControlActivity())
        r.hooks.screenChanged { it.run() }; r.fire()
        assertTrue(r.installed.isEmpty()); assertTrue(r.shots.isEmpty())
        r.hooks.callbacks.onActivityResumed(app); r.fire()
        assertEquals(listOf<Activity>(app), r.shots)
    }

    @Test fun noPictureWhileAnAppControlScreenIsUp() {
        var up = false; val shots = mutableListOf<Activity>(); val posted = mutableListOf<Runnable>()
        val hooks = HeatmapHooks({ true }, { }, { }, { shots.add(it) }, { _, a -> posted.add(a) }, { up }).also { it.debuggable = true }
        val a = Home(); hooks.callbacks.onActivityResumed(a); up = true
        posted.forEach { it.run() }
        assertTrue(shots.isEmpty())
        up = false; posted.clear(); hooks.callbacks.onActivityResumed(a); posted.forEach { it.run() }
        assertEquals(listOf<Activity>(a), shots)
    }

    @Test fun nothingIsInstalledWhileOff() {
        val r = Rig(active = false); r.hooks.callbacks.onActivityResumed(Home())
        assertTrue(r.installed.isEmpty()); assertTrue(r.posted.isEmpty())
    }

    @Test fun installsOnResumeWhenOnAndAsksForAPictureAboutOneSecondLater() {
        val r = Rig(); val a = Home(); r.hooks.callbacks.onActivityResumed(a)
        assertEquals(listOf<Activity>(a), r.installed); assertEquals(1000L, r.posted.single().first)
        assertTrue(r.shots.isEmpty()); r.fire(); assertEquals(listOf<Activity>(a), r.shots)
    }

    @Test fun releaseAppsGetNoPictureRequestButStillInstall() {
        val r = Rig(debug = false); r.hooks.callbacks.onActivityResumed(Home())
        assertEquals(1, r.installed.size); assertTrue(r.posted.isEmpty())
    }

    @Test fun aNewerRequestReplacesAnOlderOne() {
        val r = Rig(); val a = Home(); r.hooks.callbacks.onActivityResumed(a)
        r.hooks.screenChanged { it.run() }
        r.fire()
        assertEquals(1, r.shots.size)
    }

    @Test fun noPictureWhenTheActivityLeftOrCaptureWentOff() {
        val r = Rig(); val a = Home(); r.hooks.callbacks.onActivityResumed(a); r.hooks.callbacks.onActivityPaused(a); r.fire()
        assertTrue(r.shots.isEmpty())
        val s = Rig(); val b = Home(); s.hooks.callbacks.onActivityResumed(b); s.active = false; s.fire()
        assertTrue(s.shots.isEmpty())
    }

    @Test fun screenChangeWithoutAResumedActivityDoesNothing() {
        val r = Rig(); r.hooks.screenChanged { it.run() }
        assertTrue(r.installed.isEmpty())
    }

    @Test fun uninstallOnDestroy() {
        val gone = mutableListOf<Activity>(); val a = Home()
        HeatmapHooks({ true }, { }, { gone.add(it) }, { }, { _, _ -> }).callbacks.onActivityDestroyed(a)
        assertEquals(listOf<Activity>(a), gone)
    }
}
