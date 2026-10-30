package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewManagerTest {
    private class MemoryStore : ReviewStringStore {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (failWrites) return false
            this.value = value
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
        val loaded = ReviewManagerRepository(store).load()

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
}
