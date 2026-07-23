package com.abhishek.battlebucks

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The one owner of [AppContainer], and the only place that builds one.
 *
 * This class exists to hold the two Android-specific facts nothing else should have to know: that
 * there's one process, and when that process moves between foreground and background. Everything
 * below it (the container, the controller, both domain modules) is plain Kotlin and testable on a
 * JVM.
 */
class BattleBucksApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()

        // The match pauses with the app instead of burning a wake-up every 0.5-2s for a screen
        // nobody's looking at. Pausing rather than stopping matters here. The standings and the
        // engine's position survive, so coming back resumes mid-match instead of replaying the
        // whole thing from its seed with every score at zero.
        //
        // ProcessLifecycleOwner is per-process, not per-Activity, so this doesn't fire on rotation
        // or when moving between screens. Only when the app is really backgrounded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.matchController.resume()
                override fun onStop(owner: LifecycleOwner) = container.matchController.pause()
            },
        )
    }
}
