pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "AndroidLibBoxLiteGitHubRelease"
                    url = uri("https://github.com/Asterisk4Magisk/AndroidLibBoxLite/releases/download")
                    patternLayout {
                        artifact("[revision]/[artifact].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.github.asterisk4magisk", "libbox")
            }
        }
    }
}

rootProject.name = "ArabVPN"

// Canonical application. Legacy VibeApp directories remain only as source-history storage and are
// deliberately not Gradle modules, so none of their agent/builder/database dependencies can enter
// the Arab VPN build graph or APK.
include(":arabvpn")
