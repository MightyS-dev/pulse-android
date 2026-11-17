package dev.mightys.pulse

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App Control for Android: what the app owner set in the portal (require an update, suggest one, maintenance, a block, a
 * card), as a state you can read and listen to. In the default mode the SDK also shows the screens; with
 * `ControlMode.Manual` you draw your own from [state]. Reach it through `Pulse.control`.
 */
public class PulseControl internal constructor(private val client: ControlClient, private val store: ControlStore) {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Entry(val listener: ControlListener, val relay: ControlListener)
    private val entries = CopyOnWriteArrayList<Entry>()

    /** What to show right now. [ControlState.NORMAL] when nothing is asked. Safe to read from any thread. */
    public val state: ControlState get() = client.state

    /** Calls [listener] on the main thread after every change of [state]. Add it once; the same listener is not added twice. */
    public fun addListener(listener: ControlListener) {
        if (entries.any { it.listener === listener }) return
        val relay = ControlListener { value -> main.post { try { listener.onControlState(value) } catch (_: Exception) { /* Yours to handle. */ } } }
        entries.add(Entry(listener, relay)); client.addListener(relay)
    }

    /** Stops calling [listener]. */
    public fun removeListener(listener: ControlListener) {
        entries.filter { it.listener === listener }.forEach { entries.remove(it); client.removeListener(it.relay) }
    }

    /** Asks the server now instead of at the next return to the foreground. Does nothing in `ControlMode.Off`. */
    public fun checkNow() { client.checkNow() }

    /** Opens the store for the current state: the Google Play update flow when the app came from Play and has the Play In-App Updates library, else the store page. */
    public fun openStore() { val value = client.state; main.post { store.open(value) } }

    /** Lets the user leave the current state where it allows that (a suggestion, a card, a screen with [ControlState.allowContinue]). */
    public fun dismiss() { client.dismiss() }
}
