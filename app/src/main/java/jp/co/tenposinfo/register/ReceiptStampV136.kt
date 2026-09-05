package jp.co.tenposinfo.register

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class ReceiptStampDitherV136(val displayName: String) {
    THRESHOLD("閾値"),
    BAYER_4X4("Bayer 4x4"),
    FLOYD_STEINBERG("Floyd-Steinberg"),
}

data class ReceiptStampSettingsV136(
    val enabled: Boolean = false,
    val brightness: Int = 0,
    val threshold: Int = 128,
    val dither: ReceiptStampDitherV136 = ReceiptStampDitherV136.THRESHOLD,
    val stampVersion: Long = 0L,
    val sourceName: String = "",
)

data class ArgbImageV136(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }
}

data class MonochromeRasterV136(
    val widthDots: Int,
    val heightDots: Int,
    val bytesPerRow: Int,
    val data: ByteArray,
) {
    init {
        require(widthDots > 0 && heightDots > 0)
        require(bytesPerRow == (widthDots + 7) / 8)
        require(data.size == bytesPerRow * heightDots)
    }

    fun isBlack(x: Int, y: Int): Boolean {
        require(x in 0 until widthDots && y in 0 until heightDots)
        val value = data[y * bytesPerRow + x / 8].toInt() and 0xFF
        return (value and (0x80 ushr (x % 8))) != 0
    }
}

/**
 * 正式仕様 v2.5 §8.14 SCR-720 の画像スタンプ印字制約。
 * 仕様書は明るさ/閾値の数値レンジを定義していないため、下記レンジと初期値はv1.36実装値。
 */
object ReceiptStampPolicyV136 {
    const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    const val MAX_SOURCE_DIMENSION = 2_000
    const val MIN_BRIGHTNESS = -100
    const val MAX_BRIGHTNESS = 100
    const val DEFAULT_BRIGHTNESS = 0
    const val MIN_THRESHOLD = 0
    const val MAX_THRESHOLD = 255
    const val DEFAULT_THRESHOLD = 128
    const val MM58_MAX_DOTS = 384
    const val MM80_MAX_DOTS = 576

    fun maxWidthDots(paper: ReceiptPaper): Int = when (paper) {
        ReceiptPaper.MM58 -> MM58_MAX_DOTS
        ReceiptPaper.MM80 -> MM80_MAX_DOTS
    }

    fun normalize(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 = settings.copy(
        brightness = settings.brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        threshold = settings.threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
        sourceName = settings.sourceName.trim().take(120),
    )

    fun validateSource(byteCount: Int, width: Int, height: Int) {
        require(byteCount in 1..MAX_SOURCE_BYTES) { "画像は2MB以下のPNG/JPEGを選択してください" }
        require(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION) {
            "画像サイズは2000×2000px以下にしてください"
        }
    }

    fun fitDimensions(width: Int, height: Int, paper: ReceiptPaper): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val maxWidth = maxWidthDots(paper)
        if (width <= maxWidth) return width to height
        val scale = maxWidth.toDouble() / width.toDouble()
        return maxWidth to (height * scale).roundToInt().coerceAtLeast(1)
    }
}

object ReceiptStampRasterizerV136 {
    private val bayer4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    fun rasterize(
        image: ArgbImageV136,
        settings: ReceiptStampSettingsV136,
        paper: ReceiptPaper,
    ): MonochromeRasterV136 {
        val normalized = ReceiptStampPolicyV136.normalize(settings)
        val (targetWidth, targetHeight) = ReceiptStampPolicyV136.fitDimensions(image.width, image.height, paper)
        val pixels = if (targetWidth == image.width && targetHeight == image.height) {
            image.pixels.copyOf()
        } else {
            scaleNearest(image, targetWidth, targetHeight)
        }
        val luminance = DoubleArray(pixels.size) { index ->
            adjustedLuminance(pixels[index], normalized.brightness)
        }
        val black = when (normalized.dither) {
            ReceiptStampDitherV136.THRESHOLD -> threshold(luminance, normalized.threshold)
            ReceiptStampDitherV136.BAYER_4X4 -> bayer(luminance, targetWidth, normalized.threshold)
            ReceiptStampDitherV136.FLOYD_STEINBERG -> floydSteinberg(
                luminance,
                targetWidth,
                targetHeight,
                normalized.threshold,
            )
        }
        return pack(black, targetWidth, targetHeight)
    }

