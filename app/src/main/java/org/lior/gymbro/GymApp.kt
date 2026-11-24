package org.lior.gymbro

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import java.util.Locale
import java.util.TimeZone

class GymApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(filesDir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try { AppLog.e("crash", "the app crashed", err, "thread" to thread.name) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, err)
        }
        AppLog.i("app", "started", *deviceInfo().toList().toTypedArray())
        Repo.init(this)
        registerActivityLifecycleCallbacks(Lifecycle)
    }

    fun deviceInfo(): Map<String, Any?> {
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val dm = resources.displayMetrics
        return linkedMapOf(
            "version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "firstInstall" to pi?.firstInstallTime,
            "lastUpdate" to pi?.lastUpdateTime,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "screen" to "${dm.widthPixels}x${dm.heightPixels} @${dm.density}",
            "locale" to Locale.getDefault().toString(),
            "timezone" to TimeZone.getDefault().id,
            "session" to AppLog.session,
        )
    }

    /** Every screen's comings and goings go to the log. */
    private object Lifecycle : ActivityLifecycleCallbacks {
        private fun log(a: Activity, what: String) = AppLog.i("screen", "${a.javaClass.simpleName} $what")
        override fun onActivityCreated(a: Activity, b: Bundle?) = log(a, if (b == null) "created" else "re-created")
        override fun onActivityStarted(a: Activity) = log(a, "started")
        override fun onActivityResumed(a: Activity) = log(a, "resumed")
        override fun onActivityPaused(a: Activity) = log(a, "paused")
        override fun onActivityStopped(a: Activity) { log(a, "stopped"); Repo.syncLog() }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) = log(a, "destroyed")
    }
}
