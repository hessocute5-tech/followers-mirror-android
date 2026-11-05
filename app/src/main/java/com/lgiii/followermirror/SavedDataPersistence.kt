package com.lgiii.followermirror

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

internal data class SavedDataset(
    val accounts: Set<String>,
    val importedAt: Long?,
    val sourceFiles: List<String>,
    val validated: Boolean,
)

internal data class DatasetBundle(val followers: SavedDataset?, val following: SavedDataset?)

internal class UnreadableStoredValue(val recoveryCopy: String, message: String) : Exception(message)

internal sealed interface DatasetLoadResult {
    data object Missing : DatasetLoadResult
    data class Loaded(val bundle: DatasetBundle) : DatasetLoadResult
    data class RecoveryRequired(val raw: String, val reason: String) : DatasetLoadResult
    data class Unavailable(val reason: String) : DatasetLoadResult
}

internal interface DatasetStringStore {
    fun read(): String?
    fun write(value: String): Boolean
    fun preserve(value: String): Boolean
}

internal data class DatasetWriteResult(val success: Boolean, val bundle: DatasetBundle, val message: String)

internal class DatasetRepository(private val store: DatasetStringStore) {
    private var current = DatasetBundle(null, null)
    private var blocked = false
    private var blockedRaw: String? = null

    fun load(): DatasetLoadResult {
        val raw = try { store.read() } catch (e: UnreadableStoredValue) {
            blocked = true
            blockedRaw = e.recoveryCopy
            return DatasetLoadResult.RecoveryRequired(e.recoveryCopy, e.message ?: "Saved datasets were stored in an incompatible form and need recovery.")
        } catch (_: Exception) {
            blocked = true
            blockedRaw = null
            return DatasetLoadResult.Unavailable("Saved datasets could not be read. Imports and deletion are paused until recovery.")
        }
        if (raw == null) {
            blocked = false
            blockedRaw = null
            current = DatasetBundle(null, null)
            return DatasetLoadResult.Missing
        }
        return try {
            current = DatasetJsonCodec.decode(raw)
            blocked = false
            blockedRaw = null
            DatasetLoadResult.Loaded(current)
        } catch (_: Exception) {
            blocked = true
            blockedRaw = raw
            DatasetLoadResult.RecoveryRequired(raw, "Saved Followers and Following data could not be read. The original is preserved; export or restore a backup before replacing it.")
        }
    }

    fun markUnreadable(raw: String?): DatasetLoadResult.RecoveryRequired {
        blocked = true
        blockedRaw = raw
        return DatasetLoadResult.RecoveryRequired(
            raw ?: "",
            "Older saved dataset data could not be read. It is being kept in place; restore a backup or explicitly replace it.",
        )
    }

    fun replaceFollowers(dataset: SavedDataset): DatasetWriteResult = replace(current.copy(followers = dataset))
    fun replaceFollowing(dataset: SavedDataset): DatasetWriteResult = replace(current.copy(following = dataset))
    fun deleteFollowers(): DatasetWriteResult = replace(current.copy(followers = null))
    fun deleteFollowing(): DatasetWriteResult = replace(current.copy(following = null))

    fun replaceAfterRecovery(bundle: DatasetBundle): DatasetWriteResult {
        if (!canResolve()) return failure("Could not preserve the unreadable original data. Nothing was replaced.")
        return persist(bundle)
    }

    fun restore(raw: String): DatasetWriteResult {
        val restored = try { DatasetJsonCodec.decode(raw) } catch (_: Exception) {
            return failure("That backup is incomplete or invalid. Saved datasets were left unchanged.")
        }
        if (blocked && !canResolve()) return failure("Could not preserve the unreadable original data. Nothing was replaced.")
        if (!runCatching { store.write(raw) }.getOrDefault(false)) {
            return failure("Could not restore the backup. The previous datasets are unchanged.")
        }
        current = restored
        blocked = false
        blockedRaw = null
        return DatasetWriteResult(true, current, "Dataset backup restored.")
    }

    fun reset(): DatasetWriteResult {
        if (blocked && !canResolve()) return failure("Could not preserve unreadable dataset data. Nothing was reset.")
        return persist(DatasetBundle(null, null))
    }

    fun current(): DatasetBundle = current

    private fun replace(bundle: DatasetBundle): DatasetWriteResult {
        if (blocked) return failure("Dataset changes are paused until the unreadable saved data is recovered.")
        return persist(bundle)
    }

    private fun canResolve(): Boolean {
        if (!blocked) return true
        val raw = blockedRaw ?: return false
        return runCatching { store.preserve(raw) }.getOrDefault(false)
    }

