package com.commentary.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* ---------------------------------------------------------------
   OPTIONS
   --------------------------------------------------------------- */

enum class Preset(val label: String) {
    ECONOMY("Economy"),
    BALANCED("Balanced"),
    MAXIMUM("Maximum"),
    CUSTOM("Custom")
}

data class AnalysisOptions(
    val preset: Preset = Preset.BALANCED,
    val fps: Int = 5,
    val secondsPerRequest: Int = 5,
    val cellWidth: Int = 384,
    val jpegQuality: Int = 62,
    val detail: String = "high",
    val useAudio: Boolean = true,
    val maxOutputTokens: Int = 16000,
    val pricePerMillion: Double = 0.0
) {
    companion object {
        fun of(preset: Preset, current: AnalysisOptions): AnalysisOptions = when (preset) {
            Preset.ECONOMY -> current.copy(
                preset = preset, fps = 1, secondsPerRequest = 10,
                cellWidth = 512, detail = "low", jpegQuality = 55
            )
            Preset.BALANCED -> current.copy(
                preset = preset, fps = 5, secondsPerRequest = 5,
                cellWidth = 384, detail = "high", jpegQuality = 62
            )
            Preset.MAXIMUM -> current.copy(
                preset = preset, fps = 5, secondsPerRequest = 3,
                cellWidth = 512, detail = "high", jpegQuality = 72
            )
            Preset.CUSTOM -> current.copy(preset = preset)
        }
    }
}

/* ---------------------------------------------------------------
   ANALYSIS PIPELINE
   --------------------------------------------------------------- */

object AnalysisPipeline {

    private const val PROMPT_TOKEN_GUESS = 820

