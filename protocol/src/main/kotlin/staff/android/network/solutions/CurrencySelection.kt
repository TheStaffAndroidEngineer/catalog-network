package staff.android.network.solutions

enum class Currency { USD, EUR }
data class PriceRow(val id: String, val minor: Long, val currency: Currency)
data class CurrencyPage(
    val rows: List<PriceRow>, val snapshot: String, val next: String?,
)
data class RequestKey(
    val session: Long, val account: String,
    val currency: Currency, val cursor: String?,
)
data class CurrencyTicket(val key: RequestKey, val generation: Long)
data class StoredPage(val page: CurrencyPage, val etag: String?)

class CurrencySelection {
    private var currency = Currency.USD
    private var generation = 0L
    private val cache = mutableMapOf<RequestKey, StoredPage>()
    var accepted: CurrencyPage? = null
        private set

    fun select(value: Currency) {
        currency = value
        generation++
        accepted = null
    }
    fun ticket(cursor: String? = null) = CurrencyTicket(
        RequestKey(1L, "a", currency, cursor), generation,
    )

fun requestHeaders(ticket: CurrencyTicket): Map<String, String> = buildMap {
    put("X-Catalog-Currency", ticket.key.currency.name)
    cache[ticket.key]?.etag?.let { put("If-None-Match", it) }
}

fun sameWork(a: CurrencyTicket, b: CurrencyTicket): Boolean =
    a.key == b.key
fun acceptFirst(ticket: CurrencyTicket, value: StoredPage): Boolean {
    if (ticket.generation != generation || ticket.key.currency != currency)
        return false
    require(ticket.key.session == 1L && ticket.key.account == "a")
    require(ticket.key.cursor == null) { "This exercise accepts first pages only" }
    val page = value.page
    require(page.snapshot.isNotBlank())
    require(page.rows.map { it.id }.distinct().size == page.rows.size)
    require(page.rows.all {
        it.id.isNotBlank() && it.minor >= 0 && it.currency == ticket.key.currency
    })
    cache[ticket.key] = value
    accepted = page
    return true
}
}
