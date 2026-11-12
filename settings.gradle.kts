pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Only CI may enable a locally SHA-256-verified compiler artifact.
        System.getenv("CI_KOTLIN_COMPILER_REPO")?.let { localRepo ->
            maven {
                url = uri(localRepo)
                content { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "つぐレジ"
include(":app")
include(":customer-display")
include(":management-app")
