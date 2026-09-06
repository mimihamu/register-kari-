from pathlib import Path

path = Path('app/src/main/java/jp/co/tenposinfo/register/ReceiptStampV136.kt')
text = path.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one anchor, found {count}')
    text = text.replace(old, new, 1)

replace_once(
'''    val dither: ReceiptStampDitherV136 = ReceiptStampDitherV136.THRESHOLD,\n    val stampVersion: Long = 0L,''',
'''    val dither: ReceiptStampDitherV136 = ReceiptStampDitherV136.THRESHOLD,\n    val rotationDegrees: Int = 0,\n    val cropPercent: Int = 0,\n    val stampVersion: Long = 0L,''',
'data class settings',
)

replace_once(
'''    fun normalize(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 = settings.copy(\n        brightness = settings.brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),\n        threshold = settings.threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),\n        sourceName = settings.sourceName.trim().take(120),\n    )''',
'''    fun normalize(settings: ReceiptStampSettingsV136): ReceiptStampSettingsV136 = settings.copy(\n        brightness = settings.brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),\n        threshold = settings.threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),\n        rotationDegrees = normalizeRotation(settings.rotationDegrees),\n        cropPercent = settings.cropPercent.coerceIn(0, MAX_CROP_PERCENT),\n        sourceName = settings.sourceName.trim().take(120),\n    )\n\n    const val MAX_CROP_PERCENT = 40\n\n    fun normalizeRotation(degrees: Int): Int {\n        val normalized = ((degrees % 360) + 360) % 360\n        return if (normalized in setOf(0, 90, 180, 270)) normalized else 0\n    }''',
'normalize settings',
)

marker = '''object ReceiptStampRasterizerV136 {\n'''
transformer = '''object ReceiptStampTransformV136 {\n    fun apply(image: ArgbImageV136, settings: ReceiptStampSettingsV136): ArgbImageV136 {\n        val normalized = ReceiptStampPolicyV136.normalize(settings)\n        val cropX = (image.width * normalized.cropPercent / 100.0).roundToInt()\n            .coerceIn(0, (image.width - 1) / 2)\n        val cropY = (image.height * normalized.cropPercent / 100.0).roundToInt()\n            .coerceIn(0, (image.height - 1) / 2)\n        val croppedWidth = image.width - cropX * 2\n        val croppedHeight = image.height - cropY * 2\n        val cropped = IntArray(croppedWidth * croppedHeight)\n        for (y in 0 until croppedHeight) {\n            val sourceOffset = (y + cropY) * image.width + cropX\n            image.pixels.copyInto(cropped, y * croppedWidth, sourceOffset, sourceOffset + croppedWidth)\n        }\n        val source = ArgbImageV136(croppedWidth, croppedHeight, cropped)\n        return rotate(source, normalized.rotationDegrees)\n    }\n\n    private fun rotate(source: ArgbImageV136, degrees: Int): ArgbImageV136 = when (degrees) {\n        0 -> source\n        90 -> {\n            val width = source.height\n            val height = source.width\n            val out = IntArray(width * height)\n            for (y in 0 until source.height) for (x in 0 until source.width) {\n                val newX = source.height - 1 - y\n                val newY = x\n                out[newY * width + newX] = source.pixels[y * source.width + x]\n            }\n            ArgbImageV136(width, height, out)\n        }\n        180 -> {\n            val out = IntArray(source.pixels.size)\n            for (index in source.pixels.indices) out[source.pixels.lastIndex - index] = source.pixels[index]\n            ArgbImageV136(source.width, source.height, out)\n        }\n        270 -> {\n            val width = source.height\n            val height = source.width\n            val out = IntArray(width * height)\n            for (y in 0 until source.height) for (x in 0 until source.width) {\n                val newX = y\n                val newY = source.width - 1 - x\n                out[newY * width + newX] = source.pixels[y * source.width + x]\n            }\n            ArgbImageV136(width, height, out)\n        }\n        else -> error("unsupported rotation: $degrees")\n    }\n}\n\nobject ReceiptStampRasterizerV136 {\n'''
replace_once(marker, transformer, 'rasterizer marker')

replace_once(
'''        val normalized = ReceiptStampPolicyV136.normalize(settings)\n        val (targetWidth, targetHeight) = ReceiptStampPolicyV136.fitDimensions(image.width, image.height, paper)\n        val pixels = if (targetWidth == image.width && targetHeight == image.height) {\n            image.pixels.copyOf()\n        } else {\n            scaleNearest(image, targetWidth, targetHeight)\n        }''',
'''        val normalized = ReceiptStampPolicyV136.normalize(settings)\n        val transformed = ReceiptStampTransformV136.apply(image, normalized)\n        val (targetWidth, targetHeight) = ReceiptStampPolicyV136.fitDimensions(transformed.width, transformed.height, paper)\n        val pixels = if (targetWidth == transformed.width && targetHeight == transformed.height) {\n            transformed.pixels.copyOf()\n        } else {\n            scaleNearest(transformed, targetWidth, targetHeight)\n        }''',
'raster transform',
)

