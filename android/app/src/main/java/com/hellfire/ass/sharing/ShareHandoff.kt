package com.hellfire.ass.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ShareHandoff {
    fun authority(context: Context) = context.packageName + ".share"

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun send(uris: List<Uri>, mime: String, caption: String): Intent {
        require(uris.isNotEmpty())
        val multiple = uris.size > 1
        val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (caption.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, caption)
        val clip = ClipData.newRawUri("share", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        if (multiple) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        else intent.putExtra(Intent.EXTRA_STREAM, uris[0])
        return intent
    }

    fun chooser(uris: List<Uri>, mime: String, caption: String): Intent =
        Intent.createChooser(send(uris, mime, caption), "Share sanitized copy").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
