package dev.mightys.pulse.sample

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.mightys.pulse.Pulse

/** A small tour of the whole public API: track, setUser, flush and optOut. */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private var userSet = false
    private val configured get() = BuildConfig.PULSE_APP_KEY.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        log(if (configured) "Ready" else "Build with -PpulseAppKey=YOUR_KEY to enable the buttons")
    }

    private fun log(message: String) {
        logView.append("$message\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun action(label: String, block: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = configured
        setOnClickListener { block() }
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
                insets
            }
        }
        column.addView(TextView(this).apply {
            text = "Pulse Sample"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(action("Track an event") { Pulse.track("level.complete"); log("Tracked level.complete") })
        column.addView(action("Track a purchase (value and props)") {
            Pulse.track("purchase", 4.99, mapOf("sku" to "gems_100", "count" to 2))
            log("Tracked purchase 4.99")
        })
        val userButton = action("Set user") {}
        userButton.setOnClickListener {
            userSet = !userSet
            Pulse.setUser(if (userSet) "example-user-1" else null) // only the SHA-256 hash is stored or sent
            userButton.text = if (userSet) "Clear user" else "Set user"
            log(if (userSet) "User set (hashed)" else "User cleared")
        }
        column.addView(userButton)
        column.addView(action("Flush") {
            Pulse.flush(Runnable { runOnUiThread { log("Flush complete") } })
            log("Flush requested")
        })
        column.addView(Switch(this).apply {
            text = "Opt out"
            isEnabled = configured
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                Pulse.optOut = checked
                log(if (checked) "Opted out, collection stopped" else "Opted in")
            }
        })
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        column.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }
}
