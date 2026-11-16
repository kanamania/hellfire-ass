package com.hellfire.ass.templates

import androidx.room.*
import androidx.room.withTransaction
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.FinanceRepository
import com.hellfire.ass.finance.Money
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.TaskTime
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/** A saved set of tasks, expenses and income the owner adds together in one step (v14). */
@Entity(tableName = "action_template")
data class ActionTemplate(@PrimaryKey val id: String, val name: String,
    /** JSON array of [TemplateItem]; read and written only through [TemplateItems]. */
    val items: String, val createdAt: Long, val updatedAt: Long, val lastUsedAt: Long? = null, val uses: Int = 0)

/**
 * One line of a template. TASK uses [title], [dueInDays], [dueTime] and [bill]; EXPENSE and INCOME use [title] as the
 * description with [category] and [accountId]. A money line without an [amount] is filled in when the template is used.
 */
data class TemplateItem(val type: String, val title: String, val amount: Long? = null, val category: String? = null,
    val accountId: String? = null, val dueInDays: Int = 0, val dueTime: String = "09:00", val bill: Boolean = false)

object TemplateItems {
    const val MAX = 40
    val types = listOf("TASK", "EXPENSE", "INCOME")

    fun validate(item: TemplateItem) {
        require(item.type in types) { "Unknown template line type" }
        require(item.title.isNotBlank() && item.title.length <= 200) { "Each line needs a name of up to 200 characters" }
        require(item.amount == null || item.amount in 1..1_000_000_000_000L) { "Use a whole-shilling amount" }
        require(item.dueInDays in 0..3660) { "Due days must be 0–3660" }
        requireNotNull(runCatching { LocalTime.parse(item.dueTime) }.getOrNull()) { "Use a time like 09:00" }
    }

    fun encode(items: List<TemplateItem>): String {
        require(items.isNotEmpty() && items.size <= MAX) { "A template needs 1–$MAX lines" }
        return JSONArray().apply {
            items.forEach { item ->
                validate(item)
                put(JSONObject().put("type", item.type).put("title", item.title.trim()).put("amount", item.amount ?: JSONObject.NULL)
                    .put("category", item.category ?: JSONObject.NULL).put("accountId", item.accountId ?: JSONObject.NULL)
                    .put("dueInDays", item.dueInDays).put("dueTime", item.dueTime).put("bill", item.bill))
            }
        }.toString()
    }

    fun decode(json: String): List<TemplateItem> = JSONArray(json).let { array ->
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            TemplateItem(o.getString("type"), o.getString("title"), if (o.isNull("amount")) null else o.getLong("amount"),
                if (o.isNull("category")) null else o.getString("category"), if (o.isNull("accountId")) null else o.getString("accountId"),
                o.optInt("dueInDays"), o.optString("dueTime", "09:00"), o.optBoolean("bill"))
        }
    }

    /** When a task line falls due if the template is used at [at]: that many days later, at the line's clock time. */
    fun dueAt(item: TemplateItem, at: Long): Long = Instant.ofEpochMilli(at).atZone(TaskTime.zone).toLocalDate()
        .plusDays(item.dueInDays.toLong()).atTime(LocalTime.parse(item.dueTime)).atZone(TaskTime.zone).toInstant().toEpochMilli()

    fun describe(item: TemplateItem): String = when (item.type) {
        "TASK" -> "Task: ${item.title}" + (item.amount?.let { " · ${Money.display(it)}" } ?: "")
        else -> "${if (item.type == "INCOME") "Income" else "Expense"}: ${item.title} · ${item.amount?.let(Money::display) ?: "amount when used"}"
    }
}

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: ActionTemplate)
    @Query("SELECT * FROM action_template ORDER BY COALESCE(lastUsedAt, createdAt) DESC") fun templates(): Flow<List<ActionTemplate>>
    @Query("SELECT * FROM action_template ORDER BY name") suspend fun all(): List<ActionTemplate>
    @Query("SELECT * FROM action_template WHERE id = :id") suspend fun find(id: String): ActionTemplate?
    @Query("UPDATE action_template SET lastUsedAt = :now, uses = uses + 1 WHERE id = :id") suspend fun used(id: String, now: Long)
    @Query("DELETE FROM action_template WHERE id = :id") suspend fun delete(id: String)
}

