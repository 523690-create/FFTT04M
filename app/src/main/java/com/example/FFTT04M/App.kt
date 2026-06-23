package com.example.FFTT04M

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * App-wide edge-to-edge inset handling.
 *
 * Apps targeting SDK 35+ are forced edge-to-edge on Android 15+, so content draws UNDER the status and
 * navigation bars — the overlap seen on the Pixel 10 (Android 16). Per-layout `fitsSystemWindows` is
 * unreliable here (only some layouts had it, and the attribute behaves differently across OS versions),
 * so instead every activity's content view is padded by the system-bar insets here, once, uniformly.
 *
 * This is correct on BOTH worlds: on older devices (Pixel 3a / Android 12) the window already fits
 * system windows, so by the time insets reach the content view they're 0 and this pads by nothing
 * (the decor already inset the content); on Android 15+ the insets are non-zero and this applies them.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {
                // Force edge-to-edge on EVERY OS version so behaviour is uniform: the decor never insets
                // the content itself, so the listener below is the single source of inset padding (no
                // double-inset on legacy windows, no overlap on Android 15+).
                WindowCompat.setDecorFitsSystemWindows(a.window, false)
                val content = a.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
                    WindowInsetsCompat.CONSUMED
                }
                ViewCompat.requestApplyInsets(content)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
