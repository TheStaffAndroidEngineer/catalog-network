package staff.android.network.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ReplyScript(
    val status: Int = 200,
    val body: String? = null,
    val holdHeaders: CountDownLatch? = null,
    val holdBody: CountDownLatch? = null,
    val headers: Map<String, String> = emptyMap(),
    val extraHeaders: List<Pair<String, String>> = emptyList(),
)

data class ObservedRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val validator: String?,
)

/** Loopback-only disposable fixture. No production authentication is implemented. */
class FixtureServer(port: Int = 0) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val executor = Executors.newCachedThreadPool()
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<ReplyScript>>()
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    val requests = CopyOnWriteArrayList<ObservedRequest>()
    private val versions = mapOf("a" to AtomicInteger(1), "b" to AtomicInteger(1))
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try { handle(exchange) }
            catch (_: Exception) { exchange.close() }
        }
        server.start()
    }

    fun enqueue(route: String, script: ReplyScript) {
        scripts.computeIfAbsent(route) { ConcurrentLinkedQueue() }.add(script)
        script.holdHeaders?.let(gates::add)
        script.holdBody?.let(gates::add)
    }

    fun expire(account: String) { versions.getValue(account).incrementAndGet() }
    fun release() { gates.forEach { it.countDown() }; gates.clear() }
    fun reset() {
        release(); scripts.clear(); requests.clear()
        versions.values.forEach { it.set(1) }
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery?.split('&')?.associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, UTF_8)
        } ?: emptyMap()

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val params = query(exchange)
        if (path == "/control") {
            val mode = params["mode"] ?: ""
            when (mode) {
                "reset" -> reset()
                "release" -> release()
                "expire" -> expire(params["account"] ?: "a")
                "invalid" -> enqueue("/catalog", ReplyScript(body = page("a", null).replace("129900", "-100")))
                "malformed" -> enqueue("/catalog", ReplyScript(body = "{oops"))
                "empty" -> enqueue("/catalog", ReplyScript(body = """{"items":[],"nextCursor":null,"snapshot":"s2"}"""))
                "hold" -> enqueue("/catalog", ReplyScript(holdHeaders = CountDownLatch(1)))
                "partial" -> enqueue("/catalog", ReplyScript(holdBody = CountDownLatch(1)))
                "http503" -> enqueue("/catalog", ReplyScript(status = 503, headers = mapOf("Retry-After" to "1")))
                else -> { send(exchange, 400, "{\"error\":\"unknown control\"}"); return }
            }
            send(exchange, 200, "{\"ok\":true}")
            return
        }
        if (path == "/history") {
            val lines = requests.joinToString("\n") { "${it.method} ${it.path} ${it.authorization} If-None-Match=${it.validator}" }
            send(exchange, 200, lines, mapOf("Content-Type" to "text/plain"))
            return
        }
        requests.add(ObservedRequest(exchange.requestMethod, exchange.requestURI.toString(),
            exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("If-None-Match")))
        val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: ""
        val account = token.substringBefore('-')
        val version = versions[account]
        if (version == null) { send(exchange, 401, "{}"); return }
        val script = scripts[path]?.poll()
        // Capture the reply before holding it: later account changes cannot rewrite history.
        val expectedToken = "$account-${version.get()}"
        val cursor = params["cursor"]
        val etag = "\"$account-s1-${cursor ?: "first"}\""
        var status = script?.status ?: 200
        val body = when (path) {
            "/catalog" -> {
                if (token != expectedToken) status = 401
                else if (cursor != null && cursor != "$account-s1-next-2") status = 409
                else if (script == null && exchange.requestHeaders.getFirst("If-None-Match") == etag) status = 304
                script?.body ?: page(account, cursor)
            }
            "/reports" -> script?.body ?: """{"reports":[{"id":"r1","title":"Gate inspection"}]}"""
            "/refresh" -> {
                if (exchange.requestMethod != "POST") { status = 405; "{}" }
                else script?.body ?: "{\"accessToken\":\"$account-${version.incrementAndGet()}\"}"
            }
            else -> { status = 404; "{}" }
        }
        script?.holdHeaders?.await(10, TimeUnit.SECONDS)
        val headers = mutableMapOf("Cache-Control" to "private, no-cache")
        if (script?.body == null) headers["ETag"] = etag
        headers.putAll(script?.headers ?: emptyMap())
        send(exchange, status, body, headers, script?.holdBody, script?.extraHeaders ?: emptyList())
    }

    private fun page(account: String, cursor: String?): String {
        val cameraPrice = if (account == "a") 129900 else 89900
        val items = if (cursor == null) """
            {"id":"camera","title":"Camera","priceMinor":$cameraPrice,"currency":"USD"},
            {"id":"tea","title":"Tea","priceMinor":600,"currency":"USD"}
        """.trimIndent() else """{"id":"tripod","title":"Tripod","priceMinor":7500,"currency":"USD"}"""
        val next = if (cursor == null) "\"$account-s1-next-2\"" else "null"
        return """{"items":[$items],"nextCursor":$next,"snapshot":"s1"}"""
    }

    private fun send(exchange: HttpExchange, code: Int, text: String,
                     headers: Map<String, String> = emptyMap(), holdBody: CountDownLatch? = null,
                     extraHeaders: List<Pair<String, String>> = emptyList()) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        extraHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        if (code == 401) exchange.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"catalog-fixture\"")
        if (code == 405) exchange.responseHeaders.set("Allow", "POST")
        if (code == 304) {
            exchange.sendResponseHeaders(code, -1); exchange.close(); return
        }
        val bytes = text.toByteArray(UTF_8)
        // Chunking deliberately prevents Content-Length from being our only size guard.
        exchange.sendResponseHeaders(code, 0)
        exchange.responseBody.use { output ->
            if (holdBody == null || bytes.size < 2) output.write(bytes)
            else {
                val split = minOf(8, bytes.size - 1)
                output.write(bytes, 0, split); output.flush()
                holdBody.await(10, TimeUnit.SECONDS)
                output.write(bytes, split, bytes.size - split)
            }
        }
        exchange.close()
    }

    override fun close() { release(); server.stop(0); executor.shutdownNow() }
}
