plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vibe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.malik05255.arabvpn"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // x86_64 is included only in debug so CI can boot/install the APK on an emulator.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP 9 uses built-in Kotlin. Kotlin directories must be registered on AndroidSourceSet.kotlin;
    // java.srcDirs and kotlin.sourceSets do not feed .kt files into compileDebugKotlin anymore.
    sourceSets {
        getByName("main") {
            kotlin.directories += "../app/src/main/kotlin/com/arabvpn/app"
            kotlin.directories += "../app/src/main/kotlin/com/vibe/app/vpn"
        }
        getByName("test") {
            kotlin.directories += "src/test/kotlin"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/native-image/**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.splashscreen)
    implementation(libs.kotlinx.serialization.json)

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.tencent.tinker:bsdiff-util:1.9.15.2")
    implementation("com.github.asterisk4magisk:libbox:v1.14.0-rc.4-reF1nd@aar")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
