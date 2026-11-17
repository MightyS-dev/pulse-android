package dev.mightys.pulse

/** Configuration for collection, bounded storage and delivery. Invalid values are normalized safely. */
public data class PulseOptions @JvmOverloads constructor(
    /** Base ingest address. The SDK appends api/v1/signals. */
    public val host: String? = PulseInfo.DEFAULT_HOST,
    /** Normal sending interval in seconds. */
    public val flushIntervalSeconds: Double = 20.0,
    /** Maximum signals in one request, capped at 1000. */
    public val maxBatchSize: Int = 100,
    /** Maximum retained signals, capped at 10000. */
    public val maxQueueSize: Int = 1000,
    /** Maximum age in days, capped at 29. */
    public val maxQueueAgeDays: Double = 7.0,
    /** Compress bodies larger than 1024 bytes. */
    public val gzip: Boolean = true,
    /** Debug flag and diagnostic logging; null uses the app's debuggable flag. */
    public val debug: Boolean? = null,
    /** Include application package and signing certificate digest only when enabled. */
    public val sendAppIdentity: Boolean = false,
    /** Optional application version override. */
    public val appVersion: String? = null,
    /** Send `pulse.screen_view` automatically when an Activity (or AndroidX fragment) is shown. Set false to send no automatic screen views. Manual `screen()` calls still send. The portal can switch screen views off, never on. */
    public val automaticScreenViews: Boolean = true,
    /** Send `pulse.session_started`, `pulse.new_install` and `pulse.app_updated`. Set false to send none of them. The portal can switch them off, never on. */
    public val automaticSignals: Boolean = true,
    /** Catch unhandled errors and crashes and send them as `pulse.error`. Set false to install no handler at all. `Pulse.trackError` still works. */
    public val captureErrors: Boolean = true,
    /** `FULL` sends type, filtered message and frames; `TYPE_ONLY` sends the type alone. */
    public val errorDetail: ErrorDetail = ErrorDetail.FULL,
    /** Follow the portal for heatmaps of taps (`pulse.heatmap`). Heatmaps are off until the owner switches them on in the portal; false turns them off for good, true never forces them on over the portal. */
    public val captureHeatmaps: Boolean = true,
    /** App Control: [ControlMode.Automatic] (the default) shows the update, block and maintenance screens set in the portal, [ControlMode.Manual] only keeps the state up to date, [ControlMode.Off] makes no request at all. */
    public val control: ControlMode = ControlMode.Automatic,
) {
    internal fun normalized(): PulseOptions = copy(
        host = host ?: PulseInfo.DEFAULT_HOST,
        flushIntervalSeconds = if (flushIntervalSeconds.isFinite() && flushIntervalSeconds > 0) flushIntervalSeconds.coerceIn(0.05, 3600.0) else 20.0,
        maxBatchSize = maxBatchSize.coerceIn(1, 1000), maxQueueSize = maxQueueSize.coerceIn(1, 10000),
        maxQueueAgeDays = if (maxQueueAgeDays.isFinite() && maxQueueAgeDays > 0) maxQueueAgeDays.coerceAtMost(29.0) else 7.0,
    )
}

/** Fixed wire identity shared by the Android adapter and transport. */
public object PulseInfo {
    /** SDK identifier, without app or device metadata. */
    public const val SDK_NAME: String = "pulse-android"
    /** SDK version. Generated from the Gradle version catalog. */
    public const val VERSION: String = BuildVersion.VALUE
    /** Default ingest host. */
    public const val DEFAULT_HOST: String = "https://in.mightys.dev"
}
