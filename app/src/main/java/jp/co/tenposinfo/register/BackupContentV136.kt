package jp.co.tenposinfo.register

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Formal v2.5 BKP-003 backup-content bundle.
 *
 * The database remains the authoritative home for transactional/master/sync state. This bundle
 * adds the operational state that is intentionally stored outside SQLite. It lives inside the
 * already AES-256-GCM protected BackupEnvelopeV136 payload; no preference/image is exported in
 * plaintext.
 */
internal object BackupContentBundleV136 {
    const val FORMAT = "TSUGUREGI_CONTENT_V1"
    const val CONTENT_MANIFEST_ENTRY = "content/manifest.properties"
    const val RECEIPT_STAMP_ENTRY = "content/files/receipt_stamp_v136/source_image.bin"
    private const val RECEIPT_STAMP_RELATIVE_PATH = "receipt_stamp_v136/source_image.bin"
    private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
    private const val MAX_PREFERENCE_BYTES = 2L * 1024L * 1024L
    private const val MAX_STAMP_BYTES = 2L * 1024L * 1024L

    /**
     * Explicit allowlist: business configuration only. Runtime status, active operator sessions,
     * crash diagnostics and OAuth authorization state are deliberately excluded.
     */
    val PREFERENCE_NAMES: List<String> = listOf(
        "initial_release_settings_v135",
        "tax_invoice_settings",
        "document_print_settings_v136",
        "customer_display_server",
        "receipt_stamp_settings_v136",
        "auto_backup_settings_v2",
        "drive_sync_foundation",
        "outbox_delivery_settings_v1",
        "external_backup_settings_v1",
    )

    data class Manifest(
        val preferenceSha256: Map<String, String>,
        val preferenceBytes: Map<String, Long>,
        val receiptStampPresent: Boolean,
        val receiptStampSha256: String?,
        val receiptStampBytes: Long,
    )

    internal data class Rollback(
        private val context: Context,
        private val root: File,
    ) {
        fun restore() {
            applySnapshot(context.applicationContext, root)
            root.deleteRecursively()
        }

        fun discard() {
            root.deleteRecursively()
        }
    }

