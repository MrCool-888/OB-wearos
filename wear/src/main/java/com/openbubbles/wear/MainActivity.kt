package com.openbubbles.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.openbubbles.wear.ui.WearApp
import com.openbubbles.wear.ui.WearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRepository.attach(applicationContext)
        handleNavigationIntent(intent)
        setContent {
            WearTheme {
                WearApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        WatchRepository.attach(applicationContext)
        WatchRepository.onAppResumed()
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val threadId = intent?.getStringExtra(WatchNavigation.EXTRA_THREAD_ID)
            ?: intent?.getStringExtra("thread_id")
        WatchNavigation.requestOpen(threadId)
    }
}
