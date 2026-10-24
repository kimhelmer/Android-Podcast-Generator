package com.podcaststudio.app.ui

import com.podcaststudio.app.domain.ValidationError
import java.text.DateFormat
import java.util.Date

/** mm:ss, or h:mm:ss past an hour. */
fun formatClock(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

fun ValidationError.message(): String = when (this) {
    ValidationError.TOPIC_EMPTY -> "Describe the topic of the podcast."
    ValidationError.TOPIC_TOO_SHORT -> "Topic needs at least 10 characters."
    ValidationError.TOPIC_TOO_LONG -> "Topic is too long (max 2000 characters)."
    ValidationError.INVALID_DURATION -> "Choose 5, 10 or 15 minutes."
    ValidationError.HOST_VOICE_MISSING -> "Choose a host voice."
    ValidationError.GUEST_VOICE_MISSING -> "Choose a guest voice."
    ValidationError.SAME_VOICE -> "Host and guest need different voices."
    ValidationError.VOICE_LANGUAGE_MISMATCH -> "A selected voice does not support this language."
}
