package com.example.dmxstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Studio.start(applicationContext)
        if (Engine.schedOn || Studio.backgroundWanted) {
            try {
                DmxService.start(this)
            } catch (e: Exception) {
            }
        }
        setContent {
            MaterialTheme {
                Surface { AppRoot() }
            }
        }
    }
}
