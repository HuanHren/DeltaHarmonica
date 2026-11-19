pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\.testing(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(".tools/maven") }
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "DeltaHarmonica"
include(":app")

// Fail closed before any task executes. Device tasks remain disabled until the
// isolated APK/serial preflight can be enforced for every Gradle entry point.
gradle.taskGraph.whenReady {
    val unsafe = allTasks.filter { task ->
        val name = task.name.lowercase()
        val type = task.javaClass.name
        name.startsWith("connected") || name.startsWith("install") || name.startsWith("uninstall") ||
            name.contains("device") || name.contains("adb") ||
            type.contains("DeviceProviderInstrumentTestTask") || type.contains("ManagedDeviceInstrumentationTestTask") ||
            type.contains("InstallVariantTask") || type.contains("UninstallTask")
    }
    if (unsafe.isNotEmpty()) throw GradleException("SAFE-1: device tasks are blocked before execution: " + unsafe.joinToString { it.path })
}

