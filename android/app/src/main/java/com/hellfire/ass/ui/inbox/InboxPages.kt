package com.hellfire.ass.ui.inbox

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.HellFireApp
import java.io.FileNotFoundException

data class ReplyDraft(val channel: String = "EMAIL_IMAP", val recipient: String = "", val subject: String = "", val body: String = "", val replyTo: String? = null,
    /** Account that sends; a reply defaults to the account the message arrived on. */
    val accountId: String? = null,
    /** Set when editing a saved draft, which is then updated instead of duplicated. */
    val draftId: String? = null)

/** Accounts that can send on [channel]: every email inbox, or WhatsApp Business API gateways (Web is capture-only). */
private fun sendingAccounts(app: HellFireApp, channel: String): List<Pair<String, String>> =
    if (channel == "EMAIL_IMAP") Accounts.list(app.secrets, Accounts.MAIL).map { it.getString("id") to it.optString("address") }
    else Accounts.sendingGateways(app.secrets).map { it.getString("id") to Accounts.label(it) }

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
    var accounts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var account by remember { mutableStateOf(initial.accountId) }
    LaunchedEffect(channel) {
        accounts = withContext(Dispatchers.IO) { runCatching { sendingAccounts(app, channel) }.getOrDefault(emptyList()) }
        if (accounts.none { it.first == account }) account = accounts.firstOrNull()?.first
    }
    HfBottomSheet({ onDone(null) }) {
        Text(when { initial.draftId != null -> "Edit draft"; initial.replyTo != null || initial.recipient.isNotBlank() -> "Reply"; else -> "New message" },
            style = Hf.type.cardTitle.copy(fontSize = 18.sp), color = Hf.colors.onSurface)
        ChipPicker(listOf("EMAIL_IMAP", "WHATSAPP_BUSINESS"), channel, { when (it) { "EMAIL_IMAP" -> stringResource(R.string.ui_email); "WHATSAPP_BUSINESS" -> stringResource(R.string.ui_whatsapp); else -> Fmt.channel(it) } }) { channel = it; replyTo = null }
        if (accounts.size > 1) {
            GroupLabel("Send from")
            ChipPicker(accounts.map { it.first }, account ?: accounts.first().first, { id -> accounts.firstOrNull { it.first == id }?.second ?: id }) { account = it }
        } else if (accounts.isEmpty()) Note(if (channel == "EMAIL_IMAP") "No email account is connected. Add one in Settings › Email."
            else "No WhatsApp account can send. Sending needs a Business API connection; WhatsApp Web is capture-only.")
        HfTextField(recipient, { recipient = it; replyTo = null }, if (channel == "EMAIL_IMAP") stringResource(R.string.ui_to_email_address) else stringResource(R.string.ui_to_whatsapp_number_with_country_code))
        if (channel == "EMAIL_IMAP") HfTextField(subject, { subject = it }, stringResource(R.string.ui_subject))
        HfTextField(body, { body = it }, stringResource(R.string.ui_message), singleLine = false, minLines = 4)
        status?.let { Banner(it, Tone.Info, null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Improve on this phone", {
                scope.launch {
                    status = try { body = app.localModel.generate("Draft a concise English/Kiswahili reply. Output text only, do not send anything. Untrusted context: " + JSONObject.quote(body)).text; "Draft improved locally. Read it before saving." }
                        catch (caught: Exception) { Diagnostics.warn(caught); "No on-device model is available. Edit the draft yourself." }
                }
            }, enabled = body.isNotBlank() && app.localModel.configured)
        }
        PrimaryButton(stringResource(R.string.ui_save_draft), {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val from = requireNotNull(account?.takeIf { id -> sendingAccounts(app, channel).any { it.first == id } })
                        val dao = app.database.communications()
                        val edited = initial.draftId?.let { dao.editDraft(it, channel, from, recipient.trim(), subject, body, replyTo) } ?: 0
                        if (edited == 0) dao.draft(OutboundDraft(UUID.randomUUID().toString(), channel, from, recipient.trim(), subject, body,
                            System.currentTimeMillis(), replyToMessageId = replyTo))
                    }
                    onDone("Draft saved. It sends only after you tap Approve and send.")
                } catch (caught: Exception) { Diagnostics.warn(caught); status = "Choose a connected sending account in Settings › Email or WhatsApp. WhatsApp Web is capture-only." }
            }
        }, Modifier.fillMaxWidth(), enabled = recipient.isNotBlank() && body.isNotBlank() && account != null, height = 52.dp)
    }
}