    private fun persist(bundle: DatasetBundle): DatasetWriteResult {
        val raw = DatasetJsonCodec.encode(bundle)
        if (!runCatching { store.write(raw) }.getOrDefault(false)) {
            return DatasetWriteResult(false, current, "Could not save datasets. Your previous data is unchanged.")
        }
        current = bundle
        blocked = false
        blockedRaw = null
        return DatasetWriteResult(true, current, "Datasets saved.")
    }

    private fun failure(message: String) = DatasetWriteResult(false, current, message)
}

/** Strict reader for the existing imported_datasets_v1 document format. */
internal object DatasetJsonCodec {
    private val username = Regex("[a-z0-9._]{1,30}")

    fun decode(raw: String): DatasetBundle {
        val root = JSONObject(raw)
        if (!root.has("followers") || !root.has("following")) {
            throw JSONException("The saved dataset document is missing a category.")
        }
        return DatasetBundle(readDataset(root.get("followers")), readDataset(root.get("following")))
    }

    fun encode(bundle: DatasetBundle): String = JSONObject()
        .put("followers", writeDataset(bundle.followers))
        .put("following", writeDataset(bundle.following))
        .toString()

    private fun readDataset(value: Any?): SavedDataset? {
        if (value == null || value == JSONObject.NULL) return null
        val item = value as? JSONObject ?: throw JSONException("A saved dataset has an invalid type.")
        val accountsJson = item.opt("accounts") as? JSONArray
            ?: throw JSONException("A saved dataset is missing its accounts list.")
        val accounts = strictStrings(accountsJson, "accounts").map { it.trim().removePrefix("@").lowercase(Locale.ROOT) }
        if (accounts.any { !username.matches(it) }) throw JSONException("A saved account name is incomplete or invalid.")
        val importedAt = if (!item.has("importedAt") || item.isNull("importedAt")) null else {
            item.optLong("importedAt", Long.MIN_VALUE).takeIf { it > 0L }
                ?: throw JSONException("A saved import date is invalid.")
        }
        val sourceFiles = if (!item.has("sourceFiles") || item.isNull("sourceFiles")) emptyList() else {
            val values = item.opt("sourceFiles") as? JSONArray ?: throw JSONException("Saved source filenames have an invalid type.")
            strictStrings(values, "source filenames")
        }
        val validated = if (item.has("validated")) {
            item.opt("validated") as? Boolean ?: throw JSONException("A saved validation flag has an invalid type.")
        } else false // v1 documents created before validation metadata are still supported.
        return SavedDataset(accounts.toSet(), importedAt, sourceFiles, validated)
    }

    private fun writeDataset(dataset: SavedDataset?): Any = dataset?.let {
        JSONObject()
            .put("accounts", JSONArray(it.accounts.sorted()))
            .put("importedAt", it.importedAt ?: JSONObject.NULL)
            .put("sourceFiles", JSONArray(it.sourceFiles))
            .put("validated", it.validated)
    } ?: JSONObject.NULL

    private fun strictStrings(array: JSONArray, label: String): List<String> = buildList {
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String ?: throw JSONException("A value in the saved $label list has an invalid type.")
            if (value.isBlank()) throw JSONException("A value in the saved $label list is incomplete.")
            add(value)
        }
    }
}

internal data class Snapshot(val date: Long, val followers: Set<String>)

internal sealed interface SnapshotLoadResult {
    data object Missing : SnapshotLoadResult
    data class Loaded(val snapshots: List<Snapshot>) : SnapshotLoadResult
    data class RecoveryRequired(val raw: String, val reason: String) : SnapshotLoadResult
    data class Unavailable(val reason: String) : SnapshotLoadResult
}

internal data class SnapshotWriteResult(val success: Boolean, val snapshots: List<Snapshot>, val message: String)

internal interface SnapshotStringStore {
    fun read(): String?
    fun write(value: String): Boolean
    fun preserve(value: String): Boolean
}

/** Keeps the current snapshots read-only after any malformed read until the user restores or discards them. */
internal class SnapshotRepository(private val store: SnapshotStringStore) {
    private var blockedRaw: String? = null
    private var blocked = false
    private var current = emptyList<Snapshot>()

