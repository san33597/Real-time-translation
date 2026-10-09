package com.localfirst.realtimetranslator.asr

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

/** Explicit SAF model import. All native inference is offline and audio never persists. */
object EnglishModelInstaller {
    const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
    const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-64.onnx"
    const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
    const val TOKENS = "tokens.txt"
    private const val READY = ".verified-r3-english"

    private val hashes = linkedMapOf(
        ENCODER to "0d072fd4ef956294ba9db9e9a71a541ac70659095ec4934c8453d8b2fe740187",
        DECODER to "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef",
        JOINER to "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297",
    )
    private val files = hashes.keys + TOKENS
    fun directory(context: Context) = File(context.filesDir, "asr/english-v1")
    fun isReady(context: Context): Boolean {
        val directory = directory(context)
        return File(directory, READY).isFile &&
            files.all { File(directory, it).isFile && File(directory, it).length() > 0 }
    }

    /** SHA-256 validates the published upstream ONNX model files before activation. */
    fun install(context: Context, uri: Uri) {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: error("无法打开模型文件夹")
        val target = directory(context)
        val staged = File(target.parentFile, ".english-staging")
        staged.deleteRecursively()
        check(staged.mkdirs()) { "无法创建模型暂存目录" }
        try {
            files.forEach { name ->
                val source = tree.findFile(name)?.takeIf { it.isFile } ?: error("缺少模型文件: $name")
                val digest = MessageDigest.getInstance("SHA-256")
                val output = File(staged, name)
                val input = context.contentResolver.openInputStream(source.uri)
                    ?: error("无法读取: $name")
                input.use { stream ->
                    output.outputStream().use { dest ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val length = stream.read(bytes)
                            if (length < 0) break
                            if (length == 0) continue
                            digest.update(bytes, 0, length)
                            dest.write(bytes, 0, length)
                        }
                    }
                }
                check(output.length() > 0) { "$name 为空" }
                hashes[name]?.let { expected ->
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(actual == expected) { "$name SHA-256 校验不通过" }
                }
            }
            val tokens = File(staged, TOKENS)
            check(tokens.length() in 1000..100000 && tokens.bufferedReader().use { it.readLine() } == "<blk> 0") {
                "tokens.txt 不匹配"
            }
            File(staged, READY).writeText(MODEL_NAME)
            val backup = File(target.parentFile, ".english-backup")
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "旧模型备份失败" }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("模型安装失败")
            }
            backup.deleteRecursively()
        } finally {
            staged.deleteRecursively()
        }
    }
}
