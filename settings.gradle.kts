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
        // Tesseract4Android (text from pictures) is published there.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "VantaView"
include(":app")
