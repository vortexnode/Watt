package montafra.beam

import android.app.Application

class WattApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        migrateAppLanguage(this)
    }
}
