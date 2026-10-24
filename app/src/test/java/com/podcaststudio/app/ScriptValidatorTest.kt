package com.podcaststudio.app

import com.podcaststudio.app.domain.AppJson
import com.podcaststudio.app.domain.IssueSeverity
import com.podcaststudio.app.domain.Language
import com.podcaststudio.app.domain.PodcastScript
import com.podcaststudio.app.domain.ScriptSegment
import com.podcaststudio.app.domain.ScriptValidator
import com.podcaststudio.app.domain.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptValidatorTest {
    private fun seg(i: Int, speaker: Speaker, text: String) = ScriptSegment("s$i", speaker, text)

    private fun script(segments: List<ScriptSegment>, title: String = "Title") = PodcastScript(
        title, "Summary", Language.ENGLISH, segments, emptyList(), 0, 0, "r", "s", 0, 0,
    )

    private fun alternating(n: Int, words: Int = 50) = (0 until n).map {
        seg(it + 1, if (it % 2 == 0) Speaker.HOST else Speaker.GUEST, (1..words).joinToString(" ") { "word" } + ".")
    }

    private fun errors(s: PodcastScript, target: Int = 0) =
        ScriptValidator.validate(s, Language.ENGLISH, target).filter { it.severity == IssueSeverity.ERROR }.map { it.message }

    @Test fun goodScriptHasNoErrors() = assertTrue(errors(script(alternating(8))).isEmpty())

    @Test fun blankTitleIsAnError() = assertTrue(errors(script(alternating(8), title = " ")).any { it.contains("Title") })

    @Test fun tooFewSegmentsIsAnError() = assertTrue(errors(script(alternating(2))).any { it.contains("at least") })

    @Test fun duplicateIdsAreAnError() {
        val segs = alternating(6).map { it.copy(id = "same") }
        assertTrue(errors(script(segs)).any { it.contains("unique") })
    }

    @Test fun mustStartWithHostAndAlternate() {
        val swapped = alternating(6).map { it.copy(speaker = if (it.speaker == Speaker.HOST) Speaker.GUEST else Speaker.HOST) }
        assertTrue(errors(script(swapped)).any { it.contains("alternate") })
        val twice = alternating(6).toMutableList().also { it[3] = it[3].copy(speaker = Speaker.HOST) }
        assertTrue(errors(script(twice)).any { it.contains("alternate") })
    }

    @Test fun emptyTextUrlAndMarkupAreErrors() {
        fun withText(t: String) = script(alternating(6).toMutableList().also { it[2] = it[2].copy(text = t) })
        assertTrue(errors(withText("  ")).any { it.contains("no spoken text") })
        assertTrue(errors(withText("go to www.example.org now")).any { it.contains("URL") })
        assertTrue(errors(withText("as shown [1] earlier")).any { it.contains("markup") })
        assertTrue(errors(withText("this is **bold**")).any { it.contains("markup") })
    }

    @Test fun overlongSegmentIsAnError() {
        val segs = alternating(6).toMutableList().also { it[1] = it[1].copy(text = "word ".repeat(300)) }
        assertTrue(errors(script(segs)).any { it.contains("split") })
    }

    @Test fun lengthThresholds() {
        val s = script(alternating(10, words = 50)) // 500 words
        fun sev(target: Int) = ScriptValidator.validate(s, Language.ENGLISH, target).map { it.severity to it.message }
        assertTrue(sev(500).isEmpty())
        assertTrue(sev(600).isEmpty())                       // 83%
        assertTrue(sev(700).single().first == IssueSeverity.WARNING) // 71%
        assertTrue(sev(1200).single().first == IssueSeverity.ERROR)  // 42%
        assertTrue(sev(400).isEmpty())                       // 125%
        assertTrue(sev(380).single().first == IssueSeverity.WARNING) // 131%
        assertTrue(sev(300).single().first == IssueSeverity.ERROR)   // 167%
    }

    @Test fun scriptSurvivesJsonRoundTrip() {
        val s = script(alternating(6))
        assertEquals(s, AppJson.decodeFromString(PodcastScript.serializer(), AppJson.encodeToString(PodcastScript.serializer(), s)))
    }
}
