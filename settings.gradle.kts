pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Rotom"
include(":app")
include(":core:model")
include(":core:network")
include(":core:ui")
include(":feature:attendance")
include(":feature:library")

include(":feature:leave")
