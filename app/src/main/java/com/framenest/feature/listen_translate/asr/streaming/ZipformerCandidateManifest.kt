package com.framenest.feature.listen_translate.asr.streaming

/**
 * Metadata only; no downloader, installation UI or enabled default. Candidate source languages: en/zh.
 * Upstream model card: https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t
 * Apache-2.0 as declared in that immutable revision. The ONNX LFS pointer SHA-256/size and tokens
 * bytes were inspected at this revision; no model weights were downloaded or executed for FN-72.
 * File paths below are exact upstream repository paths, NOT assumed release tarball contents.
 */
internal val ZIPFORMER_BILINGUAL_SMALL_CANDIDATE = ZipformerPackDescriptor(
    id = "zipformer-small-bilingual-zh-en-exp32-int8",
    immutableRevision = "e2382758de9a0219b4efe682b95af30b399db3b8",
    licenseEvidenceUrl = "https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/blob/e2382758de9a0219b4efe682b95af30b399db3b8/README.md",
    sourceLanguages = setOf("en", "zh"),
    files = mapOf(
        ZipformerFile.ENCODER to ZipformerFileDigest("exp/32/encoder-epoch-99-avg-1.int8.onnx", 42_980_793, "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb"),
        ZipformerFile.DECODER to ZipformerFileDigest("exp/32/decoder-epoch-99-avg-1.onnx", 13_877_276, "89be509a83175261695bdef5fd1c7b9ab1129a663d1284e7ba9f8507b21e0906"),
        ZipformerFile.JOINER to ZipformerFileDigest("exp/32/joiner-epoch-99-avg-1.int8.onnx", 3_228_485, "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9"),
        ZipformerFile.TOKENS to ZipformerFileDigest("data/lang_char_bpe/tokens.txt", 56_317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
    ),
)
