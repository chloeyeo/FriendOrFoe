import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The ClikaRT Kotlin binding (io.clika:clika-runtime) is not on any public repository: it resolves from the
// directory where clika-runtime-maven-0.6.4.zip was extracted (it holds io/clika/clika-runtime/0.6.4/*).
// Lookup order: Gradle property clikaRtMavenRepo (-PclikaRtMavenRepo=... or gradle.properties) ->
// local.properties key clikaRtMavenRepo (gitignored, see local.properties.example) -> env CLIKA_RT_MAVEN_REPO ->
// the integration lead's path as the last resort.
val clikaRtMavenRepo: String = run {
    val fromProperty = providers.gradleProperty("clikaRtMavenRepo").orNull?.trim().orEmpty()
    if (fromProperty.isNotEmpty()) return@run fromProperty
    val localProperties = Properties()
    val localPropertiesFile = rootDir.resolve("local.properties")
    if (localPropertiesFile.isFile) localPropertiesFile.inputStream().use { localProperties.load(it) }
    val fromLocal = localProperties.getProperty("clikaRtMavenRepo")?.trim().orEmpty()
    if (fromLocal.isNotEmpty()) return@run fromLocal
    val fromEnv = System.getenv("CLIKA_RT_MAVEN_REPO")?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return@run fromEnv
    "C:/Users/C/clika"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = uri(clikaRtMavenRepo))
        // usb-serial-for-android (stream 2's USB CDC link) is published on JitPack only; nothing else may come from it.
        maven(url = uri("https://jitpack.io")) {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "FriendOrFoe"
include(":app")
