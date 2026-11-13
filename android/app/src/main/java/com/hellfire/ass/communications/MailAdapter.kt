package com.hellfire.ass.communications

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.sun.mail.imap.IMAPFolder
import java.nio.charset.Charset
import java.util.Properties
import javax.mail.*
import javax.mail.internet.*
import org.json.JSONObject

/** IMAP/SMTP only; normal passwords, insecure sockets and Gmail API are never substituted. */
data class MailConfig(val id: String, val address: String, val password: String,
    val imapHost: String, val smtpHost: String, val smtpPort: Int) {
    fun validate() {
        val parsed = InternetAddress(address, true).also { it.validate() }
        require(parsed.address == address && !address.contains(Regex("[\\r\\n]")))
        require(password.isNotBlank())
        for (host in listOf(imapHost, smtpHost)) require(host.matches(Regex("[A-Za-z0-9.-]{1,253}")) && !host.startsWith("."))
        require(smtpPort in setOf(465, 587))
    }
    fun json() = JSONObject().put("id", id).put("address", address).put("password", password)
        .put("imap_host", imapHost).put("smtp_host", smtpHost).put("smtp_port", smtpPort)
    companion object {
        fun from(o: JSONObject) = MailConfig(o.getString("id"), o.getString("address"), o.getString("password"),
            o.getString("imap_host"), o.getString("smtp_host"), o.getInt("smtp_port")).also { it.validate() }
    }
}

