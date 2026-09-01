package com.maxrave.domain.sync

import com.maxrave.domain.sync.LibraryMerge.Record
import com.maxrave.domain.sync.LibraryMerge.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merge decides what survives a sync, so every rule that could silently lose a playlist is
 * pinned here rather than left to be discovered on someone's phone.
 */
class LibraryMergeTest {
    private fun snap(
        vararg records: Record<String>,
        tombstones: Map<String, Long> = emptyMap(),
    ) = Snapshot(records.toList(), tombstones)

    private fun rec(
        key: String,
        at: Long?,
        payload: String = key,
    ) = Record(key, at, payload)

    @Test
    fun newerVersionWins() {
        val result =
            LibraryMerge.merge(
                local = snap(rec("p:1", 100, "old")),
                remote = snap(rec("p:1", 200, "new")),
            )
        assertEquals(listOf("new"), result.merged.map { it.payload })
    }

    @Test
    fun recordOnOneSideOnlyIsKept() {
        val result =
            LibraryMerge.merge(
                local = snap(rec("p:1", 100)),
                remote = snap(rec("p:2", 100)),
            )
        assertEquals(listOf("p:1", "p:2"), result.merged.map { it.key })
    }

    @Test
    fun deletionRemovesAnOlderRecord() {
        val result =
            LibraryMerge.merge(
                local = snap(rec("p:1", 100)),
                remote = snap(tombstones = mapOf("p:1" to 200)),
            )
        assertTrue(result.merged.isEmpty())
        assertEquals(setOf("p:1"), result.deleted)
    }

    /** The one that matters most: deleting on one device must not erase a later edit on another. */
    @Test
    fun editAfterDeletionSurvives() {
        val result =
            LibraryMerge.merge(
                local = snap(rec("p:1", 300, "edited after delete")),
                remote = snap(tombstones = mapOf("p:1" to 200)),
            )
        assertEquals(listOf("edited after delete"), result.merged.map { it.payload })
        assertTrue(result.deleted.isEmpty())
    }

    @Test
    fun unknownTimestampNeverBeatsAKnownOne() {
        val fromRemote =
            LibraryMerge.merge(
                local = snap(rec("p:1", null, "undated")),
                remote = snap(rec("p:1", 50, "dated")),
            )
        assertEquals(listOf("dated"), fromRemote.merged.map { it.payload })

        val fromLocal =
            LibraryMerge.merge(
                local = snap(rec("p:1", 50, "dated")),
                remote = snap(rec("p:1", null, "undated")),
            )
        assertEquals(listOf("dated"), fromLocal.merged.map { it.payload })
    }

    @Test
    fun undatableRecordDoesNotOutrankAnExplicitDeletion() {
        val result =
            LibraryMerge.merge(
                local = snap(rec("p:1", null)),
                remote = snap(tombstones = mapOf("p:1" to 200)),
            )
        assertEquals(setOf("p:1"), result.deleted)
    }

    @Test
    fun tombstonesFromBothSidesAreCarriedForward() {
        val result =
            LibraryMerge.merge(
                local = snap(tombstones = mapOf("p:1" to 100)),
                remote = snap(tombstones = mapOf("p:2" to 200)),
            )
        assertEquals(setOf("p:1", "p:2"), result.tombstones.keys)
        assertEquals(setOf("p:1", "p:2"), result.deleted)
    }

    @Test
    fun laterDeletionWinsBetweenTwoTombstones() {
        val result =
            LibraryMerge.merge(
                local = snap(tombstones = mapOf("p:1" to 100)),
                remote = snap(tombstones = mapOf("p:1" to 500)),
            )
        assertEquals(500L, result.tombstones["p:1"])
    }

    /** Merging must be stable: syncing twice with no changes must not churn the library. */
    @Test
    fun mergeIsIdempotent() {
        val local = snap(rec("p:1", 100), rec("p:2", 200), tombstones = mapOf("p:3" to 50))
        val remote = snap(rec("p:2", 300), tombstones = mapOf("p:4" to 60))

        val once = LibraryMerge.merge(local, remote)
        val twice =
            LibraryMerge.merge(
                local = Snapshot(once.merged, once.tombstones),
                remote = Snapshot(once.merged, once.tombstones),
            )
        assertEquals(once.merged, twice.merged)
        assertEquals(once.tombstones, twice.tombstones)
    }

    /** Order of arguments must not change the outcome, or two devices would disagree. */
    @Test
    fun mergeIsSymmetric() {
        val a = snap(rec("p:1", 100, "a1"), rec("p:2", 400, "a2"), tombstones = mapOf("p:9" to 10))
        val b = snap(rec("p:1", 300, "b1"), rec("p:3", 200, "b3"), tombstones = mapOf("p:8" to 20))

        val ab = LibraryMerge.merge(a, b)
        val ba = LibraryMerge.merge(b, a)
        assertEquals(ab.merged, ba.merged)
        assertEquals(ab.deleted, ba.deleted)
        assertEquals(ab.tombstones, ba.tombstones)
    }
}
