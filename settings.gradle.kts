// Two modules:
//   core  the app's logic in plain Kotlin (data model, JSON, station grid fitting, statistics): tested on the JVM
//   app   the Android app
rootProject.name = "GymBro"
include(":core")
// The Android app needs Google's package site and the Android SDK. Where they are not at hand (the cloud session that
// maintains this project has neither), GYMBRO_SKIP_ANDROID=1 leaves the app out; core still builds and tests.
if (System.getenv("GYMBRO_SKIP_ANDROID") == null) include(":app")
