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

rootProject.name = "orator"
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:navigation")
include(":core:playback")
include(":core:database")
include(":core:network")
include(":feature:player")
include(":feature:audiobooks")
include(":feature:settings")
