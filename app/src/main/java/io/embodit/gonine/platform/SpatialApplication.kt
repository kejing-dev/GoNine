package io.embodit.gonine.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.gonine.mainApp

class SpatialApplication : Application() {
    lateinit var graph: GoGraph
        private set

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        graph = GoGraph(this)
        // Background / foreground: stop thinking and save when the last activity stops (design lifecycle).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) graph.flow.onForeground() }
            override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; graph.flow.onBackground() } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        launch(::mainApp)
    }
}
