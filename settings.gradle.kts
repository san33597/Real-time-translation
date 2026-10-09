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
        google()
        mavenCentral()
        // Official sherpa AAR installed locally with SHA-256 verification.
        maven { url = uri(".tooling/maven") }
    }
}
rootProject.name = "RealtimeTranslator"
include(":app", ":core-model", ":core-audio", ":core-asr", ":service")
