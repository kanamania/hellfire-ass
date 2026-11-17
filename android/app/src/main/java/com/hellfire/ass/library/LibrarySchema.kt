package com.hellfire.ass.library

import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema 17 → 18. SQL mirrors app/schemas/.../18.json exactly (Room validates it after migration). */
object LibrarySchema {
    /** media_item is rebuilt because sourceId/contentHash become nullable (external index entries) and the hash index stops being unique. */
    private const val MEDIA_ITEM_V18 = "CREATE TABLE IF NOT EXISTS `media_item_new` (`id` TEXT NOT NULL, `sourceId` TEXT, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `kind` TEXT NOT NULL, `contentHash` TEXT, `size` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, `recordedAt` INTEGER, `processedMs` INTEGER NOT NULL, `state` TEXT NOT NULL, `summary` TEXT NOT NULL, `summaryState` TEXT NOT NULL, `error` TEXT, `durationMs` INTEGER, `storage` TEXT NOT NULL DEFAULT 'MANAGED', `contentKind` TEXT NOT NULL DEFAULT 'OTHER', `externalUri` TEXT, `availability` TEXT NOT NULL DEFAULT 'AVAILABLE', `origin` TEXT NOT NULL DEFAULT 'IMPORT', `favorite` INTEGER NOT NULL DEFAULT 0, `modifiedAt` INTEGER, `width` INTEGER, `height` INTEGER, `originalMetadata` TEXT, `syncEnabled` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) REFERENCES `source_event`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
    private val mediaItemIndices = listOf(
        "CREATE INDEX IF NOT EXISTS `index_media_item_contentHash` ON `media_item` (`contentHash`)",
        "CREATE INDEX IF NOT EXISTS `index_media_item_sourceId` ON `media_item` (`sourceId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_item_externalUri` ON `media_item` (`externalUri`)",
        "CREATE INDEX IF NOT EXISTS `index_media_item_contentKind_importedAt` ON `media_item` (`contentKind`, `importedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_media_item_importedAt` ON `media_item` (`importedAt`)",
    )
    private val newTables = listOf(
        "CREATE TABLE IF NOT EXISTS `media_analysis` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `analyzer` TEXT NOT NULL, `modelId` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, `pipelineVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `rawJson` TEXT NOT NULL, `editedJson` TEXT, `text` TEXT NOT NULL, `confidence` REAL, `createdAt` INTEGER NOT NULL, `editedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_media_analysis_mediaId_analyzer_createdAt` ON `media_analysis` (`mediaId`, `analyzer`, `createdAt`)",
        "CREATE TABLE IF NOT EXISTS `media_segment` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `analysisId` TEXT, `kind` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `labels` TEXT NOT NULL, `thumbnailPath` TEXT, `confidence` REAL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`analysisId`) REFERENCES `media_analysis`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_media_segment_mediaId_startMs` ON `media_segment` (`mediaId`, `startMs`)",
        "CREATE INDEX IF NOT EXISTS `index_media_segment_analysisId` ON `media_segment` (`analysisId`)",
        "CREATE TABLE IF NOT EXISTS `media_embedding` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `model` TEXT NOT NULL, `startMs` INTEGER, `endMs` INTEGER, `vector` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_media_embedding_mediaId_model` ON `media_embedding` (`mediaId`, `model`)",
        "CREATE INDEX IF NOT EXISTS `index_media_embedding_model` ON `media_embedding` (`model`)",
        "CREATE TABLE IF NOT EXISTS `media_category` (`id` TEXT NOT NULL, `parentId` TEXT, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `isPrivate` INTEGER NOT NULL, `policy` TEXT NOT NULL, `mergedInto` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_media_category_parentId_name` ON `media_category` (`parentId`, `name`)",
        "CREATE INDEX IF NOT EXISTS `index_media_category_mergedInto` ON `media_category` (`mergedInto`)",
        "CREATE TABLE IF NOT EXISTS `media_item_category` (`itemId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `origin` TEXT NOT NULL, `state` TEXT NOT NULL, `confidence` REAL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `categoryId`), FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `media_category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_media_item_category_categoryId` ON `media_item_category` (`categoryId`)",
        "CREATE TABLE IF NOT EXISTS `media_item_tag` (`itemId` TEXT NOT NULL, `tag` TEXT NOT NULL, `origin` TEXT NOT NULL, `state` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `tag`), FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_media_item_tag_tag` ON `media_item_tag` (`tag`)",
        "CREATE TABLE IF NOT EXISTS `media_collection` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `description` TEXT NOT NULL, `coverItemId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `collection_member` (`collectionId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `itemId`), FOREIGN KEY(`collectionId`) REFERENCES `media_collection`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_collection_member_itemId` ON `collection_member` (`itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_collection_member_collectionId_position` ON `collection_member` (`collectionId`, `position`)",
        "CREATE TABLE IF NOT EXISTS `media_bookmark` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `categoryId` TEXT, `thumbnailPath` TEXT, `favorite` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `origin` TEXT NOT NULL, `segmentId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `media_category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_media_bookmark_mediaId_startMs` ON `media_bookmark` (`mediaId`, `startMs`)",
        "CREATE INDEX IF NOT EXISTS `index_media_bookmark_categoryId` ON `media_bookmark` (`categoryId`)",
        "CREATE TABLE IF NOT EXISTS `playback_state` (`mediaId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `audioTrack` TEXT, `subtitleTrack` TEXT, `loopStartMs` INTEGER, `loopEndMs` INTEGER, `completed` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `play_history` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `fromMs` INTEGER NOT NULL, `toMs` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_play_history_mediaId` ON `play_history` (`mediaId`)",
        "CREATE INDEX IF NOT EXISTS `index_play_history_startedAt` ON `play_history` (`startedAt`)",
        "CREATE TABLE IF NOT EXISTS `processing_job` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `itemId` TEXT, `state` TEXT NOT NULL, `priority` INTEGER NOT NULL, `checkpoint` TEXT NOT NULL, `progress` REAL NOT NULL, `attempts` INTEGER NOT NULL, `error` TEXT, `notBefore` INTEGER NOT NULL, `claimToken` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_processing_job_type_itemId` ON `processing_job` (`type`, `itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_processing_job_state_priority_notBefore` ON `processing_job` (`state`, `priority`, `notBefore`)",
        "CREATE INDEX IF NOT EXISTS `index_processing_job_itemId` ON `processing_job` (`itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_processing_job_claimToken` ON `processing_job` (`claimToken`)",
        "CREATE TABLE IF NOT EXISTS `inbox_item` (`id` TEXT NOT NULL, `contentKind` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `notes` TEXT NOT NULL, `originalRef` TEXT, `origin` TEXT NOT NULL, `captureState` TEXT NOT NULL, `captureError` TEXT, `lifecycle` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `processingState` TEXT NOT NULL, `syncState` TEXT NOT NULL, `backupState` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `trashedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_inbox_item_capturedAt` ON `inbox_item` (`capturedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_inbox_item_lifecycle` ON `inbox_item` (`lifecycle`)",
        "CREATE TABLE IF NOT EXISTS `inbox_member` (`inboxId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`inboxId`, `itemId`), FOREIGN KEY(`inboxId`) REFERENCES `inbox_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_inbox_member_itemId` ON `inbox_member` (`itemId`)",
        "CREATE TABLE IF NOT EXISTS `drawer_message` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `inboxId` TEXT, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `inContext` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`inboxId`) REFERENCES `inbox_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_drawer_message_sessionId_createdAt` ON `drawer_message` (`sessionId`, `createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_drawer_message_inboxId` ON `drawer_message` (`inboxId`)",
        "CREATE TABLE IF NOT EXISTS `action_record` (`id` TEXT NOT NULL, `inboxId` TEXT, `itemId` TEXT, `actionType` TEXT NOT NULL, `label` TEXT NOT NULL, `payload` TEXT NOT NULL, `state` TEXT NOT NULL, `result` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`inboxId`) REFERENCES `inbox_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_action_record_inboxId` ON `action_record` (`inboxId`)",
        "CREATE INDEX IF NOT EXISTS `index_action_record_itemId` ON `action_record` (`itemId`)",
        "CREATE TABLE IF NOT EXISTS `saved_location` (`id` TEXT NOT NULL, `inboxId` TEXT, `itemId` TEXT, `label` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `address` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`inboxId`) REFERENCES `inbox_item`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_saved_location_inboxId` ON `saved_location` (`inboxId`)",
        "CREATE INDEX IF NOT EXISTS `index_saved_location_itemId` ON `saved_location` (`itemId`)",
        "CREATE TABLE IF NOT EXISTS `share_export` (`id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `startMs` INTEGER, `endMs` INTEGER, `profile` TEXT NOT NULL, `captionStyle` TEXT, `approvedText` TEXT NOT NULL, `sidecarJson` TEXT, `outputPath` TEXT, `outputMime` TEXT, `outputSize` INTEGER, `outputHash` TEXT, `state` TEXT NOT NULL, `verification` TEXT NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `handedOffAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`itemId`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_share_export_itemId` ON `share_export` (`itemId`)",
        "CREATE TABLE IF NOT EXISTS `privacy_finding` (`id` TEXT NOT NULL, `exportId` TEXT NOT NULL, `field` TEXT NOT NULL, `container` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`exportId`) REFERENCES `share_export`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_privacy_finding_exportId` ON `privacy_finding` (`exportId`)",
        "CREATE TABLE IF NOT EXISTS `sync_record` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `state` TEXT NOT NULL, `deleted` INTEGER NOT NULL, `remoteId` TEXT, `version` INTEGER NOT NULL, `localHash` TEXT, `remoteUpdatedAt` INTEGER, `bytesTotal` INTEGER NOT NULL, `bytesDone` INTEGER NOT NULL, `uploadSession` TEXT, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `error` TEXT, `updatedAt` INTEGER NOT NULL, `syncedAt` INTEGER, PRIMARY KEY(`entityType`, `entityId`))",
        "CREATE INDEX IF NOT EXISTS `index_sync_record_state_nextAttemptAt` ON `sync_record` (`state`, `nextAttemptAt`)",
        "CREATE TABLE IF NOT EXISTS `sync_conflict` (`id` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `localJson` TEXT NOT NULL, `remoteJson` TEXT NOT NULL, `detectedAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `resolution` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_sync_conflict_entityType_entityId` ON `sync_conflict` (`entityType`, `entityId`)",
        "CREATE TABLE IF NOT EXISTS `backup_manifest` (`id` TEXT NOT NULL, `destination` TEXT NOT NULL, `kind` TEXT NOT NULL, `baseId` TEXT, `schema` INTEGER NOT NULL, `state` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `archiveHash` TEXT, `remoteRef` TEXT, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, `verifiedAt` INTEGER, `error` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_backup_manifest_createdAt` ON `backup_manifest` (`createdAt`)",
        "CREATE TABLE IF NOT EXISTS `backup_item` (`manifestId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `size` INTEGER NOT NULL, `backedUpAt` INTEGER NOT NULL, `verifiedAt` INTEGER, PRIMARY KEY(`manifestId`, `itemId`), FOREIGN KEY(`manifestId`) REFERENCES `backup_manifest`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_backup_item_itemId` ON `backup_item` (`itemId`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `library_search` USING FTS4(`ref` TEXT NOT NULL, `itemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, tokenize=unicode61, notindexed=`ref`, notindexed=`itemId`, notindexed=`kind`)",
    )

