package world.syncloom

import android.app.Application

class SyncloomApp : Application() {
    lateinit var store: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
    }
}
