package za.ac.tendertrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import za.ac.tendertrack.ui.nav.TenderTrackNavGraph
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
                    TenderTrackNavGraph()
                }
            }
        }
    }
}
