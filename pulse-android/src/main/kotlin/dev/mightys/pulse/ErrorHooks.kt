package dev.mightys.pulse

/**
 * Puts the crash handler in as the default uncaught-exception handler, once, chaining the one that was there before.
 * With `captureErrors` off nothing is installed.
 */
internal class ErrorHooks(
    private val client: PulseClient,
    private val current: () -> Thread.UncaughtExceptionHandler?,
    private val install: (Thread.UncaughtExceptionHandler) -> Unit,
) {
    private var installed = false

    @Synchronized
    fun install(captureErrors: Boolean): Boolean {
        if (!captureErrors || installed) return false
        installed = true
        install(client.crashHandler(current()))
        return true
    }
}
