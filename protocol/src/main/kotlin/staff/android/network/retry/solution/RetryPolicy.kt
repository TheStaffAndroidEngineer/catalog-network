package staff.android.network.retry.solution

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import staff.android.network.retry.*

private val httpDate = DateTimeFormatter.ofPattern(
    "EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US,
).withResolverStyle(java.time.format.ResolverStyle.STRICT)
    .withZone(java.time.ZoneOffset.UTC)

fun decideRetry(input: RetryInput): RetryDecision {
    if (input.method != Method.GET ||
        input.status !in setOf(429, 503)
    ) return RetryDecision.Stop(StopReason.NotEligible)
    if (input.attemptsMade >= input.maxAttempts) {
        return RetryDecision.Stop(StopReason.AttemptsExhausted)
    }
    val delay = parseDelay(input)
        ?: return RetryDecision.Stop(StopReason.InvalidHeader)
    if (delay >= input.remainingMillis) {
        return RetryDecision.Stop(StopReason.BudgetExhausted)
    }
    return RetryDecision.Wait(delay)
}

private fun parseDelay(input: RetryInput): Long? {
    val value = input.retryAfter?.trim(' ', '\t') ?: return null
    return try {
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            Math.multiplyExact(value.toLong(), 1_000L)
        } else {
            val shape = Regex(
                "[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} " +
                    "[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT",
            )
            if (!shape.matches(value)) return null
            val target = ZonedDateTime.parse(value, httpDate)
                .toInstant()
            if (target.atZone(java.time.ZoneOffset.UTC).year < 1900) {
                return null
            }
            val duration = Duration.between(input.now, target)
            if (duration.isNegative || duration.isZero) 0L
            else Math.addExact(
                Math.multiplyExact(duration.seconds, 1_000L),
                (duration.nano + 999_999L) / 1_000_000L,
            )
        }
    } catch (_: java.time.DateTimeException) {
        null
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