@Composable
fun MessagePage(id: String) {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    var message by remember { mutableStateOf<CommunicationMessage?>(null) }
    var replying by remember { mutableStateOf<ReplyDraft?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var accountLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { withContext(Dispatchers.IO) {
        message = app.database.communications().message(id)
        accountLabel = message?.let { m -> runCatching { Accounts.labels(app.secrets)[m.accountId] }.getOrNull() }
    } }
    val m = message
    PushedPage(m?.sender ?: "Message", breadcrumb = m?.let { "Inbox › ${Fmt.channel(it.channel)}" }) {
        if (m == null) { Note("This message is no longer available."); return@PushedPage }
        status?.let { Banner(it) }
        KvList(listOf("From" to m.sender, "To" to m.recipient, "When" to Fmt.full(m.occurredAt), "Channel" to Fmt.channel(m.channel)) +
            listOfNotNull(accountLabel?.let { "Account" to it }))
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
                    m.externalId.takeIf { m.channel == "EMAIL_IMAP" && it.matches(Regex("<[^<>\\s]{1,498}>")) }, accountId = m.accountId)
            })
            SecondaryButton("Source record", { nav.push(Screen.Source, m.sourceId) })
        }
        if (m.channel == "WHATSAPP_WEB") Note("WhatsApp Web capture is read-only. Replies need the Business connection.")
    }
    replying?.let { d -> ReplySheet(d) { msg -> replying = null; if (msg != null) status = msg } }
}

