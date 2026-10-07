package com.quicknote.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

object VoiceTranscriber {
    private data class DecodeJob(val thread: Thread, val cancelled: AtomicBoolean)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeCancellation = WeakHashMap<FragmentActivity, () -> Unit>()
    private val activeDecoders = WeakHashMap<FragmentActivity, DecodeJob>()

    fun isSupported(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }.getOrDefault(false)

    fun transcribe(
        activity: FragmentActivity,
        entry: Entry,
        language: String,
        onText: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 33) { onFailure(activity.getString(R.string.transcription_requires_android_13)); return }
        if (!isSupported(activity)) { onFailure(activity.getString(R.string.transcription_unavailable)); return }
        val source = entry.audioPath?.let(::File)
        if (source == null || !source.isFile || !source.absolutePath.startsWith(activity.filesDir.absolutePath + File.separator)) {
            onFailure(activity.getString(R.string.voice_play_failed)); return
        }
        val pcm = try { File.createTempFile("quicknote_transcribe_", ".pcm", activity.cacheDir) }
        catch (_: Exception) { onFailure(activity.getString(R.string.transcription_failed)); return }
        val cancelled = AtomicBoolean(false)
        lateinit var worker: Thread
        worker = Thread({
            val format = try { decodeToPcm(source, pcm) } catch (_: Exception) { null }
            mainHandler.post {
                if (cancelled.get() || activity.isFinishing || activity.isDestroyed) {
                    synchronized(activeDecoders) { if (activeDecoders[activity]?.thread === worker) activeDecoders.remove(activity) }
                    pcm.delete()
                    return@post
                }
                synchronized(activeDecoders) { if (activeDecoders[activity]?.thread === worker) activeDecoders.remove(activity) }
                if (format == null || pcm.length() < 64L) { pcm.delete(); onFailure(activity.getString(R.string.transcription_failed)); return@post }
                startRecognition(activity, pcm, format.first, format.second, language, onText, onFailure)
            }
        }, "QuickNote-AudioDecode").apply { isDaemon = true }
        val job = DecodeJob(worker, cancelled)
        val previous = synchronized(activeDecoders) { activeDecoders.put(activity, job) }
        previous?.let { it.cancelled.set(true); it.thread.interrupt() }
        worker.start()
    }

    fun cancel(activity: FragmentActivity) {
        val decoder = synchronized(activeDecoders) { activeDecoders.remove(activity) }
        decoder?.let { it.cancelled.set(true); it.thread.interrupt() }
        val cancel = synchronized(activeCancellation) { activeCancellation.remove(activity) }
        cancel?.invoke()
    }

    @androidx.annotation.RequiresApi(33)
    private fun startRecognition(
        activity: FragmentActivity,
        pcm: File,
        sampleRate: Int,
        channels: Int,
        language: String,
        onText: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val descriptor = try { ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY) }
        catch (_: Exception) { pcm.delete(); onFailure(activity.getString(R.string.transcription_failed)); return }
        val recognizer = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) }
        catch (_: Exception) { descriptor.close(); pcm.delete(); onFailure(activity.getString(R.string.transcription_unavailable)); return }
        var finished = false
        var timeout: Runnable? = null
        lateinit var finish: () -> Unit
        finish = {
            if (!finished) {
                finished = true
                timeout?.let(mainHandler::removeCallbacks)
                synchronized(activeCancellation) {
                    if (activeCancellation[activity] === finish) activeCancellation.remove(activity)
                }
                try { descriptor.close() } catch (_: Exception) { }
                pcm.delete()
                try { recognizer.destroy() } catch (_: Exception) { }
            }
        }
        val previous = synchronized(activeCancellation) { activeCancellation.remove(activity) }
        previous?.invoke()
        synchronized(activeCancellation) { activeCancellation[activity] = finish }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onError(error: Int) {
                if (finished) return
                finish()
                if (!activity.isFinishing && !activity.isDestroyed) onFailure(activity.getString(R.string.transcription_failed))
            }
            override fun onResults(results: android.os.Bundle?) {
                if (finished) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish()
                if (activity.isFinishing || activity.isDestroyed) return
                if (text.isBlank()) onFailure(activity.getString(R.string.transcription_no_text)) else onText(text)
            }
        })
        val locale = when (language) {
            "en" -> Locale.ENGLISH
            "ar" -> Locale.forLanguageTag("ar")
            else -> AppLocale.uiLocale(activity)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        timeout = Runnable {
            if (!finished) {
                finish()
                if (!activity.isFinishing && !activity.isDestroyed) onFailure(activity.getString(R.string.transcription_failed))
            }
        }.also { mainHandler.postDelayed(it, RECOGNITION_TIMEOUT_MS) }
        try { recognizer.startListening(intent) }
        catch (_: Exception) {
            if (!finished) { finish(); onFailure(activity.getString(R.string.transcription_failed)) }
        }
    }

    /** Decodes a bounded AAC/M4A memo to PCM for Android's on-device audio-source recognizer. */
    private fun decodeToPcm(source: File, destination: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            require(source.length() in 1..MAX_INPUT_AUDIO_BYTES)
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; trackFormat = format; break }
            }
            require(track >= 0 && trackFormat != null)
            val inputFormat = trackFormat!!
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val inputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(track)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var inputDone = false
            var outputDone = false
            var outputRate = inputRate
            var outputChannels = inputChannels
            var totalBytes = 0L
            val info = MediaCodec.BufferInfo()
            FileOutputStream(destination).use { out ->
                var idleLoops = 0
                while (!outputDone && idleLoops < MAX_IDLE_LOOPS) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Transcription cancelled")
                    var didWork = false
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            didWork = true
                        }
                    }
                    when (val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output = decoder.outputFormat
                            if (output.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) require(output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                            didWork = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            val buffer: ByteBuffer = decoder.getOutputBuffer(index)!!
                            if (info.size > 0) {
                                totalBytes += info.size
                                require(totalBytes <= MAX_PCM_BYTES)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                out.write(bytes)
                                didWork = true
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                    idleLoops = if (didWork) 0 else idleLoops + 1
                }
                check(outputDone && outputRate > 0 && outputChannels > 0)
            }
            return outputRate to outputChannels
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 20_000
    private const val MAX_INPUT_AUDIO_BYTES = 100L * 1024 * 1024
    private const val MAX_PCM_BYTES = 256L * 1024 * 1024
    private const val RECOGNITION_TIMEOUT_MS = 120_000L
}