/** What using a template added: scheduled tasks, payments posted to the ledger, and payments left in "To confirm". */
data class TemplateResult(val tasks: Int, val posted: Int, val toConfirm: Int) {
    val message: String get() = listOfNotNull(
        tasks.takeIf { it > 0 }?.let { if (it == 1) "1 task scheduled" else "$it tasks scheduled" },
        posted.takeIf { it > 0 }?.let { if (it == 1) "1 payment added to Money" else "$it payments added to Money" },
        toConfirm.takeIf { it > 0 }?.let { "$it left in To confirm (no account chosen)" },
    ).joinToString(" · ").ifEmpty { "Nothing added." }
}

/**
 * Applies a template the owner has just reviewed. The owner's tap is the confirmation: tasks become active, and money
 * lines with an account are posted to the ledger with this run as their source evidence. Lines without an account wait
 * in "To confirm" like any cash entry.
 */
class TemplateRunner(private val db: EventDatabase, private val finances: FinanceRepository, private val ownPackage: String,
    private val capture: suspend (NotificationSnapshot) -> Unit) {
    suspend fun run(template: ActionTemplate, items: List<TemplateItem>, at: Long, now: Long = System.currentTimeMillis()): TemplateResult {
        require(items.isNotEmpty() && items.size <= TemplateItems.MAX) { "Choose 1–${TemplateItems.MAX} lines" }
        items.forEach { item ->
            TemplateItems.validate(item)
            require(item.type == "TASK" || item.amount != null) { "Enter an amount for ${item.title}" }
        }
        require(at >= 0)
        val text = "Template \"${template.name}\" used:\n" + items.joinToString("\n", transform = TemplateItems::describe)
        val raw = NotificationSnapshot(UUID.randomUUID().toString(), "owner.template", at, now, source = "owner_entry", fields = mapOf("text" to listOf(text)))
        val safe = requireNotNull(NotificationNormalizer.normalize(raw, ownPackage))
        require(!safe.securitySensitive) { "Remove security words such as password or OTP from the template" }
        capture(raw)
        var tasks = 0; var posted = 0; var toConfirm = 0
        db.withTransaction {
            items.forEachIndexed { index, item ->
                if (item.type == "TASK") {
                    val due = TemplateItems.dueAt(item, at)
                    val id = UUID.randomUUID().toString()
                    db.tasks().insert(AssistantTask(id, safe.id, item.title.trim(), due, party = "OWNER", evidence = item.title.trim(), state = "ACTIVE",
                        createdAt = now, updatedAt = now, nextAlertAt = TaskTime.firstAlert(due, now), amount = item.amount, kind = if (item.bill) "BILL" else "TASK"))
                    db.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, "OWNER_TEMPLATE_TASK", "ACTIVE", now))
                    tasks++
                } else {
                    val candidateId = UUID.randomUUID().toString()
                    val account = item.accountId?.let { db.finances().account(it) }
                    val category = item.category ?: if (item.type == "INCOME") "Income" else "Other"
                    db.finances().candidate(FinanceCandidate(candidateId, safe.id, index, "MANUAL", null, item.type, item.amount!!, 0, null,
                        item.title.trim(), at, accountId = account?.id, category = category))
                    if (account != null) { finances.approve(candidateId, account.id, item.type, category, item.title.trim()); posted++ } else toConfirm++
                }
            }
            db.templates().used(template.id, now)
        }
        return TemplateResult(tasks, posted, toConfirm)
    }
}
