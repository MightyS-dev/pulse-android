package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ErrorHooksTest {
    private class Recorder : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) { calls.add(t to e) }
    }

    @Test fun captureErrorsFalseInstallsNothing() {
        var installed: Thread.UncaughtExceptionHandler? = null
        val hooks = ErrorHooks(PulseClient(), { null }, { installed = it })
        assertFalse(hooks.install(false))
        assertNull(installed)
    }

    @Test fun installsOnceAndChainsThePreviousHandlerWithTheSameArguments() {
        val previous = Recorder(); var current: Thread.UncaughtExceptionHandler? = previous; var sets = 0
        val hooks = ErrorHooks(PulseClient(), { current }, { current = it; sets++ })
        assertTrue(hooks.install(true)); assertFalse(hooks.install(true))
        assertEquals(1, sets); assertNotSame(previous, current)
        val error = IllegalStateException("x"); val thread = Thread.currentThread()
        current!!.uncaughtException(thread, error)
        assertSame(thread, previous.calls.single().first); assertSame(error, previous.calls.single().second)
    }

    @Test fun errorApiNeverThrowsBeforeInitialization() {
        Pulse.trackError(null); Pulse.trackError(IllegalStateException("x"), mapOf("a" to 1))
    }

    @Test fun optionsDefaultToCapturingWithFullDetail() {
        val options = PulseOptions()
        assertTrue(options.captureErrors); assertEquals(ErrorDetail.FULL, options.errorDetail)
    }
}
