package staff.android.network

import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Tokens below are disposable fixture values, not a production OAuth flow.
data class Session(val epoch: Long, val account: String, val token: String)
private data class PageKey(val epoch: Long, val account: String, val cursor: String?)
private data class CachedPage(val page: CatalogPage, val etag: String?)

class CatalogClient(
    private val baseUrl: HttpUrl,
    private val transport: HttpTransport = HttpTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val endpoint: CatalogEndpoint = DirectCatalogEndpoint(baseUrl, transport),
) : AutoCloseable {
    private val stateLock = Mutex()
    private val refreshLock = Mutex()
    private var current = Session(1, "a", "a-1")
    private val cache = mutableMapOf<PageKey, CachedPage>()
    private val flights = mutableMapOf<PageKey, Deferred<CatalogPage>>()

    suspend fun session(): Session = stateLock.withLock { current }

    suspend fun switchAccount(account: String) {
        require(account == "a" || account == "b")
        stateLock.withLock {
            current = Session(current.epoch + 1, account, "$account-1")
            cache.clear()
        }
        // Let bounded old reads finish to expose the publication guard in this lab.
        // They cannot install a token or cache entry for the new session.
    }

    suspend fun readPage(cursor: String? = null): CatalogPage {
        val pair = stateLock.withLock {
            val ticket = current
            val key = PageKey(ticket.epoch, ticket.account, cursor)
            val existing = flights[key]
            if (existing != null) key to existing
            else {
                val created = scope.async(start = CoroutineStart.LAZY) {
                    try { withTimeout(8_000) { fetch(ticket, key) } }
                    catch (deadline: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw SocketTimeoutException("Operation deadline exceeded")
                    }
                }
                flights[key] = created
                created.invokeOnCompletion {
                    scope.launch { stateLock.withLock {
                        if (flights[key] === created) flights.remove(key)
                    } }
                }
                key to created
            }
        }
        val (key, work) = pair
        try {
            return work.await()
        } finally {
            // One cancelled waiter must not remove still-shared work.
            if (work.isCompleted) stateLock.withLock {
                if (flights[key] === work) flights.remove(key)
            }
        }
    }

    private suspend fun fetch(ticket: Session, key: PageKey): CatalogPage {
        val previous = stateLock.withLock { cache[key] }
        var reply = endpoint.page(ticket.token, key.cursor, previous?.etag)
        if (reply.code == 401) {
            val token = refreshedToken(ticket)
            reply = endpoint.page(token, key.cursor, previous?.etag)
        }
        val result = when (reply.code) {
            200 -> reply.value ?: throw ContractFailure("Missing validated page")
            304 -> previous?.takeIf { it.etag != null }?.page
                ?: throw ContractFailure("304 without a matching stored representation")
            else -> throw HttpFailure(reply.code)
        }
        stateLock.withLock {
            if (current.epoch != ticket.epoch) throw ObsoleteSession()
            if (CacheControl.parse(reply.headers).noStore) {
                cache.remove(key)
            } else {
                cache[key] = CachedPage(result, if (reply.code == 304) reply.headers["ETag"] ?: previous?.etag else reply.headers["ETag"])
            }
        }
        return result
    }

    private suspend fun refreshedToken(ticket: Session): String = refreshLock.withLock {
        val before = stateLock.withLock {
            if (current.epoch != ticket.epoch) throw ObsoleteSession()
            current
        }
        if (before.token != ticket.token) return@withLock before.token
        val reply = endpoint.refresh(ticket.token)
        if (reply.code != 200) throw HttpFailure(reply.code)
        val replacement = reply.value ?: throw ContractFailure("Invalid refresh representation")
        if (!Regex("[ab]-[0-9]{1,10}").matches(replacement) || !replacement.startsWith("${ticket.account}-")) throw ContractFailure("Invalid access token")
        stateLock.withLock {
            if (current.epoch != ticket.epoch) throw ObsoleteSession()
            current = current.copy(token = replacement)
        }
        replacement
    }

    override fun close() { scope.cancel() }
}
