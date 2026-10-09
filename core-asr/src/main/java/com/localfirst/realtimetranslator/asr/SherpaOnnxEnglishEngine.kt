package com.localfirst.realtimetranslator.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.localfirst.realtimetranslator.model.AsrEngine
import com.localfirst.realtimetranslator.model.AsrUpdate
import com.localfirst.realtimetranslator.model.PcmFrame
import com.localfirst.realtimetranslator.model.SessionIdentity
import java.io.File

/** Owns sherpa JNI and one stream; all calls MUST be serialized by the caller. */
class SherpaOnnxEnglishEngine(
    private val identity: SessionIdentity,
    modelDirectory: File,
    private val nowMs: () -> Long,
    private val onUpdate: (AsrUpdate) -> Unit,
) : AsrEngine {
    private val recognizer: OnlineRecognizer
    private val stream: com.k2fsa.sherpa.onnx.OnlineStream
    private var utterance = 0L
    private var revision = 0L
    private var partial = ""
    private var finished = false
    private var closed = false

    init {
        fun file(name: String) = File(modelDirectory, name).absolutePath
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = file(EnglishModelInstaller.ENCODER),
                    decoder = file(EnglishModelInstaller.DECODER),
                    joiner = file(EnglishModelInstaller.JOINER),
                ),
                tokens = file(EnglishModelInstaller.TOKENS),
                numThreads = 2,
                provider = "cpu",
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 15.0f),
            ),
        )
        recognizer = OnlineRecognizer(config = config)
        try {
            stream = recognizer.createStream()
        } catch (failure: Throwable) {
            recognizer.release()
            throw failure
        }
    }

    override fun accept(frame: PcmFrame) {
        check(!closed && !finished)
        require(frame.sessionId == identity.sessionId && frame.audioEpoch == identity.audioEpoch)
        require(frame.sampleRateHz == 16000 && frame.channelCount == 1)
        val pcm = FloatArray(frame.samples.size) { frame.samples[it] / 32768f }
        try {
            stream.acceptWaveform(pcm, 16000)
            decode()
            val text = recognizer.getResult(stream).text.trim()
            if (text != partial) {
                partial = text
                publish(text, false)
            }
            if (recognizer.isEndpoint(stream)) {
                if (text.isNotBlank()) publish(text, true)
                recognizer.reset(stream)
                utterance++
                revision = 0
                partial = ""
            }
        } finally { pcm.fill(0f) }
    }

    override fun resetAfterGap() {
        if (closed || finished) return
        if (partial.isNotBlank()) publish(partial, true)
        recognizer.reset(stream)
        utterance++
        revision = 0
        partial = ""
    }

    override fun finish() {
        if (closed || finished) return
        finished = true
        stream.inputFinished()
        decode()
        val text = recognizer.getResult(stream).text.trim()
        if (text.isNotBlank()) publish(text, true)
    }

    private fun decode() { while (recognizer.isReady(stream)) recognizer.decode(stream) }
    private fun publish(text: String, isFinal: Boolean) {
        onUpdate(AsrUpdate(identity.sessionId, identity.audioEpoch,
            utterance, revision++, text, isFinal, nowMs()))
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.release()
        recognizer.release()
    }
}
