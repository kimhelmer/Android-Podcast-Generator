package com.podcaststudio.app.data.remote

import com.podcaststudio.app.domain.AppJson
import com.podcaststudio.app.domain.SourceRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

const val OPENAI_BASE_URL = "https://api.openai.com/"

interface OpenAiApi {
    @POST("v1/responses")
    suspend fun createResponse(@Body body: okhttp3.RequestBody): Response<ResponseBody>
}

/** What came back from a Responses call, reduced to what the app needs. */
data class ResponsesResult(
    val model: String,
    val text: String,
    /** Deduplicated url_citation annotations, in first-cited order. */
    val sources: List<SourceRef>,
    /** Token usage as reported by OpenAI for this call; 0 if the response had none. */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/**
 * Thin wrapper over the Responses API: sends a request, retries rate limits and 5xx with backoff,
 * and turns every failure into an [OpenAiException] with a safe message.
 */
class OpenAiClient(
    private val api: OpenAiApi,
    private val maxRetries: Int = 2,
    private val knownKey: () -> String? = { null },
    private val backoff: suspend (ms: Long) -> Unit = { delay(it) },
) {
    suspend fun createResponse(body: JsonObject): ResponsesResult {
        var attempt = 0
        while (true) {
            try {
                return send(body)
            } catch (e: OpenAiException) {
                if (!e.isRetryable || attempt >= maxRetries) throw e
                val wait = e.retryAfterMs ?: (2_000L shl attempt)
                attempt++
                backoff(wait.coerceAtMost(30_000))
            }
        }
    }

    private suspend fun send(body: JsonObject): ResponsesResult {
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val code: Int
        val raw: String
        val retryAfter: String?
        try {
            val response = api.createResponse(requestBody)
            code = response.code()
            retryAfter = response.headers()["Retry-After"]
            raw = (if (response.isSuccessful) response.body() else response.errorBody())?.string().orEmpty()
        } catch (e: OpenAiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw OpenAiException(OpenAiErrorKind.TIMEOUT, "OpenAI took too long to respond. Try again, or a shorter episode.")
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw OpenAiException(OpenAiErrorKind.NETWORK, "Could not reach OpenAI. Check your internet connection.")
        }
        if (code !in 200..299) throw mapHttpError(code, raw, retryAfter)
        return parseResponse(raw)
    }

    internal fun mapHttpError(code: Int, rawBody: String, retryAfter: String?): OpenAiException {
        val err = runCatching { AppJson.parseToJsonElement(rawBody).jsonObject["error"]?.jsonObject }.getOrNull()
        val apiMessage = err?.get("message")?.jsonPrimitive?.contentOrNull
            ?.let { redactSecrets(it, knownKey()).take(300) }
        val apiCode = err?.get("code")?.jsonPrimitive?.contentOrNull
        return when {
            code == 401 || code == 403 -> OpenAiException(
                OpenAiErrorKind.AUTH,
                "OpenAI rejected the API key. Check it in Settings.",
            )
            code == 404 -> OpenAiException(
                OpenAiErrorKind.MODEL_UNAVAILABLE,
                "OpenAI could not find that model for your account. Check the model name in Settings.",
            )
            code == 429 && apiCode == "insufficient_quota" -> OpenAiException(
                OpenAiErrorKind.QUOTA,
                "Your OpenAI account is out of credit or over its quota. Check billing on platform.openai.com.",
            )
            code == 429 -> OpenAiException(
                OpenAiErrorKind.RATE_LIMIT,
                "OpenAI rate limit reached. Wait a moment and try again.",
                retryAfterMs = retryAfter?.toLongOrNull()?.times(1000),
            )
            code in 500..599 -> OpenAiException(OpenAiErrorKind.SERVER, "OpenAI had a server problem (HTTP $code). Try again shortly.")
            else -> OpenAiException(
                OpenAiErrorKind.BAD_REQUEST,
                "OpenAI rejected the request (HTTP $code)" +
                    (apiMessage?.let { ": $it" } ?: "") +
                    ". The selected model may not support this feature.",
            )
        }
    }

    internal fun parseResponse(raw: String): ResponsesResult {
        val root = try {
            AppJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw OpenAiException(OpenAiErrorKind.MALFORMED, "OpenAI returned an unreadable response.")
        }
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status == "failed") {
            val msg = root["error"]?.takeIf { it is JsonObject }?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw OpenAiException(
                OpenAiErrorKind.SERVER,
                "OpenAI could not complete the request" + (msg?.let { ": " + redactSecrets(it, knownKey()).take(200) } ?: "."),
            )
        }
        if (status == "incomplete") {
            val reason = root["incomplete_details"]?.takeIf { it is JsonObject }?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            throw OpenAiException(
                OpenAiErrorKind.INCOMPLETE,
                if (reason == "max_output_tokens") "OpenAI's answer was cut off because it ran out of output space. Try a shorter episode."
                else "OpenAI stopped early (${reason ?: "unknown reason"}).",
            )
        }

        val model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val text = StringBuilder()
        val sources = LinkedHashMap<String, SourceRef>()
        val output = (root["output"] as? JsonArray) ?: JsonArray(emptyList())
        for (item in output) {
            val obj = item as? JsonObject ?: continue
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "message") continue
            val content = (obj["content"] as? JsonArray) ?: continue
            for (part in content) {
                val p = part as? JsonObject ?: continue
                when (p["type"]?.jsonPrimitive?.contentOrNull) {
                    "refusal" -> throw OpenAiException(
                        OpenAiErrorKind.REFUSAL,
                        "OpenAI declined this request" + (p.str("refusal")?.let { ": ${it.take(200)}" } ?: "."),
                    )
                    "output_text" -> {
                        p.str("text")?.let { text.append(it) }
                        (p["annotations"] as? JsonArray)?.forEach { a -> addCitation(a, sources) }
                    }
                }
            }
        }
        if (text.isBlank()) throw OpenAiException(OpenAiErrorKind.MALFORMED, "OpenAI returned an empty answer.")
        val usage = root["usage"] as? JsonObject
        return ResponsesResult(
            model = model,
            text = text.toString(),
            sources = sources.values.toList(),
            inputTokens = usage?.str("input_tokens")?.toIntOrNull() ?: 0,
            outputTokens = usage?.str("output_tokens")?.toIntOrNull() ?: 0,
        )
    }

    private fun addCitation(a: JsonElement, into: MutableMap<String, SourceRef>) {
        val o = a as? JsonObject ?: return
        if (o.str("type") != "url_citation") return
        val url = cleanUrl(o.str("url") ?: return) ?: return
        into.getOrPut(url) { SourceRef(title = o.str("title")?.trim().orEmpty().ifEmpty { url }, url = url) }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Keeps https links only and drops OpenAI's utm_source tracking parameter. */
    internal fun cleanUrl(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        val builder = parsed.newBuilder()
        if (parsed.queryParameter("utm_source") == "openai") builder.removeAllQueryParameters("utm_source")
        return builder.build().toString().removeSuffix("?")
    }
}

