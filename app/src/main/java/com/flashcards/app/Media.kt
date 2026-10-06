@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStore {
    private const val MAX = 1600

    fun dir(ctx: Context): File = File(ctx.filesDir, "images").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Reads an image from any Uri, fixes rotation, shrinks it to at most 1600px, saves it as JPEG. Returns the file name or null. */
    fun saveFromUri(ctx: Context, uri: Uri): String? {
        try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val rot = try { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (e: Exception) { 0 }
            if (bmp.hasAlpha()) {
                val nb = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val cv = Canvas(nb)
                cv.drawColor(android.graphics.Color.WHITE)
                cv.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
                bmp = nb
            }
            val big = maxOf(bmp.width, bmp.height)
            val scale = if (big > MAX) MAX.toFloat() / big else 1f
            if (scale < 1f || rot != 0) {
                val m = Matrix()
                m.postScale(scale, scale)
                if (rot != 0) m.postRotate(rot.toFloat())
                val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (nb !== bmp) bmp.recycle()
                bmp = nb
            }
            val name = UUID.randomUUID().toString() + ".jpg"
            FileOutputStream(file(ctx, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            return name
        } catch (e: Throwable) {
            return null
        }
    }

    /** The image Uri on the clipboard, or null if the clipboard holds no image. */
    fun clipboardImageUri(ctx: Context): Uri? {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val uri = clip.getItemAt(0).uri ?: return null
            val isImage = clip.description.hasMimeType("image/*") || ctx.contentResolver.getType(uri)?.startsWith("image/") == true
            return if (isImage) uri else null
        } catch (e: Exception) {
            return null
        }
    }

    fun load(ctx: Context, name: String, maxSide: Int): Bitmap? {
        try {
            val f = file(ctx, name)
            if (!f.exists()) return null
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Throwable) {
            return null
        }
    }
}

@Composable
fun ImageThumb(name: String, modifier: Modifier = Modifier, maxSide: Int = 1000, onClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val state by produceState<Pair<Boolean, Bitmap?>>(Pair(false, null), name) {
        value = Pair(true, withContext(Dispatchers.IO) { ImageStore.load(ctx, name, maxSide) })
    }
    val bmp = state.second
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = if (onClick != null) modifier.clickable { onClick() } else modifier
        )
    } else {
        Box(modifier.height(60.dp), contentAlignment = Alignment.Center) {
            Text(if (state.first) "Image missing" else "…")
        }
    }
}

/** Full-screen image with pinch-to-zoom and pan. */
@Composable
fun ZoomDialog(name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var off by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ImageThumb(
                name,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            off = if (scale <= 1f) Offset.Zero else off + pan
                        }
                    },
                maxSide = 2200
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
