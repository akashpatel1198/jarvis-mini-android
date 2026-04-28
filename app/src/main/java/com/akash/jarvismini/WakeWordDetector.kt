package com.akash.jarvismini

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

// openWakeWord pipeline (3 chained ONNX models):
//   audio (16kHz s16) → melspectrogram → mel frames (32 bins)
//   76 mel frames     → embedding model → 1 embedding (96-dim)
//   16 embeddings     → "hey_jarvis" classifier → probability ∈ [0, 1]

private const val TAG = "WakeWord"
private const val CHUNK_SAMPLES = 1280              // 80 ms @ 16 kHz
private const val MEL_BINS = 32
private const val MEL_FRAMES_PER_EMBED = 76
private const val EMBED_DIM = 96
private const val EMBEDS_PER_CLASSIFIER = 16
private const val MEL_BUFFER_FRAMES = 96

class WakeWordDetector(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melspec: OrtSession
    private val embedding: OrtSession
    private val classifier: OrtSession

    private val melspecInputName: String
    private val embeddingInputName: String
    private val classifierInputName: String

    private val melBuffer = FloatArray(MEL_BUFFER_FRAMES * MEL_BINS)
    private var melFilled = 0

    private val embedBuffer = FloatArray(EMBEDS_PER_CLASSIFIER * EMBED_DIM)
    private var embedFilled = 0

    init {
        melspec = env.createSession(loadModelBytes(context, "oww/melspectrogram.onnx"))
        embedding = env.createSession(loadModelBytes(context, "oww/embedding_model.onnx"))
        classifier = env.createSession(loadModelBytes(context, "oww/hey_jarvis_v0.1.onnx"))

        melspecInputName = melspec.inputNames.iterator().next()
        embeddingInputName = embedding.inputNames.iterator().next()
        classifierInputName = classifier.inputNames.iterator().next()

        Log.d(TAG, "melspec    inputs=${melspec.inputNames} outputs=${melspec.outputNames}")
        Log.d(TAG, "embedding  inputs=${embedding.inputNames} outputs=${embedding.outputNames}")
        Log.d(TAG, "classifier inputs=${classifier.inputNames} outputs=${classifier.outputNames}")
    }

    private fun loadModelBytes(context: Context, asset: String): ByteArray =
        context.assets.open(asset).use { it.readBytes() }

    private var feedCount = 0
    private var loggedShapesOnce = false

    /**
     * Feed an 80 ms chunk of int16 PCM. Returns the wake word probability
     * computed this call, or null while warming up the pipeline.
     */
    fun feed(samples: ShortArray): Float? {
        require(samples.size == CHUNK_SAMPLES)
        feedCount++

        // 1. Audio → mel frames.
        val audioFloat = FloatArray(CHUNK_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES) audioFloat[i] = samples[i].toFloat()
        val audioTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audioFloat), longArrayOf(1, CHUNK_SAMPLES.toLong()),
        )
        val melResult = audioTensor.use {
            melspec.run(mapOf(melspecInputName to it))
        }
        if (!loggedShapesOnce) {
            Log.d(TAG, "melspec raw output type: ${melResult[0].value::class.java.name}")
        }
        val melArr = melResult.use { extractMelFrames(it) }
        if (!loggedShapesOnce) {
            Log.d(TAG, "melspec yielded ${melArr.size} mel frames per chunk")
        }
        appendMelFrames(melArr)

        if (melFilled < MEL_FRAMES_PER_EMBED) return null

        // 2. 76 most-recent mel frames → 1 embedding.
        val embedInputBuf = FloatArray(MEL_FRAMES_PER_EMBED * MEL_BINS)
        val startFrame = melFilled - MEL_FRAMES_PER_EMBED
        System.arraycopy(
            melBuffer, startFrame * MEL_BINS,
            embedInputBuf, 0,
            MEL_FRAMES_PER_EMBED * MEL_BINS,
        )
        val embedTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(embedInputBuf),
            longArrayOf(1, MEL_FRAMES_PER_EMBED.toLong(), MEL_BINS.toLong(), 1),
        )
        val embedResult = embedTensor.use {
            embedding.run(mapOf(embeddingInputName to it))
        }
        if (!loggedShapesOnce) {
            Log.d(TAG, "embedding raw output type: ${embedResult[0].value::class.java.name}")
        }
        val newEmbed = embedResult.use { extractEmbedding(it) }
        appendEmbedding(newEmbed)

        if (embedFilled < EMBEDS_PER_CLASSIFIER) return null

        // 3. 16 embeddings → 1 probability.
        val classifierTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(embedBuffer),
            longArrayOf(1, EMBEDS_PER_CLASSIFIER.toLong(), EMBED_DIM.toLong()),
        )
        val classResult = classifierTensor.use {
            classifier.run(mapOf(classifierInputName to it))
        }
        if (!loggedShapesOnce) {
            Log.d(TAG, "classifier raw output type: ${classResult[0].value::class.java.name}")
            loggedShapesOnce = true
        }
        return classResult.use { extractScalarProb(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractMelFrames(result: OrtSession.Result): Array<FloatArray> {
        // melspectrogram output is float32 with shape [1, 1, frames, 32].
        val raw = result[0].value
        val arr = raw as Array<Array<Array<FloatArray>>>
        return arr[0][0]
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractEmbedding(result: OrtSession.Result): FloatArray {
        // embedding output shape is [1, 1, 1, 96].
        val raw = result[0].value as Array<Array<Array<FloatArray>>>
        return raw[0][0][0]
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractScalarProb(result: OrtSession.Result): Float {
        // classifier output is shape [1, 1].
        val raw = result[0].value as Array<FloatArray>
        return raw[0][0]
    }

    private fun appendMelFrames(frames: Array<FloatArray>) {
        val count = frames.size
        if (count == 0) return
        val capacity = MEL_BUFFER_FRAMES
        if (melFilled + count > capacity) {
            val drop = melFilled + count - capacity
            System.arraycopy(
                melBuffer, drop * MEL_BINS,
                melBuffer, 0,
                (melFilled - drop) * MEL_BINS,
            )
            melFilled -= drop
        }
        // openWakeWord normalizes mel features before they enter the embedding
        // model: see openwakeword/utils.py — `new_features.astype(np.float32) / 10 + 2`.
        for (f in 0 until count) {
            for (b in 0 until MEL_BINS) {
                melBuffer[(melFilled + f) * MEL_BINS + b] = frames[f][b] / 10f + 2f
            }
        }
        melFilled += count
    }

    private fun appendEmbedding(embed: FloatArray) {
        val capacity = EMBEDS_PER_CLASSIFIER
        if (embedFilled + 1 > capacity) {
            System.arraycopy(
                embedBuffer, EMBED_DIM,
                embedBuffer, 0,
                (capacity - 1) * EMBED_DIM,
            )
            embedFilled -= 1
        }
        System.arraycopy(embed, 0, embedBuffer, embedFilled * EMBED_DIM, EMBED_DIM)
        embedFilled += 1
    }

    fun close() {
        melspec.close()
        embedding.close()
        classifier.close()
    }
}
