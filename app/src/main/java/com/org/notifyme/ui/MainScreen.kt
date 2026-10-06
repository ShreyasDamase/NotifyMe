package com.org.notifyme.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.org.notifyme.StockPingApp
import com.org.notifyme.data.StockStatus
import com.org.notifyme.data.WatchedProduct
import com.org.notifyme.watch.WatchService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val productsLoaded by vm.productsLoaded.collectAsStateWithLifecycle()
    val shimmerProgress = if (!productsLoaded) {
        val transition = rememberInfiniteTransition(label = "product-shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer-progress",
        )
    } else null
    val ui by vm.ui.collectAsStateWithLifecycle()
    val watchState by vm.watchState.collectAsStateWithLifecycle()
    val pausedBanner by vm.pausedBanner.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notifier = remember(ctx) { (ctx.applicationContext as StockPingApp).container.notifier }
    var dndAccess by remember { mutableStateOf(notifier.hasDndAccess()) }
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var selectedHours by remember { mutableStateOf(2) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Long)
            vm.messageShown()
        }
    }

    DisposableEffect(lifecycleOwner, notifier) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dndAccess = notifier.hasDndAccess()
                // Recreate the channel after special access is granted so Android can apply DND bypass.
                notifier.ensureChannels()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StockPing") },
                actions = {
                    // Debug-only test alert button (Item 6)
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
        val openAlertSettings = {
            val action = if (!dndAccess) Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                else Settings.ACTION_APP_NOTIFICATION_SETTINGS
            val settings = Intent(action).apply {
                if (dndAccess) putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            }
            ctx.startActivity(settings)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.checking) {
                item(key = "checking") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            pausedBanner?.let { bannerText ->
                item(key = "paused-banner") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = bannerText,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            item(key = "urgent-controls") {
                UrgentWatchCard(
                    isRunning = WatchService.isRunning || watchState != null,
                    selectedHours = selectedHours,
                    dndAccess = dndAccess,
                    onSelectHours = { selectedHours = it },
                    onAlertSettings = openAlertSettings,
                    onStart = { vm.startUrgentWatch(ctx, selectedHours) },
                    onStop = { vm.stopUrgentWatch(ctx) },
                )
            }

            when {
                !productsLoaded -> items(5, key = { "loading-$it" }) {
                    ShimmerProductCard(shimmerProgress!!)
                }
                products.isEmpty() -> item(key = "empty-products") {
                    EmptyProductsCard(onAddExample = {
                        vm.add("https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/")
                    })
                }
                else -> items(products, key = { "product-${it.id}" }) { p -> ProductCard(p, vm) }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onAdd = { vm.add(it); showAdd = false })
}

@Composable
private fun UrgentWatchCard(
    isRunning: Boolean,
    selectedHours: Int,
    dndAccess: Boolean,
    onSelectHours: (Int) -> Unit,
    onAlertSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Urgent mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (dndAccess) "DND bypass on · alarm + long vibration" else "DND bypass not set up",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dndAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onAlertSettings, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (dndAccess) "Alert settings" else "Set up alerts")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1, 2, 4, 8).forEach { hours ->
                    FilterChip(
                        selected = selectedHours == hours,
                        onClick = { onSelectHours(hours) },
                        enabled = !isRunning,
                        label = { Text("${hours}h") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isRunning) "Watching in background" else "Fast stock checks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isRunning) {
                    OutlinedButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onStart, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                        Text("Start · ${selectedHours}h")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProductsCard(onAddExample: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No products watched yet.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAddExample) { Text("Add Robu test product") }
        }
    }
}

@Composable
private fun ShimmerProductCard(progress: State<Float>) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
    val shape = RoundedCornerShape(8.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShimmerBlock(Modifier.size(64.dp), progress, base, highlight, RoundedCornerShape(12.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBlock(Modifier.fillMaxWidth(0.92f).height(16.dp), progress, base, highlight, shape)
                ShimmerBlock(Modifier.fillMaxWidth(0.7f).height(14.dp), progress, base, highlight, shape)
                ShimmerBlock(Modifier.width(104.dp).height(24.dp), progress, base, highlight, RoundedCornerShape(50))
                ShimmerBlock(Modifier.width(132.dp).height(11.dp), progress, base, highlight, shape)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ShimmerBlock(Modifier.width(42.dp).height(24.dp), progress, base, highlight, RoundedCornerShape(50))
                ShimmerBlock(Modifier.size(20.dp), progress, base, highlight, RoundedCornerShape(4.dp))
            }
        }
    }
}

@Composable
private fun ShimmerBlock(
    modifier: Modifier,
    progress: State<Float>,
    base: androidx.compose.ui.graphics.Color,
    highlight: androidx.compose.ui.graphics.Color,
    shape: Shape,
) {
    val shimmer = remember(highlight) {
        Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, highlight, androidx.compose.ui.graphics.Color.Transparent))
    }
    BoxWithConstraints(modifier.clip(shape).background(base)) {
        val width = maxWidth
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.65f)
                .graphicsLayer {
                    translationX = width.toPx() * progress.value - size.width
                }
                .background(shimmer),
        )
    }
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
