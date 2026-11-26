package com.hellfire.ass.insights

import androidx.room.*
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.Money
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.hellfire.ass.tasks.TaskTime

/**
 * One loan or repayment the owner recorded against a person (v14). [amount] is signed: positive means the person owes
 * the owner more, negative means the owner owes the person more. These rows are the owner's own notes; they never
 * change the ledger, and [ledgerId] only points at the payment that moved the money.
 */
@Entity(tableName = "debt_entry", indices = [Index("person")])
data class DebtEntry(@PrimaryKey val id: String, val person: String, val amount: Long,
    /** LENT, BORROWED, REPAID_TO_ME or I_REPAID. */
    val kind: String, val note: String, val occurredAt: Long,
    /** When the money was promised back; null if no date was agreed. */
    val dueAt: Long? = null, val ledgerId: String? = null, val createdAt: Long, val personKey: String? = null)

@Dao
interface DebtDao {
    @Query("UPDATE debt_entry SET personKey = :key WHERE personKey IS NULL AND LOWER(TRIM(person)) = LOWER(TRIM(:name))") suspend fun linkLegacy(name: String, key: String)
    @Query("UPDATE debt_entry SET personKey = :key, person = :name WHERE id = :id") suspend fun setPerson(id: String, key: String, name: String)

    @Query("UPDATE debt_entry SET person = :name, personKey = :into WHERE personKey = :from") suspend fun renamePerson(from: String, into: String, name: String)

    @Query("UPDATE debt_entry SET person = :name WHERE LOWER(TRIM(person)) = LOWER(TRIM(:oldName))") suspend fun rename(oldName: String, name: String)

    @Insert suspend fun insert(value: DebtEntry)
    @Query("SELECT * FROM debt_entry ORDER BY occurredAt DESC, createdAt DESC") fun entries(): Flow<List<DebtEntry>>
    @Query("SELECT * FROM debt_entry ORDER BY occurredAt DESC, createdAt DESC") suspend fun all(): List<DebtEntry>
    @Query("DELETE FROM debt_entry WHERE id = :id") suspend fun delete(id: String)
}

/** Net position with one person. [balance] > 0: they owe the owner; < 0: the owner owes them. */
data class DebtBalance(val person: String, val balance: Long, val lastAt: Long, val dueAt: Long?, val personKey: String? = null) {
    fun overdue(now: Long) = balance != 0L && dueAt != null && dueAt < now
}

/** An approved payment that looks like a repayment of an open debt; a suggestion until the owner records it. */
data class RepaymentSuggestion(val person: String, val kind: String, val candidate: FinanceCandidate)

object Debts {
    val kinds = listOf("LENT", "BORROWED", "REPAID_TO_ME", "I_REPAID")
    fun label(kind: String) = when (kind) { "LENT" -> "I lent"; "BORROWED" -> "I borrowed"; "REPAID_TO_ME" -> "They paid me back"; else -> "I paid back" }
    fun signed(kind: String, amount: Long): Long {
        require(kind in kinds && amount > 0)
        return if (kind == "LENT" || kind == "I_REPAID") amount else Math.negateExact(amount)
    }
    fun key(person: String) = person.trim().lowercase(Locale.ROOT)

    /** Open positions, largest first. People who are settled (zero balance) are left out. */
    fun balances(entries: List<DebtEntry>): List<DebtBalance> = entries.groupBy { it.personKey ?: key(it.person) }.values.map { list ->
        val balance = list.fold(0L) { sum, e -> Math.addExact(sum, e.amount) }
        // The agreed date that still matters is the one on the side that is open.
        val due = list.filter { it.dueAt != null && (if (balance > 0) it.kind == "LENT" else it.kind == "BORROWED") }.maxOfOrNull { it.dueAt!! }
        DebtBalance(list.maxBy { it.createdAt }.person.trim(), balance, list.maxOf { it.occurredAt }, due, list.first().personKey)
    }.filter { it.balance != 0L }.sortedByDescending { kotlin.math.abs(it.balance) }

    /**
     * Approved payments that name someone with an open debt: money in from a person who owes the owner, or money out to
     * a person the owner owes. [taken] holds source ids already linked or dismissed.
     */
    fun suggestRepayments(balances: List<DebtBalance>, approved: List<FinanceCandidate>, since: Long, taken: Set<String>): List<RepaymentSuggestion> =
        balances.flatMap { b ->
            val word = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(b.person) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            approved.filter { c -> c.state == "APPROVED" && c.occurredAt >= since && c.sourceId !in taken &&
                c.kind == (if (b.balance > 0) "INCOME" else "EXPENSE") && b.person.length >= 3 && word.containsMatchIn(c.evidence) }
                .map { RepaymentSuggestion(b.person, if (b.balance > 0) "REPAID_TO_ME" else "I_REPAID", it) }
        }

    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    /** Editable reminder text for money owed to the owner. Nothing is sent until the owner sends it. */
    fun reminderText(person: String, amount: Long, dueAt: Long?, swahili: Boolean): String {
        val first = person.trim().substringBefore(' ')
        val due = dueAt?.let { date.format(Instant.ofEpochMilli(it).atZone(TaskTime.zone)) }
        return if (swahili) "Habari $first, nakukumbusha kuhusu ${Money.display(amount)}" + (due?.let { " tuliyokubaliana kurudishwa $it" } ?: " uliyoahidi kurudisha") + ". Asante."
        else "Hi $first, a gentle reminder about the ${Money.display(amount)}" + (due?.let { " that was due on $it" } ?: " you promised to pay back") + ". Thank you."
    }
}