    fun adjustedLuminance(argb: Int, brightness: Int): Double {
        val a = argb ushr 24 and 0xFF
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        // SCR-720: 透過背景は白として扱う。半透明も白へアルファ合成する。
        val rr = (r * a + 255 * (255 - a)) / 255.0
        val gg = (g * a + 255 * (255 - a)) / 255.0
        val bb = (b * a + 255 * (255 - a)) / 255.0
        val base = (299.0 * rr + 587.0 * gg + 114.0 * bb) / 1000.0
        val offset = brightness.coerceIn(
            ReceiptStampPolicyV136.MIN_BRIGHTNESS,
            ReceiptStampPolicyV136.MAX_BRIGHTNESS,
        ) * 255.0 / 100.0
        return (base + offset).coerceIn(0.0, 255.0)
    }

    private fun scaleNearest(image: ArgbImageV136, targetWidth: Int, targetHeight: Int): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = ((y.toLong() * image.height) / targetHeight).toInt().coerceAtMost(image.height - 1)
            for (x in 0 until targetWidth) {
                val sourceX = ((x.toLong() * image.width) / targetWidth).toInt().coerceAtMost(image.width - 1)
                out[y * targetWidth + x] = image.pixels[sourceY * image.width + sourceX]
            }
        }
        return out
    }

    private fun threshold(values: DoubleArray, threshold: Int): BooleanArray =
        BooleanArray(values.size) { index -> values[index] < threshold }

    private fun bayer(values: DoubleArray, width: Int, threshold: Int): BooleanArray =
        BooleanArray(values.size) { index ->
            val x = index % width
            val y = index / width
            val matrix = bayer4[(y % 4) * 4 + (x % 4)]
            val localThreshold = (threshold + (matrix - 7.5) * 8.0).coerceIn(0.0, 255.0)
            values[index] < localThreshold
        }

    private fun floydSteinberg(
        input: DoubleArray,
        width: Int,
        height: Int,
        threshold: Int,
    ): BooleanArray {
        val values = input.copyOf()
        val black = BooleanArray(values.size)
        fun add(x: Int, y: Int, error: Double, weight: Double) {
            if (x !in 0 until width || y !in 0 until height) return
            val index = y * width + x
            values[index] = (values[index] + error * weight).coerceIn(0.0, 255.0)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val old = values[index]
                val isBlack = old < threshold
                black[index] = isBlack
                val quantized = if (isBlack) 0.0 else 255.0
                val error = old - quantized
                add(x + 1, y, error, 7.0 / 16.0)
                add(x - 1, y + 1, error, 3.0 / 16.0)
                add(x, y + 1, error, 5.0 / 16.0)
                add(x + 1, y + 1, error, 1.0 / 16.0)
            }
        }
        return black
    }

    private fun pack(black: BooleanArray, width: Int, height: Int): MonochromeRasterV136 {
        val bytesPerRow = (width + 7) / 8
        val data = ByteArray(bytesPerRow * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!black[y * width + x]) continue
                val index = y * bytesPerRow + x / 8
                data[index] = (data[index].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
        return MonochromeRasterV136(width, height, bytesPerRow, data)
    }
}

