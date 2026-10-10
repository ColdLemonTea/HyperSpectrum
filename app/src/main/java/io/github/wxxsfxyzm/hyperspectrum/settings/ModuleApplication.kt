// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.settings

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Collections
import java.util.IdentityHashMap

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(XposedService?) -> Unit>()
    private val services = mutableListOf<XposedService>()
    private val dead = Collections.newSetFromMap(IdentityHashMap<XposedService, Boolean>())
    private var current: XposedService? = null

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        main.post {
            if (synchronized(dead) { dead.contains(service) }) return@post
            services.removeAll { it === service }
            services.add(service)
            publish(service)
        }
    }

    override fun onServiceDied(service: XposedService) {
        synchronized(dead) { dead.add(service) }
        main.post {
            services.removeAll { it === service }
            if (current === service) publish(services.lastOrNull())
        }
    }

    fun observe(listener: (XposedService?) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeObserver(listener: (XposedService?) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(service: XposedService?) {
        if (current === service) return
        current = service
        listeners.toList().forEach { it(service) }
    }
}
