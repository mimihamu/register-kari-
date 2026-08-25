package jp.co.tenposinfo.register

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2.5 APP-DATA-001 encrypted backup envelope.
 *
 * The legacy database bundle is created only under app-private cache. The bundle is then encrypted
 * with a random 256-bit DEK using AES-256-GCM. The DEK is always wrapped by Android Keystore for
 * same-device use. A manual external export additionally carries a PBKDF2-HMAC-SHA256 passphrase
 * wrap so the same encrypted payload can be recovered on a replacement terminal.
 */
object BackupEnvelopeV136 {
    const val FORMAT = "TSUGUREGI_BACKUP_V2"
    const val MANIFEST_ENTRY = "backup_manifest.json"
    const val PAYLOAD_ENTRY = "payload.bin"
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 210_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 16
    const val GCM_NONCE_BYTES = 12
    const val MAX_ENVELOPE_BYTES = 600L * 1024L * 1024L
    private const val KEY_ALIAS = "tsuguregi.backup.wrap.v2"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD = "TSUGUREGI_BACKUP_V2:payload.bin".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    data class Manifest(
        val createdAt: Long,
        val appVersion: String,
        val databaseUserVersion: Int,
        val databaseSha256: String,
        val databaseBytes: Long,
        val tableCounts: Map<String, Long>,
        val plainBundleSha256: String,
        val encryptedPayloadSha256: String,
        val payloadNonce: ByteArray,
        val deviceWrapNonce: ByteArray,
        val deviceWrappedDek: ByteArray,
        val portableSalt: ByteArray? = null,
        val portableIterations: Int? = null,
        val portableWrapNonce: ByteArray? = null,
        val portableWrappedDek: ByteArray? = null,
    ) {
        val portable: Boolean
            get() = portableSalt != null && portableIterations != null &&
                portableWrapNonce != null && portableWrappedDek != null

        fun toBackupManifest(): BackupManifest = BackupManifest(
            format = FORMAT,
            createdAt = createdAt,
            appVersion = appVersion,
            databaseUserVersion = databaseUserVersion,
            databaseSha256 = databaseSha256,
            tableCounts = tableCounts,
        )
    }