object ReceiptStampEscPosV136 {
    fun encodeRaster(raster: MonochromeRasterV136): ByteArray {
        val x = raster.bytesPerRow
        val y = raster.heightDots
        val prefix = byteArrayOf(
            0x1B, 0x40, // ESC @ initialize
            0x1B, 0x61, 0x01, // ESC a 1 center
            0x1D, 0x76, 0x30, 0x00, // GS v 0 normal density raster
            (x and 0xFF).toByte(), ((x ushr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(), ((y ushr 8) and 0xFF).toByte(),
        )
        return prefix + raster.data + byteArrayOf(
            0x0A,
            0x1B, 0x61, 0x00, // left alignment before existing text encoder re-initializes
        )
    }
}

class ReceiptStampSettingsStoreV136(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        "receipt_stamp_settings_v136",
        Context.MODE_PRIVATE,
    )
    private val sourceFile = File(applicationContext.filesDir, "receipt_stamp_v136/source_image.bin")

    fun load(): ReceiptStampSettingsV136 {
        val stored = ReceiptStampSettingsV136(
            enabled = preferences.getBoolean("enabled", false),
            brightness = preferences.getInt("brightness", ReceiptStampPolicyV136.DEFAULT_BRIGHTNESS),
            threshold = preferences.getInt("threshold", ReceiptStampPolicyV136.DEFAULT_THRESHOLD),
            dither = runCatching {
                ReceiptStampDitherV136.valueOf(
                    preferences.getString("dither", ReceiptStampDitherV136.THRESHOLD.name).orEmpty(),
                )
            }.getOrDefault(ReceiptStampDitherV136.THRESHOLD),
            stampVersion = preferences.getLong("stamp_version", 0L).coerceAtLeast(0L),
            sourceName = preferences.getString("source_name", "").orEmpty(),
        )
        return ReceiptStampPolicyV136.normalize(
            if (sourceFile.isFile) stored else stored.copy(enabled = false, sourceName = ""),
        )
    }

    fun hasImage(): Boolean = sourceFile.isFile && sourceFile.length() > 0L

    fun save(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 {
        val normalized = ReceiptStampPolicyV136.normalize(settings)
        require(!normalized.enabled || hasImage()) { "画像スタンプを有効にするにはPNG/JPEG画像を選択してください" }
        val next = normalized.copy(stampVersion = load().stampVersion + 1L)
        persist(next)
        return next
    }

    fun importImage(uri: Uri): ReceiptStampSettingsV136 {
        val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= ReceiptStampPolicyV136.MAX_SOURCE_BYTES) {
                    "画像は2MB以下のPNG/JPEGを選択してください"
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("画像を読み込めませんでした")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        ReceiptStampPolicyV136.validateSource(bytes.size, bounds.outWidth, bounds.outHeight)
        require(bounds.outMimeType == "image/png" || bounds.outMimeType == "image/jpeg") {
            "PNGまたはJPEG画像を選択してください"
        }

        sourceFile.parentFile?.mkdirs()
        val temporary = File(sourceFile.parentFile, "source_image.tmp")
        temporary.writeBytes(bytes)
        if (sourceFile.exists() && !sourceFile.delete()) {
            temporary.delete()
            throw IllegalStateException("旧スタンプ画像を更新できませんでした")
        }
        require(temporary.renameTo(sourceFile)) { "スタンプ画像を保存できませんでした" }

        val current = load()
        val next = current.copy(
            enabled = true,
            stampVersion = current.stampVersion + 1L,
            sourceName = uri.lastPathSegment.orEmpty().substringAfterLast('/').take(120),
        )
        persist(next)
        return next
    }

    fun clearImage(): ReceiptStampSettingsV136 {
        val current = load()
        if (sourceFile.exists()) require(sourceFile.delete()) { "スタンプ画像を削除できませんでした" }
        val next = current.copy(
            enabled = false,
            stampVersion = current.stampVersion + 1L,
            sourceName = "",
        )
        persist(next)
        return next
    }

    fun raster(settings: ReceiptStampSettingsV136, paper: ReceiptPaper): MonochromeRasterV136? {
        if (!settings.enabled || !hasImage()) return null
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalStateException("スタンプ画像をデコードできませんでした")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ReceiptStampRasterizerV136.rasterize(
                ArgbImageV136(bitmap.width, bitmap.height, pixels),
                settings,
                paper,
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun printPrefix(paper: ReceiptPaper): ByteArray {
        val settings = load()
        val raster = raster(settings, paper) ?: return ByteArray(0)
        return ReceiptStampEscPosV136.encodeRaster(raster)
    }

    fun previewBitmap(settings: ReceiptStampSettingsV136, paper: ReceiptPaper): Bitmap? {
        val raster = raster(settings, paper) ?: return null
        val pixels = IntArray(raster.widthDots * raster.heightDots) { 0xFFFFFFFF.toInt() }
        for (y in 0 until raster.heightDots) {
            for (x in 0 until raster.widthDots) {
                if (raster.isBlack(x, y)) pixels[y * raster.widthDots + x] = 0xFF000000.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, raster.widthDots, raster.heightDots, Bitmap.Config.ARGB_8888)
    }

    private fun persist(settings: ReceiptStampSettingsV136) {
        preferences.edit()
            .putBoolean("enabled", settings.enabled)
            .putInt("brightness", settings.brightness)
            .putInt("threshold", settings.threshold)
            .putString("dither", settings.dither.name)
            .putLong("stamp_version", settings.stampVersion)
            .putString("source_name", settings.sourceName)
            .apply()
    }
}

/**
 * EscPosEncoderは複数部を1 payloadへ連結するため、各部のPrinterCommandEncoder.beginDocument()
 * (`ESC @`, `ESC t`) の直前へ同じ画像スタンプを差し込む。カット設定NONEでも部数を失わない。
 */
object ReceiptStampPayloadComposerV136 {
    private val documentMarker = byteArrayOf(0x1B, 0x40, 0x1B, 0x74)

    fun prependToEachDocument(payload: ByteArray, prefix: ByteArray): ByteArray {
        if (prefix.isEmpty() || payload.isEmpty()) return payload.copyOf()
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= payload.size - documentMarker.size) {
            if (matches(payload, index, documentMarker)) {
                starts += index
                index += documentMarker.size
            } else {
                index++
            }
        }
        if (starts.isEmpty()) {
            val output = ByteArrayOutputStream(prefix.size + payload.size)
            output.write(prefix)
            output.write(payload)
            return output.toByteArray()
        }

        val output = ByteArrayOutputStream(payload.size + prefix.size * starts.size)
        var cursor = 0
        starts.forEach { start ->
            output.write(payload, cursor, start - cursor)
            output.write(prefix)
            cursor = start
        }
        output.write(payload, cursor, payload.size - cursor)
        return output.toByteArray()
    }

    fun countDocuments(payload: ByteArray): Int {
        var count = 0
        var index = 0
        while (index <= payload.size - documentMarker.size) {
            if (matches(payload, index, documentMarker)) {
                count++
                index += documentMarker.size
            } else {
                index++
            }
        }
        return count
    }

    private fun matches(payload: ByteArray, offset: Int, marker: ByteArray): Boolean {
        for (i in marker.indices) {
            if (payload[offset + i] != marker[i]) return false
        }
        return true
    }
}

/** 売上レシートだけにSCR-720画像スタンプを各印刷部の先頭へ付加する。業務帳票へは付加しない。 */
class ReceiptStampGatewayV136(
    context: Context,
    private val delegate: PrinterGateway,
    paperWidthMm: Int,
) : PrinterGateway {
    private val store = ReceiptStampSettingsStoreV136(context.applicationContext)
    private val paper = ReceiptPaper.fromWidth(paperWidthMm)

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        val prefix = store.printPrefix(paper)
        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, prefix)
        delegate.send(composed).getOrThrow()
    }
}

@Composable
fun ReceiptStampSettingsPanelV136() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ReceiptStampSettingsStoreV136(context) }
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(revision) { store.load() }
    var enabled by remember(revision) { mutableStateOf(loaded.enabled) }
    var brightnessText by remember(revision) { mutableStateOf(loaded.brightness.toString()) }
    var thresholdText by remember(revision) { mutableStateOf(loaded.threshold.toString()) }
    var dither by remember(revision) { mutableStateOf(loaded.dither) }
    var message by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { store.importImage(uri) }
                .onSuccess {
                    revision++
                    message = "画像スタンプを取込みました（version ${it.stampVersion}）"
                }
                .onFailure { message = it.message ?: "画像を取込めませんでした" }
        }
    }

    val brightness = brightnessText.toIntOrNull()
    val threshold = thresholdText.toIntOrNull()
    val draft = if (brightness != null && threshold != null) {
        loaded.copy(enabled = enabled, brightness = brightness, threshold = threshold, dither = dither)
    } else {
        null
    }
    val validDraft = draft?.takeIf {
        it.brightness in ReceiptStampPolicyV136.MIN_BRIGHTNESS..ReceiptStampPolicyV136.MAX_BRIGHTNESS &&
            it.threshold in ReceiptStampPolicyV136.MIN_THRESHOLD..ReceiptStampPolicyV136.MAX_THRESHOLD
    }
    val preview58 = remember(revision, validDraft) {
        validDraft?.let { runCatching { store.previewBitmap(it, ReceiptPaper.MM58) }.getOrNull() }
    }
    val preview80 = remember(revision, validDraft) {
        validDraft?.let { runCatching { store.previewBitmap(it, ReceiptPaper.MM80) }.getOrNull() }
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Text("店名画像スタンプ（SCR-720）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "PNG/JPEG・2MB以下・2000×2000px以下。透過は白へ合成し、58mm=384dot / 80mm=576dot以内へ縦横比を維持して縮小します。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text(if (store.hasImage()) "画像を差替" else "画像を選択")
            }
            OutlinedButton(
                onClick = {
                    runCatching { store.clearImage() }
                        .onSuccess { revision++; message = "画像スタンプを削除しました" }
                        .onFailure { message = it.message ?: "画像を削除できませんでした" }
                },
                enabled = store.hasImage(),
                modifier = Modifier.weight(1f),
            ) { Text("画像を削除") }
        }
        if (loaded.sourceName.isNotBlank()) {
            Text("画像: ${loaded.sourceName} / stampVersion ${loaded.stampVersion}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth()) {
            Checkbox(checked = enabled, onCheckedChange = { enabled = it }, enabled = store.hasImage())
            Column {
                Text("レシートへ画像スタンプを印字")
                Text("変更は次回印刷から反映", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = brightnessText,
                onValueChange = { brightnessText = it.filter { c -> c.isDigit() || c == '-' }.take(4) },
                label = { Text("明るさ（-100～100）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { thresholdText = it.filter(Char::isDigit).take(3) },
                label = { Text("閾値（0～255）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "明るさ/閾値の数値範囲はv2.5に数値指定がないためv1.36実装値です。値を変えると白黒ラスタ自体が変化します。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReceiptStampDitherV136.entries.forEach { mode ->
                OutlinedButton(
                    onClick = { dither = mode },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (dither == mode) "● ${mode.displayName}" else mode.displayName)
                }
            }
        }
        if (validDraft == null) {
            Text("明るさは-100～100、閾値は0～255で入力してください", color = MaterialTheme.colorScheme.error)
        }
        if (preview58 != null) {
            Text("58mm実幅プレビュー（最大384dot）", fontWeight = FontWeight.Bold)
            Image(
                bitmap = preview58.asImageBitmap(),
                contentDescription = "58mm画像スタンププレビュー",
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (preview80 != null) {
            Text("80mm実幅プレビュー（最大576dot）", fontWeight = FontWeight.Bold)
            Image(
                bitmap = preview80.asImageBitmap(),
                contentDescription = "80mm画像スタンププレビュー",
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Button(
            onClick = {
                val candidate = validDraft
                if (candidate == null) {
                    message = "明るさ/閾値を確認してください"
                } else {
                    runCatching { store.save(candidate) }
                        .onSuccess {
                            revision++
                            message = "画像スタンプ設定を保存しました（version ${it.stampVersion}）"
                        }
                        .onFailure { message = it.message ?: "画像スタンプ設定を保存できませんでした" }
                }
            },
            enabled = validDraft != null && (!enabled || store.hasImage()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("画像スタンプ設定を保存") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}