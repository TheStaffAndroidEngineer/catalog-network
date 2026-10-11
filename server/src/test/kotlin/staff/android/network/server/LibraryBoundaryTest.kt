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

class LibraryBoundaryTest {
    private fun endpoint(retrofit: Boolean, url: HttpUrl, http: OkHttpClient = defaultCatalogHttpClient(), cap: Int = 65536): CatalogEndpoint =
        if (retrofit) RetrofitCatalogEndpoint(url, http, cap)
        else DirectCatalogEndpoint(url, HttpTransport(http, cap))
    private suspend fun until(block: () -> Boolean) = withTimeout(3000) { while (!block()) delay(5) }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `adapters reject malformed domain-invalid media and oversized bodies`(retrofit: Boolean) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api=endpoint(retrofit,server.url("/"),cap=256)
            for ((body, media) in listOf("{" to "application/json",
                """{"items":[{"id":"x","title":"X","priceMinor":-1,"currency":"USD"}],"nextCursor":null,"snapshot":"s1"}""" to "application/json",
                """{"items":[],"nextCursor":null,"snapshot":"s1"}""" to "text/plain",
                "x".repeat(257) to "application/json",
                "\uFEFF{\"items\":[],\"nextCursor\":null,\"snapshot\":\"s1\"}" to "application/json")) {
                server.enqueue(MockResponse.Builder().addHeader("Content-Type",media).body(body).build())
                try { api.page("a-1",null,null); fail<Unit>("must reject") } catch (_: ContractFailure) { }
                val request=server.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals("Bearer a-1",request.headers["Authorization"])
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `actual adapter caps unknown length and uses UTF8 independent of declared charset`(retrofit:Boolean)=runBlocking {
        MockWebServer().use {server->
            server.start();val api=endpoint(retrofit,server.url("/"),cap=256)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json").chunkedBody("x".repeat(257),16).build())
            try{api.page("a-1",null,null);fail<Unit>("chunked cap") }catch(_:ContractFailure){}
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json; charset=iso-8859-1").body(
                """{"items":[{"id":"tea","title":"Café","priceMinor":1,"currency":"USD"}],"nextCursor":null,"snapshot":"s1"}""").build())
            assertEquals("Café",api.page("a-1",null,null).value!!.items.single().title)
            server.enqueue(MockResponse.Builder().code(201).addHeader("Content-Type","application/json").body("{").build())
            CatalogClient(server.url("/"),endpoint=api).use{client->
                try{client.readPage();fail<Unit>("unexpected201")}catch(failure:HttpFailure){assertEquals(201,failure.status)}
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `actual adapter cancellation closes a partially read body`(retrofit:Boolean) = runBlocking {
        FixtureServer().use { server -> supervisorScope {
            val hold=CountDownLatch(1); val started=CountDownLatch(1); val call=AtomicReference<Call>()
            server.enqueue("/catalog",ReplyScript(holdBody=hold))
            val http=defaultCatalogHttpClient().newBuilder().readTimeout(10,TimeUnit.SECONDS)
                .eventListener(object:EventListener(){override fun responseBodyStart(value:Call){call.set(value);started.countDown()}}).build()
            val pending=async { endpoint(retrofit,server.baseUrl.toHttpUrl(),http).page("a-1",null,null) }
            try {
                until { started.count==0L }; withTimeout(1000){pending.cancelAndJoin()}
                assertTrue(call.get().isCanceled());assertEquals(1,hold.count)
            } finally { hold.countDown() }
        } }
    }
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `body deadline remains distinguishable from connection failure`(retrofit:Boolean) = runBlocking {
        FixtureServer().use { server ->
            val hold=CountDownLatch(1);server.enqueue("/catalog",ReplyScript(holdBody=hold))
            try {
                val http=defaultCatalogHttpClient().newBuilder().readTimeout(100,TimeUnit.MILLISECONDS).build()
                try { endpoint(retrofit,server.baseUrl.toHttpUrl(),http).page("a-1",null,null);fail<Unit>("deadline") }
                catch (_:InterruptedIOException) { }
            } finally { hold.countDown() }
        }
    }
    @Test fun `MockK verifies one refresh while real policy rejects a second401`() = runBlocking {
        val endpoint=mockk<CatalogEndpoint>()
        coEvery { endpoint.page(any(),any(),any()) } returns EndpointReply(401,Headers.headersOf(),null)
        coEvery { endpoint.refresh("a-1") } returns EndpointReply(200,Headers.headersOf(),"a-2")
        CatalogClient("http://127.0.0.1/".toHttpUrl(),endpoint=endpoint).use { client ->
            try { client.readPage();fail<Unit>("second401 must terminate") } catch (failure:HttpFailure){assertEquals(401,failure.status)}
            coVerify(exactly=1){endpoint.refresh("a-1")}
            coVerify(exactly=1){endpoint.page("a-2",null,null)}
        }
    }
    @Test fun `Turbine observes failed append and holds retry across account switch`() = runBlocking {
        FixtureServer().use { server -> CatalogClient(server.baseUrl.toHttpUrl(),endpoint=RetrofitCatalogEndpoint(server.baseUrl.toHttpUrl())).use { client ->
            val store=CatalogStore(client);store.refresh()
            store.state.test {
                assertEquals(2,awaitItem().items.size)
                val failureGate=CountDownLatch(1)
                server.enqueue("/catalog",ReplyScript(status=503,holdHeaders=failureGate))
                val failed=async{store.loadMore()}
                assertTrue(awaitItem().loading);failureGate.countDown()
                val failure=awaitItem();assertEquals(2,failure.items.size);assertTrue(failure.problem!!.contains("503"));failed.await()
                val hold=CountDownLatch(1);server.enqueue("/catalog",ReplyScript(holdHeaders=hold))
                val retry=async{store.loadMore()}
                assertTrue(awaitItem().loading);until{server.requests.size==3}
                store.switchAccount("b")
                val changed=awaitItem();assertEquals("b",changed.account);assertTrue(changed.items.isEmpty())
                hold.countDown();retry.await()
                assertEquals("b",store.state.value.account);assertTrue(store.state.value.items.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        } }
    }
}
