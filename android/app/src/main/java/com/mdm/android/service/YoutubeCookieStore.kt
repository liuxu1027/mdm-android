package com.mdm.android.service

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local, encrypted storage for user-imported yt-dlp cookies.txt files. */
object YoutubeCookieStore {
    private const val PREFS_NAME = "youtube_cookie_state"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COOKIE_COUNT = "cookie_count"
    private const val KEY_ALIAS = "mdm.youtube.cookies.v1"
    private const val FILE_NAME = "youtube-cookies.enc"
    private const val TEMP_PREFIX = "mdm-ytdlp-cookies-"
    private const val FORMAT_VERSION = 1
    private const val MAX_COOKIE_FILE_BYTES = 2 * 1024 * 1024
    private const val TAG = "YoutubeCookieStore"

    private val temporaryFileLock = Any()
    private val activeTemporaryPaths = mutableSetOf<String>()
    private val allowedCookieDomains = listOf(
        "youtube.com",
        "youtube-nocookie.com",
        "google.com",
        "googlevideo.com"
    )

    fun hasStoredCookieFile(context: Context): Boolean = encryptedFile(context).let {
        it.isFile && it.length() > 0L
    }

    /** Returns true only when the encrypted file can currently be authenticated and decrypted. */
    fun hasCookies(context: Context): Boolean {
        if (!YoutubeRuntime.youtubeEnabled) return false
        val file = encryptedFile(context)
        if (!file.isFile || file.length() == 0L) return false
        return runCatching {
            val payload = file.readBytes()
            try {
                decrypt(payload).also { it.fill(0) }.isNotEmpty()
            } finally {
                payload.fill(0)
            }
        }.onFailure { Log.w(TAG, "Stored Cookie file is not usable; re-import may be required") }
            .getOrDefault(false)
    }

    fun hasAnyCookieData(context: Context): Boolean =
        hasStoredCookieFile(context) || context.cacheDir.listFiles()
            ?.any { it.name.startsWith(TEMP_PREFIX) } == true

    fun cookieCount(context: Context): Int = preferences(context).getInt(KEY_COOKIE_COUNT, 0)

