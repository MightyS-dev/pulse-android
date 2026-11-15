package dev.mightys.pulse.sample

import android.app.Application
import dev.mightys.pulse.Pulse

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize once, early. Without a key the sample still starts and shows a hint.
        if (BuildConfig.PULSE_APP_KEY.isNotEmpty()) Pulse.initialize(this, BuildConfig.PULSE_APP_KEY)
    }
}
