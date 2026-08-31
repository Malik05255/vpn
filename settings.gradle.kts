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

rootProject.name = "VibeApp"
include(":app")
include(":build-engine")
include(":build-tools:android-common-resources")
include(":build-tools:android-stubs")
include(":build-tools:build-logic")
include(":build-tools:common")
include(":build-tools:javac")
include(":build-tools:jaxp")
include(":build-tools:kotlinc")
include(":build-tools:logging")
include(":build-tools:manifmerger")
include(":build-tools:project")
include(":build-tools:snapshots")
include(":build-tools:jaxp:jaxp-internal")
include(":build-tools:jaxp:xml")
include(":shadow-runtime")