    fun writeTo(context: Context, zip: ZipOutputStream, createdAt: Long) {
        val appContext = context.applicationContext
        val encodedPreferences = PREFERENCE_NAMES.associateWith { name ->
            BackupPreferenceCodecV136.encode(
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all,
            )
        }
        val stampFile = File(appContext.filesDir, RECEIPT_STAMP_RELATIVE_PATH)
        require(!stampFile.isFile || stampFile.length() in 1..MAX_STAMP_BYTES) {
            "レシート画像スタンプのサイズが不正です"
        }
        val manifest = Manifest(
            preferenceSha256 = encodedPreferences.mapValues { (_, bytes) -> sha256(bytes) },
            preferenceBytes = encodedPreferences.mapValues { (_, bytes) -> bytes.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
        )
        writeBytes(zip, CONTENT_MANIFEST_ENTRY, encodeManifest(manifest), createdAt)
        PREFERENCE_NAMES.forEach { name ->
            writeBytes(zip, preferenceEntry(name), encodedPreferences.getValue(name), createdAt)
        }
        if (manifest.receiptStampPresent) {
            zip.putNextEntry(ZipEntry(RECEIPT_STAMP_ENTRY).apply { time = createdAt })
            stampFile.inputStream().buffered().use { input -> copyWithLimit(input, zip, MAX_STAMP_BYTES) }
            zip.closeEntry()
        }
    }

    /**
     * Validate all BKP-003 content entries and extract them under [targetRoot]. Returns false for a
     * legacy DB-only inner bundle so existing encrypted backups remain restorable.
     */
    fun extractAndVerify(archive: File, targetRoot: File): Boolean = ZipFile(archive).use { zip ->
        val allNames = zip.entries().asSequence().map { it.name }.toSet()
        val contentNames = allNames.filter { it.startsWith("content/") }.toSet()
        if (CONTENT_MANIFEST_ENTRY !in contentNames) {
            require(contentNames.isEmpty()) { "バックアップcontent manifestがありません" }
            return@use false
        }

        val manifestEntry = zip.getEntry(CONTENT_MANIFEST_ENTRY)
            ?: error("バックアップcontent manifestがありません")
        require(!manifestEntry.isDirectory && manifestEntry.size in 1..MAX_MANIFEST_BYTES) {
            "バックアップcontent manifestのサイズが不正です"
        }
        val manifestBytes = zip.getInputStream(manifestEntry).use { readLimited(it, MAX_MANIFEST_BYTES) }
        val manifest = decodeManifest(manifestBytes)
        val expected = buildSet {
            add(CONTENT_MANIFEST_ENTRY)
            PREFERENCE_NAMES.forEach { add(preferenceEntry(it)) }
            if (manifest.receiptStampPresent) add(RECEIPT_STAMP_ENTRY)
        }
        require(contentNames == expected) {
            "バックアップcontentのファイル構成が不正です"
        }

        targetRoot.deleteRecursively()
        targetRoot.mkdirs()
        val contentRoot = File(targetRoot, "content").apply { mkdirs() }
        File(contentRoot, "manifest.properties").writeBytes(manifestBytes)

        PREFERENCE_NAMES.forEach { name ->
            val entry = zip.getEntry(preferenceEntry(name)) ?: error("設定バックアップがありません: $name")
            val expectedBytes = manifest.preferenceBytes.getValue(name)
            require(expectedBytes in 0..MAX_PREFERENCE_BYTES) { "設定バックアップのサイズが不正です: $name" }
            require(!entry.isDirectory && entry.size == expectedBytes) { "設定バックアップのサイズが一致しません: $name" }
            val bytes = zip.getInputStream(entry).use { readLimited(it, MAX_PREFERENCE_BYTES) }
            require(sha256(bytes) == manifest.preferenceSha256.getValue(name)) { "設定バックアップのSHA-256が一致しません: $name" }
            // Parse now so malformed typed preferences fail before restore is staged.
            BackupPreferenceCodecV136.decode(bytes)
            val target = File(contentRoot, "settings/$name.pref")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        val stampTarget = File(contentRoot, "files/$RECEIPT_STAMP_RELATIVE_PATH")
        if (manifest.receiptStampPresent) {
            val entry = zip.getEntry(RECEIPT_STAMP_ENTRY) ?: error("レシート画像スタンプがありません")
            require(manifest.receiptStampBytes in 1..MAX_STAMP_BYTES && entry.size == manifest.receiptStampBytes) {
                "レシート画像スタンプのサイズが一致しません"
            }
            stampTarget.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                stampTarget.outputStream().buffered().use { output -> copyWithLimit(input, output, MAX_STAMP_BYTES) }
            }
            require(sha256(stampTarget) == manifest.receiptStampSha256) {
                "レシート画像スタンプのSHA-256が一致しません"
            }
        } else {
            require(manifest.receiptStampSha256 == null && manifest.receiptStampBytes == 0L) {
                "レシート画像スタンプmanifestが不正です"
            }
        }
        validateSnapshotDirectory(targetRoot)
        true
    }

    fun hasStagedContent(root: File): Boolean = File(root, "content/manifest.properties").isFile

    /**
     * Apply staged operational state after the database has passed migration/final verification.
     * Current operational state is snapshotted first. Any apply error self-rolls back; callers can
     * retain the returned rollback until the enclosing database restore is committed.
     */
    fun applyStagedWithRollback(context: Context, stagedRoot: File, restoreDir: File): Rollback? {
        if (!hasStagedContent(stagedRoot)) return null
        validateSnapshotDirectory(stagedRoot)
        val rollbackRoot = File(restoreDir, "content-rollback-v136-${System.currentTimeMillis()}")
        captureSnapshotDirectory(context.applicationContext, rollbackRoot)
        return try {
            applySnapshot(context.applicationContext, stagedRoot)
            Rollback(context.applicationContext, rollbackRoot)
        } catch (error: Throwable) {
            runCatching { applySnapshot(context.applicationContext, rollbackRoot) }
            rollbackRoot.deleteRecursively()
            throw error
        }
    }

    fun copyVerifiedSnapshot(sourceRoot: File, targetRoot: File) {
        validateSnapshotDirectory(sourceRoot)
        targetRoot.deleteRecursively()
        sourceRoot.copyRecursively(targetRoot, overwrite = true)
        validateSnapshotDirectory(targetRoot)
    }

    fun removePending(root: File) {
        root.deleteRecursively()
    }

    private fun captureSnapshotDirectory(context: Context, root: File) {
        root.deleteRecursively()
        val contentRoot = File(root, "content").apply { mkdirs() }
        val encoded = PREFERENCE_NAMES.associateWith { name ->
            BackupPreferenceCodecV136.encode(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
        val stampFile = File(context.filesDir, RECEIPT_STAMP_RELATIVE_PATH)
        val manifest = Manifest(
            preferenceSha256 = encoded.mapValues { sha256(it.value) },
            preferenceBytes = encoded.mapValues { it.value.size.toLong() },
            receiptStampPresent = stampFile.isFile,
            receiptStampSha256 = stampFile.takeIf(File::isFile)?.let(::sha256),
            receiptStampBytes = stampFile.takeIf(File::isFile)?.length() ?: 0L,
        )
        File(contentRoot, "manifest.properties").writeBytes(encodeManifest(manifest))
        encoded.forEach { (name, bytes) ->
            File(contentRoot, "settings/$name.pref").also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
        }
        if (stampFile.isFile) {
            require(stampFile.length() in 1..MAX_STAMP_BYTES) { "現在のレシート画像スタンプが大きすぎます" }
            val target = File(contentRoot, "files/$RECEIPT_STAMP_RELATIVE_PATH")
            target.parentFile?.mkdirs()
            stampFile.copyTo(target, overwrite = true)
        }
        validateSnapshotDirectory(root)
    }

    private fun validateSnapshotDirectory(root: File): Manifest {
        val contentRoot = File(root, "content")
        val manifestFile = File(contentRoot, "manifest.properties")
        require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES) { "復元content manifestがありません" }
        val manifest = decodeManifest(manifestFile.readBytes())
        PREFERENCE_NAMES.forEach { name ->
            val file = File(contentRoot, "settings/$name.pref")
            require(file.isFile && file.length() == manifest.preferenceBytes.getValue(name)) { "復元設定のサイズが一致しません: $name" }
            require(sha256(file) == manifest.preferenceSha256.getValue(name)) { "復元設定のSHA-256が一致しません: $name" }
            BackupPreferenceCodecV136.decode(file.readBytes())
        }
        val stamp = File(contentRoot, "files/$RECEIPT_STAMP_RELATIVE_PATH")
        if (manifest.receiptStampPresent) {
            require(stamp.isFile && stamp.length() == manifest.receiptStampBytes) { "復元画像スタンプのサイズが一致しません" }
            require(sha256(stamp) == manifest.receiptStampSha256) { "復元画像スタンプのSHA-256が一致しません" }
        } else {
            require(!stamp.exists() && manifest.receiptStampSha256 == null && manifest.receiptStampBytes == 0L) {
                "復元画像スタンプmanifestが不正です"
            }
        }
        return manifest
    }

    private fun applySnapshot(context: Context, root: File) {
        val manifest = validateSnapshotDirectory(root)
        val contentRoot = File(root, "content")
        PREFERENCE_NAMES.forEach { name ->
            val values = BackupPreferenceCodecV136.decode(File(contentRoot, "settings/$name.pref").readBytes())
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.toSortedMap().forEach { (key, value) -> BackupPreferenceCodecV136.put(editor, key, value) }
            require(editor.commit()) { "設定を復元できません: $name" }
        }

        val liveStamp = File(context.filesDir, RECEIPT_STAMP_RELATIVE_PATH)
        val stagedStamp = File(contentRoot, "files/$RECEIPT_STAMP_RELATIVE_PATH")
        if (manifest.receiptStampPresent) {
            liveStamp.parentFile?.mkdirs()
            val temporary = File(liveStamp.parentFile, "source_image.restore.tmp")
            stagedStamp.copyTo(temporary, overwrite = true)
            require(sha256(temporary) == manifest.receiptStampSha256) { "画像スタンプ復元コピーのSHA-256が一致しません" }
            DataProtectionManager.atomicReplace(temporary, liveStamp)
        } else {
            liveStamp.delete()
        }

        // SAF URI strings are useful connection information, but the OS permission grant itself is
        // not transferable. Fail safe by disabling only when this device does not own the grant.
        normalizeExternalDestinationPermissions(context)
    }

    private fun normalizeExternalDestinationPermissions(context: Context) {
        runCatching {
            val store = OutboxDeliverySettingsStore(context)
            val settings = store.load()
            val uri = settings.treeUri?.let(Uri::parse)
            if (settings.enabled && (uri == null || !hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
        }
        runCatching {
            val store = ExternalBackupSettingsStore(context)
            val settings = store.load()
            val uri = settings.treeUri?.let(Uri::parse)
            if (settings.enabled && (uri == null || !hasPersistedWritePermission(context, uri))) {
                store.save(settings.copy(enabled = false))
            }
        }
    }

    private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }

    private fun preferenceEntry(name: String): String {
        require(name.matches(Regex("[a-z0-9_]+"))) { "設定バックアップ名が不正です" }
        return "content/settings/$name.pref"
    }

    private fun encodeManifest(manifest: Manifest): ByteArray = buildString {
        appendLine("format=$FORMAT")
        PREFERENCE_NAMES.forEach { name ->
            appendLine("pref.$name.sha256=${manifest.preferenceSha256.getValue(name)}")
            appendLine("pref.$name.bytes=${manifest.preferenceBytes.getValue(name)}")
        }
        appendLine("receipt_stamp.present=${manifest.receiptStampPresent}")
        appendLine("receipt_stamp.bytes=${manifest.receiptStampBytes}")
        manifest.receiptStampSha256?.let { appendLine("receipt_stamp.sha256=$it") }
    }.toByteArray(Charsets.UTF_8)

    private fun decodeManifest(bytes: ByteArray): Manifest {
        require(bytes.size.toLong() in 1..MAX_MANIFEST_BYTES) { "content manifestのサイズが不正です" }
        val properties = linkedMapOf<String, String>()
        bytes.toString(Charsets.UTF_8).lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val separator = line.indexOf('=')
            require(separator > 0) { "content manifestの形式が不正です" }
            val key = line.substring(0, separator)
            require(properties.put(key, line.substring(separator + 1)) == null) { "content manifestに重複keyがあります" }
        }
        require(properties["format"] == FORMAT) { "未対応のcontent bundle形式です" }
        val hashes = PREFERENCE_NAMES.associateWith { name ->
            properties.getValue("pref.$name.sha256").lowercase(Locale.ROOT).also(::requireSha256)
        }
        val sizes = PREFERENCE_NAMES.associateWith { name ->
            properties.getValue("pref.$name.bytes").toLong().also { require(it in 0..MAX_PREFERENCE_BYTES) }
        }
        val present = properties.getValue("receipt_stamp.present").toBooleanStrict()
        val stampBytes = properties.getValue("receipt_stamp.bytes").toLong()
        val stampHash = properties["receipt_stamp.sha256"]?.lowercase(Locale.ROOT)?.also(::requireSha256)
        if (present) {
            require(stampBytes in 1..MAX_STAMP_BYTES && stampHash != null) { "画像スタンプmanifestが不正です" }
        } else {
            require(stampBytes == 0L && stampHash == null) { "画像スタンプmanifestが不正です" }
        }
        val expectedKeys = buildSet {
            add("format")
            PREFERENCE_NAMES.forEach { name -> add("pref.$name.sha256"); add("pref.$name.bytes") }
            add("receipt_stamp.present")
            add("receipt_stamp.bytes")
            if (present) add("receipt_stamp.sha256")
        }
        require(properties.keys == expectedKeys) { "content manifestに未知の項目があります" }
        return Manifest(hashes, sizes, present, stampHash, stampBytes)
    }

    private fun requireSha256(value: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "content SHA-256が不正です" }
    }

    private fun writeBytes(zip: ZipOutputStream, name: String, bytes: ByteArray, createdAt: Long) {
        zip.putNextEntry(ZipEntry(name).apply { time = createdAt })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyWithLimit(input, output, maxBytes)
        return output.toByteArray()
    }

    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "バックアップcontentが上限サイズを超えています" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun sha256(file: File): String = DataProtectionManager.sha256(file)
}

/** Stable typed serialization for allowlisted SharedPreferences. */
internal object BackupPreferenceCodecV136 {
    private const val FORMAT = "TSUGUREGI_PREFS_V1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(values: Map<String, *>): ByteArray = buildString {
        appendLine(FORMAT)
        values.toSortedMap().forEach { (key, value) ->
            val encodedKey = b64(key)
            when (value) {
                is String -> appendLine("S\t$encodedKey\t${b64(value)}")
                is Boolean -> appendLine("B\t$encodedKey\t$value")
                is Int -> appendLine("I\t$encodedKey\t$value")
                is Long -> appendLine("L\t$encodedKey\t$value")
                is Float -> appendLine("F\t$encodedKey\t${value.toRawBits()}")
                is Set<*> -> {
                    require(value.all { it is String }) { "SharedPreferences SetはStringのみ対応です: $key" }
                    val items = value.filterIsInstance<String>().sorted().joinToString(",") { b64(it) }
                    appendLine("T\t$encodedKey\t$items")
                }
                else -> error("未対応のSharedPreferences型です: $key / ${value?.javaClass?.name}")
            }
        }
    }.toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Map<String, Any> {
        require(bytes.size <= 2 * 1024 * 1024) { "設定バックアップが大きすぎます" }
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().toList()
        require(lines.firstOrNull() == FORMAT) { "設定バックアップ形式が不正です" }
        val result = linkedMapOf<String, Any>()
        lines.drop(1).filter(String::isNotBlank).forEach { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "設定バックアップ行の形式が不正です" }
            val key = fromB64(parts[1])
            require(key.isNotBlank() && key !in result) { "設定バックアップkeyが不正または重複しています" }
            val value: Any = when (parts[0]) {
                "S" -> fromB64(parts[2])
                "B" -> parts[2].toBooleanStrict()
                "I" -> parts[2].toInt()
                "L" -> parts[2].toLong()
                "F" -> Float.fromBits(parts[2].toInt())
                "T" -> if (parts[2].isEmpty()) emptySet<String>() else parts[2].split(',').map(::fromB64).toSet()
                else -> error("未対応の設定バックアップ型です")
            }
            result[key] = value
        }
        return result
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("未対応の復元設定型です: $key")
        }
    }

    private fun b64(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun fromB64(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)
}
