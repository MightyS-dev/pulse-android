package dev.mightys.pulse

import android.app.Activity
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.widget.TextView
import java.util.WeakHashMap

/** The real view tree for [HitTest]: platform views, their scroll and transform, and the privacy rules of one view. */
internal object ViewHitTree : HitTree<View> {
    override fun children(node: View): List<View> {
        val group = node as? ViewGroup ?: return emptyList()
        return List(group.childCount) { group.getChildAt(it) }
    }

    override fun enter(parent: View, child: View, x: Float, y: Float): FloatArray? {
        if (child.visibility != View.VISIBLE) return null
        val point = floatArrayOf(x + parent.scrollX - child.left, y + parent.scrollY - child.top)
        val matrix = child.matrix
        if (!matrix.isIdentity) {
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            inverse.mapPoints(point)
        }
        return if (point[0] >= 0 && point[1] >= 0 && point[0] <= child.width && point[1] <= child.height) point else null
    }

    /**
     * An EditText or any other text editor, a password field of any kind, and WebView content (a form field inside a page
     * cannot be seen from outside, so a page is never part of a heatmap).
     */
    override fun isInput(node: View): Boolean =
        node is WebView || node.onCheckIsTextEditor() ||
            node is TextView && (node.transformationMethod is PasswordTransformationMethod || HeatmapRules.isPasswordInputType(node.inputType))

    override fun isIgnored(node: View): Boolean = node.getTag(R.id.pulse_heatmap_ignore) == true
}

/** Whether the soft keyboard is showing in a window. IME insets from API 30, a height heuristic before that or when insets are missing. */
internal object Keyboard {
    fun visible(decor: View): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets: WindowInsets? = decor.rootWindowInsets
            if (insets != null) return insets.isVisible(WindowInsets.Type.ime())
        }
        val frame = Rect()
        decor.getWindowVisibleDisplayFrame(frame)
        return HeatmapRules.keyboardByHeight(decor.rootView.height, frame.bottom)
    }
}

/**
 * Wraps a window's callback to see touches. Every call goes on to the original callback unchanged; the wrapper only
 * looks at a touch before passing it on, and never consumes it.
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class HeatmapWindowCallback(private val original: Window.Callback, private val touch: HeatmapTouch) : Window.Callback {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return original.dispatchTouchEvent(null)
        return touch.handle(event.actionMasked, event.pointerCount, event.x, event.y) { original.dispatchTouchEvent(event) }
    }

    /** The callback this one wraps, put back when the Activity is destroyed. */
    fun unwrapped(): Window.Callback = original

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = original.dispatchKeyEvent(event)
    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean = original.dispatchKeyShortcutEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean = original.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = original.dispatchGenericMotionEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean = original.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = original.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = original.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = original.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = original.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = original.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) = original.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = original.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = original.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = original.onAttachedToWindow()
    override fun onDetachedFromWindow() = original.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = original.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = original.onSearchRequested()
    @androidx.annotation.RequiresApi(23)
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = original.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? = original.onWindowStartingActionMode(callback)
    @androidx.annotation.RequiresApi(23)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = original.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode?) = original.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode?) = original.onActionModeFinished(mode)
    @androidx.annotation.RequiresApi(24)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        original.onProvideKeyboardShortcuts(data, menu, deviceId)
    @androidx.annotation.RequiresApi(26)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = original.onPointerCaptureChanged(hasCapture)
}

/** Puts [HeatmapWindowCallback] on an Activity's window, once per window, and reports each tap with its facts to [tap]. */
internal class HeatmapInstaller(private val tap: (Double, Double, Double, Double, Boolean, Boolean, Boolean) -> Unit, private val active: () -> Boolean) {
    private val installed = WeakHashMap<Window, HeatmapWindowCallback>()

    fun install(activity: Activity) {
        if (activity is PulseControlActivity) return
        try {
            val window = activity.window ?: return
            if (installed.containsKey(window)) return
            val current = window.callback ?: return
            val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            val wrapper = HeatmapWindowCallback(current, HeatmapTouch(slop, active) { x, y -> report(activity, window, x, y) })
            window.callback = wrapper
            installed[window] = wrapper
        } catch (_: Throwable) { /* never reaches the app */ }
    }

    /** Puts the original callback back when this wrapper is still the top one. */
    fun uninstall(activity: Activity) {
        try {
            val window = activity.window ?: return
            val wrapper = installed.remove(window) ?: return
            if (window.callback === wrapper) window.callback = wrapper.unwrapped()
        } catch (_: Throwable) { /* never reaches the app */ }
    }

    /** Runs on the main thread inside the touch dispatch: one hit test, then the tap goes to the SDK worker. */
    private fun report(activity: Activity, window: Window, x: Float, y: Float) {
        val decor = window.decorView
        val density = activity.resources.displayMetrics.density
        if (decor.width <= 0 || decor.height <= 0 || !(density > 0f)) return
        val keyboard = Keyboard.visible(decor)
        val facts = if (keyboard) HitFacts(false, false) else HitTest.probe(decor as View, x, y, ViewHitTree)
        tap(x / density.toDouble(), y / density.toDouble(), decor.width / density.toDouble(), decor.height / density.toDouble(),
            keyboard, facts.input, facts.ignored)
    }
}
