package com.maxrave.domain.sync

object LibraryMerge {
    data class Record<T>(
        val key: String,
        val updatedAtMs: Long?,
        val payload: T,
    )

    data class Snapshot<T>(
        val records: List<Record<T>>,
        val tombstonesMs: Map<String, Long>,
    )

    data class Result<T>(
        val merged: List<Record<T>>,
        val deleted: Set<String>,
        val tombstones: Map<String, Long>,
    )

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
        for (key in tombstones.keys) {
            if (key !in byKey) deleted += key
        }

        return Result(
            merged = merged.sortedBy { it.key },
            deleted = deleted,
            tombstones = tombstones,
        )
    }

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

    private fun <T> survivesDeletion(
        record: Record<T>,
        deletedAtMs: Long,
    ): Boolean {
        val updated = record.updatedAtMs ?: return false
        return updated > deletedAtMs
    }
}
