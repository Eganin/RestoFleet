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

include(":tools:dummy-waiter", ":shared:kernel", ":shared:protocol", ":shared:shadow", ":shared:probes", ":agent:domain", ":agent:data", ":agent:presentation", ":agent:app", ":server:domain", ":server:data", ":server:api", ":server:app", ":server:simulator", ":konsist-tests", ":spikes:broker-load")

// Модули консоли (этап П) ведёт поток B в отдельном файле, чтобы параллельные ветки не правили этот (реш. 407)
apply(from = "console/modules.settings.gradle.kts")
