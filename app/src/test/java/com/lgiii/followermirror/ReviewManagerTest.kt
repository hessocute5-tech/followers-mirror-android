package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewManagerTest {
    private class MemoryStore : ReviewStringStore {
        var value: String? = null
        var preserved: String? = null
        var failWrites = false
        var failPreserve = false
        var readFailure: Exception? = null
        override fun read(): String? { readFailure?.let { throw it }; return value }
        override fun write(value: String): Boolean {
            if (failWrites) return false
            this.value = value
            return true
        }
        override fun preserve(value: String): Boolean {
            if (failPreserve) return false
            preserved = value
            return true
        }
    }

    private val store = MemoryStore()
    private val repository = ReviewManagerRepository(store)

    @Test fun queueAdditionNormalizesNamesAndPreventsCaseInsensitiveDuplicates() {
        val first = repository.addToQueue(ReviewState(), listOf(" @Some.User ", "OTHER"), 100)
        assertTrue(first.success)
        assertEquals(setOf("some.user", "other"), first.state.queue.keys)
        assertEquals(ReviewScope.BULK, first.state.history.first().scope)

        val duplicate = repository.addToQueue(first.state, listOf("SOME.USER", "@Other"), 200)
        assertFalse(duplicate.success)
        assertEquals(first.state, duplicate.state)
        assertEquals(2, duplicate.state.queue.size)
    }

    @Test fun bulkStatusUpdatesAreRecordedAsBulkHistory() {
        val queued = repository.addToQueue(ReviewState(), listOf("first", "second"), 1).state
        val changed = repository.changeStatus(queued, queued.queue.keys, ReviewStatus.KEEP_FOLLOWING, 2)
        assertTrue(changed.success)
        assertTrue(changed.state.queue.values.all { it.status == ReviewStatus.KEEP_FOLLOWING })
        assertEquals(ReviewScope.BULK, changed.state.history.first().scope)
        assertEquals(2, changed.state.history.count { it.event == ReviewEvent.STATUS_CHANGED })
    }

    @Test fun protectionCancelsExistingUnfollowMarkAndBlocksFurtherUnfollowTransitions() {
        var state = repository.addToQueue(ReviewState(), listOf("guarded"), 1).state
        state = repository.changeStatus(state, listOf("guarded"), ReviewStatus.MARKED_FOR_UNFOLLOW, 2).state
        val protected = repository.addProtected(state, listOf("GUARDED"), 3)
        assertTrue(protected.success)
        state = protected.state
        assertEquals(ReviewStatus.AWAITING_REVIEW, state.queue.getValue("guarded").status)
        assertEquals(ReviewEvent.PROTECTION_ENFORCED, state.history.first().event)

        assertFalse(repository.changeStatus(state, listOf("guarded"), ReviewStatus.MARKED_FOR_UNFOLLOW, 4).success)
        assertFalse(repository.confirmManualCompletion(state, listOf("guarded"), 4).success)
        state = repository.removeProtected(state, listOf("@Guarded")).state
        assertTrue(repository.changeStatus(state, listOf("guarded"), ReviewStatus.MARKED_FOR_UNFOLLOW, 5).success)
    }

    @Test fun onlyExplicitManualConfirmationCanSetCompletedStatus() {
        var state = repository.addToQueue(ReviewState(), listOf("alex"), 1).state
        assertFalse(repository.changeStatus(state, listOf("alex"), ReviewStatus.UNFOLLOWED_MANUALLY_CONFIRMED, 2).success)
        assertFalse(repository.confirmManualCompletion(state, listOf("alex"), 2).success)
        state = repository.changeStatus(state, listOf("alex"), ReviewStatus.MARKED_FOR_UNFOLLOW, 3).state
        val result = repository.confirmManualCompletion(state, listOf("ALEX"), 4)
        assertTrue(result.success)
        assertEquals(ReviewStatus.UNFOLLOWED_MANUALLY_CONFIRMED, result.state.queue.getValue("alex").status)
        assertTrue(result.state.history.first().manualCompletion)
        assertEquals(ReviewEvent.MANUAL_COMPLETION_CONFIRMED, result.state.history.first().event)
    }

    @Test fun queueRemovalPreservesReviewHistory() {
        val queued = repository.addToQueue(ReviewState(), listOf("remove.me"), 10).state
        val removed = repository.removeFromQueue(queued, listOf("REMOVE.ME"), 20)
        assertTrue(removed.success)
        assertTrue(removed.state.queue.isEmpty())
        assertEquals(2, removed.state.history.size)
        assertEquals(ReviewEvent.REMOVED, removed.state.history.first().event)
        assertEquals(ReviewStatus.AWAITING_REVIEW, removed.state.history.first().previousStatus)
        assertEquals(null, removed.state.history.first().newStatus)
    }

    @Test fun queueProtectionNotesAndHistorySurviveRepositoryReloadAndDatasetKeyChanges() {
        var state = repository.addToQueue(ReviewState(), listOf("Keep.Me"), 100).state
        state = repository.addProtected(state, listOf("SafeAccount"), 110).state
        state = repository.updateNotes(state, "KEEP.ME", "Check this later").state
        val persistedReviewDocument = store.value
        val preferences = mutableMapOf("review_manager_v1" to persistedReviewDocument, "imported_datasets_v1" to "old")

        // Replacing the dataset preference leaves the dedicated review preference intact.
        preferences["imported_datasets_v1"] = "replacement"
        store.value = preferences.getValue("review_manager_v1")
        val loaded = (ReviewManagerRepository(store).load() as ReviewLoadResult.Loaded).state

        assertEquals("Check this later", loaded.queue.getValue("keep.me").notes)
        assertEquals(setOf("safeaccount"), loaded.protected)
        assertEquals(state.history, loaded.history)
        assertEquals(state.queue, loaded.queue)
    }

    @Test fun failedPersistenceDoesNotChangeThePreviousStateOrStoredDocument() {
        val queued = repository.addToQueue(ReviewState(), listOf("valid"), 1).state
        val persisted = store.value
        store.failWrites = true
        val failed = repository.changeStatus(queued, listOf("valid"), ReviewStatus.KEEP_FOLLOWING, 2)
        assertFalse(failed.success)
        assertEquals(queued, failed.state)
        assertEquals(persisted, store.value)
    }

    @Test fun notesCanBeClearedAndUsernamesAreNormalized() {
        val queued = repository.addToQueue(ReviewState(), listOf("  @MiXeD.Name  "), 1).state
        val noted = repository.updateNotes(queued, "MIXED.NAME", "remember").state
        val cleared = repository.updateNotes(noted, "@mixed.name", " ").state
        assertEquals("mixed.name", cleared.queue.keys.single())
        assertEquals("", cleared.queue.getValue("mixed.name").notes)
    }

    @Test fun invalidUsernamesAreRejectedWithoutPartialAddition() {
        val result = repository.addToQueue(ReviewState(), listOf("valid", "not a username"), 1)
        assertFalse(result.success)
        assertTrue(result.state.queue.isEmpty())
    }

    @Test fun malformedReviewJsonBlocksWritesAndLeavesOriginalBytesUntouched() {
        val raw = "{not valid JSON"
        store.value = raw
        val reloaded = ReviewManagerRepository(store)
        assertTrue(reloaded.load() is ReviewLoadResult.RecoveryRequired)

        val attemptedWrite = reloaded.addToQueue(ReviewState(), listOf("new.account"), 5)

        assertFalse(attemptedWrite.success)
        assertEquals(raw, store.value)
    }

    @Test fun missingRequiredReviewFieldsAndIncompleteRecordsAreRejected() {
        val badDocuments = listOf(
            "{}",
            """{"version":1,"queue":[{}],"protected":[],"history":[]}""",
            """{"version":1,"queue":[{"username":""}],"protected":[],"history":[]}""",
            """{"version":1,"queue":[],"protected":"not-an-array","history":[]}""",
        )
        badDocuments.forEach { raw ->
            store.value = raw
            assertTrue(ReviewManagerRepository(store).load() is ReviewLoadResult.RecoveryRequired)
            assertEquals("Unreadable document must stay byte-for-byte intact", raw, store.value)
        }
    }

    @Test fun unsupportedReviewSchemaVersionIsNotTreatedAsEmpty() {
        store.value = """{"version":2,"queue":[],"protected":[],"history":[]}"""
        assertTrue(ReviewManagerRepository(store).load() is ReviewLoadResult.RecoveryRequired)
        assertEquals(2, org.json.JSONObject(store.value!!).getInt("version"))
    }

    @Test fun validRestoredReviewDocumentIsBackedUpThenRecovered() {
        val valid = repository.addToQueue(ReviewState(), listOf("restored.user"), 100).let { store.value!! }
        val corrupt = "[partial saved review data"
        store.value = corrupt
        val recoveredRepository = ReviewManagerRepository(store)
        assertTrue(recoveredRepository.load() is ReviewLoadResult.RecoveryRequired)

        val restored = recoveredRepository.restore(valid)

        assertTrue(restored.success)
        assertEquals(corrupt, store.preserved)
        assertEquals(setOf("restored.user"), restored.state.queue.keys)
        assertEquals(restored.state, (recoveredRepository.load() as ReviewLoadResult.Loaded).state)
    }

    @Test fun failedRecoveryBackupOrWriteNeverReplacesTheUnreadableReviewDocument() {
        val corrupt = "{broken"
        store.value = corrupt
        val recoveredRepository = ReviewManagerRepository(store)
        recoveredRepository.load()
        store.failPreserve = true
        assertFalse(recoveredRepository.restore("""{"version":1,"queue":[],"protected":[],"history":[]}""").success)
        assertEquals(corrupt, store.value)
    }

    @Test fun originalV1ReviewDocumentsWithoutVersionStillLoad() {
        store.value = """{"queue":[],"protected":[],"history":[]}"""
        assertEquals(ReviewState(), (ReviewManagerRepository(store).load() as ReviewLoadResult.Loaded).state)
    }

    @Test fun unreadablePreferenceValueIsNotConvertedIntoAnEmptyWritableQueue() {
        val opaqueValue = "{\"storedValue\":\"preserve me\"}"
        store.readFailure = UnreadableStoredValue(opaqueValue, "Incompatible preference value")
        assertTrue(repository.load() is ReviewLoadResult.RecoveryRequired)
        assertFalse(repository.addToQueue(ReviewState(), listOf("unsafe.overwrite"), 1).success)
        assertEquals(null, store.value)
    }
}
