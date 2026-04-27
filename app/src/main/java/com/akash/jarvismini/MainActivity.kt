package com.akash.jarvismini

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.akash.jarvismini.ui.theme.JarvisMiniTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisMiniTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    JarvisScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun JarvisScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember { AudioRecorder(context) }
    val api = remember { JarvisApi() }
    val tts = remember { JarvisTts(context) }
    val spotify = remember { SpotifyController(context) }
    DisposableEffect(Unit) {
        onDispose {
            tts.shutdown()
            spotify.shutdown()
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var isRecording by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = {
                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                if (isRecording) {
                    val file = recorder.stop()
                    isRecording = false
                    if (file == null) {
                        error = "Recording was too short."
                        return@Button
                    }
                    isThinking = true
                    error = null
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) { api.command(file) }
                            transcript = response.transcript
                            reply = response.reply
                            tts.speak(response.reply)
                            Log.d("Jarvis", "got ${response.phoneActions.size} phone actions: ${response.phoneActions}")
                            response.phoneActions.forEach { spotify.dispatch(it) }
                        } catch (e: Exception) {
                            error = e.message ?: "Unknown error"
                        } finally {
                            isThinking = false
                        }
                    }
                } else {
                    try {
                        val file = File(context.cacheDir, "recording.m4a")
                        recorder.start(file)
                        isRecording = true
                        error = null
                    } catch (e: Exception) {
                        error = "Couldn't start recording: ${e.message}"
                    }
                }
            },
            enabled = !isThinking,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.size(width = 200.dp, height = 60.dp),
        ) {
            Text(
                text = when {
                    isThinking -> "Thinking..."
                    isRecording -> "Tap to Stop"
                    !hasPermission -> "Grant Mic Permission"
                    else -> "Tap to Record"
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        error?.let {
            Text(text = "Error: $it", color = Color.Red)
            Spacer(Modifier.height(16.dp))
        }

        if (transcript.isNotEmpty()) {
            Text(text = "You said:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(text = transcript, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }

        if (reply.isNotEmpty()) {
            Text(text = "Jarvis:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(text = reply, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
