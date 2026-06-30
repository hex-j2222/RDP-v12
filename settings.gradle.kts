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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // NOTE: net.zetetic:android-database-sqlcipher is published directly on
        // mavenCentral() (including its Gradle module metadata), so the old
        // "https://www.zetetic.net/maven/" repository entry has been removed.
        // That host is no longer a reliable/maintained Maven repo for this
        // artifact and was causing dependency resolution to fail in CI
        // (manifesting as "Unresolved reference 'zetetic'" /
        // "Unresolved reference 'SQLiteDatabase'" / "Unresolved reference
        // 'SupportFactory'" during :app:compileDebugKotlin, even though the
        // dependency itself was correctly declared in libs.versions.toml).
    }
}

rootProject.name = "HexRDP"
include(":app")
