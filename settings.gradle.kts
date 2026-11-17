pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MightyS Pulse"
include(":pulse-core", ":pulse-android", ":sample")
// An internal preview app that shows every App Control screen; it is not part of the public tree.
if (file("control-preview").isDirectory) include(":control-preview")
