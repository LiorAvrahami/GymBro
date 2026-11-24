// The Kotlin plugin, and the Android plugin unless GYMBRO_SKIP_ANDROID is set, are on the build's classpath here, so
// every module gets the same ones (the same setup as the 6SteP project).
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        if (System.getenv("GYMBRO_SKIP_ANDROID") == null) classpath("com.android.tools.build:gradle:8.5.2")
    }
}

allprojects {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
