package com.hellfire.ass.security

/** Bounded streaming archives can contain many originals; each original keeps the separate 8 GiB media limit. */
object BackupReadiness {
    const val MAX_ARCHIVE = 512L * 1024 * 1024 * 1024
    const val MAX_ORIGINALS = 500L * 1024 * 1024 * 1024
    fun stagingBytes(originals: Long, database: Long): Long {
        require(originals in 0..MAX_ORIGINALS && database in 0..(1024L * 1024 * 1024))
        val base = Math.addExact(originals, database)
        // Two encrypted copies may coexist during export to a local destination; allow ZIP and chunk overhead.
        return Math.addExact(Math.multiplyExact(base, 22) / 10, 16L * 1024 * 1024)
    }
}