    fun isEnabled(context: Context): Boolean =
        YoutubeRuntime.youtubeEnabled && hasCookies(context) &&
            preferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_ENABLED, enabled && YoutubeRuntime.youtubeEnabled && hasCookies(context))
            .apply()
    }

    /** Validates and filters a Netscape-format file, then encrypts it into no-backup storage. */
    fun importFile(context: Context, uri: Uri): Int {
        require(YoutubeRuntime.youtubeEnabled) { "YouTube Cookie 仅在 Full 版本可用" }
        cleanupOrphanedTemporaryFiles(context)
        val inputBytes = context.contentResolver.openInputStream(uri)?.use(::readBounded)
            ?: error("无法读取所选 Cookie 文件")
        try {
            val filtered = filterCookies(String(inputBytes, StandardCharsets.UTF_8))
            val plaintext = filtered.first.toByteArray(StandardCharsets.UTF_8)
            val encrypted = try {
                encrypt(plaintext)
            } finally {
                plaintext.fill(0)
            }
            writeEncryptedFile(encryptedFile(context), encrypted)
            preferences(context).edit().putInt(KEY_COOKIE_COUNT, filtered.second).apply()
            return filtered.second
        } finally {
            inputBytes.fill(0)
        }
    }

    /** Creates a private plaintext copy only for the lifetime of one yt-dlp request. */
    fun createTemporaryCookieFile(context: Context): File? {
        if (!YoutubeRuntime.youtubeEnabled || !isEnabled(context)) return null
        cleanupOrphanedTemporaryFiles(context)
        val encryptedPayload = encryptedFile(context).readBytes()
        val plaintext = decrypt(encryptedPayload)
        encryptedPayload.fill(0)
        var temporaryFile: File? = null
        try {
            val file = File.createTempFile(TEMP_PREFIX, ".txt", context.cacheDir)
            temporaryFile = file
            FileOutputStream(file).use { output ->
                output.write(plaintext)
                output.fd.sync()
            }
            synchronized(temporaryFileLock) { activeTemporaryPaths.add(file.absolutePath) }
            return file
        } catch (error: Throwable) {
            temporaryFile?.let(::secureDelete)
            throw error
        } finally {
            plaintext.fill(0)
        }
    }

    fun deleteTemporaryCookieFile(file: File?) {
        if (file == null) return
        val deleted = secureDelete(file)
        synchronized(temporaryFileLock) { activeTemporaryPaths.remove(file.absolutePath) }
        if (!deleted) Log.e(TAG, "Could not remove a temporary Cookie file from app-private cache")
    }

    /** Removes encrypted credentials, their Keystore key, and any temporary plaintext copies. */
    fun clear(context: Context): Boolean {
        val temporaryFiles = context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(TEMP_PREFIX) }
            .orEmpty()
        val temporaryFilesRemoved = temporaryFiles.map(::secureDelete).all { it }
        synchronized(temporaryFileLock) {
            temporaryFiles.forEach { activeTemporaryPaths.remove(it.absolutePath) }
        }

        val storedFile = encryptedFile(context)
        AtomicFile(storedFile).delete()
        val storedFileRemoved = !storedFile.exists()
        preferences(context).edit()
            .remove(KEY_ENABLED)
            .remove(KEY_COOKIE_COUNT)
            .commit()
        val keyRemoved = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
            true
        }.onFailure { Log.e(TAG, "Could not remove the local Cookie encryption key", it) }
            .getOrDefault(false)

        val fullyCleared = temporaryFilesRemoved && storedFileRemoved && keyRemoved
        if (!fullyCleared) Log.e(TAG, "Cookie cleanup did not complete; retry Clear from settings")
        return fullyCleared
    }

    private fun encryptedFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_COOKIE_FILE_BYTES) { "Cookie 文件过大（上限 2 MB）" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun filterCookies(content: String): Pair<String, Int> {
        val lines = content.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trimEnd('\r') }
            .toList()
        val header = lines.firstOrNull() ?: error("Cookie 文件为空")
        require(header == "# Netscape HTTP Cookie File" || header == "# HTTP Cookie File") {
            "文件格式不正确，请选择 Netscape/Mozilla 格式的 cookies.txt"
        }

        val keptLines = mutableListOf(header)
        var count = 0
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val isHttpOnly = line.startsWith("#HttpOnly_")
            if (line.startsWith("#") && !isHttpOnly) continue
            val cookieLine = if (isHttpOnly) line.removePrefix("#HttpOnly_") else line
            val fields = cookieLine.split('\t')
            if (!isValidCookie(fields) || !isAllowedCookieDomain(fields[0])) continue
            keptLines += line
            count++
        }
        require(count > 0) { "文件中没有有效的 YouTube/Google 域名 Cookie" }
        return keptLines.joinToString("\n", postfix = "\n") to count
    }

    private fun isValidCookie(fields: List<String>): Boolean =
        fields.size == 7 &&
            fields[1].equals("TRUE", ignoreCase = true).or(fields[1].equals("FALSE", ignoreCase = true)) &&
            fields[2].startsWith("/") &&
            (fields[3].equals("TRUE", ignoreCase = true) || fields[3].equals("FALSE", ignoreCase = true)) &&
            fields[4].toLongOrNull() != null &&
            fields[5].isNotBlank()

    private fun isAllowedCookieDomain(domain: String): Boolean {
        val host = domain.removePrefix(".").lowercase(Locale.ROOT)
        return allowedCookieDomains.any { root -> host == root || host.endsWith(".$root") }
    }

    private fun cleanupOrphanedTemporaryFiles(context: Context) {
        val activePaths = synchronized(temporaryFileLock) { activeTemporaryPaths.toSet() }
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(TEMP_PREFIX) && it.absolutePath !in activePaths }
            ?.forEach(::secureDelete)
    }

    /** Best-effort overwrite followed by checked deletion; private cache is app-sandboxed. */
    private fun secureDelete(file: File): Boolean {
        if (!file.exists()) return true
        val overwriteSucceeded = runCatching {
            var remaining = file.length()
            FileOutputStream(file, false).use { output ->
                val zeros = ByteArray(8192)
                while (remaining > 0L) {
                    val count = minOf(zeros.size.toLong(), remaining).toInt()
                    output.write(zeros, 0, count)
                    remaining -= count
                }
                output.fd.sync()
                zeros.fill(0)
            }
        }.onFailure { Log.w(TAG, "Could not overwrite a temporary Cookie file before deletion", it) }
            .isSuccess
        val deleted = runCatching { file.delete() || !file.exists() }.getOrDefault(false)
        if (!deleted) Log.e(TAG, "Could not delete a temporary Cookie file from app-private cache")
        if (!overwriteSucceeded && deleted) {
            Log.w(TAG, "Temporary Cookie file was deleted without a confirmed overwrite")
        }
        return deleted
    }

    private fun writeEncryptedFile(file: File, payload: ByteArray) {
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(payload)
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        } finally {
            payload.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeByte(cipher.iv.size)
                output.write(cipher.iv)
                output.write(encrypted)
            }
            buffer.toByteArray()
        }
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Cookie 存储格式不受支持，请重新导入" }
        val ivLength = input.readUnsignedByte()
        require(ivLength in 12..16) { "Cookie 存储数据损坏，请重新导入" }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val encrypted = input.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
