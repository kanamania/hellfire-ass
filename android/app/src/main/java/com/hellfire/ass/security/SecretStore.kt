package com.hellfire.ass.security

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class SecretStore(private val context: Context, private val cipher: () -> PayloadCipher) {
    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-z-]{1,30}")))
        return AtomicFile(File(context.noBackupFilesDir, "$name.credentials.enc"))
    }
    fun read(name: String): JSONObject? {
        val f = file(name)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        return JSONObject(cipher().decrypt(f.readFully()).toString(Charsets.UTF_8))
    }
    fun save(name: String, value: JSONObject) {
        val f = file(name)
        val output = f.startWrite()
        try { output.write(cipher().encrypt(value.toString().toByteArray(Charsets.UTF_8))); f.finishWrite(output) }
        catch (error: Exception) { f.failWrite(output); throw error }
    }
    fun remove(name: String) = file(name).delete()
}
