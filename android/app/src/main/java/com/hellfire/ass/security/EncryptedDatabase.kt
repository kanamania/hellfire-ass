package com.hellfire.ass.security

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object EncryptedDatabase {
    @Synchronized
    fun factory(context: Context): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath("hellfire.db")
        val plain = file.exists() && file.inputStream().use { input -> ByteArray(16).also { input.read(it) }.contentEquals("SQLite format 3\u0000".toByteArray()) }
        val keyFile = AtomicFile(File(context.noBackupFilesDir, "database-key.enc"))
        val cipher = PayloadCipher.deviceCipher()
        val password = if (keyFile.baseFile.exists() || File(keyFile.baseFile.path + ".bak").exists()) {
            cipher.decrypt(keyFile.readFully())
        } else {
            check(!file.exists() || plain) { "Encrypted database key is missing; no plaintext fallback is allowed" }
            Base64.encode(SecureRandom().generateSeed(32), Base64.NO_WRAP).also { key ->
                val output = keyFile.startWrite()
                try { output.write(cipher.encrypt(key)); keyFile.finishWrite(output) }
                catch (error: Exception) { keyFile.failWrite(output); throw error }
            }
        }
        if (plain) encryptExisting(file, password)
        return SupportOpenHelperFactory(password)
    }

    private fun encryptExisting(file: File, password: ByteArray) {
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Existing database is busy" }
            }
        }
        val encrypted = File(file.path + ".encrypting")
        if (encrypted.exists()) check(encrypted.delete())
        var count = 0L
        var version = 0
        SQLiteDatabase.openDatabase(file.path, byteArrayOf(), null, SQLiteDatabase.OPEN_READWRITE, null).use { old ->
            version = old.version
            old.rawQuery("SELECT COUNT(*) FROM source_event", emptyArray<String>()).use { it.moveToFirst(); count = it.getLong(0) }
            old.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", encrypted.path, password.toString(Charsets.UTF_8))
            try {
                old.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { check(it.moveToFirst()) }
                old.execSQL("PRAGMA encrypted.user_version = $version")
            } finally { old.execSQL("DETACH DATABASE encrypted") }
        }
        SQLiteDatabase.openDatabase(encrypted.path, password, null, SQLiteDatabase.OPEN_READWRITE, null).use { verify ->
            verify.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            verify.rawQuery("SELECT COUNT(*) FROM source_event", emptyArray<String>()).use { check(it.moveToFirst() && it.getLong(0) == count) }
            check(verify.version == version)
        }
        for (suffix in listOf("-wal", "-shm")) File(file.path + suffix).let { if (it.exists()) check(it.delete()) }
        // Atomic replacement keeps the original intact if export or validation fails.
        Files.move(encrypted.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