    fun isSecureEnvelope(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry(MANIFEST_ENTRY) != null && zip.getEntry(PAYLOAD_ENTRY) != null
        }
    }.getOrDefault(false)

    fun readBackupManifest(file: File): BackupManifest = readManifest(file).toBackupManifest()

    fun createLocalEnvelope(
        context: Context,
        plainBundle: File,
        metadata: BackupManifest,
        databaseBytes: Long,
        target: File,
    ) {
        require(plainBundle.isFile && plainBundle.length() > 0L) { "暗号化対象バックアップがありません" }
        require(databaseBytes > 0L) { "バックアップDBサイズが不正です" }
        val work = privateWorkDir(context, "encrypt")
        val encrypted = File(work, PAYLOAD_ENTRY)
        val dek = ByteArray(KEY_BITS / 8).also(random::nextBytes)
        try {
            val payloadNonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
            encryptFile(plainBundle, encrypted, SecretKeySpec(dek, "AES"), payloadNonce)
            val deviceWrap = wrapWithDeviceKey(dek)
            val manifest = Manifest(
                createdAt = metadata.createdAt,
                appVersion = metadata.appVersion,
                databaseUserVersion = metadata.databaseUserVersion,
                databaseSha256 = metadata.databaseSha256,
                databaseBytes = databaseBytes,
                tableCounts = metadata.tableCounts,
                plainBundleSha256 = sha256(plainBundle),
                encryptedPayloadSha256 = sha256(encrypted),
                payloadNonce = payloadNonce,
                deviceWrapNonce = deviceWrap.first,
                deviceWrappedDek = deviceWrap.second,
            )
            writeEnvelope(target, encrypted, manifest)
            require(target.isFile && target.length() in 1..MAX_ENVELOPE_BYTES) { "暗号化バックアップを作成できません" }
            selfTestLocal(context, target)
        } finally {
            dek.fill(0)
            work.deleteRecursively()
        }
    }

    /** Decrypt a same-device envelope into app-private storage. */
    fun decryptLocalTo(context: Context, envelope: File, output: File): BackupManifest {
        val manifest = readManifest(envelope)
        val dek = unwrapWithDeviceKey(manifest.deviceWrapNonce, manifest.deviceWrappedDek)
        try {
            decryptPayload(envelope, manifest, dek, output)
            return manifest.toBackupManifest()
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Create a portable package. Output is written only after portable unwrap/decrypt self-test has
     * succeeded, so a SAF destination is never reported successful for an unusable package.
     */
    fun exportPortable(
        context: Context,
        localEnvelope: File,
        passphrase: CharArray,
        output: OutputStream,
    ): BackupManifest {
        requirePassphrase(passphrase)
        val original = readManifest(localEnvelope)
        val dek = unwrapWithDeviceKey(original.deviceWrapNonce, original.deviceWrappedDek)
        val work = privateWorkDir(context, "portable-export")
        val portableFile = File(work, "portable.tgbak")
        try {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val kek = derivePortableKey(passphrase, salt, PBKDF2_ITERATIONS)
            val portableWrap = try { wrapRaw(dek, kek) } finally { kek.encoded?.fill(0) }
            val manifest = original.copy(
                portableSalt = salt,
                portableIterations = PBKDF2_ITERATIONS,
                portableWrapNonce = portableWrap.first,
                portableWrappedDek = portableWrap.second,
            )
            copyPayloadToEnvelope(localEnvelope, portableFile, manifest)
            selfTestPortable(context, portableFile, passphrase)
            FileInputStream(portableFile).buffered().use { input -> copyLimited(input, output, MAX_ENVELOPE_BYTES) }
            return manifest.toBackupManifest()
        } finally {
            dek.fill(0)
            work.deleteRecursively()
        }
    }

    /**
     * Import a portable package, prove the passphrase can decrypt it, then add a device-local
     * Keystore wrap for the current terminal. The original portable wrap is retained for recovery.
     */
    fun importPortable(
        context: Context,
        input: InputStream,
        passphrase: CharArray,
        target: File,
    ): BackupManifest {
        requirePassphrase(passphrase)
        val work = privateWorkDir(context, "portable-import")
        val incoming = File(work, "incoming.tgbak")
        val testPlain = File(work, "self-test.bundle")
        try {
            FileOutputStream(incoming).buffered().use { out -> copyLimited(input, out, MAX_ENVELOPE_BYTES) }
            val manifest = readManifest(incoming)
            require(manifest.portable) { "別端末復元用のパスフレーズ情報がありません" }
            val dek = unwrapPortable(manifest, passphrase)
            try {
                decryptPayload(incoming, manifest, dek, testPlain)
                val deviceWrap = wrapWithDeviceKey(dek)
                val local = manifest.copy(
                    deviceWrapNonce = deviceWrap.first,
                    deviceWrappedDek = deviceWrap.second,
                )
                copyPayloadToEnvelope(incoming, target, local)
                selfTestLocal(context, target)
                return local.toBackupManifest()
            } finally {
                dek.fill(0)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    fun portableSelfTest(context: Context, envelope: File, passphrase: CharArray): BackupManifest {
        requirePassphrase(passphrase)
        val manifest = readManifest(envelope)
        val dek = unwrapPortable(manifest, passphrase)
        val work = privateWorkDir(context, "portable-test")
        try {
            decryptPayload(envelope, manifest, dek, File(work, "verified.bundle"))
            return manifest.toBackupManifest()
        } finally {
            dek.fill(0)
            work.deleteRecursively()
        }
    }

    private fun selfTestLocal(context: Context, envelope: File) {
        val work = privateWorkDir(context, "local-test")
        try {
            decryptLocalTo(context, envelope, File(work, "verified.bundle"))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun selfTestPortable(context: Context, envelope: File, passphrase: CharArray) {
        portableSelfTest(context, envelope, passphrase)
    }

    private fun decryptPayload(envelope: File, manifest: Manifest, dek: ByteArray, output: File) {
        require(envelope.isFile && envelope.length() in 1..MAX_ENVELOPE_BYTES) { "暗号化バックアップのサイズが不正です" }
        output.parentFile?.mkdirs()
        ZipFile(envelope).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            require(names == setOf(MANIFEST_ENTRY, PAYLOAD_ENTRY)) { "暗号化バックアップ内のファイル構成が不正です" }
            val payload = zip.getEntry(PAYLOAD_ENTRY) ?: error("暗号化payloadがありません")
            require(!payload.isDirectory && payload.size in 1..MAX_ENVELOPE_BYTES) { "暗号化payloadのサイズが不正です" }
            val encryptedTmp = File(output.parentFile, "${output.name}.encrypted.tmp")
            try {
                zip.getInputStream(payload).use { input -> FileOutputStream(encryptedTmp).buffered().use { out -> copyLimited(input, out, MAX_ENVELOPE_BYTES) } }
                require(sha256(encryptedTmp) == manifest.encryptedPayloadSha256) { "暗号化payloadのSHA-256が一致しません" }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, manifest.payloadNonce))
                cipher.updateAAD(AAD)
                CipherInputStream(BufferedInputStream(FileInputStream(encryptedTmp)), cipher).use { input ->
                    BufferedOutputStream(FileOutputStream(output)).use { out -> copyLimited(input, out, MAX_ENVELOPE_BYTES) }
                }
                require(sha256(output) == manifest.plainBundleSha256) { "復号バックアップのSHA-256が一致しません" }
            } catch (error: Throwable) {
                output.delete()
                throw IllegalArgumentException("バックアップを復号できません。パスフレーズまたはデータを確認してください", error)
            } finally {
                encryptedTmp.delete()
            }
        }
    }

    private fun encryptFile(input: File, output: File, key: SecretKey, nonce: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        BufferedInputStream(FileInputStream(input)).use { source ->
            CipherOutputStream(BufferedOutputStream(FileOutputStream(output)), cipher).use { encrypted ->
                copyLimited(source, encrypted, MAX_ENVELOPE_BYTES)
            }
        }
    }

    private fun wrapWithDeviceKey(dek: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateDeviceKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(dek)
    }

    private fun unwrapWithDeviceKey(nonce: ByteArray, wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateDeviceKey(), GCMParameterSpec(128, nonce))
        return runCatching { cipher.doFinal(wrapped) }
            .getOrElse { throw IllegalArgumentException("この端末のKeystoreではバックアップ鍵を復号できません", it) }
    }

    private fun getOrCreateDeviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun derivePortableKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        require(iterations >= PBKDF2_ITERATIONS) { "PBKDF2反復回数が安全基準を満たしません" }
        require(salt.size >= SALT_BYTES) { "PBKDF2 saltが短すぎます" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val raw = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
            try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun wrapRaw(dek: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(dek)
    }

    private fun unwrapPortable(manifest: Manifest, passphrase: CharArray): ByteArray {
        require(manifest.portable) { "別端末復元用のパスフレーズ情報がありません" }
        val kek = derivePortableKey(passphrase, manifest.portableSalt!!, manifest.portableIterations!!)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, manifest.portableWrapNonce!!))
            runCatching { cipher.doFinal(manifest.portableWrappedDek!!) }
                .getOrElse { throw IllegalArgumentException("パスフレーズが違うか、鍵情報が破損しています", it) }
        } finally {
            kek.encoded?.fill(0)
        }
    }

    private fun copyPayloadToEnvelope(sourceEnvelope: File, target: File, manifest: Manifest) {
        val work = target.parentFile ?: error("保存先が不正です")
        work.mkdirs()
        val tmp = File(work, "${target.name}.tmp-${System.nanoTime()}")
        try {
            ZipFile(sourceEnvelope).use { source ->
                val payload = source.getEntry(PAYLOAD_ENTRY) ?: error("暗号化payloadがありません")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                    writeManifestEntry(zip, manifest)
                    zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY).apply { time = manifest.createdAt })
                    source.getInputStream(payload).use { input -> copyLimited(input, zip, MAX_ENVELOPE_BYTES) }
                    zip.closeEntry()
                }
            }
            replace(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun writeEnvelope(target: File, encryptedPayload: File, manifest: Manifest) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                writeManifestEntry(zip, manifest)
                zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY).apply { time = manifest.createdAt })
                BufferedInputStream(FileInputStream(encryptedPayload)).use { input -> copyLimited(input, zip, MAX_ENVELOPE_BYTES) }
                zip.closeEntry()
            }
            replace(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun writeManifestEntry(zip: ZipOutputStream, manifest: Manifest) {
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = manifest.createdAt })
        zip.write(encodeManifest(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readManifest(file: File): Manifest {
        require(file.isFile && file.length() in 1..MAX_ENVELOPE_BYTES) { "暗号化バックアップのサイズが不正です" }
        return ZipFile(file).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("backup_manifest.jsonがありません")
            require(!entry.isDirectory && entry.size in 1..1_048_576) { "backup_manifest.jsonのサイズが不正です" }
            decodeManifest(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }

    private fun encodeManifest(m: Manifest): String = JSONObject().apply {
        put("format", FORMAT)
        put("created_at", m.createdAt)
        put("app_version", m.appVersion)
        put("database_user_version", m.databaseUserVersion)
        put("database_sha256", m.databaseSha256.lowercase())
        put("database_bytes", m.databaseBytes)
        put("plain_bundle_sha256", m.plainBundleSha256.lowercase())
        put("encrypted_payload_sha256", m.encryptedPayloadSha256.lowercase())
        put("payload_nonce", b64(m.payloadNonce))
        put("device_wrap_nonce", b64(m.deviceWrapNonce))
        put("device_wrapped_dek", b64(m.deviceWrappedDek))
        put("table_counts", JSONObject().apply { m.tableCounts.toSortedMap().forEach { (key, value) -> put(key, value) } })
        if (m.portable) {
            put("portable", true)
            put("portable_kdf", PBKDF2_ALGORITHM)
            put("portable_salt", b64(m.portableSalt!!))
            put("portable_iterations", m.portableIterations!!)
            put("portable_wrap_nonce", b64(m.portableWrapNonce!!))
            put("portable_wrapped_dek", b64(m.portableWrappedDek!!))
        } else {
            put("portable", false)
        }
    }.toString()

    private fun decodeManifest(text: String): Manifest {
        val json = JSONObject(text)
        require(json.getString("format") == FORMAT) { "未対応の暗号化バックアップ形式です" }
        fun hash(name: String): String = json.getString(name).lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "$name が不正です" }
        }
        val payloadNonce = unb64(json.getString("payload_nonce"))
        val deviceNonce = unb64(json.getString("device_wrap_nonce"))
        val deviceWrapped = unb64(json.getString("device_wrapped_dek"))
        require(payloadNonce.size == GCM_NONCE_BYTES && deviceNonce.size == GCM_NONCE_BYTES) { "GCM nonce長が不正です" }
        require(deviceWrapped.isNotEmpty()) { "端末鍵ラップがありません" }
        val countsJson = json.getJSONObject("table_counts")
        val counts = linkedMapOf<String, Long>()
        countsJson.keys().forEach { key -> counts[key] = countsJson.getLong(key) }
        val portable = json.optBoolean("portable", false)
        val iterations = if (portable) json.getInt("portable_iterations") else null
        if (portable) {
            require(json.getString("portable_kdf") == PBKDF2_ALGORITHM) { "未対応のパスフレーズKDFです" }
            require(iterations!! >= PBKDF2_ITERATIONS) { "PBKDF2反復回数が安全基準を満たしません" }
        }
        return Manifest(
            createdAt = json.getLong("created_at"),
            appVersion = json.getString("app_version"),
            databaseUserVersion = json.getInt("database_user_version"),
            databaseSha256 = hash("database_sha256"),
            databaseBytes = json.getLong("database_bytes").also { require(it > 0L) { "DBサイズが不正です" } },
            tableCounts = counts,
            plainBundleSha256 = hash("plain_bundle_sha256"),
            encryptedPayloadSha256 = hash("encrypted_payload_sha256"),
            payloadNonce = payloadNonce,
            deviceWrapNonce = deviceNonce,
            deviceWrappedDek = deviceWrapped,
            portableSalt = if (portable) unb64(json.getString("portable_salt")).also { require(it.size >= SALT_BYTES) { "PBKDF2 saltが短すぎます" } } else null,
            portableIterations = iterations,
            portableWrapNonce = if (portable) unb64(json.getString("portable_wrap_nonce")).also { require(it.size == GCM_NONCE_BYTES) { "portable nonce長が不正です" } } else null,
            portableWrappedDek = if (portable) unb64(json.getString("portable_wrapped_dek")).also { require(it.isNotEmpty()) { "portable鍵ラップがありません" } } else null,
        )
    }

    private fun requirePassphrase(passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "バックアップ用パスフレーズを入力してください" }
    }

    private fun privateWorkDir(context: Context, prefix: String): File =
        File(context.cacheDir, "$prefix-${System.nanoTime()}").apply {
            require(mkdirs() || isDirectory) { "private作業領域を作成できません" }
        }

    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "バックアップデータが上限サイズを超えています" }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) require(target.delete()) { "旧バックアップを置換できません" }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            require(target.isFile && target.length() == source.length()) { "バックアップ置換の検証に失敗しました" }
            source.delete()
        }
    }

    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
