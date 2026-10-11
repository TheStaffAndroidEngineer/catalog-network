package staff.android.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Header
import retrofit2.http.Query

/** Policy-free wire boundary. Session ownership and retry policy stay in CatalogClient. */
data class EndpointReply<T>(val code: Int, val headers: Headers, val value: T?)
interface CatalogEndpoint {
    suspend fun page(token: String, cursor: String?, etag: String?): EndpointReply<CatalogPage>
    suspend fun refresh(token: String): EndpointReply<String>
}

class DirectCatalogEndpoint(private val baseUrl: HttpUrl, private val transport: HttpTransport = HttpTransport()) : CatalogEndpoint {
    override suspend fun page(token: String, cursor: String?, etag: String?): EndpointReply<CatalogPage> {
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegment("catalog")
            .apply { cursor?.let { addQueryParameter("cursor", it) } }.build())
            .header("Authorization", "Bearer $token").apply { etag?.let { header("If-None-Match", it) } }.build()
        val reply = transport.exchange(request)
        val value = if (reply.code == 200) { requireJson(reply.headers); decodePage(reply.body) } else null
        return EndpointReply(reply.code, reply.headers, value)
    }
    override suspend fun refresh(token: String): EndpointReply<String> {
        val reply = transport.exchange(Request.Builder().url(baseUrl.newBuilder().addPathSegment("refresh").build())
            .header("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody()).build())
        val value = if (reply.code == 200) {
            requireJson(reply.headers)
            try { wireJson.decodeFromString<TokenDto>(strictJsonText(reply.body)).accessToken }
            catch (failure: Exception) { throw ContractFailure("Invalid refresh representation") }
        } else null
        return EndpointReply(reply.code, reply.headers, value)
    }
}

private interface CatalogApi {
    @GET("catalog") suspend fun page(@Header("Authorization") authorization: String,
        @Query("cursor") cursor: String?, @Header("If-None-Match") etag: String?): retrofit2.Response<PageDto>
    @POST("refresh") suspend fun refresh(@Header("Authorization") authorization: String): retrofit2.Response<TokenDto>
}

/** Retrofit converts wire DTOs; validation still decides whether the application can accept them. */
class RetrofitCatalogEndpoint(baseUrl: HttpUrl, client: OkHttpClient = defaultCatalogHttpClient(), maximumBytes: Int = 64 * 1024) : CatalogEndpoint {
    private val api = Retrofit.Builder().baseUrl(baseUrl)
        .client(client.newBuilder().addInterceptor(BoundedJsonBody(maximumBytes)).build())
        .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType()))
        .build().create(CatalogApi::class.java)
    override suspend fun page(token: String, cursor: String?, etag: String?): EndpointReply<CatalogPage> = decode {
        val reply = api.page("Bearer $token", cursor, etag)
        reply.errorBody()?.close()
        EndpointReply(reply.code(), reply.headers(), if (reply.code() == 200)
            validatePage(reply.body() ?: throw ContractFailure("Missing page")) else null)
    }
    override suspend fun refresh(token: String): EndpointReply<String> = decode {
        val reply = api.refresh("Bearer $token")
        reply.errorBody()?.close()
        EndpointReply(reply.code(), reply.headers(), if (reply.code() == 200)
            reply.body()?.accessToken ?: throw ContractFailure("Missing token") else null)
    }
    private suspend fun <T> decode(block: suspend () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SerializationException) { throw ContractFailure("Invalid JSON representation") }
        catch (failure: IOException) {
            val contract = generateSequence<Throwable>(failure) { it.cause }.take(12)
                .firstOrNull { it is ContractFailure || it is HttpFailure }
            throw (contract ?: failure)
        }
}

fun defaultCatalogHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

private fun requireJson(headers: Headers) {
    if (!headers["Content-Type"]?.substringBefore(';')?.trim().equals("application/json", ignoreCase = true))
        throw ContractFailure("Expected JSON media type")
}

/** Runs while the Retrofit suspension still owns the Call, including a held or chunked body. */
private class BoundedJsonBody(private val maximumBytes: Int) : Interceptor {
    init { require(maximumBytes in 1 until Int.MAX_VALUE) }
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        try {
            if (response.code in 201..299) throw HttpFailure(response.code)
            val body = response.body
            if (body.contentLength() > maximumBytes) throw ContractFailure("Response exceeds byte limit")
            val output = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size() + 1))
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    if (output.size() > maximumBytes) throw ContractFailure("Response exceeds byte limit")
                }
            }
            val bytes = output.toByteArray()
            if (response.code == 200) {
                requireJson(response.headers)
                try { strictJsonText(bytes) }
                catch (failure: java.nio.charset.CharacterCodingException) { throw ContractFailure("Invalid UTF-8") }
            }
            return response.newBuilder().body(bytes.toResponseBody(if (response.code == 200) "application/json; charset=utf-8".toMediaType() else body.contentType())).build()
        } catch (failure: Exception) {
            response.close()
            // OkHttp requires interceptor failures to be IOException; retain the application cause.
            if (failure is IOException) throw failure
            throw IOException("Response boundary rejected", failure)
        }
    }
}
