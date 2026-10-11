package staff.android.network.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import staff.android.network.HttpTransport
import staff.android.network.retry.*

class RetryHttpTest {
    private fun request(server: FixtureServer) = Request.Builder()
        .url(server.baseUrl + "reports").header("Authorization", "Bearer a-1").build()

    @Test fun `safe report GET waits for503 policy then really requests again`() = runBlocking {
        FixtureServer().use { server ->
            server.enqueue("/reports", ReplyScript(status = 503,
                headers = mapOf("Retry-After" to "1")))
            val firstBodyEnd = AtomicLong()
            val secondHeadersStart = AtomicLong()
            val headerCount = AtomicInteger()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    firstBodyEnd.compareAndSet(0, System.nanoTime())
                }
                override fun requestHeadersStart(call: Call) {
                    if (headerCount.incrementAndGet() == 2) secondHeadersStart.set(System.nanoTime())
                }
            }).build()
            val result = readReport(HttpTransport(http), request(server), 5_000)
            assertEquals(RetryDecision.Wait(1_000), result.decision)
            assertTrue(firstBodyEnd.get() > 0)
            assertTrue(secondHeadersStart.get() - firstBodyEnd.get() >= 1_000_000_000L)
            assertEquals(200, result.reply.code)
            assertEquals(2, server.requests.count { it.path == "/reports" })
            println("GET /reports ->503 Retry-After:1; waited at least1000ms; GET /reports ->200")
        }
    }

    @Test fun `ten second request cannot fit four second budget and sends no retry`() = runBlocking {
        FixtureServer().use { server ->
            server.enqueue("/reports", ReplyScript(status = 429,
                headers = mapOf("Retry-After" to "10")))
            val result = readReport(HttpTransport(), request(server), 4_000)
            assertEquals(RetryDecision.Stop(StopReason.BudgetExhausted), result.decision)
            assertEquals(429, result.reply.code)
            assertEquals(1, server.requests.size)
            println("GET /reports ->429 Retry-After:10;4s budget; stop; exactly one wire request")
        }
    }

    @Test fun `remaining operation budget also cancels second body read`() = runBlocking {
        FixtureServer().use { server ->
            server.enqueue("/reports", ReplyScript(status = 503,
                headers = mapOf("Retry-After" to "0")))
            val gate = CountDownLatch(1)
            server.enqueue("/reports", ReplyScript(holdBody = gate))
            try { readReport(HttpTransport(), request(server), 300); fail<Unit>("deadline must expire") }
            catch (_: TimeoutCancellationException) { }
            assertEquals(2, server.requests.size)
            assertEquals(1, gate.count)
        }
    }
}