object MailMime {
    fun attachments(part: Part, path: String = ""): List<String> = attachments(part, path, 0, intArrayOf(100))
    private fun attachments(part: Part, path: String, depth: Int, budget: IntArray): List<String> {
        require(depth <= 12 && --budget[0] >= 0) { "MIME structure exceeds local processing limits" }
        if (part.isMimeType("multipart/*")) {
            val multi = part.content as Multipart
            return (0 until minOf(multi.count, 50)).flatMap { attachments(multi.getBodyPart(it), if (path.isEmpty()) "$it" else "$path.$it", depth + 1, budget) }
        }
        if (part.disposition.equals(Part.ATTACHMENT, true) || part.fileName != null) {
            return listOf(JSONObject().put("part", path).put("name", part.fileName.orEmpty())
                .put("mime", part.contentType.substringBefore(';')).put("size", part.size).toString())
        }
        return emptyList()
    }
    const val MAX_TEXT = 256 * 1024
    fun text(part: Part): String = text(part, 0, intArrayOf(100, MAX_TEXT))
    private fun text(part: Part, depth: Int, budget: IntArray): String {
        require(depth <= 12 && --budget[0] >= 0) { "MIME structure exceeds local processing limits" }
        if (part.disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return ""
        if (part.isMimeType("multipart/*")) {
            val multi = part.content as Multipart
            val items = (0 until minOf(multi.count, 50)).map { multi.getBodyPart(it) }
            val preferred = if (part.isMimeType("multipart/alternative")) items.firstOrNull { it.isMimeType("text/plain") } else null
            return if (preferred != null) text(preferred, depth + 1, budget) else items.joinToString("\n") { text(it, depth + 1, budget) }.take(MAX_TEXT)
        }
        if (!part.isMimeType("text/plain") && !part.isMimeType("text/html")) return ""
        val bytes = part.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (budget[1] > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, budget[1]))
                if (count < 0) break
                require(count > 0); budget[1] -= count
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val charset = runCatching { Charset.forName(ContentType(part.contentType).getParameter("charset") ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        val value = bytes.toString(charset)
        return if (part.isMimeType("text/html")) android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else value
    }
}

class MailAdapter(private val app: HellFireApp) {
    private fun properties() = Properties().apply {
        setProperty("mail.imaps.ssl.checkserveridentity", "true")
        setProperty("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
        setProperty("mail.imaps.peek", "true")
        for (protocol in listOf("imaps", "smtp", "smtps")) {
            setProperty("mail.$protocol.connectiontimeout", "15000")
            setProperty("mail.$protocol.timeout", "20000")
            setProperty("mail.$protocol.writetimeout", "20000")
            setProperty("mail.$protocol.ssl.checkserveridentity", "true")
        }
        setProperty("mail.smtp.starttls.enable", "true")
        setProperty("mail.smtp.starttls.required", "true")
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtps.auth", "true")
    }

    suspend fun sync(config: MailConfig): Boolean {
        config.validate()
        val store = Session.getInstance(properties()).getStore("imaps")
        var more = false
        try {
            store.connect(config.imapHost, 993, config.address, config.password)
            val folders = store.defaultFolder.list("*").take(200)
            val sent = folders.filterIsInstance<IMAPFolder>().firstOrNull { it.attributes.contains("\\Sent") }
            for (folder in listOfNotNull(store.getFolder("INBOX"), sent).distinctBy { it.fullName }) {
                folder.open(Folder.READ_ONLY)
                try {
                    val uidFolder = folder as UIDFolder
                    val validity = uidFolder.uidValidity
                    val previous = app.database.communications().cursor(config.id, folder.fullName)
                    val after = if (previous?.uidValidity == validity) previous.lastUid else 0L
                    val messages = uidFolder.getMessagesByUID(after + 1, UIDFolder.LASTUID).filterNotNull()
                    more = more || messages.size > 100
                    for (message in messages.take(100)) {
                        val uid = uidFolder.getUID(message)
                        require(uid > 0)
                        val messageId = message.getHeader("Message-ID")?.firstOrNull()?.trim()?.takeIf { it.length <= 500 }
                            ?: "${folder.fullName}:$validity:$uid"
                        val occurred = message.receivedDate?.time ?: message.sentDate?.time ?: System.currentTimeMillis()
                        val body = runCatching { MailMime.text(message) }.getOrDefault("")
                        val sender = message.from?.joinToString { it.toString() }.orEmpty()
                        val recipient = message.allRecipients?.joinToString { it.toString() }.orEmpty()
                        val subject = message.subject.orEmpty()
                        val snapshot = NotificationSnapshot("imap:${config.id}:${folder.fullName}:$validity:$uid", "imap.mail",
                            occurred, System.currentTimeMillis(), kind = if (folder == sent) "OUTGOING" else "INCOMING", source = "email_imap",
                            provenance = mapOf("account_id" to config.id, "folder" to folder.fullName, "uid_validity" to validity.toString(), "uid" to uid.toString()),
                            fields = mapOf("title" to listOf(subject), "text" to listOf(body), "sender_hint" to listOf(sender), "recipient" to listOf(recipient), "attachment_metadata" to runCatching { MailMime.attachments(message) }.getOrDefault(emptyList())))
                        val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
                        app.events.capture(snapshot, app.packageName)
                        app.database.communications().message(CommunicationMessage(NotificationNormalizer.sha256("email:${config.id}:$messageId"),
                            "EMAIL_IMAP", config.id, messageId, safe.id,
                            message.getHeader("In-Reply-To")?.firstOrNull() ?: messageId,
                            safe.snapshot.fields["sender_hint"]?.joinToString().orEmpty(), safe.snapshot.fields["recipient"]?.joinToString().orEmpty(),
                            safe.snapshot.fields["title"]?.joinToString().orEmpty(), safe.snapshot.fields["text"]?.joinToString("\n").orEmpty(),
                            occurred, snapshot.kind))
                        if (folder == sent) app.database.communications().confirmSent(messageId)
                        app.database.communications().cursor(MailCursor(config.id, folder.fullName, validity, uid))
                    }
                } finally { folder.close(false) }
            }
            app.database.communications().integration(IntegrationState("EMAIL_IMAP", if (sent == null) "PARTIAL" else "CONNECTED",
                System.currentTimeMillis(), if (sent == null) "SENT_FOLDER_NOT_FOUND" else null))
        } finally { store.close() }
        return more
    }

    fun send(config: MailConfig, draft: OutboundDraft): String {
        config.validate()
        require(draft.body.isNotBlank() && draft.body.length <= 100_000 && draft.subject.length <= 500)
        require(!draft.subject.contains(Regex("[\\r\\n]")))
        val to = InternetAddress(draft.recipient, true).also { it.validate() }
        require(to.address == draft.recipient && !draft.recipient.contains(Regex("[\\r\\n]")))
        val identity = "<${draft.id}@${config.address.substringAfter('@')}>"
        val session = Session.getInstance(properties())
        val message = object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", identity) }
        }.apply {
            setFrom(InternetAddress(config.address))
            setRecipient(Message.RecipientType.TO, to)
            setSubject(draft.subject, "UTF-8")
            setText(draft.body, "UTF-8")
            draft.replyToMessageId?.let { parent ->
                require(parent.matches(Regex("<[^<>\\s]{1,498}>")))
                setHeader("In-Reply-To", parent); setHeader("References", parent)
            }
            saveChanges()
        }
        val transport = session.getTransport(if (config.smtpPort == 465) "smtps" else "smtp")
        try {
            transport.connect(config.smtpHost, config.smtpPort, config.address, config.password)
            transport.sendMessage(message, message.allRecipients)
        } finally { transport.close() }
        return identity
    }
}
