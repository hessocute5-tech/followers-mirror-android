package com.lgiii.followermirror

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class ReviewStatus(val label: String) {
    AWAITING_REVIEW("Awaiting Review"),
    KEEP_FOLLOWING("Keep Following"),
    MARKED_FOR_UNFOLLOW("Marked for Unfollow"),
    UNFOLLOWED_MANUALLY_CONFIRMED("Unfollowed – Manually Confirmed"),
}

internal enum class ReviewEvent(val label: String) {
    QUEUED("Added to review queue"),
    STATUS_CHANGED("Status changed"),
    PROTECTION_ENFORCED("Protection changed status to Awaiting Review"),
    MANUAL_COMPLETION_CONFIRMED("Manual completion confirmed"),
    REMOVED("Removed from review queue"),
}

internal enum class ReviewScope(val label: String) {
    INDIVIDUAL("Individual"),
    BULK("Bulk"),
}

internal data class ReviewEntry(
    val username: String,
    val addedAt: Long,
    val status: ReviewStatus = ReviewStatus.AWAITING_REVIEW,
    val notes: String = "",
)

internal data class ReviewHistoryEntry(
    val username: String,
    val previousStatus: ReviewStatus?,
    val newStatus: ReviewStatus?,
    val occurredAt: Long,
    val event: ReviewEvent,
    val scope: ReviewScope,
    val manualCompletion: Boolean = false,
)

internal data class ReviewState(
    val queue: Map<String, ReviewEntry> = emptyMap(),
    val protected: Set<String> = emptySet(),
    val history: List<ReviewHistoryEntry> = emptyList(),
)

internal data class ReviewResult(
    val success: Boolean,
    val message: String,
    val state: ReviewState,
)

/** Persistence is isolated from Android so queue behavior and serialization can be unit tested. */
internal interface ReviewStringStore {
    fun read(): String?
    fun write(value: String): Boolean
}

internal class ReviewManagerRepository(private val store: ReviewStringStore) {
    fun load(): ReviewState = runCatching { decode(store.read()) }.getOrDefault(ReviewState())

    fun addToQueue(current: ReviewState, usernames: Collection<String>, at: Long): ReviewResult {
        val normalized = normalizeInputs(usernames) ?: return failure(current, "Enter valid Instagram usernames.")
        val queue = current.queue.toMutableMap()
        val added = normalized.filterNot(queue::containsKey)
        if (added.isEmpty()) return failure(current, "Those accounts are already in the review queue.")
        val scope = scopeFor(added.size)
        val events = added.map { username ->
            username to ReviewHistoryEntry(username, null, ReviewStatus.AWAITING_REVIEW, at, ReviewEvent.QUEUED, scope)
        }
        added.forEach { queue[it] = ReviewEntry(it, at) }
        return persist(current, ReviewState(queue, current.protected, prepend(current.history, events.map { it.second })), "${added.size} account(s) added to review.")
    }

    fun addProtected(current: ReviewState, usernames: Collection<String>, at: Long): ReviewResult {
        val normalized = normalizeInputs(usernames) ?: return failure(current, "Enter valid Instagram usernames.")
        val added = normalized.filterNot(current.protected::contains)
        if (added.isEmpty()) return failure(current, "Those accounts are already protected.")
        val scope = scopeFor(added.size)
        val queue = current.queue.toMutableMap()
        val events = mutableListOf<ReviewHistoryEntry>()
        added.forEach { username ->
            val existing = queue[username]
            if (existing?.status == ReviewStatus.MARKED_FOR_UNFOLLOW) {
                queue[username] = existing.copy(status = ReviewStatus.AWAITING_REVIEW)
                events += ReviewHistoryEntry(username, existing.status, ReviewStatus.AWAITING_REVIEW, at, ReviewEvent.PROTECTION_ENFORCED, scope)
            }
        }
        val next = ReviewState(queue, current.protected + added, prepend(current.history, events))
        return persist(current, next, "${added.size} account(s) protected.")
    }

    fun removeProtected(current: ReviewState, usernames: Collection<String>): ReviewResult {
        val normalized = usernames.map(::normalizeUsername).filter(String::isNotBlank).toSet()
        val removed = current.protected intersect normalized
        if (removed.isEmpty()) return failure(current, "No matching protected accounts were found.")
        return persist(current, current.copy(protected = current.protected - removed), "Protection removed from ${removed.size} account(s).")
    }

