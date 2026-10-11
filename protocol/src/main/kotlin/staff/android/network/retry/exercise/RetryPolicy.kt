package staff.android.network.retry.exercise

import staff.android.network.retry.*

fun decideRetry(input: RetryInput): RetryDecision {
    if (input.status != 429 && input.status != 503) {
        return RetryDecision.Stop(StopReason.NotEligible)
    }
    return RetryDecision.Wait(1_000)
}
