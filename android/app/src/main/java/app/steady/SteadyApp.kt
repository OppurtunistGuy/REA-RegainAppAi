package app.steady

import android.app.Application
import android.content.Context
import app.steady.di.AppContainer
import app.steady.di.DefaultAppContainer

class SteadyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

/** Convenience for services and activities: `context.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SteadyApp).container
