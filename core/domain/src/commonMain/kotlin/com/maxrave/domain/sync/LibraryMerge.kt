package com.maxrave.domain.sync

/**
 * Merging two devices' libraries.
 *
 * Deliberately a pure function over plain data: no database, no network, no clock. Merge is the
 * part of syncing that loses data when it is wrong, and losing a playlist is not the kind of bug
 * that announces itself — so it is kept somewhere it can be reasoned about and exercised directly,
 * rather than tangled up with the transport that will eventually carry the snapshots.
 */
object LibraryMerge {
    /**
     * One syncable thing, reduced to what merging needs.
     *
     * @param key stable identity across devices. Not the local row id, which is assigned per
     * device and would make the same playlist look like two different ones.
     * @param updatedAtMs when this version was made, epoch millis. Null means unknown — a record
     * written before change tracking existed.
     * @param payload opaque to the merge; the caller reads it back after deciding which side wins.
     */
    data class Record<T>(
        val key: String,
        val updatedAtMs: Long?,
        val payload: T,
    )

    /**
     * @param records live records on this side
     * @param tombstonesMs deletions this side knows about, keyed the same way, with when they
     * happened
     */
    data class Snapshot<T>(
        val records: List<Record<T>>,
        val tombstonesMs: Map<String, Long>,
    )

    /**
     * @param merged what should exist afterwards
     * @param deleted keys that should not exist, so the caller can remove them locally
     * @param tombstones the union of both sides' deletions, to carry forward
     */
    data class Result<T>(
        val merged: List<Record<T>>,
        val deleted: Set<String>,
        val tombstones: Map<String, Long>,
    )

    /**
     * Newest-wins per record, with deletions treated as events that can themselves be outdated.
     *
     * The rules, and why:
     * - A record present on both sides resolves to whichever was updated later.
     * - A deletion beats a version older than it, and loses to one newer — otherwise deleting on a
     *   phone would erase an edit made on the desktop afterwards, which is data loss disguised as
     *   sync.
     * - An unknown timestamp never beats a known one. Records predating change tracking would
     *   otherwise have to be dated arbitrarily, and any date chosen is a guess that silently
     *   overwrites something real.
     * - A record only on one side is kept, unless the other side deleted it.
     */
    fun <T> merge(
        local: Snapshot<T>,
        remote: Snapshot<T>,
    ): Result<T> {
        val tombstones = mutableMapOf<String, Long>()
        for ((key, at) in local.tombstonesMs) tombstones[key] = at
        for ((key, at) in remote.tombstonesMs) {
            val existing = tombstones[key]
            if (existing == null || at > existing) tombstones[key] = at
        }

        val byKey = mutableMapOf<String, Record<T>>()
        for (record in local.records + remote.records) {
            val existing = byKey[record.key]
            byKey[record.key] = if (existing == null) record else newer(existing, record)
        }

        val merged = mutableListOf<Record<T>>()
        val deleted = mutableSetOf<String>()
        for ((key, record) in byKey) {
            val deletedAt = tombstones[key]
            if (deletedAt != null && !survivesDeletion(record, deletedAt)) {
                deleted += key
            } else {
                merged += record
            }
        }
        // Deletions of things neither side still holds are still worth carrying: another device may
        // not have heard about them yet.
        for (key in tombstones.keys) {
            if (key !in byKey) deleted += key
        }

        return Result(
            merged = merged.sortedBy { it.key },
            deleted = deleted,
            tombstones = tombstones,
        )
    }

    /**
     * Later of two versions of the same record.
     *
     * A known timestamp always beats an unknown one; between two unknowns the first is kept, since
     * there is nothing to choose between them and churning the value would make merges unstable.
     */
    private fun <T> newer(
        a: Record<T>,
        b: Record<T>,
    ): Record<T> {
        val at = a.updatedAtMs
        val bt = b.updatedAtMs
        return when {
            at == null && bt == null -> a
            at == null -> b
            bt == null -> a
            bt > at -> b
            else -> a
        }
    }

    /**
     * Whether a record is newer than the deletion and so should come back.
     *
     * A record with no timestamp cannot be shown to postdate the deletion, so the deletion stands.
     * That errs towards honouring an explicit user action over an undatable record.
     */
    private fun <T> survivesDeletion(
        record: Record<T>,
        deletedAtMs: Long,
    ): Boolean {
        val updated = record.updatedAtMs ?: return false
        return updated > deletedAtMs
    }
}
