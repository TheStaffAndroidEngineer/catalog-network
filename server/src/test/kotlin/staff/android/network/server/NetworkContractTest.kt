package staff.android.network.server

import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import staff.android.network.*

open class NetworkContractTest {
    protected open fun client(server: FixtureServer) = CatalogClient(server.baseUrl.toHttpUrl())
    private suspend fun until(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }
    private fun request(server: FixtureServer) = Request.Builder()
        .url(server.baseUrl + "catalog").header("Authorization", "Bearer a-1").build()

    @Test fun `invalid refresh retains accepted catalog and valid empty replaces it`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            val store = CatalogStore(client)
            store.refresh()
            assertEquals(listOf("camera", "tea"), store.state.value.items.map { it.id })
            server.enqueue("/catalog", ReplyScript(body = """{"items":[{"id":"camera","title":"Camera","priceMinor":-100,"currency":"USD"}],"nextCursor":null,"snapshot":"s1"}"""))
            store.refresh()
            assertEquals(129900, store.state.value.items.first().priceMinor)
            assertTrue(store.state.value.problem!!.contains("Invalid price"))
            server.enqueue("/catalog", ReplyScript(body = """{"items":[],"nextCursor":null,"snapshot":"s2"}"""))
            store.refresh()
            assertEquals(emptyList<Product>(), store.state.value.items)
            assertNull(store.state.value.problem)
            store.refresh()
            assertEquals(2, store.state.value.items.size)
            assertNull(server.requests.last().validator)
            println("HTTP200 invalid price -> retained129900; HTTP200 valid empty -> accepted[]")
        } }
    }

    @Test fun `additive unknown key allowed missing required value rejected`() {
        val valid = """{"items":[],"nextCursor":null,"snapshot":"s1","future":true}"""
        assertTrue(decodePage(valid.toByteArray()).items.isEmpty())
        assertThrows(ContractFailure::class.java) { decodePage("""{"items":[],"snapshot":"s1"}""".toByteArray()) }
        val item = ProductDto("camera", "Camera", 1, "USD")
        assertThrows(ContractFailure::class.java) { validatePage(PageDto(listOf(item, item), null, "s1")) }
        assertThrows(ContractFailure::class.java) { validatePage(PageDto(listOf(item.copy(currency = "ZZZ")), null, "s1")) }
    }

    @Test fun `304 selects validated matching representation`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            val first = client.readPage()
            assertEquals(first, client.readPage())
            assertEquals("\"a-s1-first\"", server.requests.last().validator)
            println(server.requests.joinToString("\n"))
        } }
    }

    @Test fun `304 without representation fails and No-Store is case insensitive`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            server.enqueue("/catalog", ReplyScript(status = 304))
            try { client.readPage(); fail<Unit>("304 must fail") } catch (_: ContractFailure) { }
            server.enqueue("/catalog", ReplyScript(headers = mapOf("Content-Type" to "Application/JSON"), extraHeaders = listOf("Cache-Control" to "No-Store", "Cache-Control" to "No-Cache")))
            client.readPage(); client.readPage()
            assertNull(server.requests.last().validator)
        } }
    }

    @Test fun `chunked body has a real size limit`() = runBlocking {
        FixtureServer().use { server ->
            server.enqueue("/catalog", ReplyScript(body = "x".repeat(200)))
            try { HttpTransport(maximumBytes = 64).exchange(request(server)); fail<Unit>("must reject body") }
            catch (failure: ContractFailure) { assertTrue(failure.message!!.contains("byte limit")) }
        }
    }

    @Test fun `cancel while reading partial body ends the call suspension`() = runBlocking {
        FixtureServer().use { server -> supervisorScope {
            val held = CountDownLatch(1)
            val bodyStarted = CountDownLatch(1)
            val callFailed = CountDownLatch(1)
            val actualCall = AtomicReference<Call>()
            server.enqueue("/catalog", ReplyScript(holdBody = held))
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { actualCall.set(call); bodyStarted.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { callFailed.countDown() }
            }).readTimeout(10, TimeUnit.SECONDS).build()
            val work = async { HttpTransport(http).exchange(request(server)) }
            until { bodyStarted.count == 0L }
            withTimeout(1_000) { work.cancelAndJoin() }
            assertTrue(work.isCancelled)
            assertTrue(actualCall.get().isCanceled())
            until { callFailed.count == 0L }
            assertEquals(1, held.count) // Remote remainder is still held.
            held.countDown()
            println("Body read began; caller cancellation completed while server remainder stayed held")
        } }
    }

    @Test fun `body timeout is distinct from caller cancellation`() = runBlocking {
        FixtureServer().use { server ->
            server.enqueue("/catalog", ReplyScript(holdBody = CountDownLatch(1)))
            val http = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            try { HttpTransport(http).exchange(request(server)); fail<Unit>("must time out") }
            catch (_: InterruptedIOException) { }
        }
    }

    @Test fun `two waiters share one HTTP read and cancelling one preserves the other`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            val gate = CountDownLatch(1)
            server.enqueue("/catalog", ReplyScript(holdHeaders = gate))
            val first = async(start = CoroutineStart.UNDISPATCHED) { client.readPage() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { client.readPage() }
            until { server.requests.size == 1 }
            first.cancelAndJoin()
            gate.countDown()
            assertEquals(2, second.await().items.size)
            assertEquals(1, server.requests.size)
            println("Two waiters; one GET; cancelled waiter leaves remaining waiter successful")
        } }
    }

    @Test fun `concurrent unauthorized reads rotate once and retry each once`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            server.expire("a")
            val oldReplies = CountDownLatch(1)
            repeat(2) { server.enqueue("/catalog", ReplyScript(holdHeaders = oldReplies)) }
            val first = async { client.readPage() }
            val second = async { client.readPage("a-s1-next-2") }
            until { server.requests.count { it.path.startsWith("/catalog") } == 2 }
            assertTrue(server.requests.all { it.authorization == "Bearer a-1" })
            oldReplies.countDown()
            assertEquals(2, first.await().items.size)
            assertEquals(1, second.await().items.size)
            assertEquals(1, server.requests.count { it.path == "/refresh" })
            assertEquals(4, server.requests.count { it.path.startsWith("/catalog") })
            assertEquals("a-3", client.session().token)
            println(server.requests.joinToString("\n"))
        } }
    }

    @Test fun `forbidden response never refreshes token`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            server.enqueue("/catalog", ReplyScript(status = 403))
            try { client.readPage(); fail<Unit>("403 must fail") } catch (failure: HttpFailure) { assertEquals(403, failure.status) }
            assertEquals(0, server.requests.count { it.path == "/refresh" })
        } }
    }

    @Test fun `old successful response cannot cross even same-account relogin`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client -> supervisorScope {
            val gate = CountDownLatch(1)
            server.enqueue("/catalog", ReplyScript(holdHeaders = gate))
            val old = async { client.readPage() }
            until { server.requests.size == 1 }
            client.switchAccount("a")
            gate.countDown()
            try { old.await(); fail<Unit>("old epoch must fail") } catch (_: ObsoleteSession) { }
            client.readPage()
            assertNull(server.requests.last().validator)
            println("Same account a, epoch1 -> epoch2; old valid response rejected; cache not reused")
        } } }
    }

    @Test fun `held old refresh cannot replace new-account token`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client -> supervisorScope {
            val gate = CountDownLatch(1)
            server.expire("a")
            server.enqueue("/refresh", ReplyScript(holdHeaders = gate))
            val old = async { client.readPage() }
            until { server.requests.any { it.path == "/refresh" } }
            client.switchAccount("b")
            gate.countDown()
            try { old.await(); fail<Unit>("old refresh must fail") } catch (_: ObsoleteSession) { }
            assertEquals("b-1", client.session().token)
            assertEquals(89900, client.readPage().items.first().priceMinor)
            println("Held a refresh released after switch to b; token remains b-1")
        } } }
    }

    @Test fun `late next page cannot append after refresh replaces first page`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            val store = CatalogStore(client); store.refresh()
            val gate = CountDownLatch(1)
            server.enqueue("/catalog", ReplyScript(holdHeaders = gate))
            val more = async { store.loadMore() }
            until { server.requests.size == 2 }
            store.refresh()
            gate.countDown(); more.await()
            assertEquals(listOf("camera", "tea"), store.state.value.items.map { it.id })
            store.loadMore()
            assertEquals(listOf("camera", "tea", "tripod"), store.state.value.items.map { it.id })
            assertNull(store.state.value.nextCursor)
        } }
    }

    @Test fun `duplicate next page rejected atomically`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            val store = CatalogStore(client); store.refresh()
            server.enqueue("/catalog", ReplyScript(body = """{"items":[{"id":"camera","title":"Camera","priceMinor":1,"currency":"USD"}],"nextCursor":null,"snapshot":"s1"}"""))
            store.loadMore()
            assertEquals(2, store.state.value.items.size)
            assertTrue(store.state.value.problem!!.contains("Duplicate across pages"))
        } }
    }
    @Test fun `second401 stops rather than recursively refreshing`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            server.enqueue("/catalog", ReplyScript(status = 401))
            server.enqueue("/catalog", ReplyScript(status = 401))
            try { client.readPage(); fail<Unit>("second401 must fail") }
            catch (failure: HttpFailure) { assertEquals(401, failure.status) }
            assertEquals(1, server.requests.count { it.path == "/refresh" })
            assertEquals(2, server.requests.count { it.path == "/catalog" })
        } }
    }

    @Test fun `invalid refresh token never replaces current token`() = runBlocking {
        FixtureServer().use { server -> client(server).use { client ->
            server.expire("a")
            server.enqueue("/refresh", ReplyScript(body = """{"accessToken":"b-8"}"""))
            try { client.readPage(); fail<Unit>("wrong-account token must fail") }
            catch (_: ContractFailure) { }
            assertEquals("a-1", client.session().token)
        } }
    }

    @Test fun `GET refresh returns405 without rotating credentials`() = runBlocking {
        FixtureServer().use { server ->
            val transport = HttpTransport()
            val reply = transport.exchange(Request.Builder().url(server.baseUrl + "refresh")
                .header("Authorization", "Bearer a-1").build())
            assertEquals(405, reply.code)
            assertEquals("POST", reply.headers["Allow"])
            assertEquals(200, transport.exchange(request(server)).code)
        }
    }

}

/** The exact same inherited contracts exercise the typed Retrofit adapter. */
class RetrofitContractTest : NetworkContractTest() {
    override fun client(server: FixtureServer) = CatalogClient(server.baseUrl.toHttpUrl(),
        endpoint = RetrofitCatalogEndpoint(server.baseUrl.toHttpUrl()))
}
