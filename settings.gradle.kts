pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "paper2binary"

// :core holds the scanning engine and has no Android dependency, so all of the
// image processing can be exercised by fast JVM tests.
include(":core")
include(":app")