    fun changeStatus(current: ReviewState, usernames: Collection<String>, status: ReviewStatus, at: Long): ReviewResult {
        if (status == ReviewStatus.UNFOLLOWED_MANUALLY_CONFIRMED) {
            return failure(current, "Use the explicit manual completion confirmation for this status.")
        }
        val requested = usernames.map(::normalizeUsername).filter(String::isNotBlank).toSet()
        val entries = requested.mapNotNull(current.queue::get)
        if (entries.isEmpty()) return failure(current, "Select an account in the review queue first.")
        if (status == ReviewStatus.MARKED_FOR_UNFOLLOW && entries.any { it.username in current.protected }) {
            return failure(current, "Protected accounts cannot be marked for unfollow. Remove protection first.")
        }
        val changed = entries.filter { it.status != status }
        if (changed.isEmpty()) return failure(current, "The selected account(s) already have that status.")
        val scope = scopeFor(changed.size)
        val queue = current.queue.toMutableMap()
        val events = changed.map { entry ->
            queue[entry.username] = entry.copy(status = status)
            ReviewHistoryEntry(entry.username, entry.status, status, at, ReviewEvent.STATUS_CHANGED, scope)
        }
        return persist(current, current.copy(queue = queue, history = prepend(current.history, events)), "${changed.size} account(s) updated to ${status.label}.")
    }

    fun confirmManualCompletion(current: ReviewState, usernames: Collection<String>, at: Long): ReviewResult {
        val requested = usernames.map(::normalizeUsername).filter(String::isNotBlank).toSet()
        val entries = requested.mapNotNull(current.queue::get)
        if (entries.isEmpty()) return failure(current, "Select a queued account first.")
        if (entries.any { it.username in current.protected }) {
            return failure(current, "Protected accounts cannot be confirmed as unfollowed. Remove protection first.")
        }
        if (entries.any { it.status != ReviewStatus.MARKED_FOR_UNFOLLOW }) {
            return failure(current, "Only accounts marked for unfollow can be manually confirmed.")
        }
        val scope = scopeFor(entries.size)
        val queue = current.queue.toMutableMap()
        val events = entries.map { entry ->
            queue[entry.username] = entry.copy(status = ReviewStatus.UNFOLLOWED_MANUALLY_CONFIRMED)
            ReviewHistoryEntry(entry.username, entry.status, ReviewStatus.UNFOLLOWED_MANUALLY_CONFIRMED, at, ReviewEvent.MANUAL_COMPLETION_CONFIRMED, scope, manualCompletion = true)
        }
        return persist(current, current.copy(queue = queue, history = prepend(current.history, events)), "Manual completion confirmed for ${entries.size} account(s).")
    }

    fun updateNotes(current: ReviewState, username: String, notes: String): ReviewResult {
        val key = normalizeUsername(username)
        val entry = current.queue[key] ?: return failure(current, "That account is no longer in the review queue.")
        val updated = current.copy(queue = current.queue + (key to entry.copy(notes = notes.trim())))
        return persist(current, updated, "Notes saved for @${entry.username}.")
    }

    fun removeFromQueue(current: ReviewState, usernames: Collection<String>, at: Long): ReviewResult {
        val requested = usernames.map(::normalizeUsername).filter(String::isNotBlank).toSet()
        val entries = requested.mapNotNull(current.queue::get)
        if (entries.isEmpty()) return failure(current, "Select an account in the review queue first.")
        val scope = scopeFor(entries.size)
        val events = entries.map { entry ->
            ReviewHistoryEntry(entry.username, entry.status, null, at, ReviewEvent.REMOVED, scope)
        }
        val queue = current.queue - entries.map { it.username }.toSet()
        return persist(current, current.copy(queue = queue, history = prepend(current.history, events)), "${entries.size} account(s) removed. Review history was retained.")
    }

    fun clearAll(current: ReviewState): ReviewResult =
        persist(current, ReviewState(), "Review queue, protected accounts, and review history cleared.")

