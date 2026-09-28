import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is injected at build time. The first source is a keystore.properties file in the
// project root (git-ignored, so the key never lands in the repository); -Prelease.* properties and
// the matching RELEASE_* environment variables stay supported for CI. A plain local build without
// any of them keeps working and simply produces an unsigned release APK.
//
// Properties is imported explicitly: inside a Gradle Kotlin DSL script the bare name `java` resolves
// to the Java plugin's extension, so `java.util.Properties` does not compile here.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("release.keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.github.rove24.assistrestore"
    // Android 17 ships as a minor-version platform (android-37.0), so the minor level has to be
    // stated explicitly or AGP looks for a plain "android-37" directory that does not exist.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.github.rove24.assistrestore"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.3"
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Every release build also drops a copy of the APK in the project root under a stable name, so the
// packaged module is always at the same place instead of buried in build/outputs.
//
// Deliberately not a Copy task: declaring the project root as an output location trips Gradle's
// output-location validation, because that directory also holds .gradle/ and app/build/. A plain
// doLast action declares no outputs, so nothing conflicts.
val copyReleaseApk = tasks.register("copyReleaseApk") {
    group = "build"
    description = "Copies the release APK into the project root as AssistRestore_v<version>.apk"
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = apkDir.listFiles()?.firstOrNull {
            it.name == "app-release.apk" || it.name == "app-release-unsigned.apk"
        }
        if (apk == null) {
            logger.warn("copyReleaseApk: no release APK found in ${apkDir.absolutePath}")
            return@doLast
        }
        val target = rootProject.file("AssistRestore_v${android.defaultConfig.versionName}.apk")
        apk.copyTo(target, overwrite = true)
        logger.lifecycle("Release APK -> ${target.absolutePath}")
    }
}

// assembleRelease is created lazily by AGP, so it cannot be looked up with tasks.named here;
// matching by name binds to it whenever it appears.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApk)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Modern Xposed API 102 must stay compile-only; the framework provides it at runtime.
    compileOnly(project(":libxposed-api"))
    // Pinned to what the runtime classpath resolves: AGP's consistent resolution makes the compile
    // classpath strictly match it, so a higher version here fails the build outright.
    compileOnly("androidx.annotation:annotation:1.9.1")

    // Settings UI: Compose + Material 3.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Framework bridge: the settings UI writes the configuration the hooks read.
    implementation("io.github.libxposed:service:102.0.0")
}
