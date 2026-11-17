package com.hellfire.ass

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives untrusted shares without reading or displaying them before the app's unlock gate. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            startActivity(Intent(intent).putExtra("hellfire-share-id", java.util.UUID.randomUUID().toString()).putExtra("hellfire-drawer", true).setClass(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
