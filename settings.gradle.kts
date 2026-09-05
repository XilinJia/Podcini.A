
pluginManagement {
    repositories {
//       mavenLocal() // 👈 Add this line
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

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}

// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
// }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
//       mavenLocal() // 👈 Add this line
        google()
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
}

include(":app")
