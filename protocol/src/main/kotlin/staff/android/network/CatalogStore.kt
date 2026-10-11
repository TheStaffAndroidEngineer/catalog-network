package staff.android.network

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CatalogState(
    val account: String = "a",
    val items: List<Product> = emptyList(),
    val nextCursor: String? = null,
    val snapshot: String? = null,
    val loading: Boolean = false,
    val problem: String? = null,
)

class CatalogStore(private val client: CatalogClient) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutable.asStateFlow()
    private var generation = 0L
    private val usedCursors = mutableSetOf<String>()

    suspend fun switchAccount(account: String) {
        lock.withLock {
            client.switchAccount(account)
            generation++
            usedCursors.clear()
            mutable.value = CatalogState(account = account)
        }
    }

    suspend fun refresh() {
        val owner = lock.withLock {
            generation++
            mutable.value = mutable.value.copy(loading = true, problem = null)
            generation
        }
        load(owner, null)
    }

    suspend fun loadMore() {
        val pending = lock.withLock {
            val now = mutable.value
            if (now.loading || now.nextCursor == null) return
            mutable.value = now.copy(loading = true, problem = null)
            generation to now.nextCursor
        }
        load(pending.first, pending.second)
    }

    private suspend fun load(owner: Long, cursor: String?) {
        try {
            val page = client.readPage(cursor)
            lock.withLock {
                if (owner != generation) return
                val before = mutable.value
                val items = if (cursor == null) page.items else {
                    if (page.snapshot != before.snapshot) throw ContractFailure("Snapshot changed; refresh required")
                    if (cursor in usedCursors || page.nextCursor == cursor ||
                        page.nextCursor in usedCursors) throw ContractFailure("Cursor loop; refresh required")
                    val oldIds = before.items.map { it.id }.toSet()
                    if (page.items.any { it.id in oldIds }) throw ContractFailure("Duplicate across pages")
                    if (before.items.size + page.items.size > 1_000) throw ContractFailure("Catalog item limit")
                    before.items + page.items
                }
                if (cursor == null) usedCursors.clear() else usedCursors.add(cursor)
                mutable.value = before.copy(items = items, nextCursor = page.nextCursor,
                    snapshot = page.snapshot, loading = false, problem = null)
            }
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock { if (owner == generation) mutable.value = mutable.value.copy(loading = false) }
            }
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val message = when (failure) {
                is ContractFailure -> "Invalid response: ${failure.message}"
                is HttpFailure -> "Server returned ${failure.status}"
                is ObsoleteSession -> "Session changed"
                is InterruptedIOException -> "Request timed out"
                is IOException -> "Connection failed"
                else -> throw failure
            }
            lock.withLock {
                if (owner == generation) mutable.value = mutable.value.copy(loading = false, problem = message)
            }
        }
    }
}
