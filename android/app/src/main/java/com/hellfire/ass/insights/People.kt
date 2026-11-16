package com.hellfire.ass.insights

import android.content.Context
import androidx.room.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ui.EventText
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** The owner's name for one phone number, WhatsApp id or email address (v14). Handles that share a name are one person. */
@Entity(tableName = "person_alias")
data class PersonAlias(@PrimaryKey val handle: String, val name: String, val updatedAt: Long, val personKey: String? = null)

@Dao
interface PeopleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: PersonAlias)
    @Query("SELECT * FROM person_alias ORDER BY name") suspend fun all(): List<PersonAlias>
    @Query("SELECT * FROM person_alias ORDER BY name") fun aliases(): Flow<List<PersonAlias>>
    @Query("DELETE FROM person_alias WHERE handle = :handle") suspend fun delete(handle: String)
}

/** Turns the sender and recipient strings of each channel into one comparable key per phone number or address. */
object Handles {
    private val whatsappUser = Regex("^([0-9]{6,20})@(?:c\\.us|s\\.whatsapp\\.net)$")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phone = Regex("^[+0-9 ()./-]+$")
    private val automated = Regex("^(?:no-?reply|do-?not-?reply|notifications?|mailer(?:-daemon)?|newsletter|news|info|support|billing|alerts?|hello|team|accounts?|service|updates?|marketing|admin|security)(?:[.+_-].*)?$")

    /** Phone numbers compare on their last nine digits, so +255712345678, 0712345678 and a WhatsApp id are one key. */
    fun key(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.endsWith("@g.us")) return "group:" + text.substringBefore('@')
        whatsappUser.find(text)?.let { return telephone(it.groupValues[1]) }
        if (text.endsWith("@lid")) return "wa:" + text.substringBefore('@')
        email.find(text)?.let { return "mail:" + it.value.lowercase(Locale.ROOT) }
        val digits = text.filter(Char::isDigit)
        return when {
            phone.matches(text) && digits.length >= 9 -> telephone(digits)
            phone.matches(text) && digits.length >= 3 -> "short:$digits"
            else -> "name:" + text.lowercase(Locale.ROOT)
        }
    }

    private fun telephone(digits: String): String {
        val value = digits.removePrefix("00")
        return "tel:" + when {
            value.length == 12 && value.startsWith("255") -> value.takeLast(9)
            value.length == 10 && value.startsWith("0") -> value.takeLast(9)
            value.length == 9 -> value
            else -> "+$value"
        }
    }

    /** A real person's number or address, as opposed to a group, a short code, a sender name such as M-PESA, or an automated mailbox. */
    fun person(key: String): Boolean = key.startsWith("tel:") || key.startsWith("wa:") ||
        (key.startsWith("mail:") && !automated.matches(key.removePrefix("mail:").substringBefore('@')))

    fun label(key: String): String = when {
        key.startsWith("tel:") -> key.removePrefix("tel:").let { if (it.length == 9) "+255$it" else it }
        key.startsWith("mail:") -> key.removePrefix("mail:")
        key.startsWith("wa:") -> "WhatsApp " + key.removePrefix("wa:")
        else -> key
    }

    /** Readable form of a raw sender: the display name of "Asha <a@b.c>", a WhatsApp id as a phone number, otherwise the text. */
    fun display(raw: String): String {
        val text = raw.trim()
        whatsappUser.find(text)?.let { return "+" + it.groupValues[1] }
        val name = text.substringBefore('<').trim().trim('"')
        return if ('<' in text && name.isNotEmpty()) name else text.removeSurrounding("<", ">")
    }
}

/** One message to or from someone, on any channel. [handle] is the other side's [Handles.key]. */
data class Interaction(val handle: String, val raw: String, val channel: String, val incoming: Boolean, val at: Long,
    val preview: String, val sourceId: String, val messageId: String? = null, val group: Boolean = false, val thread: String? = null)

data class Person(val key: String, val name: String, val named: Boolean, val handles: Map<String, String>, val lastAt: Long,
    val incoming: Int, val outgoing: Int, val channels: Set<String>)

object People {
    fun namedKey(name: String): String = "n:" + name.trim().lowercase(Locale.ROOT)

    /** Key of the person behind a handle: the owner's name for it when there is one, else the handle itself. */
    fun personKey(handle: String, aliases: Map<String, String>, identities: Map<String, String> = emptyMap()): String = identities[handle] ?: aliases[handle]?.let { namedKey(it) } ?: handle

    fun build(interactions: List<Interaction>, aliases: Map<String, String>, identities: Map<String, String> = emptyMap()): List<Person> =
        interactions.filter { Handles.person(it.handle) || it.handle in aliases }.groupBy { personKey(it.handle, aliases, identities) }.map { (key, list) ->
            val latest = list.maxBy { it.at }
            val alias = aliases[latest.handle]
            Person(key, alias ?: Handles.display(latest.raw), alias != null, list.associate { it.handle to Handles.display(it.raw) }, latest.at,
                list.count { it.incoming }, list.count { !it.incoming }, list.map { it.channel }.toSet())
        }.sortedByDescending { it.lastAt }
}

