package dev.mightys.pulse

/** How the SDK treats App Control. */
public enum class ControlMode {
    /** Checks the rules and shows the built-in screens. This is the default. */
    Automatic,
    /** Checks the rules and keeps the state up to date, but shows nothing: draw your own screens from the state. */
    Manual,
    /** No network request is made for App Control and the state is always [ControlState.Kind.Normal]. */
    Off,
}

/** What the app should show right now. Read it from `Pulse.control.state` or listen for changes. */
public data class ControlState(
    /** What to show. */
    public val kind: Kind,
    /** Screen title, or null. */
    public val title: String? = null,
    /** Screen text, or the banner text for [Kind.MaintenanceUpcoming]. */
    public val body: String? = null,
    /** Text of the main button (update, try again, OK), or null when the screen has none. */
    public val buttonText: String? = null,
    /** Text of the second button ("Not now" or "Continue"), or null when the user cannot leave the screen. */
    public val secondaryButtonText: String? = null,
    /** Where the main button goes: the store page or the link of an announcement, or null. */
    public val url: String? = null,
    /** Start of the maintenance window in milliseconds since the epoch, or null. */
    public val startsAt: Long? = null,
    /** End of the maintenance window in milliseconds since the epoch, or null. */
    public val endsAt: Long? = null,
    /** The version a card is about (what's new) or the version the update leads to, or null. */
    public val version: String? = null,
    /** True when the user may leave the screen ([dismiss][ControlClient.dismiss] works). */
    public val allowContinue: Boolean = true,
    /** True in a debug build: a locking screen then also shows a test banner and a Continue button. */
    public val isTestBuild: Boolean = false,
    /** Text of the test banner when [isTestBuild] is true. */
    public val testBuildText: String? = null,
) {
    /** The eight things App Control can ask for. */
    public enum class Kind {
        /** Nothing to show. */
        Normal,
        /** A newer version is available: a dialog with "Update" and "Not now". */
        UpdateSuggested,
        /** The version is too old: a full screen the user cannot close. */
        UpdateRequired,
        /** This version is blocked: a full screen, with Continue when [allowContinue] is true. */
        VersionBlocked,
        /** Down for maintenance: a full screen with "Try again". */
        Maintenance,
        /** A maintenance window starts soon: a bar. */
        MaintenanceUpcoming,
        /** An announcement card. */
        Announcement,
        /** A what's new card after an update. */
        WhatsNew,
    }

    /** True for the kinds that cover the whole app or ask a question (everything except [Kind.Normal]). */
    public val isActive: Boolean get() = kind != Kind.Normal

    public companion object {
        /** The state when there is nothing to show. */
        @JvmField public val NORMAL: ControlState = ControlState(Kind.Normal)
    }
}

/** Receives every change of the control state. Called on a background thread, not on the main thread. */
public fun interface ControlListener {
    /** The state changed. */
    public fun onControlState(state: ControlState)
}
