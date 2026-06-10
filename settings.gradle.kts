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
    }
}

rootProject.name = "akouo"
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:navigation")
include(":core:playback")
include(":feature:player")
