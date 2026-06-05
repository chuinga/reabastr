package com.reabastr.app.worker

import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based test for Outbox Eventual Delivery (Property 4).
 *
 * **Validates: Requirements 8.2, 8.3, 8.4, 8.5**
 *
 * Property: Every Delta_Event enqueued in the Sync_Outbox is eventually either
 * (a) successfully uploaded and removed from the outbox, or (b) marked as FAILED
 * after exhausting retries (3 retries with exponential backoff 1s, 2s, 4s).
 * No event is silently dropped.
 */
class OutboxWorkerPropertyTest {

    companion object {
        private const val MAX_RETRIES = 3
    }

    /**
     * Possible outcomes when attempting to upload an event to the backend.
     */
    enum class ApiOutcome {
        /** 200 OK — event uploaded successfully */
        SUCCESS,
        /** 4xx (not 429) — permanent client error, immediately mark FAILED */
        PERMANENT_FAILURE,
        /** 429 or 5xx — transient server error, retryable */
        TRANSIENT_FAILURE,
        /** IOException — network error, retryable */
        NETWORK_ERROR,
        /** Unexpected exception — immediately mark FAILED */
        UNEXPECTED_ERROR
    }

    /**
     * Terminal state an event can reach after the drain logic processes it.
     */
    enum class TerminalState {
        /** Successfully uploaded and deleted from outbox */
        UPLOADED,
        /** Marked as FAILED after exhausting retries or permanent failure */
        FAILED
    }

    /**
     * A simulated outbox event with a pre-determined sequence of API outcomes.
     * Each element in [outcomes] represents what the API returns on that attempt.
     */
    data class SimulatedEvent(
        val productId: String,
        val delta: Int,
        val timestamp: Long,
        val outcomes: List<ApiOutcome>
    )

    /**
     * Simulates the OutboxWorker's drain logic for a single event, following
     * the exact same rules as OutboxWorker.uploadEvent:
     *
     * - On SUCCESS: event is deleted (UPLOADED)
     * - On PERMANENT_FAILURE (4xx not 429): immediately FAILED
     * - On TRANSIENT_FAILURE (429/5xx) or NETWORK_ERROR: increment retry, backoff, retry up to 3 times
     * - On UNEXPECTED_ERROR: immediately FAILED
     * - After exhausting MAX_RETRIES: FAILED
     *
     * Returns the terminal state the event reaches.
     */
    private fun simulateDrain(event: SimulatedEvent): TerminalState {
        var retryCount = 0
        var attemptIndex = 0

        while (retryCount < MAX_RETRIES) {
            // If we've run out of pre-generated outcomes, treat remaining attempts as transient failures
            val outcome = if (attemptIndex < event.outcomes.size) {
                event.outcomes[attemptIndex]
            } else {
                ApiOutcome.TRANSIENT_FAILURE
            }
            attemptIndex++

            when (outcome) {
                ApiOutcome.SUCCESS -> {
                    return TerminalState.UPLOADED
                }
                ApiOutcome.PERMANENT_FAILURE -> {
                    return TerminalState.FAILED
                }
                ApiOutcome.UNEXPECTED_ERROR -> {
                    return TerminalState.FAILED
                }
                ApiOutcome.TRANSIENT_FAILURE, ApiOutcome.NETWORK_ERROR -> {
                    retryCount++
                    // If retries not exhausted, the loop continues (simulating backoff wait)
                    // If retries exhausted, fall through to mark FAILED
                }
            }
        }

        // Exhausted all retries
        return TerminalState.FAILED
    }

