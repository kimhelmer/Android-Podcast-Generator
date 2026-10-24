package com.podcaststudio.app.data.remote

import com.podcaststudio.app.domain.AppJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

const val ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/"

/** Raw PCM, 16-bit little-endian mono at 24 kHz. Needs no paid tier, unlike 44.1 kHz PCM. */
const val TTS_OUTPUT_FORMAT = "pcm_24000"
const val TTS_SAMPLE_RATE = 24_000

enum class ElevenLabsErrorKind {
    NO_API_KEY,
    AUTH,
    PERMISSION,
    QUOTA,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    SERVER,
    BAD_REQUEST,
    VOICE_NOT_FOUND,
    MODEL_NOT_FOUND,
    TEXT_TOO_LONG,
    INCOMPLETE_AUDIO,
    MALFORMED,
    BUDGET,
}

/** Failure talking to ElevenLabs. [message] is safe to show and contains no key material. */
class ElevenLabsException(
    val kind: ElevenLabsErrorKind,
    message: String,
) : IOException(message) {
    /**
     * Only a 429 (concurrency or busy rejection) is retried automatically: it is refused before any speech is
     * generated, so repeating it cannot bill twice. Server errors, timeouts and truncated audio are ambiguous,
     * so they stop the run; "Retry audio" resumes it and reuses every clip already saved.
     */
    val isRetryable: Boolean get() = kind == ElevenLabsErrorKind.RATE_LIMIT
}

interface ElevenLabsApi {
    @GET("v2/voices")
    suspend fun voices(
        @Query("page_size") pageSize: Int,
        @Query("next_page_token") nextPageToken: String?,
        @Query("include_total_count") includeTotalCount: Boolean,
    ): Response<ResponseBody>

    @POST("v1/text-to-speech/{voiceId}")
    suspend fun speak(
        @Path("voiceId") voiceId: String,
        @Query("output_format") outputFormat: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

data class ElVoice(
    val id: String,
    val name: String,
    val category: String?,
    val description: String,
    val previewUrl: String?,
)

class ElevenLabsClient(
    private val api: ElevenLabsApi,
    private val maxRetries: Int = 2,
    private val knownKey: () -> String? = { null },
    private val backoff: suspend (ms: Long) -> Unit = { delay(it) },
) {
    suspend fun listVoices(): List<ElVoice> {
        val out = mutableListOf<ElVoice>()
        var token: String? = null
        repeat(MAX_VOICE_PAGES) {
            val page = withRetry { fetchVoicePage(token) }
            out += page.voices
            token = page.next
            if (token == null) return out
        }
        return out
    }

    /** Returns raw PCM bytes ([TTS_OUTPUT_FORMAT]) for [body]'s text. */
    suspend fun synthesize(voiceId: String, body: JsonObject): ByteArray = withRetry {
        val (code, headers, bytes) = call {
            val r = api.speak(voiceId, TTS_OUTPUT_FORMAT, body.toString().toRequestBody("application/json".toMediaType()))
            val ok = r.isSuccessful
            val b = if (ok) r.body() else r.errorBody()
            val declared = b?.contentLength() ?: -1L
            val data = b?.bytes() ?: ByteArray(0)
            if (ok && declared >= 0 && declared != data.size.toLong()) {
                throw ElevenLabsException(ElevenLabsErrorKind.INCOMPLETE_AUDIO, "Audio download was cut short.")
            }
            Triple(r.code(), r.headers()["Content-Type"].orEmpty(), data)
        }
        if (code !in 200..299) throw mapError(code, bytes.toString(Charsets.UTF_8))
        val type = headers.lowercase()
        if (type.startsWith("application/json") || type.startsWith("text/")) {
            throw ElevenLabsException(ElevenLabsErrorKind.MALFORMED, "ElevenLabs returned text instead of audio.")
        }
        if (bytes.isEmpty()) throw ElevenLabsException(ElevenLabsErrorKind.INCOMPLETE_AUDIO, "ElevenLabs returned empty audio.")
        bytes
    }

    private data class VoicePage(val voices: List<ElVoice>, val next: String?)

    private suspend fun fetchVoicePage(token: String?): VoicePage {
        val (code, _, bytes) = call {
            val r = api.voices(VOICE_PAGE_SIZE, token, false)
            val b = if (r.isSuccessful) r.body() else r.errorBody()
            Triple(r.code(), r.headers()["Content-Type"].orEmpty(), b?.bytes() ?: ByteArray(0))
        }
        val raw = bytes.toString(Charsets.UTF_8)
        if (code !in 200..299) throw mapError(code, raw)
        val root = try {
            AppJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw ElevenLabsException(ElevenLabsErrorKind.MALFORMED, "ElevenLabs returned an unreadable voice list.")
        }
        val voices = (root["voices"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("voice_id") ?: return@mapNotNull null
            val name = o.str("name")?.trim().orEmpty().ifEmpty { id }
            ElVoice(
                id = id,
                name = name,
                category = o.str("category"),
                description = describe(o),
                previewUrl = o.str("preview_url")?.takeIf { it.startsWith("https://") },
            )
        }
        val hasMore = (root["has_more"] as? JsonPrimitive)?.contentOrNull == "true"
        return VoicePage(voices, root.str("next_page_token")?.takeIf { hasMore })
    }

    /** "warm, british, female" style summary from labels, falling back to the description. */
    private fun describe(o: JsonObject): String {
        val labels = (o["labels"] as? JsonObject)
        val parts = listOf("accent", "age", "gender", "use_case", "descriptive")
            .mapNotNull { labels?.str(it)?.replace('_', ' ') }
            .distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ") else o.str("description")?.take(60).orEmpty()
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ElevenLabsException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw ElevenLabsException(ElevenLabsErrorKind.TIMEOUT, "ElevenLabs took too long to respond.")
    } catch (e: ProtocolException) {
        throw ElevenLabsException(ElevenLabsErrorKind.INCOMPLETE_AUDIO, "Audio download was cut short.")
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        if (e.message?.contains("unexpected end of stream", ignoreCase = true) == true || e is java.io.EOFException) {
            throw ElevenLabsException(ElevenLabsErrorKind.INCOMPLETE_AUDIO, "Audio download was cut short.")
        }
        throw ElevenLabsException(ElevenLabsErrorKind.NETWORK, "Could not reach ElevenLabs. Check your internet connection.")
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ElevenLabsException) {
                if (!e.isRetryable || attempt >= maxRetries) throw e
                val wait = 2_000L shl attempt
                attempt++
                backoff(wait)
            }
        }
    }

    internal fun mapError(httpCode: Int, rawBody: String): ElevenLabsException {
        val detail: JsonElement? = runCatching { AppJson.parseToJsonElement(rawBody).jsonObject["detail"] }.getOrNull()
        val obj = detail as? JsonObject
        val code = obj?.str("code") ?: obj?.str("status")
        val message = when (detail) {
            is JsonObject -> detail.str("message")
            is JsonArray -> detail.firstOrNull()?.let { (it as? JsonObject)?.str("msg") }
            is JsonPrimitive -> detail.contentOrNull
            else -> null
        }?.let { redactSecrets(it, knownKey()).take(300) }

        fun ex(kind: ElevenLabsErrorKind, text: String) = ElevenLabsException(kind, text)
        return when {
            httpCode == 401 && code == "quota_exceeded" -> ex(ElevenLabsErrorKind.QUOTA, QUOTA_TEXT)
            httpCode == 401 -> ex(ElevenLabsErrorKind.AUTH, "ElevenLabs rejected the API key. Check it in Settings.")
            httpCode == 402 -> ex(ElevenLabsErrorKind.QUOTA, QUOTA_TEXT)
            httpCode == 403 -> ex(
                ElevenLabsErrorKind.PERMISSION,
                "This ElevenLabs key is not allowed to do that. Check its permissions (text to speech, voices) and IP restrictions.",
            )
            httpCode == 404 && code == "voice_not_found" ->
                ex(ElevenLabsErrorKind.VOICE_NOT_FOUND, "ElevenLabs could not find the selected voice. Pick another voice.")
            httpCode == 404 && code == "model_not_found" ->
                ex(ElevenLabsErrorKind.MODEL_NOT_FOUND, "ElevenLabs does not know that speech model. Check the model name in Settings.")
            httpCode == 400 && code == "text_too_long" ->
                ex(ElevenLabsErrorKind.TEXT_TOO_LONG, "A script segment is too long for the selected speech model.")
            httpCode == 429 -> ex(ElevenLabsErrorKind.RATE_LIMIT, "ElevenLabs is busy or at its concurrency limit. Try again shortly.")
            httpCode in 500..599 -> ex(ElevenLabsErrorKind.SERVER, "ElevenLabs had a server problem (HTTP $httpCode).")
            else -> ex(
                ElevenLabsErrorKind.BAD_REQUEST,
                "ElevenLabs rejected the request (HTTP $httpCode)" + (message?.let { ": $it" } ?: "") + ".",
            )
        }
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key]
        return if (v == null || v is JsonNull) null else (v as? JsonPrimitive)?.contentOrNull
    }

