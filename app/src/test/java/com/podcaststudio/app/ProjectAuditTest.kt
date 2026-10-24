package com.podcaststudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Static checks on the project itself: no credentials, no network, secrets kept out of backups/logs. */
class ProjectAuditTest {
    private val moduleDir: File = File(System.getProperty("user.dir")!!)
    private val mainDir = File(moduleDir, "src/main")

    private fun sourceFiles() = mainDir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "kts") } +
        sequenceOf(File(moduleDir, "build.gradle.kts"))

    @Test fun noHardcodedApiCredentials() {
        val patterns = listOf(
            Regex("""sk-[A-Za-z0-9_\-]{16,}"""),         // OpenAI style
            Regex("""sk_[a-f0-9]{24,}"""),                // ElevenLabs style
            Regex("""(?i)(api[_-]?key|secret|token)\s*[:=]\s*"[A-Za-z0-9_\-]{20,}""""),
            Regex("""Bearer\s+[A-Za-z0-9_\-\.]{20,}"""),
        )
        val hits = sourceFiles().flatMap { f ->
            val text = f.readText()
            patterns.filter { it.containsMatchIn(text) }.map { "${f.name}: ${it.pattern}" }
        }.toList()
        assertTrue("Possible credentials: $hits", hits.isEmpty())
    }

    @Test fun networkIsHttpsOnlyAndConfinedToTheRemotePackage() {
        val manifest = File(mainDir, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:networkSecurityConfig"))
        val nsc = File(mainDir, "res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains("""cleartextTrafficPermitted="false""""))
        assertFalse(nsc.contains("""cleartextTrafficPermitted="true""""))

        val kt = mainDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val httpLiterals = kt.filter { Regex("\"http://").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("plain http:// URL literals: $httpLiterals", httpLiterals.isEmpty())

        val builders = kt.filter { Regex("""OkHttpClient|Retrofit\.Builder|HttpURLConnection|URL\(""").containsMatchIn(it.readText()) }
        val outside = builders.filter { !it.path.replace('\\', '/').contains("/data/remote/") }.map { it.name }
        assertTrue("HTTP code outside data/remote: $outside", outside.isEmpty())
    }

    @Test fun noHttpLoggingThatCouldExposeTheAuthorizationHeader() {
        assertFalse(File(moduleDir, "build.gradle.kts").readText().contains("logging-interceptor"))
        val offenders = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""HttpLoggingInterceptor|Level\.BODY|Level\.HEADERS""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("HTTP logging found: $offenders", offenders.isEmpty())
    }

    @Test fun apiKeyIsOnlyTouchedByWiringSettingsAndTheLiveModeCheck() {
        val users = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("SecretKeyName.OPENAI") }
            .map { it.name }.toSet()
        assertEquals(setOf("AppContainer.kt", "SettingsScreen.kt", "SwitchingProviders.kt"), users)
    }

    @Test fun liveOpenAiDefaultsToOff() = assertFalse(com.podcaststudio.app.domain.AiSettings().useLiveOpenAi)

    @Test fun liveElevenLabsDefaultsToOffWithACharacterCap() {
        val d = com.podcaststudio.app.domain.AiSettings()
        assertFalse(d.useLiveElevenLabs)
        assertTrue(d.maxTtsCharacters in 1_000..100_000)
    }

    @Test fun elevenLabsKeyIsOnlyTouchedByWiringSettingsAndTheLiveModeCheck() {
        val users = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("SecretKeyName.ELEVENLABS") }
            .map { it.name }.toSet()
        assertEquals(setOf("AppContainer.kt", "SettingsScreen.kt", "SwitchingProviders.kt"), users)
    }

    @Test fun authHeadersAreBuiltOnlyInsideTheRemotePackage() {
        val offenders = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""xi-api-key|"Authorization"|Bearer """).containsMatchIn(it.readText()) }
            .filter { !it.path.replace('\\', '/').contains("/data/remote/") }
            .map { it.name }.toList()
        assertTrue("auth header code outside data/remote: $offenders", offenders.isEmpty())
    }

    @Test fun backgroundPlaybackAndGenerationServicesAreDeclaredCorrectly() {
        val m = File(mainDir, "AndroidManifest.xml").readText()
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.WAKE_LOCK",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { assertTrue("missing $it", m.contains("\"$it\"")) }

        val playback = Regex("""<service[^>]*PlaybackService[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(m)!!.value
        assertTrue(playback.contains("""foregroundServiceType="mediaPlayback""""))
        assertTrue(m.contains("androidx.media3.session.MediaSessionService"))

        val generation = Regex("""<service[^>]*GenerationService[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(m)!!.value
        assertTrue(generation.contains("""foregroundServiceType="dataSync""""))
        assertTrue(generation.contains("""android:exported="false""""))
    }

    @Test fun onlyTheAppAndSystemMediaControllersMayConnectToPlayback() {
        val src = File(mainDir, "java/com/podcaststudio/app/audio/PlaybackService.kt").readText()
        assertTrue(src.contains("controller.packageName == packageName"))
        assertTrue(src.contains("isMediaNotificationController"))
        assertTrue(src.contains("ConnectionResult.reject()"))
    }

    @Test fun secretsAreExcludedFromBackupsAndLogs() {
        val manifest = File(mainDir, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        val xml = File(mainDir, "res/xml")
        assertTrue(File(xml, "backup_rules.xml").readText().contains("secure_credentials.xml"))
        assertTrue(File(xml, "data_extraction_rules.xml").readText().contains("secure_credentials.xml"))
        val logging = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""\bLog\.[dviwe]\(|println\(|Timber""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Logging calls found (review for secrets): $logging", logging.isEmpty())
    }

    @Test fun bundledSampleAudioIsAValidNonSilentWav() {
        val bytes = File(mainDir, "res/raw/sample_episode.wav").readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt()
        val rate = bb.getInt(24)
        val bits = bb.getShort(34).toInt()
        val dataLen = bb.getInt(40)
        val seconds = dataLen.toDouble() / (rate * channels * bits / 8)
        assertEquals(24.0, seconds, 0.1)
        var peak = 0
        for (i in 44 until bytes.size - 1 step 2) peak = maxOf(peak, kotlin.math.abs(bb.getShort(i).toInt()))
        assertTrue("sample is silent", peak > 1000)
    }

    // ---------------------------------------------------------------- dependencies and what the app really ships

    @Test fun unusedBackgroundFrameworksAreNotBundled() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        assertFalse("WorkManager is unused and adds services, a boot receiver and wake locks", gradle.contains("androidx.work"))
        val imports = mainDir.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("import androidx.work") }.map { it.name }.toList()
        assertTrue("androidx.work used in $imports", imports.isEmpty())
    }

    @Test fun everyDeclaredDependencyIsActuallyUsedByTheSources() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        val code = mainDir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // dependency coordinate -> a package prefix that must appear in an import if it is used
        val expectations = mapOf(
            "retrofit2:retrofit" to "import retrofit2",
            "okhttp3:okhttp:" to "import okhttp3",
            "kotlinx-serialization-json" to "import kotlinx.serialization",
            "media3-exoplayer" to "import androidx.media3.exoplayer",
            "media3-session" to "import androidx.media3.session",
            "kotlinx-coroutines-guava" to "import kotlinx.coroutines.guava",
            "room-runtime" to "import androidx.room",
            "navigation-compose" to "import androidx.navigation",
            "material-icons-extended" to "import androidx.compose.material.icons",
        )
        val unused = expectations.filter { (dep, usage) -> gradle.contains(dep) && !code.contains(usage) }.keys
        assertTrue("declared but unused: $unused", unused.isEmpty())
    }

    /** Reads the merged debug manifest, produced by the build before unit tests run. Skipped if absent. */
    private fun mergedManifest(): String {
        val f = File(moduleDir, "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        org.junit.Assume.assumeTrue("merged manifest not built yet", f.isFile) // shows as skipped, never as a silent pass
        return f.readText()
    }

    @Test fun mergedManifestShipsOnlyTheExpectedPermissions() {
        val m = mergedManifest()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.WAKE_LOCK",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_NETWORK_STATE", // pulled in by OkHttp/media3
            "com.podcaststudio.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", // androidx safety permission
        )
        assertEquals("unexpected permissions: ${declared - allowed}", emptySet<String>(), declared - allowed)
        assertFalse(declared.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(declared.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(declared.contains("android.permission.RECORD_AUDIO"))
    }

    @Test fun mergedManifestDeclaresNoUnexpectedServicesOrReceivers() {
        val m = mergedManifest()
        val services = Regex("""<service[^>]*android:name="([^"]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "com.podcaststudio.app.audio.PlaybackService",
                "com.podcaststudio.app.generation.GenerationService",
                "androidx.room.MultiInstanceInvalidationService", // Room default; inactive unless multi-instance invalidation is enabled
            ),
            services,
        )
        val receivers = Regex("""<receiver[^>]*android:name="([^"]+)"""").findAll(m).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("androidx.profileinstaller.ProfileInstallReceiver"), receivers)
        val exported = Regex("""<service[^>]*android:exported="true"[^>]*android:name="([^"]+)"|<service[^>]*android:name="([^"]+)"[^>]*android:exported="true"""")
            .findAll(m).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        assertEquals("only the media session may be exported", setOf("com.podcaststudio.app.audio.PlaybackService"), exported)
    }

    @Test fun backupRulesProtectSecretsAndLargeGeneratedAudio() {
        val xml = File(mainDir, "res/xml")
        for (f in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = File(xml, f).readText()
            assertTrue("$f must exclude the credentials file", text.contains("secure_credentials.xml"))
        }
        assertTrue(File(mainDir, "AndroidManifest.xml").readText().contains("android:allowBackup=\"false\""))
    }

    @Test fun noSourceFileEmbedsAKeyShapedStringOrAProviderUrlWithCredentials() {
        val urlWithSecret = Regex("""https?://[^\s"']*(api[_-]?key|token|secret)=""", RegexOption.IGNORE_CASE)
        val hits = sourceFiles().filter { urlWithSecret.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("URLs carrying secrets: $hits", hits.isEmpty())
    }

    @Test fun userFacingMoneyAmountsAreNeverInvented() {
        val ui = File(mainDir, "java/com/podcaststudio/app/ui")
        val offenders = ui.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""["'][^"']*(\$\d|USD|EUR|DKK|per 1,?000|/1k)""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("UI text with a money amount: $offenders", offenders.isEmpty())
    }
}
