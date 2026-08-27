package com.commentary.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

class MediaFailure(message: String) : Exception(message)

/* ---------------------------------------------------------------
   BASIC FILE FACTS
   --------------------------------------------------------------- */

object FileFacts {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) {
                    val v = c.getString(i)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "video"
    }

    fun sizeBytes(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) return c.getLong(i)
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    fun readAllBytes(context: Context, uri: Uri, limitBytes: Long): ByteArray {
        val size = sizeBytes(context, uri)
        if (size > limitBytes) {
            throw MediaFailure(
                "This video is ${size / 1_000_000} MB. The limit for this mode is " +
                    "${limitBytes / 1_000_000} MB. Trim or compress it."
            )
        }
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw MediaFailure("Could not read the selected video.")
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/* ---------------------------------------------------------------
   VIDEO PROBE
   --------------------------------------------------------------- */

data class VideoFacts(
    val durationMs: Long,
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hasAudio: Boolean
) {
    val aspect: Float
        get() {
            val w: Int
            val h: Int
            if (rotation == 90 || rotation == 270) {
                w = height; h = width
            } else {
                w = width; h = height
            }
            return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 16f / 9f
        }
}

object VideoProbe {

    fun read(context: Context, uri: Uri): VideoFacts {
        var pfd: ParcelFileDescriptor? = null
        val r = MediaMetadataRetriever()
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw MediaFailure("Could not open the selected video.")
            r.setDataSource(pfd.fileDescriptor)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val hasAudio =
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (ms <= 0L) throw MediaFailure("Could not read the length of this video.")
            return VideoFacts(
                durationMs = ms,
                durationSec = ceil(ms / 1000.0).toInt(),
                width = w,
                height = h,
                rotation = rot,
                hasAudio = hasAudio
            )
        } catch (e: MediaFailure) {
            throw e
        } catch (e: Exception) {
            throw MediaFailure("Could not read this video: ${e.message}")
        } finally {
            try { r.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}

/* ---------------------------------------------------------------
   FRAME GRABBER
   --------------------------------------------------------------- */

class FrameGrabber(context: Context, uri: Uri) : AutoCloseable {

    private val pfd: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw MediaFailure("Could not open the selected video.")

    private val retriever = MediaMetadataRetriever().apply {
        try {
            setDataSource(pfd.fileDescriptor)
        } catch (e: Exception) {
            try { pfd.close() } catch (_: Exception) {}
            throw MediaFailure("This video format could not be opened: ${e.message}")
        }
    }

    /**
     * Grabs the frame nearest to [ms], scaled natively to the requested box.
     * OPTION_CLOSEST is required for sub-second accuracy. OPTION_CLOSEST_SYNC
     * snaps to keyframes and would return the same picture five times in a row.
     */
    fun frameAt(ms: Long, targetW: Int, targetH: Int): Bitmap? {
        return try {
            retriever.getScaledFrameAtTime(
                ms * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                targetW,
                targetH
            )
        } catch (_: Exception) {
            try {
                retriever.getScaledFrameAtTime(
                    ms * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetW,
                    targetH
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun close() {
        try { retriever.release() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

/* ---------------------------------------------------------------
   MOSAIC BUILDER
   --------------------------------------------------------------- */

object MosaicBuilder {

    private const val LABEL_H = 24

    /**
     * Composites labelled frames into one grid image.
     * Five frames become a 3 x 2 grid; one frame stays a single cell.
     */
    fun build(
        frames: List<Pair<String, Bitmap>>,
        cellW: Int,
        cellH: Int
    ): Bitmap? {
        if (frames.isEmpty()) return null

        val cols = when {
            frames.size <= 1 -> 1
            frames.size <= 4 -> 2
            frames.size <= 6 -> 3
            frames.size <= 9 -> 3
            else -> 4
        }
        val rows = ceil(frames.size / cols.toDouble()).toInt()

        val out = Bitmap.createBitmap(
            cols * cellW,
            rows * (cellH + LABEL_H),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(8, 9, 11))

        val imgPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        val stripPaint = Paint().apply { color = Color.rgb(0, 60, 44) }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 15f
            isAntiAlias = true
            isFakeBoldText = true
        }

        frames.forEachIndexed { index, entry ->
            val label = entry.first
            val bmp = entry.second
            val x = (index % cols) * cellW
            val y = (index / cols) * (cellH + LABEL_H)

            canvas.drawRect(
                x.toFloat(),
                y.toFloat(),
                (x + cellW).toFloat(),
                (y + LABEL_H).toFloat(),
                stripPaint
            )
            canvas.drawText(label, x + 8f, y + LABEL_H - 7f, textPaint)
            canvas.drawBitmap(
                bmp,
                null,
                Rect(x, y + LABEL_H, x + cellW, y + LABEL_H + cellH),
                imgPaint
            )
        }
        return out
    }

    fun toJpegBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/* ---------------------------------------------------------------
   AUDIO REMUX  (video -> .m4a, no re-encoding)
   --------------------------------------------------------------- */

object AudioRemux {

    /**
     * Copies the audio track out of the video into an MPEG-4 container.
     * Returns null when the video has no audio track or the remux fails.
     */
    fun extract(context: Context, uri: Uri, outFile: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            extractor.setDataSource(pfd.fileDescriptor)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)
            if (outFile.exists()) outFile.delete()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(format)
            muxer.start()

            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                512 * 1024
            }
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            return if (outFile.length() > 1024L) outFile else null
        } catch (_: Exception) {
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}

/* ---------------------------------------------------------------
   COST ESTIMATOR
   --------------------------------------------------------------- */

data class CostEstimate(
    val requests: Int,
    val images: Int,
    val estimatedInputTokens: Int,
    val estimatedCostUsd: Double
) {
    fun tokensLabel(): String = when {
        estimatedInputTokens >= 1_000_000 ->
            "${(estimatedInputTokens / 100_000) / 10.0}M input tokens"
        estimatedInputTokens >= 1000 -> "${estimatedInputTokens / 1000}k input tokens"
        else -> "$estimatedInputTokens input tokens"
    }

    fun costLabel(): String =
        if (estimatedCostUsd <= 0.0) "set a price to estimate"
        else "about $" + ((estimatedCostUsd * 1000).roundToInt() / 1000.0).toString()
}

object CostMath {

    /** Rough per-image input token cost. Providers vary; this is a planning figure. */
    private fun tokensPerImage(detail: String, cellW: Int, frameCount: Int): Int {
        if (detail == "low") return 85
        val cols = if (frameCount <= 1) 1 else if (frameCount <= 4) 2 else 3
        val rows = ceil(frameCount / cols.toDouble()).toInt()
        val approxTiles = (cols * cellW / 512.0).coerceAtLeast(1.0) *
            (rows * (cellW * 9 / 16) / 512.0).coerceAtLeast(1.0)
        return (85 + 170 * approxTiles).roundToInt()
    }

    fun estimate(
        durationSec: Int,
        fps: Int,
        secondsPerRequest: Int,
        cellW: Int,
        detail: String,
        promptTokens: Int,
        pricePerMillion: Double
    ): CostEstimate {
        if (durationSec <= 0) return CostEstimate(0, 0, 0, 0.0)
        val requests = ceil(durationSec / secondsPerRequest.toDouble()).toInt()
        val images = durationSec
        val perImage = tokensPerImage(detail, cellW, fps)
        val speechTokens = durationSec * 25
        val total = requests * promptTokens + images * perImage + speechTokens
        val cost = if (pricePerMillion > 0) total / 1_000_000.0 * pricePerMillion else 0.0
        return CostEstimate(requests, images, total, cost)
    }
}
