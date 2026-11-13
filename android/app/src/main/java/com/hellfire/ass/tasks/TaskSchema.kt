package com.hellfire.ass.tasks

import androidx.sqlite.db.SupportSQLiteDatabase

object TaskSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_task (id TEXT NOT NULL, sourceId TEXT NOT NULL, title TEXT NOT NULL, dueAt INTEGER NOT NULL, timezone TEXT NOT NULL, party TEXT NOT NULL, evidence TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, nextAlertAt INTEGER, lastAlertAt INTEGER, alertCount INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_assistant_task_sourceId ON assistant_task(sourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_assistant_task_nextAlertAt ON assistant_task(nextAlertAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_review (day TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, PRIMARY KEY(day))")
    }
}
