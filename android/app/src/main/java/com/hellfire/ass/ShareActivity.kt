package com.hellfire.ass

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives untrusted shares without reading or displaying them before the app's unlock gate. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Text selected in another app ("Save to HellFire" in the selection menu) arrives as plain shared text.
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            if (selected.isNotBlank()) startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, selected.take(20_000))
                .putExtra("hellfire-share-id", java.util.UUID.randomUUID().toString()).setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            startActivity(Intent(intent).putExtra("hellfire-share-id", java.util.UUID.randomUUID().toString()).setClass(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
