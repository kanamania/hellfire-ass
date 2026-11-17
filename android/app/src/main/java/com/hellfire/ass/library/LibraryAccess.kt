package com.hellfire.ass.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class LibraryAccessState(
    val images: Boolean,
    val videos: Boolean,
    val audio: Boolean,
    val imagesFull: Boolean,
    val videosFull: Boolean,
    val partialVisual: Boolean,
    val location: Boolean,
)

object LibraryAccess {
    fun read(context: Context): LibraryAccessState {
        if (Build.VERSION.SDK_INT <= 32) {
            val storage = granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            return LibraryAccessState(storage, storage, storage, storage, storage, false, granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION))
        }
        val images = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        val audio = granted(context, Manifest.permission.READ_MEDIA_AUDIO)
        val selected = Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return LibraryAccessState(
            images || selected, videos || selected, audio, images, videos,
            selected && (!images || !videos), granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION),
        )
    }

    fun missing(context: Context): Array<String> {
        val state = read(context)
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 32) {
            if (!state.images) out += Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            if (!state.imagesFull) out += Manifest.permission.READ_MEDIA_IMAGES
            if (!state.videosFull) out += Manifest.permission.READ_MEDIA_VIDEO
            if (!state.audio) out += Manifest.permission.READ_MEDIA_AUDIO
            if (Build.VERSION.SDK_INT >= 34) out += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        if (!state.location) out += Manifest.permission.ACCESS_MEDIA_LOCATION
        return out.distinct().toTypedArray()
    }

    fun visibility(granted: Boolean, full: Boolean): ScanVisibility = when {
        !granted -> ScanVisibility.DENIED
        !full -> ScanVisibility.PARTIAL
        else -> ScanVisibility.FULL
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
