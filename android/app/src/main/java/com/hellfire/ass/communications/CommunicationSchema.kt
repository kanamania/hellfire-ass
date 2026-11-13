package com.hellfire.ass.communications

import androidx.sqlite.db.SupportSQLiteDatabase

object CommunicationSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS communication_message (id TEXT NOT NULL, channel TEXT NOT NULL, accountId TEXT NOT NULL, externalId TEXT NOT NULL, sourceId TEXT NOT NULL, threadKey TEXT NOT NULL, sender TEXT NOT NULL, recipient TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, occurredAt INTEGER NOT NULL, direction TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_communication_message_channel_accountId_externalId ON communication_message(channel, accountId, externalId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_communication_message_sourceId ON communication_message(sourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS mail_cursor (accountId TEXT NOT NULL, folder TEXT NOT NULL, uidValidity INTEGER NOT NULL, lastUid INTEGER NOT NULL, PRIMARY KEY(accountId,folder))")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbound_draft (id TEXT NOT NULL, channel TEXT NOT NULL, accountId TEXT NOT NULL, recipient TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, approvalId TEXT, approvedAt INTEGER, externalId TEXT, error TEXT, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS action_audit (id TEXT NOT NULL, draftId TEXT NOT NULL, action TEXT NOT NULL, state TEXT NOT NULL, occurredAt INTEGER NOT NULL, error TEXT, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_audit_draftId ON action_audit(draftId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS integration_state (adapter TEXT NOT NULL, state TEXT NOT NULL, updatedAt INTEGER NOT NULL, error TEXT, PRIMARY KEY(adapter))")
    }
}
