package dev.mightys.pulse

/** The pure rules of the built-in App Control screens: which kind gets which surface and what Back does. */
internal object ControlUiPolicy {
    enum class Surface { None, FullScreen, Dialog, Card, Bar }
    enum class Back { MoveToBackground, Dismiss }

    fun surface(kind: ControlState.Kind): Surface = when (kind) {
        ControlState.Kind.Normal -> Surface.None
        ControlState.Kind.UpdateRequired, ControlState.Kind.VersionBlocked, ControlState.Kind.Maintenance -> Surface.FullScreen
        ControlState.Kind.UpdateSuggested -> Surface.Dialog
        ControlState.Kind.Announcement, ControlState.Kind.WhatsNew -> Surface.Card
        ControlState.Kind.MaintenanceUpcoming -> Surface.Bar
    }

    /** True when the surface lives in the transparent PulseControlActivity (the bar is drawn on the app's own screen). */
    fun needsActivity(kind: ControlState.Kind): Boolean = surface(kind).let { it != Surface.None && it != Surface.Bar }

    /** Back never closes a locking screen and never leaves the app by force: it sends the app to the background. */
    fun back(kind: ControlState.Kind): Back = if (surface(kind) == Surface.FullScreen) Back.MoveToBackground else Back.Dismiss

    /** The kinds whose main button takes the user to the store (and counts as an Update press). */
    fun isUpdate(kind: ControlState.Kind): Boolean =
        kind == ControlState.Kind.UpdateRequired || kind == ControlState.Kind.UpdateSuggested || kind == ControlState.Kind.VersionBlocked
}

/** Where the Update button goes. */
internal object StoreLinks {
    fun playPage(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"
    fun market(packageName: String): String = "market://details?id=$packageName"

    /** Addresses to try in order. A link from the rules wins; otherwise the Play app first, then the web page. */
    fun candidates(url: String?, packageName: String?): List<String> {
        val own = url?.takeIf { it.isNotBlank() }
        val page = packageName?.let(::playPage)
        return when {
            own != null && own != page -> listOf(own)
            packageName != null -> listOf(market(packageName), playPage(packageName))
            else -> emptyList()
        }
    }
}

/** How an update is started when the app came from Google Play and the Play In-App Updates library is in the app. */
internal object PlayUpdatePlan {
    enum class Plan { Page, Immediate, Flexible }

    fun plan(kind: ControlState.Kind, installedFromPlay: Boolean, libraryPresent: Boolean): Plan = when {
        !installedFromPlay || !libraryPresent -> Plan.Page
        kind == ControlState.Kind.UpdateRequired || kind == ControlState.Kind.VersionBlocked -> Plan.Immediate
        kind == ControlState.Kind.UpdateSuggested -> Plan.Flexible
        else -> Plan.Page
    }
}

/** Looks for the Play In-App Updates library by class name, so nothing of it is loaded in apps that do not have it. */
internal object PlayLibrary {
    private const val CLASS = "com.google.android.play.core.appupdate.AppUpdateManagerFactory"
    fun available(): Boolean = try { Class.forName(CLASS); true } catch (_: Throwable) { false }
}
