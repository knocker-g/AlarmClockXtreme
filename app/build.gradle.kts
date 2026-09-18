import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

// AlarmClockXtreme Personal Build (Single Variant)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.sysadmindoc.alarmclock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sysadmindoc.alarmclock"
        minSdk = 26
        targetSdk = 36
        versionCode = 136
        versionName = "1.15.34"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // Release signing - reads from keystore.properties (not committed to git)
    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    // Single variant build - distribution flavors removed for personal distribution.

    // Required for reproducible builds
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        error += "HardcodedText"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

val releaseArtifactTasks = setOf(
    "assembleRelease",
    "bundleRelease"
)

tasks.matching { it.name in releaseArtifactTasks }.configureEach {
    dependsOn(rootProject.tasks.named("verifyReleaseSigning"))
    dependsOn(rootProject.tasks.named("verifyReleaseMetadata"))
}

val verifyRoomSchemaExports by tasks.registering {
    group = "verification"
    description = "Reject Room schema exports changed by a debug build until they are reviewed and committed."
    dependsOn("kspDebugKotlin", "kspReleaseKotlin")

    doLast {
        fun runGit(vararg arguments: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git") + arguments.toList())
                .directory(rootProject.rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }

        val (workingTreeExit, workingTreeDiff) = runGit("diff", "--exit-code", "--", "app/schemas")
        check(workingTreeExit == 0) {
            "Room schema exports changed after the debug build. Review and commit app/schemas before continuing.\n$workingTreeDiff"
        }
        val (stagedExit, stagedDiff) = runGit("diff", "--cached", "--exit-code", "--", "app/schemas")
        check(stagedExit == 0) {
            "Room schema exports are staged but not committed. Review and commit app/schemas before continuing.\n$stagedDiff"
        }
        val (statusExit, status) = runGit("status", "--short", "--untracked-files=all", "--", "app/schemas")
        check(statusExit == 0 && status.isBlank()) {
            "Room schema exports contain untracked changes. Review and commit app/schemas before continuing.\n$status"
        }
    }
}

val unlocalizedComposeFiles = setOf(
    "data/support/SupportExportManager.kt",
    "service/WebhookService.kt",
    "data/health/StubHealthConnectSleepRepository.kt",
    "service/StubYouTubeAudioDownloader.kt",
    "ui/alarmfiring/challenges/StubDigitalInkChallengeRecognizer.kt"
)

val primaryComposeScreenFiles: List<File> = fileTree("src/main/java/com/sysadmindoc/alarmclock") {
    include("**/*.kt")
}.files
    .sortedBy { it.path }
    .filterNot { candidate ->
        unlocalizedComposeFiles.any { suffix ->
            candidate.invariantSeparatorsPath.endsWith(suffix)
        }
    }

val verifyLocalizedPrimaryScreens by tasks.registering {
    group = "verification"
    description = "Rejects literals passed to Text(), etc. anywhere except unlocalizedComposeFiles."
    inputs.files(primaryComposeScreenFiles)

    doLast {
        val uiTextAttributes = listOf(
            "text", "contentDescription", "title", "description", "supportingText",
            "onClickLabel", "stateDescription", "overline", "actionLabel", "summary",
            "statusLabel", "subtitle", "headline", "message", "placeholder", "hint",
            "caption", "helperText", "errorText", "emptyText", "value"
        ).joinToString("|")
        val directUiLiteralPatterns = listOf(
            Regex("""\b[A-Za-z]*Text\s*\(\s*"([^"\r\n]*)""""),
            Regex("""\b(?:$uiTextAttributes)\s*=\s*"([^"\r\n]*)""""),
            Regex("""Toast\.makeText\s*\([^,]*,\s*"([^"\r\n]*)""""),
            Regex("""\bshowSnackbar\s*\(\s*"([^"\r\n]*)""""),
            Regex(
                """\.set(?:ContentTitle|ContentText|SubText|Ticker|ContentDescription)""" +
                    """\s*\(\s*"([^"\r\n]*)""""
            )
        )
        val uiStateNames = "[A-Za-z]*(?:Status|Label|Message|Title|Hint|Summary|Caption)"
        val branchLiteralPatterns = listOf(
            Regex("""(?:->|\belse\b|\?|:)\s*"([^"\r\n]*)"\s*(?:\r?\n|,|\)|\})"""),
            Regex("""\bif\s*\([^()]*(?:\([^()]*\)[^()]*)*\)\s*"([^"\r\n]*)""""),
            Regex("""\breturn(?:@[A-Za-z_][A-Za-z0-9_]*)?\s+"([^"\r\n]*)""""),
            Regex("""\bif(?:Blank|Empty)\s*\{\s*"([^"\r\n]*)"\s*\}"""),
            Regex("""\b$uiStateNames\s*=\s*"([^"\r\n]*)"""")
        )
        val looksLikeCopy = Regex("""^[A-Z].*|.*\s.*""")
        val nonUiComposeLabels = setOf(
            "alarmPulse", "pulseScale", "pulseAlpha", "shake", "shakeAnim", "nfcPulse",
            "nfcAlpha", "sheep-drift", "sheep-drift-value", "icon_scale", "loading-card",
            "loading-alpha", "skeleton-block", "skeleton-alpha", "funnel", "funnel-rotation",
            "funnel-drift", "glowAlpha", "burnInDrift", "driftX", "driftY", "timer-pulse",
            "key-press-scale", "barcodeScan", "scanLine", "dotWidth\$index"
        )
        val animationLabelPattern = Regex("""\blabel\s*=\s*"([^"\r\n]*)"""")
        val interpolation = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")
        val wireConstant = Regex("""^[A-Z0-9_]+$""")
        val urlLiteral = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
        val dateFormatPattern = Regex("""^[hHmMsSaEdDMyLZzGwWkKubB:./,•\s'\-]+$""")
        val truncatedByNesting = Regex("""\$\{[^}]*$""")
        val translatableRun = Regex("""[A-Za-z]{3}""")
        val violations = mutableSetOf<String>()

        primaryComposeScreenFiles.forEach { sourceFile ->
            val source = sourceFile.readText()
            val report = { match: MatchResult, requireCopyShape: Boolean ->
                val literal = match.groupValues[1]
                val bare = interpolation.replace(literal, " ").trim()
                val isProse = translatableRun.containsMatchIn(bare) &&
                    !wireConstant.matches(bare) &&
                    !urlLiteral.containsMatchIn(bare) &&
                    !dateFormatPattern.matches(bare) &&
                    !truncatedByNesting.containsMatchIn(literal) &&
                    (!requireCopyShape || looksLikeCopy.matches(bare))
                if (isProse) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    violations += "${sourceFile.relativeTo(projectDir)}:$line: \"$literal\""
                }
            }
            directUiLiteralPatterns.forEach { pattern ->
                pattern.findAll(source).forEach { report(it, false) }
            }
            branchLiteralPatterns.forEach { pattern ->
                pattern.findAll(source).forEach { report(it, true) }
            }
            animationLabelPattern.findAll(source).forEach { match ->
                if (match.groupValues[1] !in nonUiComposeLabels) report(match, false)
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Hardcoded primary-screen UI text must use stringResource(...):\n" +
                    violations.sorted().joinToString("\n")
            )
        }
    }
}

tasks.matching { it.name == "check" || it.name.startsWith("lint") }.configureEach {
    dependsOn(verifyLocalizedPrimaryScreens)
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyRoomSchemaExports)
    dependsOn(rootProject.tasks.named("verifyReleaseMetadata"))
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Retrofit + Moshi
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Glance widget
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation(kotlin("reflect"))
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
