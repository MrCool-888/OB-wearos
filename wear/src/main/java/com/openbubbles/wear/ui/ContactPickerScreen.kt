package com.openbubbles.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import com.openbubbles.wear.WatchRepository
import com.openbubbles.wear.WatchState
import com.openbubbles.wear.protocol.WatchContact

@Composable
fun ContactPickerScreen(state: WatchState, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var query by remember { mutableStateOf(state.contactsQuery) }
    var selected by remember { mutableStateOf<WatchContact?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    LaunchedEffect(state.phoneOnline) { if (state.phoneOnline) WatchRepository.requestContacts(query) }
    LaunchedEffect(state.contactsAccess) { if (state.contactsAccess == false) selected = null }
    fun choose(contact: WatchContact, address: String) {
        WatchRepository.findChatByAddress(address)?.let(onOpenChat)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("contact_search")?.toString()?.let {
                query = it.trim().take(80)
                if (state.phoneOnline) WatchRepository.requestContacts(query)
            }
        }
    }
    Box(Modifier.fillMaxSize().swipeToDismiss { if (selected != null) selected = null else onBack() }) {
        AppScaffold(timeText = { TimeText() }) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 30.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { TextButton(onClick = { if (selected != null) selected = null else onBack() }) { Text("Back") } }
                item { Text(selected?.name ?: "Choose a contact", style = MaterialTheme.typography.titleSmall) }
                val error = inputError ?: state.contactsError ?: state.error
                if (error != null) item { NoticeCard(message = error, isError = true) }
                val contact = selected
                if (contact != null) {
                    item { Text("Choose the number or email to open its saved OpenBubbles chat.", style = MaterialTheme.typography.bodyExtraSmall) }
                    items(contact.addresses, key = { it.address }) { address ->
                        Card(onClick = { choose(contact, address.address) }, modifier = Modifier.fillMaxWidth()) {
                            Text(address.label.ifBlank { "Message" }, style = MaterialTheme.typography.labelSmall)
                            Text(address.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    item { Text("Phone address book used by OpenBubbles. Only contacts with an existing saved chat can be opened.", style = MaterialTheme.typography.bodyExtraSmall) }
                    if (state.contactsAccess == false) item {
                        NoticeCard(message = "On the phone, open the bridge and tap Allow contact picker. Recent chats and groups work without Contacts permission.", isError = false)
                    }
                    item {
                        Button(onClick = {
                            inputError = null
                            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("contact_search").setLabel("Name, number or email").build()))
                            RemoteInputIntentHelper.putTitleExtra(intent, "Find a contact")
                            runCatching { launcher.launch(intent) }.onFailure { inputError = "Search input is unavailable on this watch." }
                        }, enabled = !state.contactsLoading, modifier = Modifier.fillMaxWidth()) { Text("Search contacts") }
                    }
                    if (query.isNotBlank()) item {
                        TextButton(onClick = { query = ""; WatchRepository.requestContacts() }) { Text("Clear search: $query", maxLines = 2) }
                    }
                    item {
                        TextButton(onClick = { WatchRepository.requestContacts(query) }, enabled = !state.contactsLoading) {
                            Text(if (state.contactsLoading) "Loading contacts…" else "Refresh from phone")
                        }
                    }
                    val matches = state.contacts.filter { contact ->
                        query == state.contactsQuery || query.isBlank() || contact.name.contains(query, ignoreCase = true) || contact.addresses.any { it.address.contains(query, ignoreCase = true) }
                    }
                    items(matches, key = { it.id }) { entry ->
                        Card(onClick = {
                            if (entry.addresses.size == 1) choose(entry, entry.addresses.single().address) else selected = entry
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                ContactAvatar(state, entry.avatarId, initialsFor(entry.name), size = 32.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (entry.addresses.size == 1) entry.addresses.single().address else "${entry.addresses.size} numbers / emails",
                                        style = MaterialTheme.typography.bodyExtraSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (matches.isEmpty() && !state.contactsLoading) item {
                        Text(if (query.isBlank()) "No contacts loaded yet. Allow Contacts on the phone, then refresh."
                            else "No matching contacts. Try another name, phone number or email.", style = MaterialTheme.typography.bodyExtraSmall)
                    }
                    if (state.contactsNextOffset != null && query == state.contactsQuery) item {
                        Button(onClick = { WatchRepository.requestContacts(query, more = true) }, enabled = state.phoneOnline && !state.contactsLoading) { Text("Load more contacts") }
                    }
                }
            }
        }
    }
}
