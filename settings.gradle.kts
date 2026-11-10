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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "restofleet"

include(":tools:dummy-waiter", ":shared:kernel", ":shared:protocol", ":console:core:mvi", ":agent:domain", ":agent:data", ":agent:presentation", ":agent:app", ":konsist-tests", ":spikes:broker-load")
