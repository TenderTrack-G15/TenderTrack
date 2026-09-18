package za.ac.tendertrack

import android.app.Application
import android.util.Log
import za.ac.tendertrack.data.ServiceLocator

class TenderTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Touching the locator here surfaces a misconfigured Supabase project at
        // start-up rather than on the first screen that needs data.
        if (ServiceLocator.usingSampleData) {
            Log.i(
                "TenderTrack",
                "No SUPABASE_URL / SUPABASE_ANON_KEY set in gradle.properties — " +
                    "running on bundled sample data."
            )
        }
    }
}
