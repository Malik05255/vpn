plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

val githubOAuthClientId = providers.gradleProperty("GITHUB_OAUTH_CLIENT_ID")
    .orElse(providers.environmentVariable("GITHUB_OAUTH_CLIENT_ID"))
    .orElse("")

val githubOAuthClientSecret = providers.gradleProperty("GITHUB_OAUTH_CLIENT_SECRET")
    .orElse(providers.environmentVariable("GITHUB_OAUTH_CLIENT_SECRET"))
    .orElse("")

val githubOAuthRedirectUri = providers.gradleProperty("GITHUB_OAUTH_REDIRECT_URI")
    .orElse(providers.environmentVariable("GITHUB_OAUTH_REDIRECT_URI"))
    .orElse("lmai://github-oauth")

val openRouterOAuthCallbackUrl = providers.gradleProperty("OPENROUTER_OAUTH_CALLBACK_URL")
    .orElse(providers.environmentVariable("OPENROUTER_OAUTH_CALLBACK_URL"))
    .orElse("lmai://openrouter-oauth")

val googleWebClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID")
    .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID"))
    .orElse("")

val googleAndroidSha1 = providers.gradleProperty("GOOGLE_ANDROID_SHA1")
    .orElse(providers.environmentVariable("GOOGLE_ANDROID_SHA1"))
    .orElse("")

val googleMapsApiKey = providers.gradleProperty("GOOGLE_MAPS_API_KEY")
    .orElse(providers.environmentVariable("GOOGLE_MAPS_API_KEY"))
    .orElse("")

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

android {
    namespace = "com.malik.lmai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.malik05255.lmai.hlab"
        minSdk = 29
        targetSdk = 36
        versionCode = 20101
        versionName = "2.1.1"

        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"${githubOAuthClientId.get()}\"")
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_SECRET", "\"${githubOAuthClientSecret.get()}\"")
        buildConfigField("String", "GITHUB_OAUTH_REDIRECT_URI", "\"${githubOAuthRedirectUri.get()}\"")
        buildConfigField("String", "OPENROUTER_OAUTH_CALLBACK_URL", "\"${openRouterOAuthCallbackUrl.get()}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${googleWebClientId.get()}\"")
        buildConfigField("String", "GOOGLE_ANDROID_SHA1", "\"${googleAndroidSha1.get()}\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${googleMapsApiKey.get()}\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en", "ar")
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.configureEach {
    exclude(group = "com.intellij", module = "annotations")
}

dependencies {
    implementation(project(":build-engine"))
    implementation(project(":shadow-runtime"))

    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)

    implementation(libs.androidx.navigation)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation(libs.ktor.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)
    implementation(libs.ktor.logging)

    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.jsoup)

    implementation("androidx.credentials:credentials:1.7.0-alpha03")
    implementation("androidx.credentials:credentials-play-services-auth:1.7.0-alpha03")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    // 6.12.0 stays compatible with the app's compileSdk 36 / AGP 9.1 baseline.
    // Newer 8.x releases currently require Android API 37.
    implementation("com.google.maps.android:maps-compose:6.12.0")
    implementation("com.google.api-client:google-api-client-android:2.7.2")
    implementation("com.google.apis:google-api-services-drive:v3-rev20250220-2.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    debugImplementation(libs.chucker.debug)
    releaseImplementation(libs.chucker.release)

    implementation(libs.compose.markdown)
    implementation(libs.compose.markdown.code)

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.13.12")
}
