package dev.mightys.pulse

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * AndroidX fragment support. androidx.fragment is a compile-only dependency, so this class is only touched after
 * [available] has found it on the app's classpath; apps without fragments never load it.
 */
internal object FragmentScreens {
    fun available(): Boolean = try {
        Class.forName("androidx.fragment.app.FragmentActivity"); true
    } catch (_: Throwable) { false }

    fun watch(activity: Activity, screen: (String) -> Unit) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                // Headless fragments have no view and are not screens.
                if (f.view == null) return
                val name = f.javaClass.simpleName
                if (name.isNotEmpty()) screen(name)
            }
        }, true)
    }
}
