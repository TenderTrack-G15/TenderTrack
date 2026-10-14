package za.ac.tendertrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import za.ac.tendertrack.ui.nav.TenderTrackNavGraph
import za.ac.tendertrack.ui.splash.TenderTrackSplash
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.TenderTrackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TenderTrackTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppColor.Background),
                    color = AppColor.Background
                ) {
                    // Shown once per app start: not again after rotating the phone or
                    // coming back to the app.
                    var showSplash by rememberSaveable { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize()) {
                        // The landing page loads underneath while the splash plays,
                        // so it is ready the moment the splash fades away.
                        TenderTrackNavGraph()
                        if (showSplash) TenderTrackSplash(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }
}