    private fun mmss(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

    fun estimate(durationSec: Int, o: AnalysisOptions): CostEstimate =
        CostMath.estimate(
            durationSec = durationSec,
            fps = o.fps,
            secondsPerRequest = o.secondsPerRequest,
            cellW = o.cellWidth,
            detail = o.detail,
            promptTokens = PROMPT_TOKEN_GUESS,
            pricePerMillion = o.pricePerMillion
        )

    /**
     * Full run. Mirrors what Gemini does server-side:
     * frames at a fixed rate + real audio words + per-second timestamps,
     * merged and handed to the model together.
     */
    suspend fun run(
        context: Context,
        vision: Conn,
        transcribe: Conn?,
        uri: Uri,
        options: AnalysisOptions,
        onProgress: (String, Float?) -> Unit
    ): String = withContext(Dispatchers.IO) {

        onProgress("Reading video metadata", 0.01f)
        val facts = VideoProbe.read(context, uri)
        if (facts.durationSec <= 0) throw MediaFailure("This video has no readable length.")
        if (facts.durationSec > 900) {
            throw MediaFailure("This video is longer than 15 minutes. Trim it first.")
        }

        // Gemini native path keeps its own server-side pipeline.
        if (vision.kind == ProviderKind.GEMINI) {
            return@withContext runGeminiNative(context, vision, uri, facts, onProgress)
        }

        /* ---------- 1. AUDIO ---------- */

        var transcript = TranscriptResult.EMPTY
        var audioNote = "No audio was requested."

        if (options.useAudio && facts.hasAudio && transcribe != null && transcribe.ready) {
            coroutineContext.ensureActive()
            onProgress("Extracting the audio track", 0.04f)
            val audioFile = File(context.cacheDir, "cs_audio.m4a")
            val extracted = AudioRemux.extract(context, uri, audioFile)
            if (extracted == null) {
                audioNote = "The audio track could not be extracted from this container."
            } else {
                coroutineContext.ensureActive()
                onProgress("Transcribing ${extracted.length() / 1000} KB of audio", 0.08f)
                transcript = try {
                    Ai.transcribe(transcribe, extracted, null)
                } catch (e: Exception) {
                    audioNote = "Transcription failed: ${e.message}"
                    TranscriptResult.EMPTY
                }
                if (transcript.hasTimestamps) {
                    audioNote =
                        "Real audio track transcribed with word-level timestamps" +
                            (if (transcript.language.isNotBlank()) " (${transcript.language})" else "") +
                            "."
                } else if (transcript.fullText.isNotBlank()) {
                    audioNote =
                        "Audio transcribed, but this model returned no timestamps, " +
                            "so speech is supplied as a whole-clip transcript."
                }
                try { extracted.delete() } catch (_: Exception) {}
            }
        } else if (!facts.hasAudio) {
            audioNote = "This video has no audio track."
        } else if (transcribe == null || !transcribe.ready) {
            audioNote = "No transcription model is configured, so speech is unavailable."
        }

        /* ---------- 2. FRAMES + VISION ---------- */

        val grabber = FrameGrabber(context, uri)
        val analysis = StringBuilder()

        try {
            val total = facts.durationSec
            val spr = options.secondsPerRequest.coerceIn(1, 15)
            val fps = options.fps.coerceIn(1, 10)
            val batches = ceil(total / spr.toDouble()).toInt()

            val cellW = options.cellWidth.coerceIn(160, 768)
            val cellH = max(90, (cellW / facts.aspect).roundToInt())

            val hasSpeech = transcript.hasTimestamps || transcript.fullText.isNotBlank()

            for (b in 0 until batches) {
                coroutineContext.ensureActive()
                val startSec = b * spr
                val endSec = min(startSec + spr - 1, total - 1)
                val baseProgress = 0.14f + 0.74f * b / batches

                onProgress("Extracting frames ${mmss(startSec)} to ${mmss(endSec)}", baseProgress)

                val blocks = mutableListOf<Ai.Block>()
                blocks.add(
                    Ai.Block.Txt(Prompts.mosaicBatch(startSec, endSec, fps, hasSpeech))
                )

                if (transcript.fullText.isNotBlank() && !transcript.hasTimestamps) {
                    blocks.add(
                        Ai.Block.Txt(
                            "WHOLE-CLIP TRANSCRIPT (no timestamps available, " +
                                "assign speech to seconds by best judgement):\n" +
                                transcript.fullText
                        )
                    )
                }

                var mosaicsInBatch = 0

                for (sec in startSec..endSec) {
                    coroutineContext.ensureActive()

                    val frames = mutableListOf<Pair<String, Bitmap>>()
                    for (k in 0 until fps) {
                        val offsetMs = k * 1000L / fps
                        val atMs = sec * 1000L + offsetMs
                        if (atMs >= facts.durationMs) break
                        val bmp = grabber.frameAt(atMs, cellW, cellH) ?: continue
                        val label = "%s +%.2fs".format(mmss(sec), offsetMs / 1000.0)
                        frames.add(label to bmp)
                    }
                    if (frames.isEmpty()) continue

                    val mosaic = MosaicBuilder.build(frames, cellW, cellH)
                    frames.forEach { runCatching { it.second.recycle() } }
                    if (mosaic == null) continue

                    val b64 = MosaicBuilder.toJpegBase64(mosaic, options.jpegQuality)
                    runCatching { mosaic.recycle() }

                    val spoken = transcript.wordsInSecond(sec)
                    val speechLine = when {
                        !hasSpeech -> "SPEECH THIS SECOND: (no audio track)"
                        spoken.isBlank() -> "SPEECH THIS SECOND: (silence)"
                        else -> "SPEECH THIS SECOND (verbatim from the real audio): \"$spoken\""
                    }

                    blocks.add(
                        Ai.Block.Txt(
                            "=== SECOND ${mmss(sec)} === " +
                                "(${frames.size} frames inside this second)\n$speechLine"
                        )
                    )
                    blocks.add(Ai.Block.Img(b64, options.detail))
                    mosaicsInBatch++
                }

                if (mosaicsInBatch == 0) continue

                coroutineContext.ensureActive()
                onProgress(
                    "Analysing ${mmss(startSec)} to ${mmss(endSec)}  " +
                        "(request ${b + 1} of $batches)",
                    baseProgress + 0.74f * 0.5f / batches
                )

                val out = Ai.vision(
                    conn = vision,
                    system = "You are a precise, literal forensic video analyst. " +
                        "You never invent detail and you never skip a second.",
                    blocks = blocks,
                    maxTokens = options.maxOutputTokens
                )
                analysis.append(out.trim()).append("\n\n")
            }
        } finally {
            grabber.close()
        }

        if (analysis.isBlank()) {
            throw AiFailure("No frames could be read from this video.")
        }

        /* ---------- 3. SUMMARY ---------- */

        coroutineContext.ensureActive()
        onProgress("Building the summary", 0.92f)
        val summary = try {
            Ai.text(
                conn = vision,
                system = "You write precise factual summaries and never invent detail.",
                user = Prompts.SUMMARY + "\n\n" + analysis.toString().take(120_000),
                maxTokens = 4000
            )
        } catch (e: Exception) {
            "## SUMMARY\n(The automatic summary failed: ${e.message})"
        }

        onProgress("Done", 1f)

        buildString {
            appendLine("SECOND-BY-SECOND FOOTAGE ANALYSIS")
            appendLine("Vision model: ${vision.model}")
            appendLine(
                "Transcription model: " +
                    (if (transcribe != null && transcribe.ready) transcribe.model else "none")
            )
            appendLine("Frame rate analysed: ${options.fps} FPS")
            appendLine("Frames per image: ${options.fps} (composited into one mosaic per second)")
            appendLine("Seconds per request: ${options.secondsPerRequest}")
            appendLine("Audio: $audioNote")
            appendLine("TOTAL RUNTIME: ${facts.durationSec}s")
            appendLine("----------------------------------------")
            appendLine()
            append(analysis)
            appendLine()
            append(summary)
        }
    }

    private suspend fun runGeminiNative(
        context: Context,
        vision: Conn,
        uri: Uri,
        facts: VideoFacts,
        onProgress: (String, Float?) -> Unit
    ): String {
        onProgress("Reading the video file", 0.05f)
        val bytes = FileFacts.readAllBytes(context, uri, 200_000_000L)
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"

        coroutineContext.ensureActive()

        // The job is captured here because coroutineContext is not reachable
        // from inside the plain (non-suspend) callback below.
        val job = coroutineContext[Job]

        val out = Ai.geminiUploadAndAnalyse(
            conn = vision,
            bytes = bytes,
            mime = mime,
            prompt = Prompts.NATIVE_VIDEO,
            maxTokens = 64000,
            onStatus = { onProgress(it, null) },
            stillActive = { job?.isActive != false }
        )
        onProgress("Done", 1f)

        return buildString {
            appendLine("SECOND-BY-SECOND FOOTAGE ANALYSIS")
            appendLine("Vision model: ${vision.model} (native video upload)")
            appendLine("Audio: handled server-side by Gemini, speech included")
            appendLine("TOTAL RUNTIME: ${facts.durationSec}s")
            appendLine("----------------------------------------")
            appendLine()
            append(out)
        }
    }
}

/* ---------------------------------------------------------------
   SCRIPT PIPELINE
   --------------------------------------------------------------- */

object ScriptPipeline {