    private companion object {
        const val VOICE_PAGE_SIZE = 100
        const val MAX_VOICE_PAGES = 10
        const val QUOTA_TEXT = "Your ElevenLabs account is out of credits or over its quota. Check your plan on elevenlabs.io."
    }
}

private class XiKeyInterceptor(private val key: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val k = key()?.trim().orEmpty()
        if (k.isEmpty()) throw ElevenLabsException(ElevenLabsErrorKind.NO_API_KEY, "Add your ElevenLabs API key in Settings first.")
        return chain.proceed(chain.request().newBuilder().header("xi-api-key", k).build())
    }
}

private class ElevenLabsHttpsOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        if (!chain.request().isHttps) throw ElevenLabsException(ElevenLabsErrorKind.NETWORK, "Blocked a non-HTTPS request.")
        return chain.proceed(chain.request())
    }
}

object ElevenLabsClientFactory {
    /** As with OpenAI: the base URL and HTTPS switch exist for tests only, and nothing logs headers. */
    fun create(
        keyProvider: () -> String?,
        baseUrl: String = ELEVENLABS_BASE_URL,
        requireHttps: Boolean = true,
        readTimeoutSeconds: Long = 120,
        maxRetries: Int = 2,
        backoff: suspend (Long) -> Unit = { delay(it) },
    ): ElevenLabsClient {
        require(!requireHttps || baseUrl.startsWith("https://")) { "ElevenLabs base URL must use HTTPS" }
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds + 60, TimeUnit.SECONDS)
            .apply { if (requireHttps) addInterceptor(ElevenLabsHttpsOnly()) }
            .addInterceptor(XiKeyInterceptor(keyProvider))
            .build()
        val api = Retrofit.Builder().baseUrl(baseUrl).client(http).build().create(ElevenLabsApi::class.java)
        return ElevenLabsClient(api, maxRetries, keyProvider, backoff)
    }
}
