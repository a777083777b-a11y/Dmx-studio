package com.example.dmxstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Decoded image / video, scaled down. Each frame is ARGB pixels w*h.
class Frames(val w: Int, val h: Int, val fps: Int, val list: List<IntArray>)

// Pixel mapping of images and videos onto the fixture map (VSM-like)
object Media {
    private const val MAX_W = 96
    private const val MAX_FRAMES = 900
    private val cache = HashMap<String, Frames>()
    private val lock = Mutex()
    var progress by mutableStateOf("")

    suspend fun get(c: MediaClip): Frames? = lock.withLock {
        cache[c.uri + "@" + c.fps]?.let { return@withLock it }
        val ctx = Studio.appCtx ?: return@withLock null
        val fr = withContext(Dispatchers.IO) {
            try {
                if (c.isVideo) decodeVideo(ctx, Uri.parse(c.uri), c.fps) else decodeImage(ctx, Uri.parse(c.uri))
            } catch (e: Throwable) {
                progress = L("Xato: ", "Ошибка: ", "Error: ") + e.message
                null
            }
        }
        if (fr != null) {
            if (cache.size > 3) cache.clear()
            cache[c.uri + "@" + c.fps] = fr
        }
        fr
    }

    private fun small(b: Bitmap): Bitmap {
        val sw = b.width.coerceAtLeast(1)
        val sh = b.height.coerceAtLeast(1)
        val w = minOf(MAX_W, sw)
        val h = (sh.toLong() * w / sw).toInt().coerceAtLeast(1)
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        return if (soft.width == w && soft.height == h) soft else Bitmap.createScaledBitmap(soft, w, h, true)
    }

    private fun pixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun decodeImage(ctx: android.content.Context, uri: Uri): Frames? {
        progress = L("Rasm o'qilmoqda...", "Чтение изображения...", "Reading image...")
        val opt = BitmapFactory.Options()
        opt.inJustDecodeBounds = true
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        var sample = 1
        while (opt.outWidth / (sample * 2) >= MAX_W) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val s = small(bmp)
        progress = ""
        return Frames(s.width, s.height, 1, listOf(pixels(s)))
    }

    private fun decodeVideo(ctx: android.content.Context, uri: Uri, fps0: Int): Frames? {
        val fps = fps0.coerceIn(1, 30)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 160
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 90
            val w = minOf(MAX_W, vw.coerceAtLeast(1))
            val h = (vh.toLong() * w / vw.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val count = ((durMs * fps / 1000).toInt()).coerceIn(1, MAX_FRAMES)
            val list = ArrayList<IntArray>()
            for (i in 0 until count) {
                val us = i * 1_000_000L / fps
                val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (bmp != null) {
                    val s = small(bmp)
                    val px = if (s.width == w && s.height == h) pixels(s) else pixels(Bitmap.createScaledBitmap(s, w, h, true))
                    list.add(px)
                } else if (list.isNotEmpty()) {
                    list.add(list.last())
                }
                progress = L("Video tayyorlanmoqda: ", "Подготовка видео: ", "Preparing video: ") + (i + 1) + "/" + count
            }
            progress = ""
            if (list.isEmpty()) return null
            return Frames(w, h, fps, list)
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    // Output frame for time t: color under each fixture's map position
    fun sample(fr: Frames, t: Long): Map<Int, Int> {
        val idx = if (fr.list.size <= 1) 0 else ((t * fr.fps / 1000) % fr.list.size).toInt()
        val px = fr.list[idx]
        val fx = Studio.sortedFixtures(0)
        val pos = Studio.positions(fx)
        val frame = HashMap<Int, Int>()
        for ((i, f) in fx.withIndex()) {
            val (x, y) = pos[i]
            val cx = (x * (fr.w - 1)).toInt().coerceIn(0, fr.w - 1)
            val cy = (y * (fr.h - 1)).toInt().coerceIn(0, fr.h - 1)
            val c = px[cy * fr.w + cx]
            Studio.putRgb(frame, f, (c shr 16) and 255, (c shr 8) and 255, c and 255)
        }
        return frame
    }
}
