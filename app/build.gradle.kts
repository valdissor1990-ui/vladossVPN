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

// --- Чтение keystore.properties (локально) или env (CI) ---
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

fun secret(key: String, fallback: String? = null): String? =
    System.getenv(key) ?: keystoreProps.getProperty(key) ?: fallback

// --- Автоверсия ---
// versionCode: берём из github.run_number (инкремент на каждый CI-запуск),
//              локально — Unix-время / 60 (уникально в пределах минуты).
// versionName: MAJOR.MINOR из файла version.properties + .run_number как PATCH.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) load(FileInputStream(f))
}
val baseMajor = versionProps.getProperty("major", "1").toInt()
val baseMinor = versionProps.getProperty("minor", "0").toInt()
val autoVersionCode = if (runNumber > 0) {
    10000 + runNumber  // 10001, 10002, ...
} else {
    (System.currentTimeMillis() / 60_000L).toInt()
}
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

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        buildConfigField("String", "GITHUB_OWNER", "\"${System.getenv("GITHUB_REPOSITORY_OWNER") ?: "valdissor1990-ui"}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${System.getenv("GITHUB_REPOSITORY")?.substringAfter("/") ?: "vladossVPN"}\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            applicationIdSuffix = ".direct"
        }
        create("play") {
            dimension = "distribution"
            applicationIdSuffix = ".play"
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = secret("KEYSTORE_PATH")
            val ksBase64 = secret("KEYSTORE_BASE64")
            val storeFileResolved = when {
                ksPath != null && file(ksPath).exists() -> file(ksPath)
                ksBase64 != null -> {
                    // Декодируем base64 keystore во временный файл (для CI)
                    val tmp = File(rootProject.buildDir, "release.keystore")
                    tmp.parentFile.mkdirs()
                    tmp.writeBytes(java.util.Base64.getDecoder().decode(ksBase64))
                    tmp
                }
                else -> null
            }
            if (storeFileResolved != null) {
                storeFile = storeFileResolved
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
            // Если keystore настроен — подписываем; иначе — debug (для локальной сборки)
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf("**/libxray.so", "**/libhev-socks5-tunnel.so")
        }
    }
}

dependencies {
    // Compose
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

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // YAML (Clash)
    implementation("org.yaml:snakeyaml:2.3")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
