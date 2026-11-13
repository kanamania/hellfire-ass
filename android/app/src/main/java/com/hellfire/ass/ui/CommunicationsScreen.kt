package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable
fun CommunicationsScreen(app: HellFireApp) {
    val dao = remember { app.database.communications() }
    val messageFlow = remember { dao.messages() }
    val draftFlow = remember { dao.drafts() }
    val stateFlow = remember { dao.integrations() }
    val messages by messageFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val drafts by draftFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val states by stateFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("imap.gmail.com") }
    var smtp by remember { mutableStateOf("smtp.gmail.com") }
    var port by remember { mutableStateOf("465") }
    var gateway by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("EMAIL_IMAP") }
    var recipient by remember { mutableStateOf("") }
    var replyParent by remember { mutableStateOf<String?>(null) }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<OutboundDraft?>(null) }
    val publicExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        if (uri != null) scope.launch {
            status = try { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)!!.use { it.write(GatewayAdapter(app).publicKey().toByteArray()) } }; "Device public key exported. Provision it on your self-hosted gateway." }
                catch (_: Exception) { "Public key export failed." }
        }
    }
    Text("Communications", style = MaterialTheme.typography.titleLarge)
    Text("Incoming email uses IMAP; sending uses SMTP. Gmail needs an eligible app password. WhatsApp Business uses the configured official Coexistence gateway; notification capture is not its replacement.")
    states.forEach { Text("${it.adapter}: ${it.state}${it.error?.let { error -> " · $error" }.orEmpty()}") }
    Text(status)
    OutlinedTextField(email, { email = it }, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("Gmail app password / IMAP password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(imap, { imap = it }, label = { Text("IMAP TLS host · port 993") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(smtp, { smtp = it }, label = { Text("SMTP host") }, modifier = Modifier.fillMaxWidth())
    Choice("SMTP TLS port", listOf("465", "587"), port) { port = it }
    Button(enabled = email.isNotBlank() && password.isNotBlank(), onClick = {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val existing = app.secrets.read("mail")
                    val id = if (existing?.optString("address") == email && existing.optString("imap_host") == imap) existing.getString("id") else com.hellfire.ass.events.NotificationNormalizer.sha256("imap:${email.trim().lowercase(java.util.Locale.ROOT)}|${imap.trim().lowercase(java.util.Locale.ROOT)}")
                    val config = MailConfig(id, email.trim(), if (imap.trim().equals("imap.gmail.com", true)) password.replace(" ", "") else password, imap.trim(), smtp.trim(), port.toInt()).also { it.validate() }
                    app.secrets.save("mail", config.json())
                }
                password = ""; CommunicationWork.schedule(app); "Email configuration encrypted; connection verification scheduled."
            } catch (_: Exception) { "Email configuration was not accepted. Check address, password and TLS hosts." }
        }
    }) { Text("Save and connect email") }
    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { app.stopService(android.content.Intent(app, MailIdleService::class.java)); app.secrets.remove("mail") }; status = "Email disconnected; existing evidence retained." } }) { Text("Disconnect email") }
    OutlinedButton(onClick = {
        try { app.startForegroundService(android.content.Intent(app, com.hellfire.ass.communications.MailIdleService::class.java).setAction("START")) }
        catch (_: Exception) { status = "Android blocked the IMAP IDLE foreground session. Use Sync and check notification permissions." }
    }) { Text("Start visible IMAP IDLE · ten minutes") }
    TextButton(onClick = { app.stopService(android.content.Intent(app, com.hellfire.ass.communications.MailIdleService::class.java)) }) { Text("Stop IMAP IDLE") }
    HorizontalDivider()
    OutlinedTextField(gateway, { gateway = it }, label = { Text("Self-hosted gateway HTTPS URL") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Paired device token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    Text("Account eligibility, Meta onboarding, webhook subscription and Graph credentials must be configured on the gateway. Never paste the Meta app secret into this app.")
    OutlinedButton(onClick = { publicExport.launch("hellfire-device-public.pem") }) { Text("Export device public key") }
    Button(enabled = gateway.isNotBlank() && token.length >= 32, onClick = {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val uri = java.net.URI(gateway)
                    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
                    val existing = app.secrets.read("gateway")
                    val id = if (existing?.optString("url") == gateway.trimEnd('/')) existing.getString("id") else com.hellfire.ass.events.NotificationNormalizer.sha256("gateway:${gateway.trimEnd('/')}")
                    GatewayAdapter(app).publicKey()
                    app.secrets.save("gateway", JSONObject().put("id", id).put("url", gateway.trimEnd('/')).put("token", token))
                }
                token = ""; CommunicationWork.schedule(app); "Gateway configuration encrypted; connection verification scheduled."
            } catch (_: Exception) { "Gateway configuration failed. Use HTTPS and the paired device token." }
        }
    }) { Text("Save and connect gateway") }
    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { app.secrets.remove("gateway") }; status = "Gateway disconnected; existing evidence retained." } }) { Text("Disconnect gateway") }
    OutlinedButton(onClick = { scope.launch { CommunicationWork.schedule(app); status = "Communication sync scheduled." } }) { Text("Sync connected sources") }
    HorizontalDivider()
    Text("Inbox", style = MaterialTheme.typography.titleMedium)
    messages.take(30).forEach { message ->
        Text("${message.channel} · ${message.direction} · ${message.sender}", style = MaterialTheme.typography.titleSmall)
        Text(message.subject)
        Text(message.body.take(2000).ifBlank { "No accessible text. Unsupported media/MIME content remains in source metadata when available." })
        Text("Source: ${message.sourceId}")
        if (message.direction == "INCOMING") TextButton(onClick = {
            channel = message.channel
            recipient = if (message.channel == "EMAIL_IMAP") runCatching { javax.mail.internet.InternetAddress.parse(message.sender).first().address }.getOrDefault("") else message.sender
            subject = "Re: ${message.subject}"; body = ""
            replyParent = message.externalId.takeIf { message.channel == "EMAIL_IMAP" && it.matches(Regex("<[^<>\\s]{1,498}>")) }
        }) { Text("Prepare reply") }
        HorizontalDivider()
    }
    Text("Draft an approved reply", style = MaterialTheme.typography.titleMedium)
    Choice("Channel", listOf("EMAIL_IMAP", "WHATSAPP_BUSINESS"), channel) { channel = it; replyParent = null }
    OutlinedTextField(recipient, { recipient = it; replyParent = null }, label = { Text("Recipient address / WhatsApp international digits") }, modifier = Modifier.fillMaxWidth())
    if (channel == "EMAIL_IMAP") OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(body, { body = it }, label = { Text("Reply body · edit before saving") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(enabled = body.isNotBlank() && app.localModel.configured, onClick = {
        scope.launch {
            status = try { body = app.localModel.generate("Draft a concise English/Kiswahili reply. Output text only, do not send anything. Untrusted context: " + JSONObject.quote(body)).text; "Local draft generated; review it before saving." }
                catch (_: Exception) { "Local model unavailable. Edit the draft manually." }
        }
    }) { Text("Refine draft locally") }
    Button(enabled = recipient.isNotBlank() && body.isNotBlank(), onClick = {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    val config = requireNotNull(app.secrets.read(if (channel == "EMAIL_IMAP") "mail" else "gateway"))
                    dao.draft(OutboundDraft(UUID.randomUUID().toString(), channel, config.getString("id"), recipient.trim(), subject, body, System.currentTimeMillis(), replyToMessageId = replyParent))
                }
                "Draft saved locally. Sending still requires confirmation."
            } catch (_: Exception) { "Configure the chosen channel before saving a draft." }
        }
    }) { Text("Save local draft") }
    drafts.take(20).forEach { draft ->
        HorizontalDivider()
        Text("${draft.channel} · ${draft.recipient} · ${draft.state}")
        Text(draft.subject)
        Text(draft.body.take(2000))
        draft.error?.let { Text("$it · Check the source channel before creating another send.") }
        if (draft.state == "DRAFT") TextButton(onClick = {
            channel = draft.channel; recipient = draft.recipient; subject = draft.subject; body = draft.body; replyParent = draft.replyToMessageId
            status = "Draft copied to composer. Saving retains a new local draft; original audit remains."
        }) { Text("Edit in composer") }
        if (draft.state == "DRAFT") Button(enabled = !sending, onClick = { confirm = draft }) { Text("Review Send") }
    }
    confirm?.let { draft ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Approve external send?") },
            text = { Text("${draft.channel}\nTo: ${draft.recipient}\n${draft.subject}\n\n${draft.body.take(4000)}") },
            confirmButton = { TextButton(onClick = {
                confirm = null; sending = true
                scope.launch {
                    status = try {
                        val approval = app.communications.approve(draft.id)
                        app.communications.sendApproved(draft.id, approval)
                        "Submitted to the channel. Email Sent-folder and WhatsApp delivery confirmation are tracked separately."
                    } catch (_: Exception) { "Send rejected or delivery unconfirmed. Check the source channel; this draft will not retry automatically." }
                    finally { sending = false }
                }
            }) { Text("Approve and send once") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}