    fun load(): SnapshotLoadResult {
        val raw = try {
            store.read()
        } catch (e: UnreadableStoredValue) {
            blocked = true
            blockedRaw = e.recoveryCopy
            return SnapshotLoadResult.RecoveryRequired(e.recoveryCopy, e.message ?: "Saved snapshots were stored in an incompatible form and need recovery.")
        } catch (_: Exception) {
            blocked = true
            blockedRaw = null
            return SnapshotLoadResult.Unavailable("Saved snapshots could not be read. No snapshot changes are allowed until recovery.")
        }
        if (raw == null) {
            blocked = false
            blockedRaw = null
            current = emptyList()
            return SnapshotLoadResult.Missing
        }
        return try {
            val snapshots = SnapshotJsonCodec.decode(raw)
            blocked = false
            blockedRaw = null
            current = snapshots
            SnapshotLoadResult.Loaded(snapshots)
        } catch (_: Exception) {
            blocked = true
            blockedRaw = raw
            SnapshotLoadResult.RecoveryRequired(raw, "Saved snapshots could not be read. The original data is preserved; export or restore a backup before replacing it.")
        }
    }

    fun write(snapshots: List<Snapshot>): SnapshotWriteResult {
        if (blocked) return SnapshotWriteResult(false, current, "Snapshot changes are paused until the unreadable saved data is recovered.")
        return persist(snapshots)
    }

    fun restore(raw: String): SnapshotWriteResult {
        val restored = try { SnapshotJsonCodec.decode(raw) } catch (_: Exception) {
            return SnapshotWriteResult(false, emptyList(), "That backup is incomplete or invalid. The saved snapshots were left unchanged.")
        }
        if (blocked && blockedRaw == null) return SnapshotWriteResult(false, current, "Could not preserve the unreadable original data. Nothing was replaced.")
        if (blockedRaw != null && !runCatching { store.preserve(blockedRaw!!) }.getOrDefault(false)) {
            return SnapshotWriteResult(false, emptyList(), "Could not preserve the unreadable original data. Nothing was replaced.")
        }
        if (!runCatching { store.write(raw) }.getOrDefault(false)) {
            return SnapshotWriteResult(false, current, "Could not restore the backup. The original data was left unchanged.")
        }
        blocked = false
        blockedRaw = null
        current = restored
        return SnapshotWriteResult(true, restored, "Snapshot backup restored.")
    }

    fun discard(): SnapshotWriteResult {
        if (!blocked) return SnapshotWriteResult(true, emptyList(), "")
        if (blockedRaw == null) return SnapshotWriteResult(false, current, "Could not preserve the unreadable original data. Nothing was discarded.")
        if (blockedRaw != null && !runCatching { store.preserve(blockedRaw!!) }.getOrDefault(false)) {
            return SnapshotWriteResult(false, emptyList(), "Could not preserve the unreadable original data. Nothing was discarded.")
        }
        if (!runCatching { store.write("[]") }.getOrDefault(false)) {
            return SnapshotWriteResult(false, emptyList(), "Could not clear snapshots. The original data was left unchanged.")
        }
        blocked = false
        blockedRaw = null
        current = emptyList()
        return SnapshotWriteResult(true, emptyList(), "Unreadable snapshots were preserved in a local recovery copy and cleared from active history.")
    }

    private fun persist(snapshots: List<Snapshot>): SnapshotWriteResult {
        val raw = SnapshotJsonCodec.encode(snapshots)
        if (!runCatching { store.write(raw) }.getOrDefault(false)) {
            return SnapshotWriteResult(false, current, "Could not save snapshots. Your previous history is unchanged.")
        }
        current = snapshots
        return SnapshotWriteResult(true, snapshots, "Snapshots saved.")
    }
}

internal object SnapshotJsonCodec {
    private val username = Regex("[a-z0-9._]{1,30}")

    fun decode(raw: String): List<Snapshot> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject ?: throw JSONException("A saved snapshot record is incomplete.")
                val date = item.opt("date") as? Number
                    ?: throw JSONException("A saved snapshot date is missing or invalid.")
                if (date.toLong() <= 0L || date.toDouble() != date.toLong().toDouble()) throw JSONException("A saved snapshot date is invalid.")
                val followers = item.opt("followers") as? JSONArray
                    ?: throw JSONException("A saved snapshot follower list is missing or invalid.")
                val names = buildSet {
                    for (i in 0 until followers.length()) {
                        val value = followers.opt(i) as? String ?: throw JSONException("A saved snapshot contains an invalid account record.")
                        val normalized = value.trim().removePrefix("@").lowercase(Locale.ROOT)
                        if (!username.matches(normalized)) throw JSONException("A saved snapshot contains an incomplete account record.")
                        add(normalized)
                    }
                }
                add(Snapshot(date.toLong(), names))
            }
        }.sortedByDescending { it.date }
    }

    fun encode(snapshots: List<Snapshot>): String {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(JSONObject().put("date", snapshot.date).put("followers", JSONArray(snapshot.followers.sorted())))
        }
        return array.toString()
    }
}
