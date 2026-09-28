package com.github.rove24.assistrestore.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Holds the module ↔ framework bridge.
 *
 * <p>The settings UI needs it to write the configuration the hooks read, and the presence of the
 * binder is also the only first-class signal that the framework actually loaded the module, which is
 * what the status card reports.</p>
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                listener.onServiceStateChanged(service)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(state: XposedService?) {
            for (listener in listeners) {
                listener.onServiceStateChanged(state)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (Companion.service === service) {
            Companion.service = null
        }
        dispatch(Companion.service)
    }
}
