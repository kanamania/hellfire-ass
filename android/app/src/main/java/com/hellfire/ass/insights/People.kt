package com.hellfire.ass.insights

import android.content.Context
import androidx.room.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.ui.EventText
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** The owner's name for one phone number, WhatsApp id or email address (v14). Handles that share a name are one person. */
@Entity(tableName = "person_alias")
data class PersonAlias(@PrimaryKey val handle: String, val name: String, val updatedAt: Long)

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
        whatsappUser.find(text)?.let { return "tel:" + it.groupValues[1].takeLast(9) }
        if (text.endsWith("@lid")) return "wa:" + text.substringBefore('@')
        email.find(text)?.let { return "mail:" + it.value.lowercase(Locale.ROOT) }
        val digits = text.filter(Char::isDigit)
        return when {
            phone.matches(text) && digits.length >= 9 -> "tel:" + digits.takeLast(9)
            phone.matches(text) && digits.length >= 3 -> "short:$digits"
            else -> "name:" + text.lowercase(Locale.ROOT)
        }
    }

    /** A real person's number or address, as opposed to a group, a short code, a sender name such as M-PESA, or an automated mailbox. */
    fun person(key: String): Boolean = key.startsWith("tel:") || key.startsWith("wa:") ||
        (key.startsWith("mail:") && !automated.matches(key.removePrefix("mail:").substringBefore('@')))

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
    val preview: String, val sourceId: String, val messageId: String? = null, val group: Boolean = false)

data class Person(val key: String, val name: String, val named: Boolean, val handles: Map<String, String>, val lastAt: Long,
    val incoming: Int, val outgoing: Int, val channels: Set<String>)

object People {
    fun namedKey(name: String): String = "n:" + name.trim().lowercase(Locale.ROOT)

    /** Key of the person behind a handle: the owner's name for it when there is one, else the handle itself. */
    fun personKey(handle: String, aliases: Map<String, String>): String = aliases[handle]?.let { namedKey(it) } ?: handle

    fun build(interactions: List<Interaction>, aliases: Map<String, String>): List<Person> =
        interactions.filter { Handles.person(it.handle) || it.handle in aliases }.groupBy { personKey(it.handle, aliases) }.map { (key, list) ->
            val latest = list.maxBy { it.at }
            val alias = aliases[latest.handle]
            Person(key, alias ?: Handles.display(latest.raw), alias != null, list.associate { it.handle to Handles.display(it.raw) }, latest.at,
                list.count { it.incoming }, list.count { !it.incoming }, list.map { it.channel }.toSet())
        }.sortedByDescending { it.lastAt }
}

data class WaitingItem(val personKey: String, val name: String, val channel: String, val at: Long, val preview: String,
    val sourceId: String, val messageId: String?)

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
    fun compute(interactions: List<Interaction>, aliases: Map<String, String>, now: Long, dismissed: Map<String, Long> = emptyMap()): WaitingOn {
        val latest = interactions.filter { !it.group && (Handles.person(it.handle) || it.handle in aliases) && it.at <= now && it.at > now - WINDOW }
            .groupBy { People.personKey(it.handle, aliases) }.mapValues { (_, list) -> list.maxBy { it.at } }
            .filter { (key, last) -> (dismissed[key] ?: Long.MIN_VALUE) < last.at }
        fun item(key: String, last: Interaction) = WaitingItem(key, aliases[last.handle] ?: Handles.display(last.raw), last.channel, last.at, last.preview, last.sourceId, last.messageId)
        return WaitingOn(
            latest.filter { (_, last) -> last.incoming && now - last.at >= REPLY_AFTER }.map { (key, last) -> item(key, last) }.sortedBy { it.at },
            latest.filter { (_, last) -> !last.incoming && now - last.at >= CHASE_AFTER && asks.containsMatchIn(last.preview) }.map { (key, last) -> item(key, last) }.sortedBy { it.at },
        )
    }
}

/** Waiting-on rows the owner cleared ("no reply needed"); a newer message from the same person shows again. */
object WaitingPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("waiting", 0)
    fun dismissed(context: Context): Map<String, Long> = prefs(context).all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
    fun dismiss(context: Context, personKey: String, at: Long) = prefs(context).edit().putLong(personKey, at).apply()
}

/** Messages with other people from email, WhatsApp and SMS in [since, until). Decrypts SMS; call off the main thread. */
suspend fun loadInteractions(app: HellFireApp, since: Long, until: Long = System.currentTimeMillis() + 1, smsLimit: Int = 4000): List<Interaction> {
    val messages = app.database.communications().messagesIn(since, until).mapNotNull { m ->
        val incoming = m.direction == "INCOMING"
        val raw = (if (incoming) m.sender else m.recipient).substringBefore(", ").ifBlank { return@mapNotNull null }
        Interaction(Handles.key(raw) ?: return@mapNotNull null, raw, if (m.channel == "EMAIL_IMAP") "Email" else "WhatsApp", incoming, m.occurredAt,
            listOf(m.subject, m.body).filter(String::isNotBlank).joinToString(" · ").take(200), m.sourceId, m.id, m.threadKey.endsWith("@g.us"))
    }
    val sms = app.database.events().fromSource("android_sms", since, until, smsLimit).mapNotNull { event ->
        val fields = EventText.fields(app, event)
        val raw = fields["sender_hint"]?.firstOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        Interaction(Handles.key(raw) ?: return@mapNotNull null, raw, "SMS", event.kind != "OUTGOING", event.occurredAt, EventText.body(fields).take(200), event.id)
    }
    return messages + sms
}

suspend fun loadAliases(app: HellFireApp): Map<String, String> = app.database.people().all().associate { it.handle to it.name }
