package com.hellfire.ass.ui.inbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.communications.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class ReplyDraft(val channel: String = "EMAIL_IMAP", val recipient: String = "", val subject: String = "", val body: String = "", val replyTo: String? = null)

/** Compose a reply. Saving creates a local draft only; sending needs "Approve and send". */
@Composable
fun ReplySheet(initial: ReplyDraft, onDone: (String?) -> Unit) {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf(initial.channel) }
    var recipient by remember { mutableStateOf(initial.recipient) }
    var subject by remember { mutableStateOf(initial.subject) }
    var body by remember { mutableStateOf(initial.body) }
    var replyTo by remember { mutableStateOf(initial.replyTo) }
    var status by remember { mutableStateOf<String?>(null) }
    HfBottomSheet({ onDone(null) }) {
        Text(if (initial.replyTo != null || initial.recipient.isNotBlank()) "Reply" else "New message", style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        ChipPicker(listOf("EMAIL_IMAP", "WHATSAPP_BUSINESS"), channel, Fmt::channel) { channel = it; replyTo = null }
        HfTextField(recipient, { recipient = it; replyTo = null }, if (channel == "EMAIL_IMAP") "To · email address" else "To · WhatsApp number with country code")
        if (channel == "EMAIL_IMAP") HfTextField(subject, { subject = it }, "Subject")
        HfTextField(body, { body = it }, "Message", singleLine = false, minLines = 4)
        status?.let { Banner(it, Tone.Info, null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Improve on this phone", {
                scope.launch {
                    status = try { body = app.localModel.generate("Draft a concise English/Kiswahili reply. Output text only, do not send anything. Untrusted context: " + JSONObject.quote(body)).text; "Draft improved locally. Read it before saving." }
                        catch (_: Exception) { "No on-device model is available. Edit the draft yourself." }
                }
            }, enabled = body.isNotBlank() && app.localModel.configured)
        }
        PrimaryButton("Save draft", {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val config = requireNotNull(app.secrets.read(if (channel == "EMAIL_IMAP") "mail" else "gateway"))
                        require(channel == "EMAIL_IMAP" || config.optString("transport", "meta") == "meta") { "WhatsApp Web is capture-only" }
                        app.database.communications().draft(OutboundDraft(UUID.randomUUID().toString(), channel, config.getString("id"), recipient.trim(), subject, body,
                            System.currentTimeMillis(), replyToMessageId = replyTo))
                    }
                    onDone("Draft saved. It sends only after you tap Approve and send.")
                } catch (_: Exception) { status = "Set up a sending account first in Settings › Email or WhatsApp. WhatsApp Web is capture-only." }
            }
        }, Modifier.fillMaxWidth(), enabled = recipient.isNotBlank() && body.isNotBlank(), height = 52.dp)
    }
}

@Composable
fun MessagePage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var message by remember { mutableStateOf<CommunicationMessage?>(null) }
    var replying by remember { mutableStateOf<ReplyDraft?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { message = withContext(Dispatchers.IO) { app.database.communications().message(id) } }
    val m = message
    PushedPage(m?.sender ?: "Message", breadcrumb = m?.let { "Inbox › ${Fmt.channel(it.channel)}" }) {
        if (m == null) { Note("This message is no longer available."); return@PushedPage }
        status?.let { Banner(it) }
        KvList(listOf("From" to m.sender, "To" to m.recipient, "When" to Fmt.full(m.occurredAt), "Channel" to Fmt.channel(m.channel)))
        HfCard(padding = PaddingValues(16.dp)) {
            if (m.subject.isNotBlank()) Text(m.subject, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(m.body.take(8000).ifBlank { "No readable text. Attachments and unsupported formats stay in the source record." }, style = Hf.type.body, color = Hf.colors.onSurface)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.direction == "INCOMING" && m.channel != "WHATSAPP_WEB") PrimaryButton("Prepare reply", {
                replying = ReplyDraft(m.channel,
                    if (m.channel == "EMAIL_IMAP") runCatching { javax.mail.internet.InternetAddress.parse(m.sender).first().address }.getOrDefault("") else m.sender,
                    "Re: ${m.subject}".takeIf { m.channel == "EMAIL_IMAP" }.orEmpty(), "",
                    m.externalId.takeIf { m.channel == "EMAIL_IMAP" && it.matches(Regex("<[^<>\\s]{1,498}>")) })
            })
            SecondaryButton("Source record", { nav.push(Screen.Source, m.sourceId) })
        }
        if (m.channel == "WHATSAPP_WEB") Note("WhatsApp Web capture is read-only. Replies need the Business connection.")
    }
    replying?.let { d -> ReplySheet(d) { msg -> replying = null; if (msg != null) status = msg } }
}

