package montafra.beam

import android.app.Application

class BeamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        migrateAppLanguage(this)
    }
}
