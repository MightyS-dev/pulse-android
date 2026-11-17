package dev.mightys.pulse

/**
 * Tells a tap from a drag or a multi-touch gesture: a tap is a gesture with one pointer from ACTION_DOWN to ACTION_UP that
 * never moved farther than the touch slop. Plain numbers in, so it is testable without a device.
 */
internal class TapDetector(private val slop: Float) {
    private var tracking = false
    private var downX = 0f
    private var downY = 0f

    /** Feeds one event ([action] is the masked action). True exactly at the ACTION_UP that ends a tap. */
    fun onEvent(action: Int, pointerCount: Int, x: Float, y: Float): Boolean {
        when (action) {
            ACTION_DOWN -> { tracking = pointerCount == 1; downX = x; downY = y }
            ACTION_POINTER_DOWN -> tracking = false
            ACTION_MOVE -> if (tracking && !within(x, y)) tracking = false
            ACTION_CANCEL -> tracking = false
            ACTION_UP -> { val tap = tracking && pointerCount == 1 && within(x, y); tracking = false; return tap }
        }
        return false
    }

    private fun within(x: Float, y: Float): Boolean {
        val dx = x - downX; val dy = y - downY
        return dx * dx + dy * dy <= slop * slop
    }

    // The values of android.view.MotionEvent; kept here so the class has no platform dependency.
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
    }
}

/**
 * Watches the touch events of one window and reports taps. [handle] observes first and then always calls the original
 * dispatch and returns its answer untouched: the SDK never consumes, delays or changes an event, and nothing it does can
 * make the dispatch fail.
 */
internal class HeatmapTouch(slop: Float, private val active: () -> Boolean, private val report: (Float, Float) -> Unit) {
    private val detector = TapDetector(slop)

    fun handle(action: Int, pointerCount: Int, x: Float, y: Float, dispatch: () -> Boolean): Boolean {
        try { if (detector.onEvent(action, pointerCount, x, y) && active()) report(x, y) } catch (_: Throwable) { /* never reaches the app */ }
        return dispatch()
    }
}

/** What is under a tap: a text or secure input (or inside one), or an element the app marked to be ignored (or inside one). */
internal class HitFacts(val input: Boolean, val ignored: Boolean)

/** The view tree as the hit test needs it, so the walk is testable with plain objects. */
internal interface HitTree<N> {
    fun children(node: N): List<N>
    /** The point in the child's own coordinates, or null when the child is hidden or the point is outside it. */
    fun enter(parent: N, child: N, x: Float, y: Float): FloatArray?
    fun isInput(node: N): Boolean
    fun isIgnored(node: N): Boolean
}

internal object HitTest {
    private const val MAX_DEPTH = 100

    /**
     * Looks at every element that contains the point, not only the topmost one: an input hidden behind a transparent
     * overlay still counts, so a doubt always errs on the side of not capturing. Stops at the first input or ignored element.
     */
    fun <N> probe(root: N, x: Float, y: Float, tree: HitTree<N>): HitFacts = walk(root, x, y, tree, 0)

    private fun <N> walk(node: N, x: Float, y: Float, tree: HitTree<N>, depth: Int): HitFacts {
        val input = tree.isInput(node); val ignored = tree.isIgnored(node)
        if (input || ignored) return HitFacts(input, ignored)
        // Deeper than any real layout: treat as unknown and do not capture.
        if (depth >= MAX_DEPTH) return HitFacts(input = true, ignored = false)
        for (child in tree.children(node)) {
            val local = tree.enter(node, child, x, y) ?: continue
            val found = walk(child, local[0], local[1], tree, depth + 1)
            if (found.input || found.ignored) return found
        }
        return HitFacts(false, false)
    }
}

/** Keyboard and input-type rules that need no view. */
internal object HeatmapRules {
    /** Fraction of the window height that the system may take from the bottom before it counts as a visible keyboard. */
    private const val KEYBOARD_FRACTION = 0.15

    /** The height heuristic for devices without IME insets (before API 30): the visible frame ends well above the window bottom. */
    fun keyboardByHeight(windowHeight: Int, visibleBottom: Int): Boolean =
        windowHeight > 0 && windowHeight - visibleBottom > windowHeight * KEYBOARD_FRACTION

    private const val CLASS_MASK = 0xf
    private const val VARIATION_MASK = 0xff0
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2

    /** InputType values for the secure variations: text password, visible password, web password and number password. */
    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and VARIATION_MASK
        return when (inputType and CLASS_MASK) {
            CLASS_TEXT -> variation == 0x80 || variation == 0x90 || variation == 0xe0
            CLASS_NUMBER -> variation == 0x10
            else -> false
        }
    }
}
