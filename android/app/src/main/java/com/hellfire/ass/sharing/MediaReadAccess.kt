package com.hellfire.ass.sharing

import android.Manifest
import android.content.Context
import android.os.Build
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.LibraryAccess

/**
 * Read permission for one external item. Only the permission for that item's kind is needed, and
 * Android 14 "selected photos and videos" access counts. A file outside the selection still fails
 * when it is opened, and the caller reports that.
 */
object MediaReadAccess {
    fun request(kind: String, sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdk <= 32 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        kind == ContentKind.AUDIO.name -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        kind == ContentKind.VIDEO.name -> visual(Manifest.permission.READ_MEDIA_VIDEO, sdk)
        else -> visual(Manifest.permission.READ_MEDIA_IMAGES, sdk)
    }

    fun canRead(context: Context, kind: String): Boolean {
        val state = LibraryAccess.read(context)
        return when (kind) {
            ContentKind.AUDIO.name -> state.audio
            ContentKind.VIDEO.name -> state.videos
            else -> state.images
        }
    }

    /** Any one granted permission from [request] is enough; partial visual access is accepted. */
    fun granted(results: Map<String, Boolean>): Boolean = results.values.any { it }

    fun kindOf(contentKind: String, mime: String): String = when {
        contentKind == ContentKind.AUDIO.name || mime.startsWith("audio/") -> ContentKind.AUDIO.name
        contentKind == ContentKind.VIDEO.name || mime.startsWith("video/") -> ContentKind.VIDEO.name
        else -> ContentKind.IMAGE.name
    }

    private fun visual(full: String, sdk: Int) =
        if (sdk >= 34) arrayOf(full, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(full)
}
