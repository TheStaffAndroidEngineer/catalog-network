package staff.android.network.retry

import java.time.Instant

enum class Method { GET, POST }

data class RetryInput(
    val method: Method,
    val status: Int,
    val retryAfter: String?,
    val now: Instant,
    val attemptsMade: Int,
    val maxAttempts: Int,
    val remainingMillis: Long,
) {
    init {
        require(attemptsMade >= 1)
        require(maxAttempts >= 1)
        require(remainingMillis >= 0)
    }
}

enum class StopReason {
    NotEligible, AttemptsExhausted, InvalidHeader, BudgetExhausted,
}

sealed interface RetryDecision {
    data class Wait(val delayMillis: Long) : RetryDecision
    data class Stop(val reason: StopReason) : RetryDecision
}
