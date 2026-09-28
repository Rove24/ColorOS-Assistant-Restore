package com.github.rove24.assistrestore.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * UI preview shell for the module's settings screens.
 *
 * This activity only renders the screens drawn in the M3E Canvas sketch and reads a couple of
 * read-only facts (installed assistant apps, current default assistant). It deliberately does not
 * touch the hook side of the module.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AssistRestoreTheme {
                AssistRestoreApp()
            }
        }
    }
}
