package staff.android.network

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class ProductDto(
    val id: String,
    val title: String,
    val priceMinor: Long,
    val currency: String,
)

@Serializable
data class PageDto(
    val items: List<ProductDto>,
    val nextCursor: String?,
    val snapshot: String,
)

@Serializable
data class TokenDto(val accessToken: String)

@ConsistentCopyVisibility
data class Product internal constructor(
    val id: String,
    val title: String,
    val priceMinor: Long,
    val currency: String,
)

@ConsistentCopyVisibility
data class CatalogPage internal constructor(
    val items: List<Product>,
    val nextCursor: String?,
    val snapshot: String,
)

class ContractFailure(message: String) : Exception(message)
class HttpFailure(val status: Int) : Exception("HTTP $status")
class ObsoleteSession : Exception("Session is no longer current")

internal val wireJson = Json { ignoreUnknownKeys = true }

fun validatePage(dto: PageDto): CatalogPage {
    fun demand(condition: Boolean, message: String) {
        if (!condition) throw ContractFailure(message)
    }
    demand(dto.items.size <= 100, "Too many products in one page")
    demand(dto.snapshot.isNotBlank() && dto.snapshot.length <= 64,
        "Invalid snapshot")
    demand(dto.nextCursor == null ||
        (dto.nextCursor.isNotBlank() && dto.nextCursor.length <= 128),
        "Invalid next cursor")
    val seen = mutableSetOf<String>()
    val products = dto.items.map { item ->
        demand(item.id.isNotBlank() && item.id.length <= 64, "Invalid product ID")
        demand(seen.add(item.id), "Duplicate product ID: ${item.id}")
        demand(item.title.isNotBlank() && item.title.length <= 200, "Invalid title")
        demand(item.priceMinor in 0L..100_000_000L, "Invalid price: ${item.id}")
        demand(item.currency == "USD", "Unsupported currency: ${item.currency}")
        Product(item.id, item.title, item.priceMinor, item.currency)
    }
    return CatalogPage(products, dto.nextCursor, dto.snapshot)
}

internal fun strictJsonText(bytes: ByteArray): String {
    val text=bytes.decodeToString(throwOnInvalidSequence=true)
    if(text.startsWith('\uFEFF'))throw ContractFailure("JSON must not contain a byte order mark")
    return text
}

fun decodePage(bytes: ByteArray): CatalogPage {
    val dto = try {
        wireJson.decodeFromString<PageDto>(strictJsonText(bytes))
    } catch (failure: SerializationException) {
        throw ContractFailure("Catalog JSON does not match its contract")
    } catch (failure: CharacterCodingException) {
        throw ContractFailure("Catalog is not valid UTF-8")
    }
    return validatePage(dto)
}
