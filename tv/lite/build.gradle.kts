import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Aerial Lite: Home for Android 4.0-4.4 TVs, which the Compose app cannot run on (Compose needs
// Android 5.0). Plain Views, no AndroidX, so the APK stays small and Dalvik has little to load.
// Performance comes before looks here: see docs/LITE.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.xingmu.aerial"
    compileSdk = 36

    defaultConfig {
        // Its own package: the 4.x TVs never get the full app, and the two update separately.
        applicationId = "app.xingmu.aerial"
        // -PcastTestApk installs beside the official Lite instead of replacing it: an old TV can be
        // tried without uninstalling the signed release (which would drop the launcher's settings).
        if (project.hasProperty("castTestApk")) {
            applicationId = "app.xingmu.aerial.cast"
        }
        // Android 4.0.
        minSdk = 14
        targetSdk = 34
        versionCode = 32
        versionName = "1.30"
        // Test hook for the built-in agent: -PcastBackendUrl=http://192.168.1.10:18000 bakes the
        // backend address into a debug build so a TV with no keyboard can still be pointed at it.
        buildConfigField(
            "String",
            "CAST_BACKEND_URL",
            "\"${(project.findProperty("castBackendUrl") as String? ?: "").replace("\"", "")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    // Signed with the same key as Aerial (see app/build.gradle.kts and docs/HANDOFF.md).
    val signingProps = rootProject.file("local/signing.properties")
    signingConfigs {
        if (signingProps.exists()) {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            create("aerial") {
                storeFile = rootProject.file("local/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("aerial") ?: signingConfigs.getByName("debug")
        }
    }

    // The Apple aerial catalog is shared with the full app.
    sourceSets["main"].assets.srcDir("lite-assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Old firmwares are the whole point; NewApi still guards every call above Android 4.0.
        disable += setOf("OldTargetApi", "ExpiredTargetSdkVersion")
    }
    packaging {
        resources.excludes += setOf("/META-INF/**", "kotlin/**", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Shared with the full app and copied from it at build time rather than kept twice: the aerial
// catalog, the Inter font and the Chinese city list (assets), the glyphs' path data, the hand-made
// tile recipes and the city lookup (sources, moved into Lite's package).
val copyAerials by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets/aerials.json"))
    from(rootProject.file("app/src/main/res/font/inter.ttf"))
    from(rootProject.file("app/src/main/assets/cities-cn.tsv"))
    // Per-model corrections, read by DeviceProfile (MitvPanel and Brightness ask it).
    from(rootProject.file("app/src/main/assets/devices.json"))
    // The phone page, the full app's own.
    from(rootProject.file("app/src/main/assets/web")) { into("web") }
    into(layout.projectDirectory.dir("lite-assets"))
}
val sharedSources = layout.buildDirectory.dir("generated/shared")
val copySources by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/java/app/orchard/tv/ui/theme/GlyphPaths.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/apps/TileRecipes.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/weather/Cities.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/apps/LayoutStore.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/apps/AppNames.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/apps/CustomIcons.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/wallpaper/ScreensaverVideos.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/system/MitvPanel.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/system/DeviceProfile.kt"))
    from(rootProject.file("app/src/main/java/app/orchard/tv/system/OutputDevice.kt"))
    into(sharedSources.map { it.dir("app/xingmu/aerial") })
    // Into Lite's package; Compose's state and AndroidX's prefs.edit {} come from Lite's Shims.kt.
    filter { line ->
        when {
            line.startsWith("import androidx.compose.runtime.") || line == "import androidx.core.content.edit" -> ""
            line.startsWith("import app.orchard.tv.") -> ""
            else -> line.replace(Regex("^package app\\.orchard\\.tv(\\.[a-z.]+)?$"), "package app.xingmu.aerial")
                .replace("app.orchard.tv.ui.theme.Glyph", "app.xingmu.aerial.Glyph")
        }
    }
}
android.sourceSets["main"].java.srcDir(sharedSources)
// The full app's wording in all five languages, so menus and Control Center say the same thing.
val sharedRes = layout.buildDirectory.dir("generated/sharedRes")
val copyStrings by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/res")) {
        include("values*/strings.xml")
        rename("strings.xml", "app_strings.xml")
    }
    into(sharedRes)
    // Lite keeps its own name.
    filter { line -> if (line.contains("name=\"app_name\"")) "" else line }
}
android.sourceSets["main"].res.srcDir(sharedRes)
tasks.named("preBuild") { dependsOn(copyAerials, copySources, copyStrings) }

dependencies {
    // The phone page's server and its QR code. ZXing 3.3.0, the last before it used
    // StandardCharsets, which Android only has from 4.4.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.3.0")
    // The last OkHttp that runs on Android 4.x; the platform there has no WebSocket client.
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    // HLS and adaptive streams on 4.x TVs: the platform player cannot do it, ExoPlayer can.
    // ijkplayer: the engine that decodes HLS with the TV hardware codecs. The 4.4 box needs this:
    // its WebView renders video black and the platform MediaPlayer cannot do HLS at all.
    implementation("io.github.jdpxiaoming:ijkplayer-java:0.0.26")
    implementation("io.github.jdpxiaoming:ijkplayer-armv7a:0.0.26")
    implementation("io.github.jdpxiaoming:ijkplayer-arm64:0.0.26")
    implementation("com.google.android.exoplayer:exoplayer-core:2.18.7")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.7")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.18.7")
    testImplementation("junit:junit:4.13.2")
}

// `./gradlew :lite:assembleRelease -Pnohome`: a build for trying Lite on a TV that runs the full
// app, as a plain app from Aerial's grid. Without the HOME declaration it leaves the TV's choice
// of Home alone (a new Home candidate can reset it).
if (project.hasProperty("nohome")) {
    val noHomeManifest = layout.buildDirectory.file("generated/nohome/AndroidManifest.xml")
    val stripHome by tasks.registering {
        val src = file("src/main/AndroidManifest.xml")
        inputs.file(src)
        outputs.file(noHomeManifest)
        doLast {
            val text = src.readText().replace(
                Regex("""\s*<intent-filter>\s*<action android:name="android.intent.action.MAIN" />\s*<category android:name="android.intent.category.HOME" />.*?</intent-filter>""", RegexOption.DOT_MATCHES_ALL),
                "",
            )
            check(!text.contains("category.HOME")) { "the HOME filter is still there" }
            noHomeManifest.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        }
    }
    android.sourceSets["main"].manifest.srcFile(noHomeManifest)
    tasks.named("preBuild") { dependsOn(stripHome) }
}
