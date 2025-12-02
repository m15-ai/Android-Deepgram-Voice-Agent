package com.m15.deepgramagent

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m15.deepgramagent.ui.theme.DeepgramTheme

class MainActivity : ComponentActivity() {

    // No custom factory needed; VM pulls from ServiceLocator
    private val vm by viewModels<VoiceAgentViewModel>()

    private var sessionStarted = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && !sessionStarted) {
            sessionStarted = true
            vm.startSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for mic permission; start session on grant
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            DeepgramTheme {
                val uiState by vm.ui.collectAsStateWithLifecycle()

                VoiceAgentScreen(
                    ui = uiState,
                    isSpeakerOn = uiState.speakerOn,
                    onSpeakerToggle = { vm.toggleSpeaker() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopSession()
    }
}
