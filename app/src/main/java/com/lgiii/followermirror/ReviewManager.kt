package com.lgiii.followermirror

import org.json.JSONArray
import org.json.JSONException
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
    fun preserve(value: String): Boolean
}

internal sealed interface ReviewLoadResult {
    data object Missing : ReviewLoadResult
    data class Loaded(val state: ReviewState) : ReviewLoadResult
    data class RecoveryRequired(val raw: String, val reason: String) : ReviewLoadResult
    data class Unavailable(val reason: String) : ReviewLoadResult
}

internal class ReviewManagerRepository(private val store: ReviewStringStore) {
    private var blockedRaw: String? = null
    private var blocked = false

    fun load(): ReviewLoadResult {
        val raw = try {
            store.read()
        } catch (e: UnreadableStoredValue) {
            blocked = true
            blockedRaw = e.recoveryCopy
            return ReviewLoadResult.RecoveryRequired(e.recoveryCopy, e.message ?: "Review data was stored in an incompatible form and needs recovery.")
        } catch (_: Exception) {
            blocked = true
            blockedRaw = null
            return ReviewLoadResult.Unavailable("Review data could not be read. Changes are paused until recovery.")
        }
        if (raw == null) {
            blocked = false
            blockedRaw = null
            return ReviewLoadResult.Missing
        }
        return try {
            val state = decode(raw)
            blocked = false
            blockedRaw = null
            ReviewLoadResult.Loaded(state)
        } catch (_: Exception) {
            blocked = true
            blockedRaw = raw
            ReviewLoadResult.RecoveryRequired(raw, "Saved Review Manager data could not be read. It is preserved; export or restore a backup before replacing it.")
        }
    }

    fun restore(raw: String): ReviewResult {
        val restored = try { decode(raw) } catch (_: Exception) {
            return failure(ReviewState(), "That backup is incomplete or invalid. Review Manager data was left unchanged.")
        }
        val original = blockedRaw
        if (blocked && original == null) return failure(ReviewState(), "Could not preserve the unreadable original data. Nothing was replaced.")
        if (original != null && !runCatching { store.preserve(original) }.getOrDefault(false)) {
            return failure(ReviewState(), "Could not preserve the unreadable original data. Nothing was replaced.")
        }
        if (!runCatching { store.write(raw) }.getOrDefault(false)) {
            return failure(ReviewState(), "Could not restore the backup. The original data was left unchanged.")
        }
        blocked = false
        blockedRaw = null
        return ReviewResult(true, "Review Manager backup restored.", restored)
    }

    fun discardUnreadable(): ReviewResult {
        if (!blocked) return failure(ReviewState(), "No unreadable Review Manager data needs recovery.")
        val original = blockedRaw
        if (original == null) return failure(ReviewState(), "Could not preserve the unreadable original data. Nothing was discarded.")
        if (!runCatching { store.preserve(original) }.getOrDefault(false)) {
            return failure(ReviewState(), "Could not preserve the unreadable original data. Nothing was discarded.")
        }
        val empty = encode(ReviewState())
        if (!runCatching { store.write(empty) }.getOrDefault(false)) {
            return failure(ReviewState(), "Could not clear Review Manager data. The original data was left unchanged.")
        }
        blocked = false
        blockedRaw = null
        return ReviewResult(true, "Unreadable review data was preserved in a local recovery copy and cleared from active Review Manager.", ReviewState())
    }

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

    private fun persist(old: ReviewState, next: ReviewState, success: String): ReviewResult {
        if (blocked) return failure(old, "Review changes are paused until the unreadable saved data is recovered.")
        return if (runCatching { store.write(encode(next)) }.getOrDefault(false)) ReviewResult(true, success, next)
        else ReviewResult(false, "Could not save review data. No changes were applied.", old)
    }

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

