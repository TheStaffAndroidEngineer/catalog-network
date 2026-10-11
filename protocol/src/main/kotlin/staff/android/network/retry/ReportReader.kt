package staff.android.network.retry

import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import staff.android.network.HttpReply
import staff.android.network.HttpTransport
import staff.android.network.retry.solution.decideRetry

data class ReportRead(val reply: HttpReply, val decision: RetryDecision)

// Bounded integration example: one explicitly safe GET, at most two attempts.
suspend fun readReport(
    transport: HttpTransport,
    request: Request,
    budgetMillis: Long,
    now: () -> Instant = Instant::now,
): ReportRead {
    require(request.method == "GET")
    require(budgetMillis in 1..60_000)
    return withTimeout(budgetMillis) {
        val start = System.nanoTime()
        fun remaining(): Long =
            (budgetMillis - (System.nanoTime() - start) / 1_000_000)
                .coerceAtLeast(0)
        val first = transport.exchange(request)
        val decision = decideRetry(RetryInput(
            Method.GET, first.code, first.headers["Retry-After"], now(),
            attemptsMade = 1, maxAttempts = 2, remainingMillis = remaining(),
        ))
        if (decision is RetryDecision.Stop) return@withTimeout ReportRead(first, decision)
        delay((decision as RetryDecision.Wait).delayMillis)
        currentCoroutineContext().ensureActive()
        if (remaining() == 0L) {
            return@withTimeout ReportRead(first,
                RetryDecision.Stop(StopReason.BudgetExhausted))
        }
        // The same outer deadline still encloses connection and complete body read.
        ReportRead(transport.exchange(request), decision)
    }
}
