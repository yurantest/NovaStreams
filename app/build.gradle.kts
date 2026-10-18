import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Client ID приложения Twitch (публичное значение, не секрет).
// Источники по порядку: local.properties -> gradle.properties/-P -> пусто (тогда ID вводится в приложении).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val twitchClientId: String =
    localProps.getProperty("TWITCH_CLIENT_ID")
        ?: providers.gradleProperty("TWITCH_CLIENT_ID").orNull
        ?: ""
val twitchRedirectUri: String =
    localProps.getProperty("TWITCH_REDIRECT_URI")
        ?: providers.gradleProperty("TWITCH_REDIRECT_URI").orNull
        // Должен совпадать с OAuth Redirect URL в консоли Twitch (по умолчанию https://localhost, как в README).
        ?: "https://localhost"

android {
    namespace = "com.novastreams.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novastreams.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${twitchClientId.trim()}\"")
        // Адрес перенаправления после входа. Он должен ТОЧНО совпадать с «OAuth Redirect URL» в консоли Twitch.
        buildConfigField("String", "TWITCH_REDIRECT_URI", "\"${twitchRedirectUri.trim()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    // Chrome Custom Tabs: страница подтверждения входа открывается поверх приложения, «Назад» возвращает в него.
    implementation(libs.androidx.browser)
    // QR-код на экране входа (только кодирование, без камеры).
    implementation(libs.zxing.core)
    // Свой плеер: ExoPlayer (Media3) с поддержкой HLS; media3-ui даёт только поверхность для видео.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
