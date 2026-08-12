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
        // JitPack hosts NewPipeExtractor (org.schabi.newpipe:extractor is not on
        // Maven Central). Only the TeamNewPipe artifacts come from here.
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.TeamNewPipe")
            }
        }
    }
}

rootProject.name = "TurboDM"
include(":app")
