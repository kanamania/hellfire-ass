package com.hellfire.ass.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object SmsCaptureSettings {
    fun enabled(context: Context): Boolean = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
        .getBoolean("sms_enabled", false)
    // Save owner consent before starting imports or reporting capture as disabled.
    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().putBoolean("sms_enabled", enabled).commit()
    fun canRead(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
