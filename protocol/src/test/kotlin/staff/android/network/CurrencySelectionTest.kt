package staff.android.network
import staff.android.network.solutions.*
import org.junit.jupiter.api.Test
class CurrencySelectionTest { @Test fun changedCurrencyKeepsRepresentationAndAuthoritySeparate(){
val owner = CurrencySelection()
val usd = owner.ticket()
val usdPage = CurrencyPage(
    listOf(PriceRow("camera", 129900, Currency.USD),
        PriceRow("tea", 600, Currency.USD)),
    "usd-s1", "usd-next-2",
)
check(owner.acceptFirst(usd, StoredPage(usdPage, "\"usd-first\"")))
check(owner.requestHeaders(owner.ticket())["If-None-Match"] == "\"usd-first\"")
owner.select(Currency.EUR)
val eur = owner.ticket()
check(owner.requestHeaders(eur)["X-Catalog-Currency"] == "EUR")
check("If-None-Match" !in owner.requestHeaders(eur))
val eurPage = CurrencyPage(
    listOf(PriceRow("camera", 119900, Currency.EUR),
        PriceRow("tea", 550, Currency.EUR)),
    "eur-s1", "eur-next-2",
)
check(owner.acceptFirst(eur, StoredPage(eurPage, "\"eur-first\"")))
check(!owner.acceptFirst(usd, StoredPage(usdPage, "\"usd-first\"")))
check(owner.accepted == eurPage)
try {
    owner.acceptFirst(eur, StoredPage(usdPage, null))
    error("An EUR request must reject USD rows")
} catch (_: IllegalArgumentException) { }
check(owner.accepted == eurPage)
check(owner.sameWork(eur, owner.ticket()))
check(!owner.sameWork(usd, eur))
check(!owner.sameWork(eur, owner.ticket("eur-next-2")))
owner.select(Currency.EUR) // Same name, new selection occurrence.
check(!owner.acceptFirst(eur, StoredPage(eurPage, "\"eur-first\"")))
check(owner.accepted == null)
} }