    fun migrate(db: SupportSQLiteDatabase) {
        val children = childCounts(db)
        db.execSQL(MEDIA_ITEM_V18)
        db.execSQL("INSERT INTO media_item_new (id, sourceId, name, mime, kind, contentHash, size, importedAt, recordedAt, processedMs, state, summary, summaryState, error, durationMs) SELECT id, sourceId, name, mime, kind, contentHash, size, importedAt, recordedAt, processedMs, state, summary, summaryState, error, durationMs FROM media_item")
        db.execSQL("DROP TABLE media_item")
        db.execSQL("ALTER TABLE media_item_new RENAME TO media_item")
        mediaItemIndices.forEach(db::execSQL)
        // Room runs migrations with foreign keys off; if a helper ever enabled them, DROP would cascade. Refuse rather than lose rows.
        check(childCounts(db) == children) { "media_item rebuild changed dependent rows" }
        newTables.forEach(db::execSQL)
        backfill(db)
    }

    /** Derive contentKind for rows that predate it (migration and schema-17 backup restores). Idempotent. */
    fun backfill(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE media_item SET contentKind = CASE WHEN mime LIKE 'image/%' THEN 'IMAGE' WHEN mime LIKE 'video/%' THEN 'VIDEO' " +
            "WHEN mime LIKE 'audio/%' THEN 'AUDIO' WHEN mime = 'application/pdf' THEN 'DOCUMENT' WHEN mime LIKE 'text/%' THEN 'TEXT' ELSE 'OTHER' END " +
            "WHERE contentKind = 'OTHER'")
    }

    private fun childCounts(db: SupportSQLiteDatabase) = listOf("transcript_segment", "speech_span").map { table ->
        db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
    }
}
