package com.lgiii.followermirror

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ReviewManagerScreen(
    state: ReviewState,
    statusMessage: String,
    onOpenProfile: (String) -> Unit,
    onChangeStatus: (Collection<String>, ReviewStatus) -> Unit,
    onConfirmManual: (Collection<String>) -> Unit,
    onRemoveQueue: (Collection<String>) -> Unit,
    onAddProtected: (Collection<String>) -> Unit,
    onRemoveProtected: (Collection<String>) -> Unit,
    onUpdateNotes: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var section by remember { mutableStateOf(ReviewSection.QUEUE) }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<ReviewStatus?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var addProtectedDialog by remember { mutableStateOf(false) }
    var protectedInput by remember { mutableStateOf("") }
    var editingNotes by remember { mutableStateOf<ReviewEntry?>(null) }
    var notesInput by remember { mutableStateOf("") }
    var manualConfirm by remember { mutableStateOf<Set<String>?>(null) }
    var removeConfirm by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(state.queue.keys, section, query, statusFilter) {
        selected = selected intersect state.queue.keys
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReviewSection.entries.forEach { item ->
                val isSelected = section == item
                val buttonColors = if (isSelected) ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                    else ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)
                if (isSelected) {
                    Button(onClick = { section = item; selected = emptySet() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp), colors = buttonColors) {
                        Text("${item.label} (${when (item) { ReviewSection.QUEUE -> state.queue.size; ReviewSection.PROTECTED -> state.protected.size; ReviewSection.HISTORY -> state.history.size }})", fontSize = 9.sp, maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = { section = item; selected = emptySet() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp), colors = buttonColors) {
                        Text("${item.label} (${when (item) { ReviewSection.QUEUE -> state.queue.size; ReviewSection.PROTECTED -> state.protected.size; ReviewSection.HISTORY -> state.history.size }})", fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ReviewSearch(query = query, onQueryChange = { query = it })
        Spacer(Modifier.height(6.dp))

        when (section) {
            ReviewSection.QUEUE -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected.isEmpty()) "REVIEW QUEUE" else "${selected.size} SELECTED", color = colors.primary, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val shown = state.queue.values.filter { entry ->
                            entry.username.contains(query.trim(), true) && (statusFilter == null || entry.status == statusFilter)
                        }.map { it.username }.toSet()
                        selected = if (shown.isNotEmpty() && selected.containsAll(shown)) selected - shown else selected + shown
                    }) { Text("SELECT VISIBLE", fontSize = 9.sp) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("All", fontSize = 10.sp) })
                    ReviewStatus.entries.forEach { status ->
                        FilterChip(selected = statusFilter == status, onClick = { statusFilter = if (statusFilter == status) null else status }, label = { Text(status.label, fontSize = 10.sp, maxLines = 1) })
                    }
                }
                val visible = state.queue.values.filter { entry ->
                    entry.username.contains(query.trim(), true) && (statusFilter == null || entry.status == statusFilter)
                }.sortedBy { it.username }
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (state.queue.isEmpty()) "Select accounts from NOT FOLLOWING BACK and add them to your review queue." else "No accounts match this search or filter.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(visible, key = { it.username }) { entry ->
                            ReviewQueueCard(
                                entry = entry,
                                isProtected = entry.username in state.protected,
                                selected = entry.username in selected,
                                onSelect = { checked -> selected = if (checked) selected + entry.username else selected - entry.username },
                                onOpen = { onOpenProfile(entry.username) },
                                onKeep = { onChangeStatus(listOf(entry.username), ReviewStatus.KEEP_FOLLOWING) },
                                onMark = { onChangeStatus(listOf(entry.username), ReviewStatus.MARKED_FOR_UNFOLLOW) },
                                onConfirmManual = { manualConfirm = setOf(entry.username) },
                                onProtect = { onAddProtected(listOf(entry.username)) },
                                onNotes = { editingNotes = entry; notesInput = entry.notes },
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onChangeStatus(selected, ReviewStatus.KEEP_FOLLOWING) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("KEEP SELECTED", fontSize = 9.sp) }
                            Button(onClick = { onChangeStatus(selected, ReviewStatus.MARKED_FOR_UNFOLLOW) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("MARK FOR UNFOLLOW", fontSize = 9.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onAddProtected(selected) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("PROTECT", fontSize = 9.sp) }
                            OutlinedButton(onClick = { removeConfirm = selected }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("REMOVE FROM QUEUE", fontSize = 9.sp) }
                            OutlinedButton(onClick = {
                                val eligible = selected.filter { state.queue[it]?.status == ReviewStatus.MARKED_FOR_UNFOLLOW && it !in state.protected }.toSet()
                                if (eligible.isNotEmpty()) manualConfirm = eligible
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("CONFIRM MANUAL", fontSize = 9.sp) }
                        }
                    }
                }
            }
            ReviewSection.PROTECTED -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("PROTECTED ACCOUNTS", color = colors.primary, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { protectedInput = ""; addProtectedDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text("ADD ACCOUNT", fontSize = 10.sp) }
                }
                val visible = state.protected.filter { it.contains(query.trim(), true) }.sorted()
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (state.protected.isEmpty()) "No protected accounts yet. Add usernames you never want marked for unfollow." else "No protected accounts match this search.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(visible, key = { it }) { username ->
                            Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("@$username", color = colors.onSurface, fontWeight = FontWeight.Bold)
                                    Text(if (username in state.queue) "Also in review queue · unfollow status blocked" else "Protected from unfollow review", color = colors.onSurfaceVariant, fontSize = 10.sp)
                                }
                                TextButton(onClick = { onRemoveProtected(listOf(username)) }) { Text("REMOVE PROTECTION", fontSize = 9.sp, color = colors.error) }
                            }
                        }
                    }
                }
            }
            ReviewSection.HISTORY -> {
                Text("REVIEW HISTORY · ${state.history.size} EVENTS", color = colors.primary, fontWeight = FontWeight.Black, fontSize = 12.sp)
                val visible = state.history.filter { it.username.contains(query.trim(), true) }
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No review events match this search.", color = colors.onSurfaceVariant, fontSize = 13.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(visible) { item ->
                            Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(9.dp)).padding(10.dp)) {
                                Text("@${item.username} · ${item.event.label}", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("${item.previousStatus?.label ?: "—"} → ${item.newStatus?.label ?: "Removed"}", color = colors.onSurfaceVariant, fontSize = 11.sp)
                                Text("${item.occurredAt.toReviewDate()} · ${item.scope.label}${if (item.manualCompletion) " · confirmed manually" else ""}", color = colors.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        if (statusMessage.isNotBlank()) Text(statusMessage, color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }

    if (addProtectedDialog) {
        AlertDialog(
            onDismissRequest = { addProtectedDialog = false },
            title = { Text("Protect accounts") },
            text = {
                Column {
                    Text("Enter one or more Instagram usernames separated by commas. Protection blocks unfollow status changes.", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = protectedInput, onValueChange = { protectedInput = it }, label = { Text("Usernames") }, singleLine = false)
                }
            },
            confirmButton = { TextButton(onClick = {
                onAddProtected(protectedInput.split(',', '\n', ' ', '\t').filter(String::isNotBlank))
                addProtectedDialog = false
            }) { Text("PROTECT") } },
            dismissButton = { TextButton(onClick = { addProtectedDialog = false }) { Text("CANCEL") } },
        )
    }
    editingNotes?.let { entry ->
        AlertDialog(
            onDismissRequest = { editingNotes = null },
            title = { Text("Notes for @${entry.username}") },
            text = { OutlinedTextField(value = notesInput, onValueChange = { notesInput = it }, label = { Text("Optional notes") }, minLines = 3) },
            confirmButton = { TextButton(onClick = { onUpdateNotes(entry.username, notesInput); editingNotes = null }) { Text("SAVE") } },
            dismissButton = { TextButton(onClick = { editingNotes = null }) { Text("CANCEL") } },
        )
    }
    manualConfirm?.let { usernames ->
        AlertDialog(
            onDismissRequest = { manualConfirm = null },
            title = { Text("Confirm manual completion?") },
            text = { Text("Only confirm after you manually unfollowed ${usernames.joinToString { "@$it" }} in Instagram. Follower Mirror will not perform Instagram actions.") },
            confirmButton = { TextButton(onClick = { onConfirmManual(usernames); manualConfirm = null }) { Text("I UNFOLLOWED MANUALLY") } },
            dismissButton = { TextButton(onClick = { manualConfirm = null }) { Text("CANCEL") } },
        )
    }
    removeConfirm?.let { usernames ->
        AlertDialog(
            onDismissRequest = { removeConfirm = null },
            title = { Text("Remove from review queue?") },
            text = { Text("This removes ${usernames.size} account(s) from the queue. Their review history will remain.") },
            confirmButton = { TextButton(onClick = { onRemoveQueue(usernames); removeConfirm = null }) { Text("REMOVE") } },
            dismissButton = { TextButton(onClick = { removeConfirm = null }) { Text("CANCEL") } },
        )
    }
}

private enum class ReviewSection(val label: String) { QUEUE("QUEUE"), PROTECTED("PROTECTED"), HISTORY("HISTORY") }

@Composable
private fun ReviewSearch(query: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕", color = colors.onSurfaceVariant, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search reviews, protected accounts, history...", color = colors.onSurfaceVariant, fontSize = 12.sp)
            BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReviewQueueCard(
    entry: ReviewEntry,
    isProtected: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onKeep: () -> Unit,
    onMark: () -> Unit,
    onConfirmManual: () -> Unit,
    onProtect: () -> Unit,
    onNotes: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(10.dp)).padding(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelect)
            Column(Modifier.weight(1f)) {
                Text("@${entry.username}", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Added ${entry.addedAt.toReviewDate()}", color = colors.onSurfaceVariant, fontSize = 9.sp)
            }
            Text(entry.status.label.uppercase(Locale.getDefault()), color = if (entry.status == ReviewStatus.MARKED_FOR_UNFOLLOW) colors.error else colors.primary, fontSize = 8.sp, fontWeight = FontWeight.Black, maxLines = 2, modifier = Modifier.widthIn(max = 112.dp))
        }
        if (isProtected) Text("PROTECTED · unfollow status blocked", color = colors.error, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 42.dp, top = 2.dp))
        if (entry.notes.isNotBlank()) Text(entry.notes, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 42.dp, top = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) { Text("OPEN PROFILE ↗", fontSize = 9.sp) }
            TextButton(onClick = onKeep) { Text("KEEP FOLLOWING", fontSize = 9.sp) }
            TextButton(onClick = onMark, enabled = !isProtected) { Text("MARK UNFOLLOW", fontSize = 9.sp, color = if (isProtected) colors.onSurfaceVariant else colors.error) }
            TextButton(onClick = onNotes) { Text(if (entry.notes.isBlank()) "ADD NOTES" else "EDIT NOTES", fontSize = 9.sp) }
            if (isProtected) TextButton(onClick = onConfirmManual, enabled = false) { Text("PROTECTED", fontSize = 9.sp) }
            else if (entry.status == ReviewStatus.MARKED_FOR_UNFOLLOW) TextButton(onClick = onConfirmManual) { Text("CONFIRM MANUAL", fontSize = 9.sp, color = colors.error) }
            else TextButton(onClick = onProtect) { Text("PROTECT", fontSize = 9.sp) }
        }
    }
}

private fun Long.toReviewDate(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