replace_once(
'''            dither = runCatching {\n                ReceiptStampDitherV136.valueOf(\n                    preferences.getString("dither", ReceiptStampDitherV136.THRESHOLD.name).orEmpty(),\n                )\n            }.getOrDefault(ReceiptStampDitherV136.THRESHOLD),\n            stampVersion = preferences.getLong("stamp_version", 0L).coerceAtLeast(0L),''',
'''            dither = runCatching {\n                ReceiptStampDitherV136.valueOf(\n                    preferences.getString("dither", ReceiptStampDitherV136.THRESHOLD.name).orEmpty(),\n                )\n            }.getOrDefault(ReceiptStampDitherV136.THRESHOLD),\n            rotationDegrees = preferences.getInt("rotation_degrees", 0),\n            cropPercent = preferences.getInt("crop_percent", 0),\n            stampVersion = preferences.getLong("stamp_version", 0L).coerceAtLeast(0L),''',
'load settings',
)

replace_once(
'''            .putString("dither", settings.dither.name)\n            .putLong("stamp_version", settings.stampVersion)''',
'''            .putString("dither", settings.dither.name)\n            .putInt("rotation_degrees", settings.rotationDegrees)\n            .putInt("crop_percent", settings.cropPercent)\n            .putLong("stamp_version", settings.stampVersion)''',
'persist settings',
)

replace_once(
'''    var dither by remember(revision) { mutableStateOf(loaded.dither) }\n    var message by remember { mutableStateOf("") }''',
'''    var dither by remember(revision) { mutableStateOf(loaded.dither) }\n    var rotationDegrees by remember(revision) { mutableIntStateOf(loaded.rotationDegrees) }\n    var cropText by remember(revision) { mutableStateOf(loaded.cropPercent.toString()) }\n    var message by remember { mutableStateOf("") }''',
'ui state',
)

replace_once(
'''    val brightness = brightnessText.toIntOrNull()\n    val threshold = thresholdText.toIntOrNull()\n    val draft = if (brightness != null && threshold != null) {\n        loaded.copy(enabled = enabled, brightness = brightness, threshold = threshold, dither = dither)\n    } else {\n        null\n    }\n    val validDraft = draft?.takeIf {\n        it.brightness in ReceiptStampPolicyV136.MIN_BRIGHTNESS..ReceiptStampPolicyV136.MAX_BRIGHTNESS &&\n            it.threshold in ReceiptStampPolicyV136.MIN_THRESHOLD..ReceiptStampPolicyV136.MAX_THRESHOLD\n    }''',
'''    val brightness = brightnessText.toIntOrNull()\n    val threshold = thresholdText.toIntOrNull()\n    val cropPercent = cropText.toIntOrNull()\n    val draft = if (brightness != null && threshold != null && cropPercent != null) {\n        loaded.copy(\n            enabled = enabled,\n            brightness = brightness,\n            threshold = threshold,\n            dither = dither,\n            rotationDegrees = rotationDegrees,\n            cropPercent = cropPercent,\n        )\n    } else {\n        null\n    }\n    val validDraft = draft?.takeIf {\n        it.brightness in ReceiptStampPolicyV136.MIN_BRIGHTNESS..ReceiptStampPolicyV136.MAX_BRIGHTNESS &&\n            it.threshold in ReceiptStampPolicyV136.MIN_THRESHOLD..ReceiptStampPolicyV136.MAX_THRESHOLD &&\n            it.rotationDegrees in setOf(0, 90, 180, 270) &&\n            it.cropPercent in 0..ReceiptStampPolicyV136.MAX_CROP_PERCENT\n    }''',
'draft settings',
)

ui_anchor = '''        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n            OutlinedTextField(\n                value = brightnessText,'''
ui_insert = '''        Text("切抜き・回転", fontWeight = FontWeight.Bold)\n        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {\n            listOf(0, 90, 180, 270).forEach { degrees ->\n                OutlinedButton(\n                    onClick = { rotationDegrees = degrees },\n                    modifier = Modifier.weight(1f),\n                ) {\n                    Text(if (rotationDegrees == degrees) "● ${degrees}°" else "${degrees}°")\n                }\n            }\n        }\n        OutlinedTextField(\n            value = cropText,\n            onValueChange = { cropText = it.filter(Char::isDigit).take(2) },\n            label = { Text("中央切抜き（四辺 0～40%）") },\n            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),\n            modifier = Modifier.fillMaxWidth(),\n        )\n        Text(\n            "四辺を同率で切抜いた後に回転し、その結果を58/80mmの印字可能幅へ縮小します。プレビューと実印刷は同じ変換結果です。",\n            style = MaterialTheme.typography.bodySmall,\n        )\n        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n            OutlinedTextField(\n                value = brightnessText,'''
replace_once(ui_anchor, ui_insert, 'ui crop rotation')

replace_once(
'''            Text("明るさは-100～100、閾値は0～255で入力してください", color = MaterialTheme.colorScheme.error)''',
'''            Text("明るさ-100～100、閾値0～255、切抜き0～40%で入力してください", color = MaterialTheme.colorScheme.error)''',
'validation message',
)

path.write_text(text, encoding='utf-8')
print('SCR-720 crop/rotation patch applied')
