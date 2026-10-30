package com.lgiii.followermirror

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Black = Color(0xFF090909)
private val Panel = Color(0xFF171717)
private val Red = Color(0xFFFF3038)
private val White = Color(0xFFF5F5F5)
private val Muted = Color(0xFFAAAAAA)
private const val DATASET_STORE = "imported_datasets_v1"

private data class SavedDataset(
    val accounts: Set<String>,
    val importedAt: Long?,
    val sourceFiles: List<String>,
    val validated: Boolean,
)

private enum class DataAction { DELETE_FOLLOWERS, DELETE_FOLLOWING, RESET_ALL }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("mirror_local", MODE_PRIVATE) }
    private var followerDataset by mutableStateOf<SavedDataset?>(null)
    private var followingDataset by mutableStateOf<SavedDataset?>(null)
    private val followers: Set<String> get() = followerDataset?.accounts.orEmpty()
    private val following: Set<String> get() = followingDataset?.accounts.orEmpty()
    private val followersLoaded: Boolean get() = followerDataset != null
    private val followingLoaded: Boolean get() = followingDataset != null
    private var snapshots by mutableStateOf<List<Snapshot>>(emptyList())
    private var statusMessage by mutableStateOf("Import your Instagram data export to begin.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshots = loadSnapshots()
        loadDatasets()
        setContent { MirrorApp() }
    }

    private val followerPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFollowers(uris)
    }
    private val followingPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFollowing(uri)
    }

    @Composable private fun MirrorApp() {
        var tab by remember { mutableStateOf("NOT BACK") }
        var search by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var snapshotToDelete by remember { mutableStateOf<Snapshot?>(null) }
        var pendingDataAction by remember { mutableStateOf<DataAction?>(null) }
        val comparison = FollowerData.compare(followers, following)
        val lostSinceLatest = snapshots.firstOrNull()?.let {
            FollowerData.detectedDisappearances(it.followers, followers)
        }.orEmpty()
        val base = when (tab) {
            "MUTUALS" -> comparison.mutuals
            "DETECTED CHANGES" -> lostSinceLatest
            "FOLLOWERS" -> followers
            "FOLLOWING" -> following
            else -> comparison.notFollowingBack
        }
        val visible = base.filter { it.contains(search.trim(), ignoreCase = true) }.sorted()
        val tabs = listOf("NOT BACK", "DETECTED CHANGES", "MUTUALS", "FOLLOWERS", "FOLLOWING", "HISTORY", "IMPORT MANAGER")

        LaunchedEffect(visible) { selected = selected intersect visible.toSet() }

        MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Black, surface = Panel, onBackground = White, onSurface = White)) {
            Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FOLLOWER", color = White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                            Text("MIRROR", color = Red, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        }
                        Box(Modifier.background(Red, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("LOCAL ONLY", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("FOLLOWERS", if (followersLoaded) followers.size.toString() else "—", Modifier.weight(1f))
                        StatCard("FOLLOWING", if (followingLoaded) following.size.toString() else "—", Modifier.weight(1f))
                        StatCard("NOT BACK", if (followersLoaded && followingLoaded) comparison.notFollowingBack.size.toString() else "—", Modifier.weight(1f), Red)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { followerPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Red),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Import followers", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { followingPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        ) { Text("Import following", fontSize = 12.sp, color = White) }
                    }
                    TextButton(onClick = { tab = "IMPORT MANAGER"; selected = emptySet() }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                        Text("MANAGE IMPORTED DATA", color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Select all split follower files together · ${statusMessage}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEach { item ->
                        Box(
                            Modifier.width(104.dp).background(if (tab == item) Red else Panel, RoundedCornerShape(8.dp))
                                .clickable { tab = item; selected = emptySet() }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(item, fontSize = 9.sp, color = White, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }

                if (tab == "HISTORY") {
                    HistoryScreen(snapshots = snapshots, onDelete = { snapshotToDelete = it })
                } else if (tab == "IMPORT MANAGER") {
                    ImportManagerScreen(
                        followers = followerDataset,
                        following = followingDataset,
                        statusMessage = statusMessage,
                        onReplaceFollowers = { followerPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        onReplaceFollowing = { followingPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        onDeleteFollowers = { pendingDataAction = DataAction.DELETE_FOLLOWERS },
                        onDeleteFollowing = { pendingDataAction = DataAction.DELETE_FOLLOWING },
                        onResetAll = { pendingDataAction = DataAction.RESET_ALL },
                    )
                } else {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕", color = Muted, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (search.isEmpty()) Text("Search usernames...", color = Muted, fontSize = 14.sp)
                            BasicTextField(value = search, onValueChange = { search = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                        }
                        Text("${visible.size}", color = Red, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected.isEmpty()) "SELECT ACCOUNTS TO REVIEW" else "${selected.size} SELECTED", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = if (selected.containsAll(visible) && visible.isNotEmpty()) emptySet() else visible.toSet() }) {
                            Text(if (selected.containsAll(visible) && visible.isNotEmpty()) "CLEAR" else "SELECT ALL", color = Red, fontSize = 11.sp)
                        }
                    }
                    val requiredDataMissing = when (tab) {
                        "FOLLOWERS" -> !followersLoaded
                        "FOLLOWING" -> !followingLoaded
                        else -> !followersLoaded || !followingLoaded
                    }
                    if (requiredDataMissing) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("YOUR FOLLOW LIST. YOUR CALL.", color = White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(8.dp))
                            Text("Import followers and following JSON files from your Instagram data download. Choose every followers_*.json file together, then import following.json separately. Comparisons happen on this device.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    } else if (visible.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (tab == "DETECTED CHANGES" && snapshots.isEmpty()) "Save a follower snapshot, then import a newer list to compare." else "No accounts in this list.", color = Muted)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(visible, key = { it }) { username ->
                                Row(Modifier.fillMaxWidth().clickable { selected = if (username in selected) selected - username else selected + username }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = username in selected, onCheckedChange = { checked -> selected = if (checked) selected + username else selected - username }, colors = CheckboxDefaults.colors(checkedColor = Red, uncheckedColor = Muted))
                                    Box(Modifier.size(38.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(username.take(1).uppercase(), color = Red, fontWeight = FontWeight.Black, fontSize = 17.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(username, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when (tab) {
                                            "DETECTED CHANGES" -> "Absent from the latest follower export"
                                            "MUTUALS" -> "Follows you back"
                                            "NOT BACK" -> "Doesn't follow you back"
                                            else -> "Instagram account"
                                        }, color = Muted, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { openProfile(username) }) { Text("OPEN ↗", color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                }
                                HorizontalDivider(color = Color(0xFF242424), thickness = 1.dp)
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab != "HISTORY" && tab != "IMPORT MANAGER") {
                            Button(onClick = {
                                selected.sorted().firstOrNull()?.let { openProfile(it) }
                                if (selected.isEmpty()) toast("Select at least one account first.")
                            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red), shape = RoundedCornerShape(10.dp)) {
                                Text("REVIEW SELECTED", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(onClick = { saveSnapshot() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("SAVE SNAPSHOT", fontSize = 11.sp, color = White) }
                    }
                    Text("Manual review only · No password · No auto-unfollow", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                }
            }

            snapshotToDelete?.let { snapshot ->
                AlertDialog(
                    onDismissRequest = { snapshotToDelete = null },
                    title = { Text("Delete snapshot?") },
                    text = { Text("This removes the saved follower snapshot from this device.") },
                    confirmButton = {
                        TextButton(onClick = { deleteSnapshot(snapshot.date); snapshotToDelete = null }) { Text("DELETE", color = Red) }
                    },
                    dismissButton = { TextButton(onClick = { snapshotToDelete = null }) { Text("CANCEL") } },
                )
            }
            pendingDataAction?.let { action ->
                val title = when (action) {
                    DataAction.DELETE_FOLLOWERS -> "Delete Followers dataset?"
                    DataAction.DELETE_FOLLOWING -> "Delete Following dataset?"
                    DataAction.RESET_ALL -> "Reset all app data?"
                }
                val message = when (action) {
                    DataAction.DELETE_FOLLOWERS -> "This permanently removes only the imported Followers dataset. Your Following dataset and snapshots will remain."
                    DataAction.DELETE_FOLLOWING -> "This permanently removes only the imported Following dataset. Your Followers dataset and snapshots will remain."
                    DataAction.RESET_ALL -> "This removes both imported datasets and saved follower history from this device. Other app preferences are preserved. This cannot be undone."
                }
                AlertDialog(
                    onDismissRequest = { pendingDataAction = null },
                    title = { Text(title) },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = {
                            when (action) {
                                DataAction.DELETE_FOLLOWERS -> deleteDataset(FollowerData.DatasetKind.FOLLOWERS)
                                DataAction.DELETE_FOLLOWING -> deleteDataset(FollowerData.DatasetKind.FOLLOWING)
                                DataAction.RESET_ALL -> resetAllData()
                            }
                            pendingDataAction = null
                        }) { Text(if (action == DataAction.RESET_ALL) "RESET ALL" else "DELETE", color = Red) }
                    },
                    dismissButton = { TextButton(onClick = { pendingDataAction = null }) { Text("CANCEL") } },
                )
            }
        }
    }

    @Composable private fun HistoryScreen(snapshots: List<Snapshot>, onDelete: (Snapshot) -> Unit) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                "Changes show accounts missing between exports. They are not confirmed unfollows: deletions, suspensions, username changes, export timing, or incomplete files can also explain a disappearance.",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (snapshots.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No saved snapshots yet. Import followers and save a snapshot to start tracking.", color = Muted)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshots, key = { it.date }) { snapshot ->
                        val index = snapshots.indexOf(snapshot)
                        val prior = snapshots.getOrNull(index + 1)
                        val disappeared = prior?.let { FollowerData.detectedDisappearances(it.followers, snapshot.followers).size }
                        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snapshot.date.toDateLabel(), color = White, fontWeight = FontWeight.Bold)
                                Text("${snapshot.followers.size} followers", color = Muted, fontSize = 12.sp)
                                Text(disappeared?.let { "$it detected disappearances since the previous snapshot" } ?: "Baseline snapshot", color = Red, fontSize = 11.sp)
                            }
                            TextButton(onClick = { onDelete(snapshot) }) { Text("DELETE", color = Red, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun ImportManagerScreen(
        followers: SavedDataset?,
        following: SavedDataset?,
        statusMessage: String,
        onReplaceFollowers: () -> Unit,
        onReplaceFollowing: () -> Unit,
        onDeleteFollowers: () -> Unit,
        onDeleteFollowing: () -> Unit,
        onResetAll: () -> Unit,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Each dataset is managed independently. A replacement is checked before it is saved; a rejected import leaves the current dataset unchanged.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    DatasetManagerCard(
                        title = "Followers", dataset = followers,
                        onReplace = onReplaceFollowers, onDelete = onDeleteFollowers,
                    )
                }
                item {
                    DatasetManagerCard(
                        title = "Following", dataset = following,
                        onReplace = onReplaceFollowing, onDelete = onDeleteFollowing,
                    )
                }
            }
            if (statusMessage.isNotBlank()) {
                Text(statusMessage, color = if (statusMessage.startsWith("Import failed") || statusMessage.startsWith("Save failed") || statusMessage.startsWith("Delete failed") || statusMessage.startsWith("Reset failed")) Red else Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            Button(
                onClick = onResetAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(10.dp),
            ) { Text("RESET ALL DATA", color = White, fontWeight = FontWeight.Bold) }
        }
    }

    @Composable private fun DatasetManagerCard(
        title: String,
        dataset: SavedDataset?,
        onReplace: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text(title.uppercase(Locale.getDefault()), color = White, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    dataset == null -> "Status: No dataset imported"
                    dataset.validated -> "Status: Imported and validated"
                    else -> "Status: Older import; category was not validated. Replace to verify it."
                },
                color = if (dataset?.validated == false) Red else Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text("Unique accounts: ${dataset?.accounts?.size ?: 0}", color = White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Imported: ${dataset?.importedAt?.toDateLabel() ?: if (dataset == null) "—" else "Date unavailable (older import)"}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            val sources = dataset?.sourceFiles?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: if (dataset == null) "—" else "Filename unavailable (older import)"
            Text("Source: $sources", color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onReplace, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red), shape = RoundedCornerShape(8.dp)) {
                    Text("REPLACE DATASET", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onDelete, enabled = dataset != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Text("DELETE DATASET", fontSize = 10.sp, color = if (dataset != null) White else Muted)
                }
            }
        }
    }

    @Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = White) {
        Column(modifier.background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(value, color = valueColor, fontSize = 23.sp, fontWeight = FontWeight.Black)
        }
    }

    private fun importFollowers(uris: List<Uri>) {
        try {
            val documents = uris.map { uri -> FollowerData.SourceDocument(documentName(uri), readDocument(uri)) }
            val validated = FollowerData.validateAndParse(documents, FollowerData.DatasetKind.FOLLOWERS)
            val replacement = SavedDataset(validated.accounts, System.currentTimeMillis(), validated.sourceFiles, validated = true)
            if (!persistDatasets(replacement, followingDataset)) {
                throw IllegalStateException("Could not save the replacement. Your previous Followers dataset is unchanged.")
            }
            statusMessage = "Imported ${replacement.accounts.size} unique Followers from ${replacement.sourceFiles.size} file(s)."
            toast(statusMessage)
        } catch (e: Exception) {
            statusMessage = "Import failed: ${e.message ?: "invalid file"}"
            toast(statusMessage)
        }
    }

    private fun importFollowing(uri: Uri) {
        try {
            val name = documentName(uri)
            val validated = FollowerData.validateAndParse(
                listOf(FollowerData.SourceDocument(name, readDocument(uri))),
                FollowerData.DatasetKind.FOLLOWING,
            )
            val replacement = SavedDataset(validated.accounts, System.currentTimeMillis(), validated.sourceFiles, validated = true)
            if (!persistDatasets(followerDataset, replacement)) {
                throw IllegalStateException("Could not save the replacement. Your previous Following dataset is unchanged.")
            }
            statusMessage = "Imported ${replacement.accounts.size} unique Following accounts."
            toast(statusMessage)
        } catch (e: Exception) {
            statusMessage = "Import failed: ${e.message ?: "invalid file"}"
            toast(statusMessage)
        }
    }

    private fun readDocument(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Couldn't read one of the selected files.")

    private fun documentName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column)?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "selected-file.json"
    }

    private fun loadDatasets() {
        val stored = prefs.getString(DATASET_STORE, null)
        if (stored != null) {
            try {
                val root = JSONObject(stored)
                followerDataset = readDataset(root.opt("followers"))
                followingDataset = readDataset(root.opt("following"))
            } catch (_: Exception) {
                statusMessage = "Saved imports could not be read. Replace them using valid Instagram export files."
            }
            return
        }

        // One-time migration of datasets saved by earlier app builds. Their original source and type
        // were not recorded, so mark them unverified and ask the user to replace them to validate.
        val legacyFollowers = prefs.getString("followers", null)?.let { legacyDataset(it) }
        val legacyFollowing = prefs.getString("following", null)?.let { legacyDataset(it) }
        if (legacyFollowers != null || legacyFollowing != null) {
            if (!persistDatasets(legacyFollowers, legacyFollowing)) {
                followerDataset = legacyFollowers
                followingDataset = legacyFollowing
                statusMessage = "Older imports loaded; their file type was not recorded. Replace them to validate."
            }
        }
    }

    private fun legacyDataset(serializedAccounts: String): SavedDataset = SavedDataset(
        accounts = FollowerData.normalizeUsernames(jsonArrayToSet(JSONArray(serializedAccounts))),
        importedAt = null,
        sourceFiles = emptyList(),
        validated = false,
    )

    private fun readDataset(value: Any?): SavedDataset? {
        if (value == null || value == JSONObject.NULL) return null
        val item = value as? JSONObject ?: throw IllegalArgumentException("Invalid saved dataset")
        return SavedDataset(
            accounts = FollowerData.normalizeUsernames(jsonArrayToSet(item.getJSONArray("accounts"))),
            importedAt = item.optLong("importedAt").takeIf { it > 0L },
            sourceFiles = item.optJSONArray("sourceFiles")?.let(::jsonArrayToSetList).orEmpty(),
            validated = item.optBoolean("validated", false),
        )
    }

    /** Persist both categories in one preference value; update Compose state only after commit succeeds. */
    private fun persistDatasets(newFollowers: SavedDataset?, newFollowing: SavedDataset?): Boolean {
        val root = JSONObject()
            .put("followers", datasetJson(newFollowers))
            .put("following", datasetJson(newFollowing))
        val committed = prefs.edit()
            .putString(DATASET_STORE, root.toString())
            .remove("followers")
            .remove("following")
            .commit()
        if (committed) {
            followerDataset = newFollowers
            followingDataset = newFollowing
        }
        return committed
    }

    private fun datasetJson(dataset: SavedDataset?): Any = dataset?.let {
        JSONObject()
            .put("accounts", JSONArray(it.accounts.sorted()))
            .put("importedAt", it.importedAt ?: JSONObject.NULL)
            .put("sourceFiles", JSONArray(it.sourceFiles))
            .put("validated", it.validated)
    } ?: JSONObject.NULL

    private fun deleteDataset(kind: FollowerData.DatasetKind) {
        val success = when (kind) {
            FollowerData.DatasetKind.FOLLOWERS -> persistDatasets(null, followingDataset)
            FollowerData.DatasetKind.FOLLOWING -> persistDatasets(followerDataset, null)
            else -> false
        }
        statusMessage = if (success) {
            if (kind == FollowerData.DatasetKind.FOLLOWERS) "Followers dataset deleted; Following was kept." else "Following dataset deleted; Followers was kept."
        } else "Delete failed. Your saved datasets were kept."
        toast(statusMessage)
    }

    private fun resetAllData() {
        val emptyStore = JSONObject()
            .put("followers", JSONObject.NULL)
            .put("following", JSONObject.NULL)
            .toString()
        val success = prefs.edit()
            .putString(DATASET_STORE, emptyStore)
            .remove("followers")
            .remove("following")
            .remove("snapshots")
            .commit()
        if (success) {
            followerDataset = null
            followingDataset = null
            snapshots = emptyList()
            statusMessage = "All imported datasets and comparison history were cleared. Other app preferences were preserved."
        } else {
            statusMessage = "Reset failed. Your saved datasets and history were kept."
        }
        toast(statusMessage)
    }

    private fun jsonArrayToSetList(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }

    private data class Snapshot(val date: Long, val followers: Set<String>)

    private fun saveSnapshot() {
        if (!followersLoaded) { toast("Import followers before saving a snapshot."); return }
        val current = Snapshot(System.currentTimeMillis(), followers)
        snapshots = (listOf(current) + snapshots).take(30)
        persistSnapshots()
        statusMessage = "Snapshot saved: ${followers.size} followers. Import a newer list later to compare exports."
        toast("Follower snapshot saved locally.")
    }

    private fun deleteSnapshot(date: Long) {
        snapshots = snapshots.filterNot { it.date == date }
        persistSnapshots()
        toast("Snapshot deleted.")
    }

    private fun persistSnapshots() {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(JSONObject().put("date", snapshot.date).put("followers", JSONArray(snapshot.followers.sorted())))
        }
        prefs.edit().putString("snapshots", array.toString()).apply()
    }

    private fun loadSnapshots(): List<Snapshot> = try {
        val array = JSONArray(prefs.getString("snapshots", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Snapshot(item.getLong("date"), FollowerData.normalizeUsernames(jsonArrayToSet(item.getJSONArray("followers"))))
        }.sortedByDescending { it.date }
    } catch (_: Exception) { emptyList() }

    private fun openProfile(username: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${Uri.encode(username)}/")))
        } catch (_: Exception) { toast("Couldn't open Instagram profile.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun setToJson(set: Set<String>) = JSONArray(set.sorted()).toString()
    private fun jsonArrayToSet(array: JSONArray): Set<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
}

private fun Long.toDateLabel(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