    private fun persist(old: ReviewState, next: ReviewState, success: String): ReviewResult =
        if (runCatching { store.write(encode(next)) }.getOrDefault(false)) ReviewResult(true, success, next)
        else ReviewResult(false, "Could not save review data. No changes were applied.", old)

    private fun failure(state: ReviewState, message: String) = ReviewResult(false, message, state)

    private fun normalizeInputs(values: Collection<String>): Set<String>? {
        if (values.isEmpty()) return null
        val normalized = values.map(::normalizeUsername)
        if (normalized.any { !it.matches(USERNAME) }) return null
        return normalized.toCollection(linkedSetOf())
    }

    private fun normalizeUsername(value: String): String = value.trim().removePrefix("@").trim().lowercase(Locale.ROOT)
    private fun scopeFor(count: Int) = if (count > 1) ReviewScope.BULK else ReviewScope.INDIVIDUAL
    private fun prepend(existing: List<ReviewHistoryEntry>, events: List<ReviewHistoryEntry>) = (events + existing).take(MAX_HISTORY)

    private fun encode(state: ReviewState): String {
        val queue = JSONArray()
        state.queue.values.sortedBy { it.username }.forEach { entry ->
            queue.put(JSONObject()
                .put("username", entry.username)
                .put("addedAt", entry.addedAt)
                .put("status", entry.status.name)
                .put("notes", entry.notes))
        }
        val history = JSONArray()
        state.history.forEach { item ->
            history.put(JSONObject()
                .put("username", item.username)
                .put("previousStatus", item.previousStatus?.name ?: JSONObject.NULL)
                .put("newStatus", item.newStatus?.name ?: JSONObject.NULL)
                .put("occurredAt", item.occurredAt)
                .put("event", item.event.name)
                .put("scope", item.scope.name)
                .put("manualCompletion", item.manualCompletion))
        }
        return JSONObject()
            .put("version", 1)
            .put("queue", queue)
            .put("protected", JSONArray(state.protected.sorted()))
            .put("history", history)
            .toString()
    }

    private fun decode(raw: String?): ReviewState {
        if (raw.isNullOrBlank()) return ReviewState()
        val root = JSONObject(raw)
        val queue = linkedMapOf<String, ReviewEntry>()
        val queueArray = root.optJSONArray("queue") ?: JSONArray()
        for (index in 0 until queueArray.length()) {
            val item = queueArray.getJSONObject(index)
            val username = normalizeUsername(item.getString("username"))
            if (!username.matches(USERNAME)) continue
            queue[username] = ReviewEntry(
                username = username,
                addedAt = item.optLong("addedAt"),
                status = ReviewStatus.valueOf(item.optString("status", ReviewStatus.AWAITING_REVIEW.name)),
                notes = item.optString("notes"),
            )
        }
        val protectedArray = root.optJSONArray("protected") ?: JSONArray()
        val protected = buildSet {
            for (index in 0 until protectedArray.length()) {
                normalizeUsername(protectedArray.optString(index)).takeIf { it.matches(USERNAME) }?.let(::add)
            }
        }
        val historyArray = root.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until historyArray.length()) {
                val item = historyArray.optJSONObject(index) ?: continue
                val username = normalizeUsername(item.optString("username"))
                if (!username.matches(USERNAME)) continue
                add(ReviewHistoryEntry(
                    username = username,
                    previousStatus = item.optString("previousStatus").toNullableEnum<ReviewStatus>(),
                    newStatus = item.optString("newStatus").toNullableEnum<ReviewStatus>(),
                    occurredAt = item.optLong("occurredAt"),
                    event = runCatching { ReviewEvent.valueOf(item.optString("event")) }.getOrDefault(ReviewEvent.STATUS_CHANGED),
                    scope = runCatching { ReviewScope.valueOf(item.optString("scope")) }.getOrDefault(ReviewScope.INDIVIDUAL),
                    manualCompletion = item.optBoolean("manualCompletion"),
                ))
            }
        }
        return ReviewState(queue, protected, history.take(MAX_HISTORY))
    }

    private inline fun <reified T : Enum<T>> String.toNullableEnum(): T? =
        takeUnless { it.isBlank() || it == "null" }?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        const val MAX_HISTORY = 2_000
        val USERNAME = Regex("[a-z0-9._]{1,30}")
    }
}
