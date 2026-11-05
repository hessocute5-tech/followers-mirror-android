package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPersistenceTest {
    private class MemoryStore : SnapshotStringStore {
        var value: String? = null
        var preserved: String? = null
        var failWrite = false
        var failPreserve = false
        var readFailure: Exception? = null
        override fun read(): String? { readFailure?.let { throw it }; return value }
        override fun write(value: String): Boolean {
            if (failWrite) return false
            this.value = value
            return true
        }
        override fun preserve(value: String): Boolean {
            if (failPreserve) return false
            preserved = value
            return true
        }
    }

    @Test fun malformedAndIncompleteSnapshotDocumentsAreBlockedAndPreserved() {
        listOf("{bad", "[{}]", """[{"date":1,"followers":[42]}]""").forEach { raw ->
            val store = MemoryStore().apply { value = raw }
            val repository = SnapshotRepository(store)
            assertTrue(repository.load() is SnapshotLoadResult.RecoveryRequired)
            val failed = repository.write(listOf(Snapshot(10, setOf("one"))))
            assertFalse(failed.success)
            assertEquals(raw, store.value)
        }
    }

    @Test fun missingFieldsAndInvalidSnapshotTypesAreRejected() {
        listOf(
            """[{"date":1}]""",
            """[{"followers":[]}]""",
            """[{"date":"yesterday","followers":[]}]""",
            """[{"date":1,"followers":"not-an-array"}]""",
            """[{"date":1,"followers":["valid",""]}]""",
        ).forEach { raw ->
            val store = MemoryStore().apply { value = raw }
            assertTrue(SnapshotRepository(store).load() is SnapshotLoadResult.RecoveryRequired)
            assertEquals(raw, store.value)
        }
    }

    @Test fun validSnapshotsRemainCompatibleAndRoundTrip() {
        val raw = """[{"date":100,"followers":["first"]},{"date":200,"followers":["second"]}]"""
        val store = MemoryStore().apply { value = raw }
        val repository = SnapshotRepository(store)
        val loaded = repository.load() as SnapshotLoadResult.Loaded
        assertEquals(listOf(200L, 100L), loaded.snapshots.map { it.date })
        assertEquals(setOf("first"), loaded.snapshots.last().followers)
        val saved = repository.write(loaded.snapshots)
        assertTrue(saved.success)
        assertEquals(loaded.snapshots, SnapshotJsonCodec.decode(store.value!!))
    }

    @Test fun failedSnapshotWriteDoesNotClaimSuccessOrChangeStoredHistory() {
        val raw = """[{"date":100,"followers":["first"]}]"""
        val store = MemoryStore().apply { value = raw }
        val repository = SnapshotRepository(store)
        val loaded = repository.load() as SnapshotLoadResult.Loaded
        store.failWrite = true
        val result = repository.write(loaded.snapshots + Snapshot(200, setOf("second")))
        assertFalse(result.success)
        assertEquals(raw, store.value)
        assertEquals("first", (SnapshotJsonCodec.decode(store.value!!).single().followers.single()))
    }

    @Test fun validRestoredSnapshotBackupPreservesCorruptOriginalBeforeReplacement() {
        val corrupt = "[{broken"
        val backup = """[{"date":100,"followers":["restored"]}]"""
        val store = MemoryStore().apply { value = corrupt }
        val repository = SnapshotRepository(store)
        assertTrue(repository.load() is SnapshotLoadResult.RecoveryRequired)
        val restored = repository.restore(backup)
        assertTrue(restored.success)
        assertEquals(corrupt, store.preserved)
        assertEquals(setOf("restored"), (repository.load() as SnapshotLoadResult.Loaded).snapshots.single().followers)
    }

    @Test fun unreadableSnapshotDataCanOnlyBeDiscardedAfterItIsPreserved() {
        val raw = "[invalid"
        val store = MemoryStore().apply { value = raw }
        val repository = SnapshotRepository(store)
        repository.load()
        val discarded = repository.discard()
        assertTrue(discarded.success)
        assertEquals(raw, store.preserved)
        assertEquals("[]", store.value)
    }

    @Test fun incompatibleSnapshotPreferenceTypeBlocksMutationsUntilResolved() {
        val recoveryCopy = "{\"type\":\"non-string\"}"
        val store = MemoryStore().apply { readFailure = UnreadableStoredValue(recoveryCopy, "Incompatible preference value") }
        val repository = SnapshotRepository(store)
        assertTrue(repository.load() is SnapshotLoadResult.RecoveryRequired)
        assertFalse(repository.write(listOf(Snapshot(1, setOf("not.saved")))).success)
        assertEquals(null, store.value)
    }
}
