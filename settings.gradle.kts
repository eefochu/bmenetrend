pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BMenetrend"
include(":app")
// Optional MÁV tickets feature: present folder = feature on, missing folder = feature off.
// `-PnoTickets` builds without it even when the folder is present.
if (file("mav-tickets/build.gradle.kts").exists() && providers.gradleProperty("noTickets").orNull == null) include(":mav-tickets")
