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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FileExplorer"

include(":app")
include(":core:model")
include(":core:data")
include(":core:storage")
include(":core:database")
include(":core:ui")
include(":core:designsystem")
include(":feature:browser")
include(":feature:transfer")
include(":feature:settings")
include(":feature:search")
include(":core:network")
include(":core:cloud")
include(":feature:network")
include(":feature:cloud")
include(":feature:security")
include(":feature:editor")
include(":feature:apps")
