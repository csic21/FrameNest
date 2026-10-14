package com.framenest.feature.listen_translate.asr.streaming

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * API checked against k2-fsa/sherpa-onnx tag v1.12.32, kotlin-api/OnlineRecognizer.kt
 * and OnlineStream.kt. No AssetManager: private filesystem packs require newFromFile.
 * Default endpoint rules are the pinned upstream defaults, not tuned latency claims.
 */
fun createExperimentalZipformerEngine(pack: VerifiedZipformerPack, numThreads: Int = 2): StreamingAsrEngine {
    require(numThreads in 1..4)
    return ZipformerStreamingAsrEngine {
        // Re-hash at load time, not just when installation was first checked. Caller must keep the
        // private installed directory immutable until engine close; no installer is enabled here.
        val paths = pack.verifiedPaths()
        val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.getValue(ZipformerFile.ENCODER).absolutePath,
                    decoder = paths.getValue(ZipformerFile.DECODER).absolutePath,
                    joiner = paths.getValue(ZipformerFile.JOINER).absolutePath,
                ),
                tokens = paths.getValue(ZipformerFile.TOKENS).absolutePath,
                modelType = "zipformer",
                numThreads = numThreads,
                provider = "cpu",
                debug = false,
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ))
        object : ZipformerNativeRecognizer {
            override fun stream(): ZipformerNativeStream {
                val stream = recognizer.createStream()
                return object : ZipformerNativeStream {
                    override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, 16_000)
                    override fun finish() = stream.inputFinished()
                    override fun ready() = recognizer.isReady(stream)
                    override fun decode() = recognizer.decode(stream)
                    override fun result(): NativeStreamingResult {
                        val result = recognizer.getResult(stream)
                        return NativeStreamingResult(result.text, result.tokens.toList(), result.timestamps.toList())
                    }
                    override fun endpoint() = recognizer.isEndpoint(stream)
                    override fun release() = stream.release()
                }
            }
            override fun release() = recognizer.release()
        }
    }
}