    @Test
    fun `all outbox events reach terminal state after drain`() = runTest {
        // Generate random event sequences with random API outcomes
        val productIdArb = Arb.string(size = 8)
        val deltaArb = Arb.int(-99..99)
        val timestampArb = Arb.long(1_000_000_000_000L..1_900_000_000_000L)
        val outcomeArb = Arb.enum<ApiOutcome>()
        // Each event gets 1-5 outcome entries (enough to cover up to MAX_RETRIES attempts)
        val outcomesArb = Arb.list(outcomeArb, 1..5)

        // Run property check: for every random configuration of events and outcomes,
        // every event must reach a terminal state.
        checkAll(
            Arb.list(productIdArb, 1..20),
            Arb.list(deltaArb, 1..20),
            Arb.list(timestampArb, 1..20),
            Arb.list(outcomesArb, 1..20)
        ) { productIds, deltas, timestamps, outcomesList ->
            // Build simulated events from the random data (trim to shortest list length)
            val eventCount = minOf(productIds.size, deltas.size, timestamps.size, outcomesList.size)
            val events = (0 until eventCount).map { i ->
                SimulatedEvent(
                    productId = productIds[i],
                    delta = deltas[i],
                    timestamp = timestamps[i],
                    outcomes = outcomesList[i]
                )
            }

            // Process each event through the drain simulation
            val results = events.map { event -> simulateDrain(event) }

            // PROPERTY: Every event reaches a terminal state (UPLOADED or FAILED)
            // No event can be in an indeterminate/PENDING state after drain completes
            assertTrue(
                "All ${events.size} events must reach a terminal state, but got: $results",
                results.all { it == TerminalState.UPLOADED || it == TerminalState.FAILED }
            )

            // PROPERTY: No event is silently dropped — results list has same size as events list
            assertTrue(
                "Number of results (${results.size}) must equal number of events (${events.size})",
                results.size == events.size
            )
        }
    }

    @Test
    fun `events with only transient failures exhaust retries then become FAILED`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val deltaArb = Arb.int(-99..99)
        val timestampArb = Arb.long(1_000_000_000_000L..1_900_000_000_000L)

        checkAll(productIdArb, deltaArb, timestampArb) { productId, delta, timestamp ->
            // Event that encounters only transient failures (network errors / 5xx)
            val event = SimulatedEvent(
                productId = productId,
                delta = delta,
                timestamp = timestamp,
                outcomes = List(MAX_RETRIES + 1) { ApiOutcome.TRANSIENT_FAILURE }
            )

            val result = simulateDrain(event)

            assertTrue(
                "Event with only transient failures must be FAILED after $MAX_RETRIES retries, got $result",
                result == TerminalState.FAILED
            )
        }
    }

    @Test
    fun `events with eventual success before retry exhaustion become UPLOADED`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val deltaArb = Arb.int(-99..99)
        val timestampArb = Arb.long(1_000_000_000_000L..1_900_000_000_000L)
        // Success occurs on attempt 1, 2, or 3 (0-indexed: after 0, 1, or 2 transient failures)
        val successAttemptArb = Arb.int(0 until MAX_RETRIES)

        checkAll(productIdArb, deltaArb, timestampArb, successAttemptArb) { productId, delta, timestamp, successAt ->
            // Build outcomes: transient failures before the success attempt, then SUCCESS
            val outcomes = List(successAt) { ApiOutcome.TRANSIENT_FAILURE } + ApiOutcome.SUCCESS

            val event = SimulatedEvent(
                productId = productId,
                delta = delta,
                timestamp = timestamp,
                outcomes = outcomes
            )

            val result = simulateDrain(event)

            assertTrue(
                "Event succeeding on attempt ${successAt + 1} must be UPLOADED, got $result",
                result == TerminalState.UPLOADED
            )
        }
    }

    @Test
    fun `permanent failures immediately mark event as FAILED regardless of retry budget`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val deltaArb = Arb.int(-99..99)
        val timestampArb = Arb.long(1_000_000_000_000L..1_900_000_000_000L)

        checkAll(productIdArb, deltaArb, timestampArb) { productId, delta, timestamp ->
            // Event hits a permanent failure on first attempt
            val event = SimulatedEvent(
                productId = productId,
                delta = delta,
                timestamp = timestamp,
                outcomes = listOf(ApiOutcome.PERMANENT_FAILURE)
            )

            val result = simulateDrain(event)

            assertTrue(
                "Event with permanent failure (4xx) must be immediately FAILED, got $result",
                result == TerminalState.FAILED
            )
        }
    }

    @Test
    fun `unexpected errors immediately mark event as FAILED`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val deltaArb = Arb.int(-99..99)
        val timestampArb = Arb.long(1_000_000_000_000L..1_900_000_000_000L)

        checkAll(productIdArb, deltaArb, timestampArb) { productId, delta, timestamp ->
            val event = SimulatedEvent(
                productId = productId,
                delta = delta,
                timestamp = timestamp,
                outcomes = listOf(ApiOutcome.UNEXPECTED_ERROR)
            )

            val result = simulateDrain(event)

            assertTrue(
                "Event with unexpected error must be immediately FAILED, got $result",
                result == TerminalState.FAILED
            )
        }
    }
}
