import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

fun secret(key: String, fallback: String? = null): String? =
    System.getenv(key) ?: keystoreProps.getProperty(key) ?: fallback

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) load(FileInputStream(f))
}
val baseMajor = versionProps.getProperty("major", "1").toInt()
val baseMinor = versionProps.getProperty("minor", "0").toInt()
val autoVersionCode = if (runNumber > 0) 10000 + runNumber
                      else (System.currentTimeMillis() / 60_000L).toInt()
val autoVersionName = "$baseMajor.$baseMinor.$runNumber"

android {
    namespace = "com.vpnproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vpnproxy"
        minSdk = 26
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = autoVersionName

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }

        buildConfigField(
            "String", "GITHUB_OWNER",
            "\"${System.getenv("GITHUB_REPOSITORY_OWNER") ?: "valdissor1990-ui"}\""
        )
        buildConfigField(
            "String", "GITHUB_REPO",
            "\"${System.getenv("GITHUB_REPOSITORY")?.substringAfter("/") ?: "vladossVPN"}\""
        )
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution"; applicationIdSuffix = ".direct" }
        create("play")   { dimension = "distribution"; applicationIdSuffix = ".play" }
    }

    signingConfigs {
        create("release") {
            val ksPath = secret("KEYSTORE_PATH")
            val ksFile = ksPath?.let { file(it) }
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
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
            buildConfigField("boolean", "LOG_DEBUG", "false")
            signingConfig = if (signingConfigs.findByName("release")?.storeFile != null)
                signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "LOG_DEBUG", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // ★ ABI splits — уменьшает APK с 60+ MB до ~25 MB на arm64
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true   // плюс универсальный APK для GitHub Releases
        }
    }

    // ★ Упаковка: pickFirsts решает конфликты нативных .so
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Full sing-box-lx 1.14.3-lx.21 AAR with XHTTP and Go bridge classes.
    implementation(files("libs/libbox.aar"))

    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
