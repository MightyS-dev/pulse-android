package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Flush with nothing to deliver completes at once. */
class FlushEmptyQueueTest {
    private fun completes(h: Harness): Boolean {
        val done = AtomicInteger(0)
        h.client.flush(Runnable { done.incrementAndGet() }); h.settle()
        return done.get() == 1
    }

    @Test fun flushOnAnEmptyQueueCompletesAtOnce() = assertTrue(completes(Harness()))

    @Test fun flushAfterEverythingWasDeliveredCompletesAtOnce() {
        val h = Harness(); h.send("a")
        assertTrue(h.transport.requests.isNotEmpty())
        assertTrue(completes(h))
    }

    @Test fun flushBeforeInitializeCompletesAtOnce() = assertTrue(completes(Harness(initialize = false)))

    @Test fun flushOnAnEmptyQueueCompletesAtOnceWhenStorageFails() {
        val storage = FakeStorage().apply { failReads = true; failWrites = true }
        assertTrue(completes(Harness(storage = storage)))
    }
}