/** Adds the bearer token at request time; the key is never stored in the client. */
private class AuthInterceptor(private val key: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val k = key()?.trim().orEmpty()
        if (k.isEmpty()) throw OpenAiException(OpenAiErrorKind.NO_API_KEY, "Add your OpenAI API key in Settings first.")
        return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $k").build())
    }
}

/** Rejects any non-HTTPS request outright. */
private class HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        if (!chain.request().isHttps) throw OpenAiException(OpenAiErrorKind.NETWORK, "Blocked a non-HTTPS request.")
        return chain.proceed(chain.request())
    }
}

object OpenAiClientFactory {
    /**
     * [baseUrl] and [requireHttps] exist so tests can point at a local MockWebServer; the app always
     * uses the defaults. No logging interceptor is installed: request headers carry the API key.
     */
    fun create(
        keyProvider: () -> String?,
        baseUrl: String = OPENAI_BASE_URL,
        requireHttps: Boolean = true,
        readTimeoutSeconds: Long = 240,
        maxRetries: Int = 2,
        backoff: suspend (Long) -> Unit = { delay(it) },
    ): OpenAiClient {
        require(!requireHttps || baseUrl.startsWith("https://")) { "OpenAI base URL must use HTTPS" }
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds + 60, TimeUnit.SECONDS)
            .apply { if (requireHttps) addInterceptor(HttpsOnlyInterceptor()) }
            .addInterceptor(AuthInterceptor(keyProvider))
            .build()
        val api = Retrofit.Builder().baseUrl(baseUrl).client(http).build().create(OpenAiApi::class.java)
        return OpenAiClient(api, maxRetries, keyProvider, backoff)
    }
}
