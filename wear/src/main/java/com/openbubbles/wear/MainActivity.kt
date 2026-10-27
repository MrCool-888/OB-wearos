package com.openbubbles.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.openbubbles.wear.ui.WearApp
import com.openbubbles.wear.ui.WearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRepository.attach(applicationContext)
        setContent {
            WearTheme {
                WearApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WatchRepository.attach(applicationContext)
        WatchRepository.onAppResumed()
    }
}
