package dev.mightys.pulse

import android.content.Context
import android.os.Build
import java.util.Locale

/** Languages for the control screens, most preferred first: the app's own language, then the system's. */
internal object ControlLanguages {
    fun tags(context: Context): List<String> {
        val result = linkedSetOf<String>()
        result.add(Locale.getDefault().toLanguageTag())
        val configuration = context.resources.configuration
        if (Build.VERSION.SDK_INT >= 24) { val list = configuration.locales; for (i in 0 until list.size()) result.add(list[i].toLanguageTag()) }
        return result.filter { it.isNotBlank() && it != "und" }
    }
}
