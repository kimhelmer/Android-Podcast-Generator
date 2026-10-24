package com.podcaststudio.app.domain

enum class IssueSeverity { ERROR, WARNING }

data class ScriptIssue(val severity: IssueSeverity, val message: String)

/**
 * Checks a generated script before it is saved. Structure problems are errors. Length drift is a
 * warning until it is grossly off, because models routinely over- or under-shoot a word target.
 */
object ScriptValidator {
    const val MIN_SEGMENTS = 4
    const val MAX_SEGMENT_WORDS = 220

    private const val WARN_LOW = 0.75
    private const val WARN_HIGH = 1.25
    private const val FAIL_LOW = 0.5
    private const val FAIL_HIGH = 1.5

    private val urlPattern = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
    private val markupPattern = Regex("""[\[\]*#`<>]|\{|\}""")

    private val englishWords = setOf("the", "and", "is", "of", "to", "that", "it", "you", "was", "for", "with", "this")
    private val danishWords = setOf("og", "det", "er", "at", "en", "til", "af", "som", "på", "ikke", "der", "jeg", "han")

    fun validate(script: PodcastScript, language: Language, targetWords: Int): List<ScriptIssue> {
        val issues = mutableListOf<ScriptIssue>()
        fun error(m: String) = issues.add(ScriptIssue(IssueSeverity.ERROR, m))
        fun warn(m: String) = issues.add(ScriptIssue(IssueSeverity.WARNING, m))

        if (script.title.isBlank()) error("Title is empty.")
        if (script.summary.isBlank()) warn("Summary is empty.")
        val segs = script.segments
        if (segs.size < MIN_SEGMENTS) error("Script has ${segs.size} segments; at least $MIN_SEGMENTS are required.")

        if (segs.map { it.id }.toSet().size != segs.size) error("Segment ids must be unique.")
        if (segs.any { it.id.isBlank() }) error("Every segment needs an id.")
        if (segs.isNotEmpty() && !segs.alternatesHostGuest()) {
            error("Speakers must alternate HOST, GUEST, HOST, GUEST... starting with HOST.")
        }

        segs.forEach { s ->
            when {
                s.text.isBlank() -> error("Segment ${s.id} has no spoken text.")
                urlPattern.containsMatchIn(s.text) -> error("Segment ${s.id} contains a URL; citations belong in sources.")
                markupPattern.containsMatchIn(s.text) ->
                    error("Segment ${s.id} contains markup or citation markers; spoken text must be plain.")
                s.text.wordCount() > MAX_SEGMENT_WORDS ->
                    error("Segment ${s.id} is over $MAX_SEGMENT_WORDS words; split it into shorter turns.")
            }
        }

        val words = script.wordCount
        if (targetWords > 0 && segs.isNotEmpty()) {
            val ratio = words.toDouble() / targetWords
            when {
                ratio < FAIL_LOW -> error("Script has $words words but about $targetWords were requested (far too short).")
                ratio > FAIL_HIGH -> error("Script has $words words but about $targetWords were requested (far too long).")
                ratio < WARN_LOW -> warn("Script is shorter than requested: $words words vs about $targetWords.")
                ratio > WARN_HIGH -> warn("Script is longer than requested: $words words vs about $targetWords.")
            }
        }

        languageMismatch(segs, language)?.let { error(it) }
        return issues
    }

    /** Rough stopword check; it catches a whole script in the wrong language, not subtle mixing. */
    fun languageMismatch(segments: List<ScriptSegment>, language: Language): String? {
        val tokens = segments.flatMap { it.text.lowercase().split(Regex("[^\\p{L}]+")) }.filter { it.isNotEmpty() }
        if (tokens.size < 40) return null
        val en = tokens.count { it in englishWords }
        val da = tokens.count { it in danishWords }
        return when {
            language == Language.DANISH && en > da * 2 && en > 5 -> "Script looks English but Danish was requested."
            language == Language.ENGLISH && da > en * 2 && da > 5 -> "Script looks Danish but English was requested."
            else -> null
        }
    }

    fun List<ScriptIssue>.errors() = filter { it.severity == IssueSeverity.ERROR }
    fun List<ScriptIssue>.warnings() = filter { it.severity == IssueSeverity.WARNING }
}
