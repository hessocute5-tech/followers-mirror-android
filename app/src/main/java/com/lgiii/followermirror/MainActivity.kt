package com.lgiii.followermirror

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DATASET_STORE = "imported_datasets_v1"
private const val THEME_MODE_PREF = "theme_mode"
private const val REVIEW_MANAGER_STORE = "review_manager_v1"

private fun isErrorStatus(message: String): Boolean =
    listOf("Import failed", "Save failed", "Delete failed", "Reset failed").any(message::startsWith)

private data class SavedDataset(
    val accounts: Set<String>,
    val importedAt: Long?,
    val sourceFiles: List<String>,
    val validated: Boolean,
)

private enum class DataAction { DELETE_FOLLOWERS, DELETE_FOLLOWING, RESET_ALL }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("mirror_local", MODE_PRIVATE) }
    private val themePreference by lazy {
        ThemeModePreference(
            readValue = { prefs.getString(THEME_MODE_PREF, null) },
            writeValue = { value -> prefs.edit().putString(THEME_MODE_PREF, value).apply() },
        )
    }
    private val reviewRepository by lazy {
        ReviewManagerRepository(object : ReviewStringStore {
            override fun read(): String? = prefs.getString(REVIEW_MANAGER_STORE, null)
            override fun write(value: String): Boolean = prefs.edit().putString(REVIEW_MANAGER_STORE, value).commit()
        })
    }
    private var themeMode by mutableStateOf(ThemeMode.DARK)
    private var followerDataset by mutableStateOf<SavedDataset?>(null)
    private var followingDataset by mutableStateOf<SavedDataset?>(null)
    private val followers: Set<String> get() = followerDataset?.accounts.orEmpty()
    private val following: Set<String> get() = followingDataset?.accounts.orEmpty()
    private val followersLoaded: Boolean get() = followerDataset != null
    private val followingLoaded: Boolean get() = followingDataset != null
    private var snapshots by mutableStateOf<List<Snapshot>>(emptyList())
    private var reviewState by mutableStateOf(ReviewState())
    private var reviewStatusMessage by mutableStateOf("")
    private var statusMessage by mutableStateOf("Import your Instagram data export to begin.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = themePreference.load()
        snapshots = loadSnapshots()
        reviewState = reviewRepository.load()
        loadDatasets()
        setContent { FollowerMirrorTheme(themeMode) { MirrorApp() } }
    }

    private val followerPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFollowers(uris)
    }
    private val followingPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFollowing(uri)
    }

    @Composable private fun MirrorApp() {
        val colors = MaterialTheme.colorScheme
        val brandStyle = dashboardBrandStyle(themeMode)
        SideEffect {
            updateSystemBars(colors.background.toArgb(), themeMode)
        }
        var destination by remember { mutableStateOf(DashboardDestination.NOT_FOLLOWING_BACK) }
        var search by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var snapshotToDelete by remember { mutableStateOf<Snapshot?>(null) }
        var pendingDataAction by remember { mutableStateOf<DataAction?>(null) }
        val comparison = FollowerData.compare(followers, following)
        val lostSinceLatest = snapshots.firstOrNull()?.let {
            FollowerData.detectedDisappearances(it.followers, followers)
        }.orEmpty()
        val base = when (destination) {
            DashboardDestination.MUTUALS -> comparison.mutuals
            DashboardDestination.DETECTED_CHANGES -> lostSinceLatest
            DashboardDestination.FOLLOWERS -> followers
            DashboardDestination.FOLLOWING -> following
            DashboardDestination.NOT_FOLLOWING_BACK -> comparison.notFollowingBack
            else -> comparison.notFollowingBack
        }
        val visible = base.filter { it.contains(search.trim(), ignoreCase = true) }.sorted()
        val destinations = DashboardDestination.entries

        LaunchedEffect(visible) { selected = selected intersect visible.toSet() }

        run {
            Box(Modifier.fillMaxSize().background(colors.background)) {
                Image(
                    painter = painterResource(brandStyle.backgroundResource),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(brandStyle.artworkResource),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.TopEnd).size(228.dp).offset(x = 46.dp, y = 4.dp),
                    )
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FOLLOWER", color = colors.onBackground, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                            Text(
                                "MIRROR",
                                color = colors.primary,
                                fontSize = if (brandStyle.isDark) 31.sp else 29.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = if (brandStyle.isDark) FontFamily.Cursive else FontFamily.SansSerif,
                                fontStyle = if (brandStyle.isDark) FontStyle.Italic else FontStyle.Normal,
                                letterSpacing = if (brandStyle.isDark) 0.sp else 1.5.sp,
                            )
                        }
                        val badgeShape = RoundedCornerShape(12.dp)
                        val badgeModifier = Modifier.shadow(8.dp, badgeShape)
                            .background(
                                Brush.verticalGradient(listOf(MirrorThemeColors.lightBrandShade, MirrorThemeColors.lightBrandInk)),
                                badgeShape,
                            )
                            .border(BorderStroke(if (brandStyle.isDark) 2.dp else 1.5.dp, colors.primary), badgeShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                        Box(badgeModifier) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CrownMark(Modifier.size(22.dp))
                                Text("LGIII", color = brandStyle.badgeForeground, fontSize = 13.sp, fontWeight = FontWeight.Black)
                                Text("EDITION", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Box(
                        Modifier.padding(top = 4.dp).width(if (brandStyle.isDark) 158.dp else 148.dp).height(3.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(if (brandStyle.isDark) colors.primary else MirrorThemeColors.lightBrandInk, colors.primary, colors.primary.copy(alpha = 0.08f)),
                                ),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("FOLLOWERS", if (followersLoaded) followers.size.toString() else "—", Modifier.weight(1f), themeMode = themeMode)
                        StatCard("FOLLOWING", if (followingLoaded) following.size.toString() else "—", Modifier.weight(1f), themeMode = themeMode)
                        StatCard(DashboardDestination.NOT_FOLLOWING_BACK.label, if (followersLoaded && followingLoaded) comparison.notFollowingBack.size.toString() else "—", Modifier.weight(1f), colors.primary, themeMode)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val importShape = RoundedCornerShape(12.dp)
                        val followersImportModifier = Modifier.weight(1f).shadow(8.dp, importShape)
                            .background(
                                Brush.linearGradient(listOf(colors.primary, MirrorThemeColors.lightBrandRedDeep, colors.primary)),
                                importShape,
                            )
                            .clip(importShape)
                        Button(
                            onClick = { followerPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            modifier = followersImportModifier,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                            ),
                            shape = importShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                Image(
                                    painter = painterResource(R.drawable.brand_splatter_corner),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(width = 82.dp, height = 54.dp)
                                        .graphicsLayer { alpha = 0.55f },
                                )
                                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImportArrowIcon(Color.White)
                                    Text("Import followers", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                        val followingImportModifier = Modifier.weight(1f).shadow(5.dp, importShape)
                            .background(colors.surface.copy(alpha = if (brandStyle.isDark) 0.9f else 0.96f), importShape)
                            .clip(importShape)
                        OutlinedButton(
                            onClick = { followingPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            modifier = followingImportModifier,
                            border = BorderStroke(1.5.dp, if (brandStyle.isDark) colors.primary else MirrorThemeColors.lightBrandInk),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = colors.onSurface),
                            shape = importShape,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                Image(
                                    painter = painterResource(R.drawable.brand_splatter_corner),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(width = 78.dp, height = 52.dp)
                                        .graphicsLayer { alpha = if (brandStyle.isDark) 0.8f else 0.66f },
                                )
                                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImportArrowIcon(colors.onSurface)
                                    Text("Import following", fontSize = 14.sp, color = colors.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { destination = DashboardDestination.IMPORT_MANAGER; selected = emptySet() }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                        Text("MANAGE IMPORTED DATA", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Select all split follower files together · ${statusMessage}", color = if (isErrorStatus(statusMessage)) colors.error else colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    destinations.forEach { item ->
                        val selectedTab = destination == item
                        val tabShape = RoundedCornerShape(8.dp)
                        val tabBackground = if (selectedTab) {
                            Brush.verticalGradient(listOf(colors.primary, MirrorThemeColors.lightBrandRedDeep))
                        } else {
                            Brush.linearGradient(listOf(colors.surface, colors.surfaceVariant))
                        }
                        val tabModifier = Modifier.widthIn(min = 104.dp, max = 160.dp)
                            .shadow(if (selectedTab) 5.dp else 3.dp, tabShape)
                            .background(tabBackground, tabShape)
                            .clip(tabShape)
                            .border(
                                1.dp,
                                if (selectedTab) colors.primary else if (brandStyle.isDark) MirrorThemeColors.darkOutline else colors.outlineVariant,
                                tabShape,
                            )
                        Box(
                            tabModifier
                                .selectable(
                                    selected = selectedTab,
                                    role = Role.Tab,
                                    onClick = { destination = item; selected = emptySet() },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(item.label, fontSize = 9.sp, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (selectedTab) colors.onPrimary else colors.onSurface, fontWeight = FontWeight.Bold, maxLines = 2) }
                    }
                }

                if (destination == DashboardDestination.HISTORY) {
                    HistoryScreen(snapshots = snapshots, onDelete = { snapshotToDelete = it })
                } else if (destination == DashboardDestination.IMPORT_MANAGER) {
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
                } else if (destination == DashboardDestination.REVIEW_MANAGER) {
                    ReviewManagerScreen(
                        state = reviewState,
                        statusMessage = reviewStatusMessage,
                        onOpenProfile = ::openProfile,
                        onChangeStatus = { usernames, status -> applyReviewResult(reviewRepository.changeStatus(reviewState, usernames, status, System.currentTimeMillis())) },
                        onConfirmManual = { usernames -> applyReviewResult(reviewRepository.confirmManualCompletion(reviewState, usernames, System.currentTimeMillis())) },
                        onRemoveQueue = { usernames -> applyReviewResult(reviewRepository.removeFromQueue(reviewState, usernames, System.currentTimeMillis())) },
                        onAddProtected = { usernames -> applyReviewResult(reviewRepository.addProtected(reviewState, usernames, System.currentTimeMillis())) },
                        onRemoveProtected = { usernames -> applyReviewResult(reviewRepository.removeProtected(reviewState, usernames)) },
                        onUpdateNotes = { username, notes -> applyReviewResult(reviewRepository.updateNotes(reviewState, username, notes)) },
                    )
                } else if (destination == DashboardDestination.SETTINGS) {
                    SettingsScreen(themeMode = themeMode, onThemeModeChanged = ::updateThemeMode)
                } else if (destination == DashboardDestination.ABOUT) {
                    AboutScreen()
                } else {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⌕", color = colors.onSurfaceVariant, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (search.isEmpty()) Text("Search usernames...", color = colors.onSurfaceVariant, fontSize = 14.sp)
                            BasicTextField(value = search, onValueChange = { search = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                        }
                        Text("${visible.size}", color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected.isEmpty()) "SELECT ACCOUNTS TO REVIEW" else "${selected.size} SELECTED", color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = if (selected.containsAll(visible) && visible.isNotEmpty()) emptySet() else visible.toSet() }) {
                            Text(if (selected.containsAll(visible) && visible.isNotEmpty()) "CLEAR" else "SELECT ALL", color = colors.primary, fontSize = 11.sp)
                        }
                    }
                    val requiredDataMissing = when (destination) {
                        DashboardDestination.FOLLOWERS -> !followersLoaded
                        DashboardDestination.FOLLOWING -> !followingLoaded
                        else -> !followersLoaded || !followingLoaded
                    }
                    if (requiredDataMissing) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("YOUR FOLLOW LIST. YOUR CALL.", color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(8.dp))
                            Text("Import followers and following JSON files from your Instagram data download. Choose every followers_*.json file together, then import following.json separately. Comparisons happen on this device.", color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    } else if (visible.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (destination == DashboardDestination.DETECTED_CHANGES && snapshots.isEmpty()) "Save a follower snapshot, then import a newer list to compare." else "No accounts in this list.", color = colors.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(visible, key = { it }) { username ->
                                Row(Modifier.fillMaxWidth().clickable { selected = if (username in selected) selected - username else selected + username }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = username in selected, onCheckedChange = { checked -> selected = if (checked) selected + username else selected - username }, colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.outline))
                                    Box(Modifier.size(38.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(username.take(1).uppercase(), color = colors.primary, fontWeight = FontWeight.Black, fontSize = 17.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(username, color = colors.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when (destination) {
                                            DashboardDestination.DETECTED_CHANGES -> "Absent from the latest follower export"
                                            DashboardDestination.MUTUALS -> "Follows you back"
                                            DashboardDestination.NOT_FOLLOWING_BACK -> "Doesn't follow you back"
                                            else -> "Instagram account"
                                        }, color = colors.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { openProfile(username) }) { Text("OPEN ↗", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                }
                                HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
                            }
                        }
                    }
                }

                if (destination != DashboardDestination.SETTINGS && destination != DashboardDestination.ABOUT && destination != DashboardDestination.REVIEW_MANAGER) {
                    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (destination != DashboardDestination.HISTORY && destination != DashboardDestination.IMPORT_MANAGER) {
                                Button(onClick = {
                                    if (destination == DashboardDestination.NOT_FOLLOWING_BACK) {
                                        if (selected.isEmpty()) toast("Select at least one account first.")
                                        else {
                                            applyReviewResult(reviewRepository.addToQueue(reviewState, selected, System.currentTimeMillis()))
                                            selected = emptySet()
                                            destination = DashboardDestination.REVIEW_MANAGER
                                        }
                                    } else {
                                        selected.sorted().firstOrNull()?.let { openProfile(it) }
                                        if (selected.isEmpty()) toast("Select at least one account first.")
                                    }
                                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary), shape = RoundedCornerShape(10.dp)) {
                                    Text(if (destination == DashboardDestination.NOT_FOLLOWING_BACK) "ADD TO REVIEW QUEUE" else "REVIEW SELECTED", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (destination != DashboardDestination.REVIEW_MANAGER) {
                                OutlinedButton(onClick = { saveSnapshot() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("SAVE SNAPSHOT", fontSize = 11.sp, color = colors.onSurface) }
                            }
                        }
                        Text("Manual review only · No password · No auto-unfollow", color = colors.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                    }
                }
            }

            }
            snapshotToDelete?.let { snapshot ->
                AlertDialog(
                    onDismissRequest = { snapshotToDelete = null },
                    title = { Text("Delete snapshot?") },
                    text = { Text("This removes the saved follower snapshot from this device.") },
                    confirmButton = {
                        TextButton(onClick = { deleteSnapshot(snapshot.date); snapshotToDelete = null }) { Text("DELETE", color = colors.error) }
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
                    DataAction.RESET_ALL -> "This removes both imported datasets, saved follower snapshots, and Review Manager queue, protection, and history from this device. Theme and other app preferences are preserved. This cannot be undone."
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
                        }) { Text(if (action == DataAction.RESET_ALL) "RESET ALL" else "DELETE", color = colors.error) }
                    },
                    dismissButton = { TextButton(onClick = { pendingDataAction = null }) { Text("CANCEL") } },
                )
            }
        }
    }

    @Composable private fun SettingsScreen(themeMode: ThemeMode, onThemeModeChanged: (ThemeMode) -> Unit) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("SETTINGS", color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("Choose how Follower Mirror looks on this device.", color = colors.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Text("APPEARANCE", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.surface, RoundedCornerShape(12.dp))
                    .selectableGroup(),
            ) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = themeMode == mode,
                            role = Role.RadioButton,
                            onClick = { onThemeModeChanged(mode) },
                        ).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.primary,
                                unselectedColor = colors.outline,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(if (mode == ThemeMode.DARK) "Dark mode" else "Light mode", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                            Text(if (mode == ThemeMode.DARK) "Default appearance" else "Bright appearance", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your appearance preference is saved on this device. Imported datasets, history, and review preferences are kept separately.", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }

    @Composable private fun AboutScreen() {
        val colors = MaterialTheme.colorScheme
        val brandStyle = dashboardBrandStyle(themeMode)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(brandStyle.artworkResource),
                contentDescription = "LGIII portrait, crown and lettering artwork",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(if (brandStyle.isDark) 360.dp else 330.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text("FOLLOWER MIRROR", color = colors.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Box(
                Modifier.padding(top = 4.dp, bottom = 6.dp).width(236.dp).height(3.dp)
                    .background(
                        Brush.horizontalGradient(listOf(MirrorThemeColors.lightBrandInk, colors.primary, colors.primary.copy(alpha = 0.08f))),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Text("An LGIII personal utility", color = colors.primary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Follower Mirror compares Instagram data exports locally on this device. It does not ask for your Instagram password, connect to Instagram, upload your follower data, or automate account actions.",
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 23.sp,
            )
        }
    }

    @Composable private fun HistoryScreen(snapshots: List<Snapshot>, onDelete: (Snapshot) -> Unit) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                "Changes show accounts missing between exports. They are not confirmed unfollows: deletions, suspensions, username changes, export timing, or incomplete files can also explain a disappearance.",
                color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (snapshots.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No saved snapshots yet. Import followers and save a snapshot to start tracking.", color = colors.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshots, key = { it.date }) { snapshot ->
                        val index = snapshots.indexOf(snapshot)
                        val prior = snapshots.getOrNull(index + 1)
                        val disappeared = prior?.let { FollowerData.detectedDisappearances(it.followers, snapshot.followers).size }
                        Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snapshot.date.toDateLabel(), color = colors.onSurface, fontWeight = FontWeight.Bold)
                                Text("${snapshot.followers.size} followers", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                Text(disappeared?.let { "$it detected disappearances since the previous snapshot" } ?: "Baseline snapshot", color = colors.primary, fontSize = 11.sp)
                            }
                            TextButton(onClick = { onDelete(snapshot) }) { Text("DELETE", color = colors.error, fontSize = 11.sp) }
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
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Each dataset is managed independently. A replacement is checked before it is saved; a rejected import leaves the current dataset unchanged.", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
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
                Text(statusMessage, color = if (isErrorStatus(statusMessage)) colors.error else colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            Button(
                onClick = onResetAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                shape = RoundedCornerShape(10.dp),
            ) { Text("RESET ALL DATA", color = colors.onError, fontWeight = FontWeight.Bold) }
        }
    }

    @Composable private fun DatasetManagerCard(
        title: String,
        dataset: SavedDataset?,
        onReplace: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text(title.uppercase(Locale.getDefault()), color = colors.onSurface, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    dataset == null -> "Status: No dataset imported"
                    dataset.validated -> "Status: Imported and validated"
                    else -> "Status: Older import; category was not validated. Replace to verify it."
                },
                color = if (dataset?.validated == false) colors.error else colors.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text("Unique accounts: ${dataset?.accounts?.size ?: 0}", color = colors.onSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Imported: ${dataset?.importedAt?.toDateLabel() ?: if (dataset == null) "—" else "Date unavailable (older import)"}", color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            val sources = dataset?.sourceFiles?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: if (dataset == null) "—" else "Filename unavailable (older import)"
            Text("Source: $sources", color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onReplace, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary), shape = RoundedCornerShape(8.dp)) {
                    Text("REPLACE DATASET", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onDelete, enabled = dataset != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Text("DELETE DATASET", fontSize = 10.sp, color = if (dataset != null) colors.onSurface else colors.onSurfaceVariant)
                }
            }
        }
    }

    @Composable private fun StatCard(
        label: String,
        value: String,
        modifier: Modifier = Modifier,
        valueColor: Color? = null,
        themeMode: ThemeMode,
    ) {
        val colors = MaterialTheme.colorScheme
        val isDark = themeMode == ThemeMode.DARK
        val cardShape = RoundedCornerShape(14.dp)
        val cardModifier = if (isDark) {
            modifier.shadow(6.dp, cardShape)
                .background(MirrorThemeColors.darkCard, cardShape)
                .clip(cardShape)
                .border(1.dp, MirrorThemeColors.darkOutline, cardShape)
        } else {
            modifier.shadow(8.dp, cardShape)
                .background(Brush.linearGradient(listOf(colors.surface, MirrorThemeColors.lightCardHighlight)), cardShape)
                .clip(cardShape)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.65f), cardShape)
        }
        Box(cardModifier) {
            Image(
                painter = painterResource(R.drawable.brand_splatter_corner),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomEnd).size(width = 62.dp, height = 42.dp)
                    .graphicsLayer { alpha = if (isDark) 0.82f else 0.72f },
            )
            Column(Modifier.padding(12.dp)) {
                Text(label, color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(value, color = valueColor ?: colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
        }
    }

    @Composable private fun ImportArrowIcon(color: Color) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = size.minDimension * 0.095f
            val arrowX = size.width * 0.5f
            drawLine(color, Offset(arrowX, size.height * 0.08f), Offset(arrowX, size.height * 0.62f), stroke)
            drawLine(color, Offset(arrowX, size.height * 0.08f), Offset(size.width * 0.28f, size.height * 0.30f), stroke)
            drawLine(color, Offset(arrowX, size.height * 0.08f), Offset(size.width * 0.72f, size.height * 0.30f), stroke)
            drawLine(color, Offset(size.width * 0.18f, size.height * 0.52f), Offset(size.width * 0.18f, size.height * 0.84f), stroke)
            drawLine(color, Offset(size.width * 0.82f, size.height * 0.52f), Offset(size.width * 0.82f, size.height * 0.84f), stroke)
            drawLine(color, Offset(size.width * 0.18f, size.height * 0.84f), Offset(size.width * 0.82f, size.height * 0.84f), stroke)
        }
    }

    @Composable private fun CrownMark(modifier: Modifier = Modifier) {
        val original = ImageBitmap.imageResource(R.drawable.lgiii_brand)
        val crownCrop = remember(original) {
            BitmapPainter(
                image = original,
                srcOffset = IntOffset(280, 84),
                srcSize = IntSize(228, 160),
            )
        }
        Image(
            painter = crownCrop,
            contentDescription = "Crown detail from the original LGIII artwork",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
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
            .remove(REVIEW_MANAGER_STORE)
            .commit()
        if (success) {
            followerDataset = null
            followingDataset = null
            snapshots = emptyList()
            reviewState = ReviewState()
            reviewStatusMessage = "Review queue and history were cleared."
            statusMessage = "Imported datasets, follower snapshots, and Review Manager data were cleared. Other app preferences were preserved."
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

    private fun applyReviewResult(result: ReviewResult) {
        if (result.success) reviewState = result.state
        reviewStatusMessage = result.message
        toast(result.message)
    }

    private fun updateThemeMode(mode: ThemeMode) {
        if (themeMode == mode) return
        themePreference.save(mode)
        themeMode = mode
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(backgroundColor: Int, mode: ThemeMode) {
        // Android 15 enforces edge-to-edge for target 35; the Compose root paints behind the bars.
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = backgroundColor
            window.navigationBarColor = backgroundColor
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = mode == ThemeMode.LIGHT
            isAppearanceLightNavigationBars = mode == ThemeMode.LIGHT
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun setToJson(set: Set<String>) = JSONArray(set.sorted()).toString()
    private fun jsonArrayToSet(array: JSONArray): Set<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
}

private fun Long.toDateLabel(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
