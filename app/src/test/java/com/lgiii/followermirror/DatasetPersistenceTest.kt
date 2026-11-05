package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetPersistenceTest {
    private val dataset = SavedDataset(setOf("one"), 100, listOf("followers_1.json"), validated = true)

    private class MemoryStore : DatasetStringStore {
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

    @Test fun malformedDatasetJsonIsNotTreatedAsMissing() {
        val raw = "{partial"
        val store = MemoryStore().apply { value = raw }
        val repository = DatasetRepository(store)
        assertTrue(repository.load() is DatasetLoadResult.RecoveryRequired)
        assertFalse(repository.replaceFollowers(dataset).success)
        assertEquals(raw, store.value)
    }

    @Test fun missingCategoriesAndIncompleteDatasetRecordsAreRejected() {
        listOf(
            "{}",
            """{"followers":null}""",
            """{"followers":[],"following":null}""",
            """{"followers":{"accounts":[42]},"following":null}""",
            """{"followers":{"accounts":[""]},"following":null}""",
        ).forEach { raw -> assertTrue("Expected invalid document: $raw", runCatching { DatasetJsonCodec.decode(raw) }.isFailure) }
    }

    @Test fun validV1DatasetDocumentRoundTripsWithoutChangingExistingFields() {
        val raw = DatasetJsonCodec.encode(DatasetBundle(dataset, null))
        val loaded = DatasetJsonCodec.decode(raw)
        assertEquals(dataset, loaded.followers)
        assertEquals(null, loaded.following)
    }

    @Test fun replacingOneDatasetPreservesTheUnaffectedCategory() {
        val existingFollowers = dataset.copy(accounts = setOf("follower"))
        val existingFollowing = dataset.copy(accounts = setOf("following"), sourceFiles = listOf("following.json"))
        val persisted = DatasetJsonCodec.encode(DatasetBundle(existingFollowers, existingFollowing))
        val current = DatasetJsonCodec.decode(persisted)
        val replacement = dataset.copy(accounts = setOf("replacement"), importedAt = 200)

        val afterFollowersReplacement = DatasetJsonCodec.decode(
            DatasetJsonCodec.encode(current.copy(followers = replacement)),
        )

        assertEquals(replacement, afterFollowersReplacement.followers)
        assertEquals(existingFollowing, afterFollowersReplacement.following)

        val store = MemoryStore().apply { value = persisted }
        val repository = DatasetRepository(store)
        assertTrue(repository.load() is DatasetLoadResult.Loaded)
        assertTrue(repository.replaceFollowers(replacement).success)
        val afterPersist = DatasetJsonCodec.decode(store.value!!)
        assertEquals(replacement, afterPersist.followers)
        assertEquals(existingFollowing, afterPersist.following)
    }

    @Test fun oldV1DatasetWithoutValidationMetadataRemainsReadable() {
        val raw = """{"followers":{"accounts":["legacy"],"importedAt":null,"sourceFiles":[]},"following":null}"""
        val loaded = DatasetJsonCodec.decode(raw)
        assertEquals(SavedDataset(setOf("legacy"), null, emptyList(), validated = false), loaded.followers)
    }

    @Test fun restoredValidDatasetBackupPreservesCorruptRawBeforeRecovery() {
        val corrupt = "{cannot parse"
        val store = MemoryStore().apply { value = corrupt }
        val repository = DatasetRepository(store)
        assertTrue(repository.load() is DatasetLoadResult.RecoveryRequired)
        val backup = DatasetJsonCodec.encode(DatasetBundle(dataset, null))

        val restored = repository.restore(backup)

        assertTrue(restored.success)
        assertEquals(corrupt, store.preserved)
        assertEquals(dataset, DatasetJsonCodec.decode(store.value!!).followers)
    }

    @Test fun failedRecoveryBackupNeverOverwritesCorruptDatasetJson() {
        val corrupt = "{bad"
        val store = MemoryStore().apply { value = corrupt; failPreserve = true }
        val repository = DatasetRepository(store)
        repository.load()
        val result = repository.replaceAfterRecovery(DatasetBundle(dataset, null))
        assertFalse(result.success)
        assertEquals(corrupt, store.value)
    }

    @Test fun failedDatasetWriteRetainsBothStoredAndInMemoryCategories() {
        val existing = DatasetBundle(dataset.copy(accounts = setOf("keep.followers")), dataset.copy(accounts = setOf("keep.following")))
        val raw = DatasetJsonCodec.encode(existing)
        val store = MemoryStore().apply { value = raw }
        val repository = DatasetRepository(store)
        assertTrue(repository.load() is DatasetLoadResult.Loaded)
        store.failWrite = true

        val failed = repository.replaceFollowers(dataset.copy(accounts = setOf("replacement")))

        assertFalse(failed.success)
        assertEquals(raw, store.value)
        assertEquals(existing, failed.bundle)
    }

    @Test fun incompatibleStoredPreferenceValueIsRecoverableAndCannotBeOverwritten() {
        val recoveryCopy = """{"incompatiblePreferenceValueType":"java.util.Set","storedValue":["private"]}"""
        val store = MemoryStore().apply { readFailure = UnreadableStoredValue(recoveryCopy, "Incompatible preference value") }
        val repository = DatasetRepository(store)
        val load = repository.load()
        assertTrue(load is DatasetLoadResult.RecoveryRequired)
        assertFalse(repository.replaceFollowers(dataset).success)
        assertEquals(null, store.value)
    }
}
