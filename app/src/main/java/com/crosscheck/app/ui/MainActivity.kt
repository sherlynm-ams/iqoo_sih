package com.crosscheck.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crosscheck.app.CrossCheckApp
import com.crosscheck.app.ui.theme.CrossCheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = CrossCheckApp.from(this).container
        setContent {
            CrossCheckTheme {
                CrossCheckNavHost(container)
            }
        }
    }
}
