package staff.android.network.server

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import staff.android.network.*

class NewAccountRetrySolutionTest {
 private suspend fun until(block:()->Boolean)=withTimeout(3000){while(!block())delay(5)}
@Test fun `new accepted page survives old retry`() = runBlocking {
    FixtureServer().use { server ->
        val url = server.baseUrl.toHttpUrl()
        val endpoint = RetrofitCatalogEndpoint(url)
        CatalogClient(url, endpoint = endpoint).use { client ->
            val store = CatalogStore(client)
            store.refresh()
            val initial = store.state.value
assertEquals(listOf("camera", "tea"), initial.items.map { it.id })
assertEquals(129900L, initial.items.first().priceMinor)
assertEquals("a-s1-next-2", initial.nextCursor)
assertEquals("s1", initial.snapshot)
            server.enqueue("/catalog", ReplyScript(status = 503))
            store.loadMore()
            val failed = store.state.value
assertEquals(initial.items, failed.items)
assertEquals(initial.nextCursor, failed.nextCursor)
assertEquals(initial.snapshot, failed.snapshot)
assertFalse(failed.loading)
assertTrue(failed.problem!!.contains("503"))
            val hold = CountDownLatch(1)
            server.enqueue("/catalog", ReplyScript(holdHeaders = hold))
            val retry = async { store.loadMore() }
            try {
                until { server.requests.size == 3 }
                assertTrue(store.state.value.loading)
val held = server.requests[2]
assertEquals("/catalog?cursor=a-s1-next-2", held.path)
assertEquals("Bearer a-1", held.authorization)
                // A second tap while the first retry is held.
                store.loadMore()
                store.switchAccount("b")
                store.refresh()
                val acceptedB = store.state.value
assertEquals("b", acceptedB.account)
assertEquals(listOf("camera", "tea"), acceptedB.items.map { it.id })
assertEquals(89900L, acceptedB.items.first().priceMinor)
assertEquals("b-s1-next-2", acceptedB.nextCursor)
assertFalse(acceptedB.loading)
assertNull(acceptedB.problem)
                assertFalse(retry.isCompleted)
                hold.countDown()
                retry.await()
                assertEquals(acceptedB, store.state.value)
assertEquals(4, server.requests.size)
assertEquals("/catalog", server.requests[3].path)
assertEquals("Bearer b-1", server.requests[3].authorization)
            } finally {
                hold.countDown()
                retry.cancelAndJoin()
            }
        }
    }
}
}
