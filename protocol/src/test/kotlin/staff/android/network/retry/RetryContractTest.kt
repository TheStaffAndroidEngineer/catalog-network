package staff.android.network.retry

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

abstract class RetryContract {
    abstract fun decide(input: RetryInput): RetryDecision
    private val base = RetryInput(
        Method.GET, 429, "2", Instant.parse("2026-10-05T10:00:00Z"),
        attemptsMade = 1, maxAttempts = 3, remainingMillis = 5_000,
    )

    @Test fun deltaSecondsBecomeMilliseconds() {
        assertEquals(RetryDecision.Wait(2_000), decide(base))
    }
    @Test fun dateUsesInjectedClockAndRoundsUp() {
        val input = base.copy(
            retryAfter = "Mon, 05 Oct 2026 10:00:02 GMT",
            now = Instant.parse("2026-10-05T10:00:00.000000001Z"),
        )
        assertEquals(RetryDecision.Wait(2_000), decide(input))
    }
    @Test fun pastDateAndZeroPermitImmediateRetry() {
        assertEquals(RetryDecision.Wait(0), decide(base.copy(
            retryAfter = "Mon, 05 Oct 2026 09:59:59 GMT",
        )))
        assertEquals(RetryDecision.Wait(0), decide(base.copy(
            status = 503, retryAfter = "0",
        )))
    }
    @Test fun unsafeOrUnlistedStatusStopsFirst() {
        for (input in listOf(
            base.copy(method = Method.POST),
            base.copy(method = Method.POST, retryAfter = null),
            base.copy(status = 500),
            base.copy(status = 401),
        )) assertEquals(
            RetryDecision.Stop(StopReason.NotEligible), decide(input),
        )
    }
    @Test fun attemptLimitIncludesTheCompletedAttempt() {
        assertEquals(RetryDecision.Stop(StopReason.AttemptsExhausted),
            decide(base.copy(attemptsMade = 3)))
        assertEquals(RetryDecision.Stop(StopReason.AttemptsExhausted),
            decide(base.copy(attemptsMade = 3, retryAfter = null)))
    }
    @Test fun neverShortenServerDelayToFitBudget() {
        for (budget in listOf(0L, 1_999L, 2_000L)) {
            assertEquals(RetryDecision.Stop(StopReason.BudgetExhausted),
                decide(base.copy(remainingMillis = budget)))
        }
        assertEquals(RetryDecision.Wait(2_000),
            decide(base.copy(remainingMillis = 2_001)))
    }
    @Test fun invalidMissingOrOverflowingHeadersStop() {
        for (header in listOf(null, "", "-1", "+1", "1.5",
            "tomorrow", "9223372036854775808", "9223372036854776",
            "Sun, 05 Oct 2026 10:00:02 GMT",
            "Sat, 01 Jan +10000 10:00:00 GMT",
            "Sat, 01 Jan 0000 10:00:00 GMT",
            "Sun, 01 Jan 1899 10:00:00 GMT")) {
            assertEquals(RetryDecision.Stop(StopReason.InvalidHeader),
                decide(base.copy(retryAfter = header)), header)
        }
    }
    @Test fun onlyOuterHttpWhitespaceIsIgnored() {
        assertEquals(RetryDecision.Wait(2_000),
            decide(base.copy(retryAfter = " \t2\t ")))
        assertEquals(RetryDecision.Stop(StopReason.InvalidHeader),
            decide(base.copy(retryAfter = "2 0")))
    }
    @Test fun invalidCallerBudgetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(attemptsMade = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(maxAttempts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(remainingMillis = -1)
        }
    }
}

class RetryReferenceTest : RetryContract() {
    override fun decide(input: RetryInput) =
        staff.android.network.retry.solution.decideRetry(input)
}

@Tag("exercise")
class RetryLearnerTest : RetryContract() {
    override fun decide(input: RetryInput) =
        staff.android.network.retry.exercise.decideRetry(input)
}