data class WaitingItem(val personKey: String, val name: String, val channel: String, val at: Long, val preview: String,
    val sourceId: String, val messageId: String?, val reviewKey: String = personKey)

/** [iOwe]: their message is the latest and has gone unanswered. [theyOwe]: the owner asked something and has heard nothing back. */
data class WaitingOn(val iOwe: List<WaitingItem>, val theyOwe: List<WaitingItem>)

object Waiting {
    const val REPLY_AFTER = 4 * 3_600_000L
    const val CHASE_AFTER = 24 * 3_600_000L
    const val WINDOW = 14 * 86_400_000L
    private val asks = Regex("\\?|\\b(?:please|kindly|let me know|confirm|can you|could you|will you|when|tafadhali|naomba|nijulishe|unijulishe|thibitisha|unaweza|lini|utanijulisha|nipigie|nitumie)\\b", RegexOption.IGNORE_CASE)

    /**
     * Looks at the latest message with each person across all channels, so an SMS reply settles a WhatsApp question.
     * Groups and automated senders are ignored. [dismissed] maps a person key to the message time the owner cleared.
     */
    fun compute(interactions: List<Interaction>, aliases: Map<String, String>, now: Long, dismissed: Map<String, Long> = emptyMap(), identities: Map<String, String> = emptyMap()): WaitingOn {
        val groups = interactions.filter { !it.group && (Handles.person(it.handle) || it.handle in aliases) && it.at <= now && it.at > now - WINDOW }
            .groupBy { People.personKey(it.handle, aliases, identities) + (it.thread?.let { thread -> "|$thread" } ?: "") }
        val incoming = mutableListOf<WaitingItem>(); val outgoing = mutableListOf<WaitingItem>()
        for ((reviewKey, list) in groups) {
            val requests = list.filter { asks.containsMatchIn(it.preview) }
            val request = requests.maxByOrNull { it.at } ?: continue
            if (list.any { it.at > request.at && it.incoming != request.incoming }) continue
            if ((dismissed[reviewKey] ?: Long.MIN_VALUE) >= request.at || (dismissed["snooze:$reviewKey"] ?: 0) > now) continue
            val key = People.personKey(request.handle, aliases, identities)
            val item = WaitingItem(key, aliases[request.handle] ?: Handles.display(request.raw), request.channel, request.at, request.preview,
                request.sourceId, request.messageId, reviewKey)
            if (request.incoming && now - request.at >= REPLY_AFTER) incoming += item
            if (!request.incoming && now - request.at >= CHASE_AFTER) outgoing += item
        }
        return WaitingOn(incoming.sortedBy { it.at }, outgoing.sortedBy { it.at })
    }
}

/** Waiting-on rows the owner cleared ("no reply needed"); a newer message from the same person shows again. */
object WaitingPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("waiting", 0)
    fun dismissed(context: Context): Map<String, Long> = prefs(context).all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
    fun snooze(context: Context, key: String, until: Long) = prefs(context).edit().putLong("snooze:$key", until).apply()
    fun dismiss(context: Context, personKey: String, at: Long) = prefs(context).edit().putLong(personKey, at).apply()
}

/** Messages with other people from email, WhatsApp and SMS in [since, until). Decrypts SMS; call off the main thread. */
suspend fun loadInteractions(app: HellFireApp, since: Long, until: Long = System.currentTimeMillis() + 1, smsLimit: Int = 4000): List<Interaction> {
    val messages = app.database.communications().messagesIn(since, until).mapNotNull { m ->
        val incoming = m.direction == "INCOMING"
        val raw = (if (incoming) m.sender else m.recipient).substringBefore(", ").ifBlank { return@mapNotNull null }
        Interaction(Handles.key(raw) ?: return@mapNotNull null, raw, if (m.channel == "EMAIL_IMAP") "Email" else "WhatsApp", incoming, m.occurredAt,
            listOf(m.subject, m.body).filter(String::isNotBlank).joinToString(" · ").take(200), m.sourceId, m.id, m.threadKey.endsWith("@g.us"), if (m.channel == "EMAIL_IMAP") "${m.accountId}:${m.threadKey}" else null)
    }
    val sms = app.database.events().fromSource("android_sms", since, until, smsLimit).mapNotNull { event ->
        val fields = EventText.fields(app, event)
        val raw = fields["sender_hint"]?.firstOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        Interaction(Handles.key(raw) ?: return@mapNotNull null, raw, "SMS", event.kind != "OUTGOING", event.occurredAt, EventText.body(fields).take(200), event.id)
    }
    return messages + sms
}

suspend fun loadAliases(app: HellFireApp): Map<String, String> = app.database.people().all().associate { it.handle to it.name }

suspend fun loadIdentities(app: HellFireApp): Map<String, String> = app.database.people().all().associate { it.handle to (it.personKey ?: People.namedKey(it.name)) }
