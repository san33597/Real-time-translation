package com.localfirst.realtimetranslator.asr

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Explicit SAF import into private app storage. Keeps the existing Zipformer.
 * Filenames and sizes are validated, but upstream SHA fingerprints have NOT
 * been pinned. A successful import is not a native-runtime model guarantee.
 */
object ParakeetModelInstaller {
    const val MODEL_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    private const val READY = ".ready-r3-4-parakeet"
    private const val MIB = 1024L * 1024L

    private val minLengths = linkedMapOf(
        ENCODER to 450 * MIB,
        DECODER to 6 * MIB,
        JOINER to 3 * MIB,
        TOKENS to 10_000L,
    )

    fun directory(context: Context): File = File(context.filesDir, "asr/parakeet-v3-int8")

    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        return File(dir, READY).let { it.isFile && it.readText() == MODEL_NAME } &&
            minLengths.all { (name, min) -> File(dir, name).isFile &&
                File(dir, name).length() >= min }
    }

    fun install(context: Context, folderUri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("无法打开 Parakeet 模型文件夹")
        val sources = minLengths.map { (name, minSize) ->
            val source = folder.findFile(name)?.takeIf { it.isFile }
                ?: error("缺少 Parakeet 模型文件：$name")
            if (source.length() > 0) check(source.length() >= minSize) {
                "$name 文件太小，可能下载不完整"
            }
            name to source
        }
        val target = directory(context)
        val parent = target.parentFile ?: error("模型目录无效")
        check(parent.exists() || parent.mkdirs()) { "无法创建模型目录" }

        val requiredBytes = sources.sumOf { (_, file) ->
            file.length().takeIf { it > 0 } ?: 750L * MIB / sources.size
        } + 64 * MIB
        val freeBytes = StatFs(parent.absolutePath).availableBytes
        check(freeBytes >= requiredBytes) {
            "存储空间不足：Parakeet 需要约 \${requiredBytes / MIB} MB 可用空间"
        }

        val staged = File(parent, ".parakeet-v3-staging")
        val backup = File(parent, ".parakeet-v3-backup")
        staged.deleteRecursively()
        check(staged.mkdirs()) { "模型暂存目录创建失败" }
        try {
            sources.forEach { (name, source) ->
                val output = File(staged, name)
                val input = context.contentResolver.openInputStream(source.uri)
                    ?: error("无法读取 $name")
                input.use { stream ->
                    output.outputStream().use { dest -> stream.copyTo(dest, 128 * 1024) }
                }
                check(output.length() >= (minLengths[name] ?: error("Unknown model file"))) {
                    "$name 拷贝不完整"
                }
            }
            val tokenFile = File(staged, TOKENS)
            check(tokenFile.bufferedReader().use { it.readLine() }.orEmpty().isNotBlank()) {
                "Parakeet tokens.txt 不正确"
            }
            File(staged, READY).writeText(MODEL_NAME)
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "旧 Parakeet 模型备份失败" }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Parakeet 模型安装失败")
            }
            backup.deleteRecursively()
        } finally {
            staged.deleteRecursively()
        }
    }
}