/** Settings › Email: several IMAP/SMTP inboxes, each synced, idled and sent from independently. */
@Composable
fun EmailPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val states by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("imap.gmail.com") }
    var smtp by remember { mutableStateOf("smtp.gmail.com") }
    var port by remember { mutableStateOf("465") }
    suspend fun reload() {
        accounts = withContext(Dispatchers.IO) { try { Accounts.list(app.secrets, Accounts.MAIL) }
            catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); status = "Saved email settings are unavailable. Add the accounts again."; emptyList() } }
    }
    fun startEdit(account: JSONObject?) {
        editing = account?.getString("id") ?: ""
        email = account?.optString("address").orEmpty(); password = ""
        imap = account?.optString("imap_host") ?: "imap.gmail.com"; smtp = account?.optString("smtp_host") ?: "smtp.gmail.com"
        port = account?.optInt("smtp_port", 465)?.toString() ?: "465"
    }
    LaunchedEffect(Unit) { reload() }
    PushedPage(stringResource(R.string.ui_email), breadcrumb = "Settings") {
        status?.let { Banner(it, Tone.Info, null) }
        Note("Incoming email uses IMAP; sending uses SMTP. Gmail needs an app password where Google allows it. Passwords are encrypted on this phone. Up to ${Accounts.MAX} inboxes.")
        if (accounts.isNotEmpty()) ListCard {
            accounts.forEachIndexed { index, account ->
                if (index > 0) ListDivider(16.dp)
                val id = account.getString("id")
                val state = states.firstOrNull { it.adapter == Accounts.mailState(id) }
                val idle = states.firstOrNull { it.adapter == Accounts.idleState(id) }?.takeIf { it.state == "ACTIVE_BOUNDED_SESSION" }
                ListRow(account.optString("address"), subtitle = listOfNotNull(state?.let { it.state.lowercase().replace('_', ' ') + (it.error?.let { e -> " · $e" } ?: "") } ?: "Waiting for first sync",
                    idle?.let { "live" }).joinToString(" · "), icon = HfIcons.Mail, tone = Tone.Info, chevron = true, onClick = { startEdit(account) })
            }
        }
        if (editing == null) SecondaryButton(if (accounts.isEmpty()) "Add email account" else "Add another inbox", { startEdit(null) }, Modifier.fillMaxWidth(),
            enabled = accounts.size < Accounts.MAX)
        editing?.let { editingId ->
            SectionCard(if (editingId.isEmpty()) "New inbox" else "Edit ${email}") {
                HfTextField(email, { email = it }, stringResource(R.string.ui_email_address))
                HfTextField(password, { password = it }, if (editingId.isEmpty()) stringResource(R.string.ui_app_password_imap_password) else stringResource(R.string.ui_new_password_leave_blank_to_keep), password = true)
                HfTextField(imap, { imap = it }, "IMAP TLS host · port 993")
                HfTextField(smtp, { smtp = it }, "SMTP host")
                GroupLabel("SMTP TLS port")
                ChipPicker(listOf("465", "587"), port) { port = it }
                PrimaryButton("Save and connect", {
                    scope.launch {
                        status = try {
                            withContext(Dispatchers.IO) {
                                val existing = editingId.takeIf { it.isNotEmpty() }?.let { Accounts.find(app.secrets, Accounts.MAIL, it) }
                                val id = if (existing != null && existing.optString("address") == email.trim() && existing.optString("imap_host") == imap.trim()) existing.getString("id")
                                    else Accounts.mailId(email, imap)
                                require(existing?.getString("id") == id || Accounts.find(app.secrets, Accounts.MAIL, id) == null) { "This inbox is already connected" }
                                val secret = password.ifEmpty { existing?.optString("password").orEmpty() }
                                val prior = existing?.let { MailConfig.from(it) }
                                // A Microsoft account keeps its sign-in; typing a password here switches it to password login.
                                val keepOauth = prior?.oauth == true && password.isEmpty()
                                val config = MailConfig(id, email.trim(), if (imap.trim().equals("imap.gmail.com", true)) secret.replace(" ", "") else secret,
                                    imap.trim(), smtp.trim(), port.toInt(), if (keepOauth) prior!!.auth else "PASSWORD",
                                    if (keepOauth) prior!!.clientId else "", if (keepOauth) prior!!.tenant else "common").also { it.validate() }
                                // Changing the address or server is a different inbox: the old one is replaced, its messages are kept.
                                if (existing != null && existing.getString("id") != id) removeMail(app, existing.getString("id"))
                                Accounts.save(app.secrets, Accounts.MAIL, config.json())
                            }
                            password = ""; editing = null; reload(); CommunicationWork.schedule(app); "Email settings encrypted; connection check scheduled."
                        } catch (e: CancellationException) { throw e }
                        catch (caught: Exception) { Diagnostics.warn(caught); "Email settings were not accepted. Check the address, password and TLS hosts, and that this inbox is not already connected." }
                    }
                }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && (password.isNotBlank() || editingId.isNotEmpty()))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(stringResource(R.string.ui_cancel), { editing = null; password = "" })
                    if (editingId.isNotEmpty()) QuietButton("Disconnect this inbox", { removing = accounts.firstOrNull { it.getString("id") == editingId } })
                }
            }
        }
        if (editing == null && accounts.size < Accounts.MAX) MicrosoftSignIn(app, onSaved = { status = it; scope.launch { reload() } })
        if (accounts.isNotEmpty()) SectionCard("Sync") {
            SecondaryButton("Sync all inboxes now", { scope.launch { CommunicationWork.schedule(app); status = "Sync scheduled." } }, Modifier.fillMaxWidth())
            SecondaryButton("Start live mail (IMAP IDLE) · 10 minutes, every inbox", {
                try { app.startForegroundService(android.content.Intent(app, MailIdleService::class.java).setAction("START")) }
                catch (caught: Exception) { Diagnostics.warn(caught); status = "Android blocked the visible IMAP session. Use Sync now and check notification permission." }
            }, Modifier.fillMaxWidth())
            QuietButton("Stop live mail", { app.stopService(android.content.Intent(app, MailIdleService::class.java)) })
            Note("A new inbox imports its ${MailAdapter.INITIAL_WINDOW} most recent messages per folder; older mail stays on the server.")
        }
    }
    removing?.let { account ->
        ConfirmDialog("Disconnect ${account.optString("address")}?", "The saved password is deleted from this phone. Messages already captured are kept.", "Disconnect", onConfirm = {
            removing = null
            scope.launch {
                status = try { withContext(Dispatchers.IO) { removeMail(app, account.getString("id")) }; editing = null; reload(); "Inbox disconnected; saved messages are kept." }
                    catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); "The inbox could not be disconnected." }
            }
        }, onDismiss = { removing = null })
    }
}

