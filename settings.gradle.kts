pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "ble_attendance"
include(":app")
include(":core:model")
include(":core:network")
include(":core:ui")
include(":feature:attendance")
include(":feature:library")
