package com.example.nanochat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.example.nanochat.ui.navigation.NavGraph
import com.example.nanochat.ui.theme.NanoChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NanoChatApp

        setContent {
            NanoChatTheme {
                val navController = rememberNavController()
                NavGraph(
                    navController = navController,
                    chatRepository = app.chatRepository,
                    settingsRepository = app.settingsRepository,
                    presetRepository = app.presetRepository
                )
            }
        }
    }
}
