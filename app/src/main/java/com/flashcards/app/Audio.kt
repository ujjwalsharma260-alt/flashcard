package com.flashcards.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

fun audioFile(ctx: Context, name: String) = File(File(ctx.filesDir, "audio"), name)

class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    private var file: File? = null

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        return try {
            val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
            val f = File(dir, UUID.randomUUID().toString() + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            mr = r
            file = f
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    /** Returns the saved file name, or null if recording failed. */
    fun stop(): String? {
        val r = mr ?: return null
        mr = null
        return try {
            r.stop()
            r.release()
            file?.name
        } catch (e: Exception) {
            try { r.release() } catch (e2: Exception) { }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val r = mr
        mr = null
        if (r != null) {
            try { r.stop() } catch (e: Exception) { }
            try { r.release() } catch (e: Exception) { }
            file?.delete()
        }
    }
}

object Player {
    private var mp: MediaPlayer? = null

    fun play(ctx: Context, name: String, onDone: () -> Unit) {
        stop()
        val f = audioFile(ctx, name)
        if (!f.exists()) {
            Toast.makeText(ctx, "Recording file is missing", Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        try {
            val m = MediaPlayer()
            m.setDataSource(f.absolutePath)
            m.setOnCompletionListener {
                Player.stop()
                onDone()
            }
            m.prepare()
            m.start()
            mp = m
        } catch (e: Exception) {
            stop()
            onDone()
        }
    }

    fun stop() {
        val m = mp
        mp = null
        if (m != null) {
            try { m.release() } catch (e: Exception) { }
        }
    }
}

private fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)

/** Recorder + player row used in the card editor. name = saved file name ("" if nothing recorded yet). */
@Composable
fun AudioItem(name: String, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val rec = remember { Recorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var secs by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    fun startRec() {
        if (rec.start()) { recording = true; secs = 0 }
        else Toast.makeText(ctx, "Could not start recording", Toast.LENGTH_SHORT).show()
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else Toast.makeText(ctx, "Microphone permission denied", Toast.LENGTH_LONG).show()
    }
    fun beginRecord() {
        Player.stop(); playing = false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(recording) { while (recording) { delay(1000); secs++ } }
    DisposableEffect(Unit) { onDispose { rec.cancel(); Player.stop() } }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            recording -> {
                Text("● Recording  ${fmt(secs)}", Modifier.weight(1f))
                Button(onClick = {
                    val n = rec.stop()
                    recording = false
                    if (n != null) onChange(n) else Toast.makeText(ctx, "Recording failed", Toast.LENGTH_SHORT).show()
                }) { Text("■ Stop") }
            }
            name.isBlank() -> {
                Text("Voice recording", Modifier.weight(1f))
                Button(onClick = { beginRecord() }) { Text("🎙 Record") }
            }
            else -> {
                Button(onClick = {
                    if (playing) { Player.stop(); playing = false }
                    else { playing = true; Player.play(ctx, name) { playing = false } }
                }) { Text(if (playing) "■ Stop" else "▶ Play") }
                OutlinedButton(onClick = {
                    audioFile(ctx, name).delete()
                    onChange("")
                    beginRecord()
                }) { Text("Re-record") }
                Spacer(Modifier.weight(1f))
            }
        }
        IconButton(onClick = {
            rec.cancel(); Player.stop(); playing = false
            if (name.isNotBlank()) audioFile(ctx, name).delete()
            onRemove()
        }) { Icon(Icons.Default.Delete, contentDescription = "Delete recording") }
    }
}

/** Play button used on the study screen. */
@Composable
fun AudioPlayButton(name: String) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(name) { onDispose { Player.stop() } }
    OutlinedButton(onClick = {
        if (playing) { Player.stop(); playing = false }
        else { playing = true; Player.play(ctx, name) { playing = false } }
    }) { Text(if (playing) "■ Stop" else "▶ Play recording") }
}
