package com.hellfire.ass

import com.hellfire.ass.finance.AutoPost
import com.hellfire.ass.finance.FinanceCandidate
import com.hellfire.ass.finance.FinanceParser
import com.hellfire.ass.finance.Money
import org.junit.Assert.*
import org.junit.Test

class AutoPostTest {
    private val on = AutoPost.Rule(enabled = true, cap = 50_000)
    private fun candidate(provider: String = "SELCOM", ref: String? = "SLC123456", amount: Long = 12_000, kind: String = "EXPENSE") =
        FinanceCandidate("c", "s", provider = provider, reference = ref, kind = kind, amount = amount, fee = 0, merchant = null, evidence = "Synthetic", occurredAt = 1)
    private fun facts(c: FinanceCandidate = candidate(), sms: Boolean = true, sender: String? = c.provider, account: String? = "selcom", category: String? = "Food") =
        AutoPost.Facts(sms, sender, c, account, category)

    @Test fun `trustedTemplateAutoPostsWhenEnabled - only when every check passes`() {
        assertNull(AutoPost.refusal(on, facts()))
        assertEquals("off", AutoPost.refusal(on.copy(enabled = false), facts()))
        assertNotNull(AutoPost.refusal(on, facts(sms = false)))
        assertNotNull(AutoPost.refusal(on, facts(sender = null)))                        // text claims SELCOM, sender is a phone number
        assertNotNull(AutoPost.refusal(on, facts(candidate(ref = null))))
        assertNotNull(AutoPost.refusal(on, facts(candidate(amount = 50_001))))
        assertNotNull(AutoPost.refusal(on, facts(candidate(kind = "REFUND"))))
        assertNotNull(AutoPost.refusal(on, facts(account = null)))
        assertNotNull(AutoPost.refusal(on, facts(candidate().copy(state = "DUPLICATE"))))
    }
    @Test fun `DTB salary and Selcom payments are recognised`() {
        val salary = FinanceParser.parse("DTB", "Dear Customer, your account has been credited with TZS 2,450,000.00 SALARY NOV. Ref DTB778899")!!
        assertEquals("DTB", salary.provider); assertEquals("INCOME", salary.kind); assertEquals(2_450_000, salary.amount)
        assertEquals("DTB", FinanceParser.senderProvider("Diamond Trust Bank"))
        assertEquals("SELCOM", FinanceParser.senderProvider("SELCOM"))
        assertNull(FinanceParser.senderProvider("+255712000000"))
    }
    @Test fun `centsDoNotDropTransaction - rounded half up`() {
        assertEquals(12_346, FinanceParser.parse("NMB", "Paid TZS 12,345.50 ref NMB12345")!!.amount)
        assertEquals(12_345, FinanceParser.parse("NMB", "Paid TZS 12,345.49 ref NMB12345")!!.amount)
        assertNull(FinanceParser.parse("NMB", "Paid TZS 0.25 ref NMB12345"))
        assertTrue(runCatching { Money.tzs("1.25") }.isFailure) // owner-typed amounts stay whole shillings
    }
}