    private fun decode(raw: String): ReviewState {
        val root = JSONObject(raw)
        if (root.has("version")) {
            val version = root.opt("version") as? Number ?: throw JSONException("The saved Review Manager version is invalid.")
            if (version.toInt() != 1 || version.toDouble() != 1.0) throw JSONException("This Review Manager data version is not supported.")
        } // A missing version is the original v1 representation and remains supported.
        val queueArray = root.opt("queue") as? JSONArray ?: throw JSONException("The saved review queue is missing or invalid.")
        val protectedArray = root.opt("protected") as? JSONArray ?: throw JSONException("The saved protected account list is missing or invalid.")
        val historyArray = root.opt("history") as? JSONArray ?: throw JSONException("The saved review history is missing or invalid.")
        val queue = linkedMapOf<String, ReviewEntry>()
        for (index in 0 until queueArray.length()) {
            val item = queueArray.opt(index) as? JSONObject ?: throw JSONException("A saved review queue record is incomplete.")
            val rawUsername = item.opt("username") as? String ?: throw JSONException("A saved review record has no valid username.")
            val username = normalizeUsername(rawUsername)
            if (!username.matches(USERNAME)) throw JSONException("A saved review record has an incomplete username.")
            val addedAt = optionalLong(item, "addedAt", 0L)
            val statusValue = optionalString(item, "status", ReviewStatus.AWAITING_REVIEW.name)
            val notes = optionalString(item, "notes", "")
            if (queue.containsKey(username)) throw JSONException("The saved review queue contains duplicate usernames.")
            queue[username] = ReviewEntry(
                username = username,
                addedAt = addedAt,
                status = enumValueOrError(statusValue, "review status"),
                notes = notes,
            )
        }
        val protected = buildSet {
            for (index in 0 until protectedArray.length()) {
                val rawUsername = protectedArray.opt(index) as? String ?: throw JSONException("A protected account record is invalid.")
                val username = normalizeUsername(rawUsername)
                if (!username.matches(USERNAME)) throw JSONException("A protected account record is incomplete.")
                add(username)
            }
        }
        val history = buildList {
            for (index in 0 until historyArray.length()) {
                val item = historyArray.opt(index) as? JSONObject ?: throw JSONException("A saved review history record is incomplete.")
                val rawUsername = item.opt("username") as? String ?: throw JSONException("A saved history record has no valid username.")
                val username = normalizeUsername(rawUsername)
                if (!username.matches(USERNAME)) throw JSONException("A saved history record has an incomplete username.")
                val occurredAt = optionalLong(item, "occurredAt", 0L)
                val event = enumValueOrError<ReviewEvent>(optionalString(item, "event", ReviewEvent.STATUS_CHANGED.name), "review event")
                val scope = enumValueOrError<ReviewScope>(optionalString(item, "scope", ReviewScope.INDIVIDUAL.name), "review scope")
                val manualCompletion = optionalBoolean(item, "manualCompletion", false)
                add(ReviewHistoryEntry(
                    username = username,
                    previousStatus = nullableStatus(item, "previousStatus"),
                    newStatus = nullableStatus(item, "newStatus"),
                    occurredAt = occurredAt,
                    event = event,
                    scope = scope,
                    manualCompletion = manualCompletion,
                ))
            }
        }
        return ReviewState(queue, protected, history.take(MAX_HISTORY))
    }

    private inline fun <reified T : Enum<T>> enumValueOrError(value: String, field: String): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { throw JSONException("The saved $field is not supported.") }

    private fun nullableStatus(item: JSONObject, key: String): ReviewStatus? {
        if (!item.has(key) || item.isNull(key)) return null
        val value = item.opt(key) as? String ?: throw JSONException("A saved status field has an invalid type.")
        if (value == "null" || value.isBlank()) return null
        return enumValueOrError<ReviewStatus>(value, key)
    }

    private fun optionalString(item: JSONObject, key: String, default: String): String =
        if (!item.has(key)) default else item.opt(key) as? String ?: throw JSONException("A saved $key field has an invalid type.")

    private fun optionalLong(item: JSONObject, key: String, default: Long): Long {
        if (!item.has(key)) return default
        val value = item.opt(key) as? Number ?: throw JSONException("A saved $key field has an invalid type.")
        val longValue = value.toLong()
        if (value.toDouble() != longValue.toDouble() || longValue < 0L) throw JSONException("A saved $key field is invalid.")
        return longValue
    }

    private fun optionalBoolean(item: JSONObject, key: String, default: Boolean): Boolean =
        if (!item.has(key)) default else item.opt(key) as? Boolean ?: throw JSONException("A saved $key field has an invalid type.")

    private companion object {
        const val MAX_HISTORY = 2_000
        val USERNAME = Regex("[a-z0-9._]{1,30}")
    }
}
