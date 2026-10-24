package com.podcaststudio.app.data.remote

import java.io.IOException

enum class OpenAiErrorKind {
    NO_API_KEY,
    AUTH,
    RATE_LIMIT,
    QUOTA,
    TIMEOUT,
    NETWORK,
    SERVER,
    BAD_REQUEST,
    MODEL_UNAVAILABLE,
    INCOMPLETE,
    REFUSAL,
    MALFORMED,
    INVALID_SCRIPT,
}

/**
 * Failure talking to OpenAI. [message] is safe to show in the UI and contains no API key material.
 * Extends IOException so it can also be thrown from an OkHttp interceptor.
 */
class OpenAiException(
    val kind: OpenAiErrorKind,
    message: String,
    val retryAfterMs: Long? = null,
) : IOException(message) {
    /**
     * Only a rate-limit rejection (HTTP 429) is retried automatically: the request was refused before any work
     * was done, so repeating it cannot bill twice. Server errors, timeouts and cut-off answers are ambiguous
     * (the work may have run and been billed), so they are reported instead and the user decides.
     */
    val isRetryable: Boolean get() = kind == OpenAiErrorKind.RATE_LIMIT
}

// OpenAI keys start "sk-"; ElevenLabs keys start "sk_".
private val keyPattern = Regex("""sk[-_][A-Za-z0-9_\-*.]{4,}""")

/** Removes anything that looks like an API key, plus the exact key if known. */
fun redactSecrets(text: String, knownKey: String? = null): String {
    var out = keyPattern.replace(text, "[redacted]")
    if (!knownKey.isNullOrBlank() && knownKey.length >= 8) out = out.replace(knownKey, "[redacted]")
    return out
}
