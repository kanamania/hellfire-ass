package com.hellfire.ass.finance

import androidx.sqlite.db.SupportSQLiteDatabase

object FinanceSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS finance_account (id TEXT NOT NULL, label TEXT NOT NULL, provider TEXT NOT NULL, currency TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS finance_candidate (id TEXT NOT NULL, sourceId TEXT NOT NULL, position INTEGER NOT NULL, provider TEXT NOT NULL, reference TEXT, kind TEXT NOT NULL, amount INTEGER NOT NULL, fee INTEGER NOT NULL, merchant TEXT, evidence TEXT NOT NULL, occurredAt INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_finance_candidate_sourceId_position ON finance_candidate(sourceId, position)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ledger_entry (id TEXT NOT NULL, accountId TEXT NOT NULL, dedupeKey TEXT NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, category TEXT NOT NULL, description TEXT NOT NULL, occurredAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, groupId TEXT NOT NULL, reversesId TEXT, PRIMARY KEY(id), FOREIGN KEY(accountId) REFERENCES finance_account(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_entry_dedupeKey ON ledger_entry(dedupeKey)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_entry_reversesId ON ledger_entry(reversesId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entry_accountId ON ledger_entry(accountId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ledger_evidence (ledgerId TEXT NOT NULL, sourceId TEXT NOT NULL, PRIMARY KEY(ledgerId, sourceId), FOREIGN KEY(ledgerId) REFERENCES ledger_entry(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_evidence_sourceId ON ledger_evidence(sourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS merchant_alias (provider TEXT NOT NULL, number TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, confirmedAt INTEGER NOT NULL, PRIMARY KEY(provider, number))")
    }
}
