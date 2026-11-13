package com.hellfire.ass.media

import androidx.sqlite.db.SupportSQLiteDatabase

object MediaSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS media_item (id TEXT NOT NULL, sourceId TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, kind TEXT NOT NULL, contentHash TEXT NOT NULL, size INTEGER NOT NULL, importedAt INTEGER NOT NULL, recordedAt INTEGER, processedMs INTEGER NOT NULL, state TEXT NOT NULL, summary TEXT NOT NULL, summaryState TEXT NOT NULL, error TEXT, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_item_contentHash ON media_item(contentHash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_item_sourceId ON media_item(sourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_segment (id TEXT NOT NULL, mediaId TEXT NOT NULL, sourceId TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, speaker TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(mediaId) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segment_mediaId ON transcript_segment(mediaId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segment_sourceId ON transcript_segment(sourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS speech_span (id TEXT NOT NULL, mediaId TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(mediaId) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_speech_span_mediaId ON speech_span(mediaId)")
    }
}