    suspend fun run(
        script: Conn,
        analysis: String,
        brandKit: String,
        twoPass: Boolean,
        maxOutputTokens: Int,
        onProgress: (String, Float?) -> Unit
    ): String = withContext(Dispatchers.IO) {

        val inputTwo = buildString {
            appendLine("INPUT 2 - SECOND-BY-SECOND FOOTAGE ANALYSIS:")
            appendLine()
            appendLine(analysis)
            if (brandKit.isNotBlank()) {
                appendLine()
                appendLine("INPUT 3 - BRAND KIT:")
                appendLine(brandKit)
            }
        }

        if (!twoPass) {
            onProgress("Running the 12-step formula", null)
            val out = Ai.text(
                conn = script,
                system = Prompts.MASTER,
                user = inputTwo + "\n\nProduce sections A to G now, with no preamble.",
                maxTokens = maxOutputTokens
            )
            onProgress("Done", 1f)
            return@withContext out
        }

        onProgress("Pass 1 of 2: locking the angle and the runtime math", 0.15f)
        val stage1 = Ai.text(
            conn = script,
            system = Prompts.MASTER,
            user = inputTwo + "\n\n" + Prompts.STAGE_ONE,
            maxTokens = 4000
        )

        coroutineContext.ensureActive()
        onProgress("Pass 2 of 2: writing the timed script against locked numbers", 0.55f)
        val stage2 = Ai.text(
            conn = script,
            system = Prompts.MASTER,
            user = inputTwo + "\n\n" + Prompts.stageTwo(stage1),
            maxTokens = maxOutputTokens
        )

        onProgress("Done", 1f)
        stage1.trim() + "\n\n" + stage2.trim()
    }
}