/** Settings › Email (not drawn): the account setup that used to sit on the Messages tab. */
@Composable
fun EmailPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val states by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("imap.gmail.com") }
    var smtp by remember { mutableStateOf("smtp.gmail.com") }
    var port by remember { mutableStateOf("465") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { app.secrets.read("mail") }.getOrNull() }?.let { email = it.optString("address"); imap = it.optString("imap_host", imap) } }
    PushedPage("Email", breadcrumb = "Settings") {
        status?.let { Banner(it, Tone.Info, null) }
        states.firstOrNull { it.adapter == "EMAIL_IMAP" }?.let { KvList(listOf("Connection" to (it.state + (it.error?.let { e -> " · $e" } ?: "")))) }
        Note("Incoming email uses IMAP; sending uses SMTP. Gmail needs an app password where Google allows it. The password is encrypted on this phone.")
        SectionCard("Account") {
            HfTextField(email, { email = it }, "Email address")
            HfTextField(password, { password = it }, "App password / IMAP password", password = true)
            HfTextField(imap, { imap = it }, "IMAP TLS host · port 993")
            HfTextField(smtp, { smtp = it }, "SMTP host")
            GroupLabel("SMTP TLS port")
            ChipPicker(listOf("465", "587"), port) { port = it }
            PrimaryButton("Save and connect", {
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) {
                            val existing = app.secrets.read("mail")
                            val id = if (existing?.optString("address") == email && existing.optString("imap_host") == imap) existing.getString("id")
                                else com.hellfire.ass.events.NotificationNormalizer.sha256("imap:${email.trim().lowercase(java.util.Locale.ROOT)}|${imap.trim().lowercase(java.util.Locale.ROOT)}")
                            val config = MailConfig(id, email.trim(), if (imap.trim().equals("imap.gmail.com", true)) password.replace(" ", "") else password, imap.trim(), smtp.trim(), port.toInt()).also { it.validate() }
                            app.secrets.save("mail", config.json())
                        }
                        password = ""; CommunicationWork.schedule(app); "Email settings encrypted; connection check scheduled."
                    } catch (_: Exception) { "Email settings were not accepted. Check the address, password and TLS hosts." }
                }
            }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && password.isNotBlank())
        }
        SectionCard("Sync") {
            SecondaryButton("Sync now", { scope.launch { CommunicationWork.schedule(app); status = "Sync scheduled." } }, Modifier.fillMaxWidth())
            SecondaryButton("Start live mail (IMAP IDLE) · 10 minutes", {
                try { app.startForegroundService(android.content.Intent(app, MailIdleService::class.java).setAction("START")) }
                catch (_: Exception) { status = "Android blocked the visible IMAP session. Use Sync now and check notification permission." }
            }, Modifier.fillMaxWidth())
            QuietButton("Stop live mail", { app.stopService(android.content.Intent(app, MailIdleService::class.java)) })
        }
        QuietButton("Disconnect email", { scope.launch { withContext(Dispatchers.IO) { app.stopService(android.content.Intent(app, MailIdleService::class.java)); app.secrets.remove("mail") }; status = "Email disconnected; saved messages are kept." } })
    }
}

/** Settings › WhatsApp (not drawn): self-hosted gateway setup. */
@Composable
fun WhatsAppPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var gateway by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("web") }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { app.secrets.read("gateway") }?.let { gateway = it.getString("url"); transport = it.optString("transport", "meta") } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { status = "Saved gateway settings are unavailable. Set up the connection again." }
    }
    val publicExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        if (uri != null) scope.launch {
            status = try { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)!!.use { it.write(GatewayAdapter(app).publicKey().toByteArray()) } }; "Device public key exported. Add it to your self-hosted gateway." }
                catch (_: Exception) { "Public key export failed." }
        }
    }
    PushedPage("WhatsApp", breadcrumb = "Settings") {
        status?.let { Banner(it, Tone.Info, null) }
        GroupLabel("Connection type")
        ChipPicker(listOf("web", "meta"), transport, { if (it == "web") "WhatsApp Web" else "Business API" }) { transport = it }
        Note(if (transport == "web") "WhatsApp Web uses an unofficial linked session. Your server can read captured messages. Sending is disabled; attachments are metadata only. Pair at your gateway's /pair page with the device token, then scan with WhatsApp › Linked devices."
            else "Business mode needs eligible Meta Coexistence onboarding and gateway credentials. Never paste the Meta app secret into this app.")
        SectionCard("Gateway") {
            HfTextField(gateway, { gateway = it }, "Self-hosted gateway HTTPS URL")
            HfTextField(token, { token = it }, "Paired device token", password = true)
            SecondaryButton("Export device public key", { publicExport.launch("hellfire-device-public.pem") }, Modifier.fillMaxWidth())
            PrimaryButton("Save and connect", {
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) {
                            val uri = java.net.URI(gateway)
                            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
                            val existing = app.secrets.read("gateway")
                            val id = if (existing?.optString("url") == gateway.trimEnd('/') && existing.optString("transport", "meta") == transport) existing.getString("id")
                                else com.hellfire.ass.events.NotificationNormalizer.sha256("gateway:$transport:${gateway.trimEnd('/')}")
                            GatewayAdapter(app).publicKey()
                            app.secrets.save("gateway", JSONObject().put("id", id).put("url", gateway.trimEnd('/')).put("token", token).put("transport", transport))
                        }
                        token = ""; CommunicationWork.schedule(app); "Gateway settings encrypted; connection check scheduled."
                    } catch (_: Exception) { "Gateway settings failed. Use HTTPS and the paired device token." }
                }
            }, Modifier.fillMaxWidth(), enabled = gateway.isNotBlank() && token.length >= 32)
        }
        SecondaryButton("Sync now", { scope.launch { CommunicationWork.schedule(app); status = "Sync scheduled." } }, Modifier.fillMaxWidth())
        QuietButton("Disconnect gateway", { scope.launch { withContext(Dispatchers.IO) { app.secrets.remove("gateway") }; status = "Gateway disconnected; saved messages are kept. To stop Web capture, log the server out in WhatsApp › Linked devices." } })
    }
}