/**
 * Microsoft 365 / Outlook.com: device-code sign-in with the owner's own Azure app registration (owner decision 2026-11-27).
 * The refresh token is encrypted with the other mail secrets; HellFire never sees the Microsoft password.
 */
@Composable
private fun MicrosoftSignIn(app: HellFireApp, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var tenant by remember { mutableStateOf("common") }
    var code by remember { mutableStateOf<MicrosoftOAuth.DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    SectionCard("Microsoft 365 / Outlook") {
        Note("Microsoft mail needs sign-in instead of a password. Register an app in Azure (Entra ID) as a public client with " +
            "the IMAP.AccessAsUser.All and SMTP.Send delegated permissions, then paste its Application (client) ID here.")
        HfTextField(address, { address = it.trim() }, "Microsoft email address")
        HfTextField(clientId, { clientId = it.trim() }, "Application (client) ID")
        HfTextField(tenant, { tenant = it.trim() }, "Tenant", supporting = "common for most accounts; your organisation's tenant ID if it requires one")
        code?.let { c -> Banner("Open ${c.verificationUri} on any device and enter ${c.userCode}. Waiting for you to finish…", Tone.Info, null) }
        note?.let { Note(it) }
        if (job == null) PrimaryButton("Sign in with Microsoft", {
            job = scope.launch {
                try {
                    val started = withContext(Dispatchers.IO) { MicrosoftOAuth.start(clientId, tenant) }
                    code = started
                    var wait = started.intervalSeconds
                    var result: MicrosoftOAuth.Poll = MicrosoftOAuth.Poll.Pending
                    while (System.currentTimeMillis() < started.expiresAt) {
                        kotlinx.coroutines.delay(wait * 1000L)
                        result = withContext(Dispatchers.IO) { MicrosoftOAuth.poll(clientId, tenant, started.deviceCode) }
                        if (result is MicrosoftOAuth.Poll.SlowDown) wait += 5
                        if (result is MicrosoftOAuth.Poll.Done || result is MicrosoftOAuth.Poll.Failed) break
                    }
                    code = null
                    when (result) {
                        is MicrosoftOAuth.Poll.Done -> {
                            withContext(Dispatchers.IO) {
                                val id = Accounts.mailId(address, MicrosoftOAuth.IMAP_HOST)
                                require(Accounts.find(app.secrets, Accounts.MAIL, id) == null) { "This inbox is already connected" }
                                val config = MailConfig(id, address, result.tokens.refreshToken, MicrosoftOAuth.IMAP_HOST, MicrosoftOAuth.SMTP_HOST, 587,
                                    "MICROSOFT_OAUTH", clientId, tenant).also { it.validate() }
                                Accounts.save(app.secrets, Accounts.MAIL, config.json())
                            }
                            CommunicationWork.schedule(app)
                            address = ""; onSaved("Microsoft inbox connected; first sync scheduled.")
                        }
                        is MicrosoftOAuth.Poll.Failed -> note = "Microsoft sign-in did not finish (${result.code}). Nothing was saved."
                        else -> note = "The sign-in code expired. Nothing was saved."
                    }
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) { Diagnostics.warn(caught); code = null; note = caught.message ?: "Microsoft sign-in failed. Nothing was saved." }
                finally { job = null }
            }
        }, Modifier.fillMaxWidth(), enabled = address.contains('@') && MicrosoftOAuth.validClientId(clientId) && MicrosoftOAuth.validTenant(tenant))
        else QuietButton(stringResource(R.string.ui_cancel), { job?.cancel(); job = null; code = null })
    }
}

private suspend fun removeMail(app: HellFireApp, id: String) {
    // A live IDLE session holds the old credentials; stop it before they are deleted.
    app.stopService(android.content.Intent(app, MailIdleService::class.java))
    Accounts.remove(app.secrets, Accounts.MAIL, id)
    app.database.communications().clearIntegration(Accounts.mailState(id))
    app.database.communications().clearIntegration(Accounts.idleState(id))
}

/** Settings › WhatsApp: one self-hosted relay per WhatsApp account. */
@Composable
fun WhatsAppPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val states by remember { app.database.communications().integrations() }.collectAsStateWithLifecycle(emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    var label by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("web") }
    suspend fun reload() {
        accounts = withContext(Dispatchers.IO) { try { Accounts.gateways(app.secrets) }
            catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); status = "Saved gateway settings are unavailable. Set up the connections again."; emptyList() } }
    }
    fun startEdit(account: JSONObject?) {
        editing = account?.getString("id") ?: ""
        label = account?.optString("label").orEmpty(); gateway = account?.optString("url").orEmpty(); token = ""
        transport = account?.let(Accounts::transport) ?: "web"
    }
    LaunchedEffect(Unit) { reload() }
    val publicExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        if (uri != null) scope.launch {
            status = try { withContext(Dispatchers.IO) { (app.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException("Android could not open that destination")).use { it.write(GatewayAdapter(app).publicKey().toByteArray()) } }; "Device public key exported. Add it to each self-hosted gateway." }
                catch (caught: Exception) { Diagnostics.warn(caught); "Public key export failed." }
        }
    }
    PushedPage(stringResource(R.string.ui_whatsapp), breadcrumb = "Settings") {
        status?.let { Banner(it, Tone.Info, null) }
        Note("Each WhatsApp account needs its own self-hosted gateway (relay URL and device token). All gateways use this phone's public key. Up to ${Accounts.MAX} accounts.")
        if (accounts.isNotEmpty()) ListCard {
            accounts.forEachIndexed { index, account ->
                if (index > 0) ListDivider(16.dp)
                val state = states.firstOrNull { it.adapter == Accounts.gatewayState(account.getString("id")) }
                ListRow(Accounts.label(account), subtitle = listOf(if (Accounts.transport(account) == "web") "WhatsApp Web · capture only" else "Business API",
                    state?.let { it.state.lowercase().replace('_', ' ') + (it.error?.let { e -> " · $e" } ?: "") } ?: "waiting for first sync").joinToString(" · "),
                    icon = HfIcons.Chat, tone = Tone.Success, chevron = true, onClick = { startEdit(account) })
            }
        }
        if (editing == null) SecondaryButton(if (accounts.isEmpty()) "Add WhatsApp account" else "Add another WhatsApp account", { startEdit(null) }, Modifier.fillMaxWidth(),
            enabled = accounts.size < Accounts.MAX)
        editing?.let { editingId ->
            SectionCard(if (editingId.isEmpty()) "New WhatsApp account" else "Edit ${label.ifBlank { "account" }}") {
                GroupLabel("Connection type")
                ChipPicker(listOf("web", "meta"), transport, { if (it == "web") "WhatsApp Web" else "Business API" }) { transport = it }
                Note(if (transport == "web") "WhatsApp Web uses an unofficial linked session. Your server can read captured messages. Sending is disabled; attachments are metadata only. Pair at the gateway's /pair page with its device token, then scan with WhatsApp › Linked devices."
                    else "Business mode needs eligible Meta Coexistence onboarding and gateway credentials. Never paste the Meta app secret into this app.")
                HfTextField(label, { label = it.take(40) }, "Name, e.g. Personal or Shop")
                HfTextField(gateway, { gateway = it }, "Self-hosted gateway HTTPS URL")
                HfTextField(token, { token = it }, if (editingId.isEmpty()) "Paired device token" else "New device token (leave blank to keep)", password = true)
                SecondaryButton("Export device public key", { publicExport.launch("hellfire-device-public.pem") }, Modifier.fillMaxWidth())
                PrimaryButton("Save and connect", {
                    scope.launch {
                        status = try {
                            withContext(Dispatchers.IO) {
                                val url = gateway.trim().trimEnd('/')
                                val uri = java.net.URI(url)
                                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
                                val existing = editingId.takeIf { it.isNotEmpty() }?.let { Accounts.find(app.secrets, Accounts.GATEWAY, it) }
                                val id = if (existing != null && existing.optString("url") == url && Accounts.transport(existing) == transport) existing.getString("id")
                                    else Accounts.gatewayId(transport, url)
                                // One relay serves one WhatsApp account; the same URL cannot be added twice.
                                require(Accounts.gateways(app.secrets).none { it.getString("id") != existing?.getString("id") && it.optString("url") == url }) { "This gateway is already connected" }
                                val secret = token.ifEmpty { existing?.optString("token").orEmpty() }
                                require(secret.length >= 32)
                                GatewayAdapter(app).publicKey()
                                if (existing != null && existing.getString("id") != id) removeGateway(app, existing.getString("id"))
                                Accounts.save(app.secrets, Accounts.GATEWAY, JSONObject().put("id", id).put("label", label.trim()).put("url", url)
                                    .put("token", secret).put("transport", transport))
                            }
                            token = ""; editing = null; reload(); CommunicationWork.schedule(app); "Gateway settings encrypted; connection check scheduled."
                        } catch (e: CancellationException) { throw e }
                        catch (caught: Exception) { Diagnostics.warn(caught); "Gateway settings failed. Use a new HTTPS gateway URL and its paired device token (32+ characters)." }
                    }
                }, Modifier.fillMaxWidth(), enabled = gateway.isNotBlank() && (token.length >= 32 || (editingId.isNotEmpty() && token.isEmpty())))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(stringResource(R.string.ui_cancel), { editing = null; token = "" })
                    if (editingId.isNotEmpty()) QuietButton("Disconnect this account", { removing = accounts.firstOrNull { it.getString("id") == editingId } })
                }
            }
        }
        if (accounts.isNotEmpty()) SecondaryButton("Sync all WhatsApp accounts now", { scope.launch { CommunicationWork.schedule(app); status = "Sync scheduled." } }, Modifier.fillMaxWidth())
    }
    removing?.let { account ->
        ConfirmDialog("Disconnect ${Accounts.label(account)}?", "The device token is deleted from this phone and saved messages are kept. To stop WhatsApp Web capture on the server, also log it out in WhatsApp › Linked devices.",
            "Disconnect", onConfirm = {
                removing = null
                scope.launch {
                    status = try { withContext(Dispatchers.IO) { removeGateway(app, account.getString("id")) }; editing = null; reload(); "WhatsApp account disconnected; saved messages are kept." }
                        catch (e: CancellationException) { throw e } catch (caught: Exception) { Diagnostics.warn(caught); "The account could not be disconnected." }
                }
            }, onDismiss = { removing = null })
    }
}

private suspend fun removeGateway(app: HellFireApp, id: String) {
    Accounts.remove(app.secrets, Accounts.GATEWAY, id)
    app.database.communications().clearIntegration(Accounts.gatewayState(id))
}
