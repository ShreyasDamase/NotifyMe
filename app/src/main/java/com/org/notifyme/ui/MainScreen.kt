package com.org.notifyme.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.org.notifyme.data.StockStatus
import com.org.notifyme.data.WatchedProduct
import com.org.notifyme.watch.WatchService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val watchState by vm.watchState.collectAsStateWithLifecycle()
    val pausedBanner by vm.pausedBanner.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var selectedHours by remember { mutableStateOf(2) }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StockPing") },
                actions = {
                    IconButton(onClick = { vm.testAlert() }) {
                        Icon(Icons.Default.NotificationsActive, "Test Alarm")
                    }
                    IconButton(onClick = { vm.checkNow() }, enabled = !ui.checking) {
                        Icon(Icons.Default.Refresh, "Check now")
                    }
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Icon(Icons.Default.BatterySaver, "Battery settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (ui.checking) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Paused Host Red Banner (Requirement 2)
            pausedBanner?.let { bannerText ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = bannerText,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // Urgent Watch Control Card (Requirement 5)
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Urgent Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = watchState ?: "Aggressive polling & alarm alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (watchState != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(1, 2, 4, 8).forEach { h ->
                                FilterChip(
                                    selected = selectedHours == h,
                                    onClick = { selectedHours = h },
                                    label = { Text("${h}h") },
                                    enabled = !WatchService.isRunning
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { vm.startUrgentWatch(ctx, selectedHours) },
                            enabled = !WatchService.isRunning
                        ) {
                            Text("Start (${selectedHours}h)")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { vm.stopUrgentWatch(ctx) },
                            enabled = WatchService.isRunning
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }

            if (products.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No products watched yet.", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            vm.add("https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/")
                        }) {
                            Text("Add Robu Test Product")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(products, key = { it.id }) { p -> ProductCard(p, vm) }
                }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onAdd = { vm.add(it); showAdd = false })
}

@Composable
private fun ProductCard(p: WatchedProduct, vm: MainViewModel) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, p.url.toUri()))
    }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = p.imageUrl, contentDescription = null,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                p.priceText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(4.dp))
                StatusChip(p.lastStatus)
                Text(
                    p.lastCheckedAt?.let { "Checked " + DateUtils.getRelativeTimeSpanString(it) } ?: "Not checked yet",
                    style = MaterialTheme.typography.labelSmall,
                )
                // Requirement 3: HostBusy must not set lastError or change lastStatus. Cards show nothing for it.
                p.lastError?.let {
                    if (!it.contains("Paused until")) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = p.enabled, onCheckedChange = { vm.toggle(p) })
                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.Delete, "Remove") }
            }
        }
    }
}

@Composable
private fun StatusChip(s: StockStatus) {
    val (label, color) = when (s) {
        StockStatus.IN_STOCK -> "In stock" to MaterialTheme.colorScheme.primaryContainer
        StockStatus.OUT_OF_STOCK -> "Out of stock" to MaterialTheme.colorScheme.errorContainer
        StockStatus.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch a product") },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("Product URL") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
