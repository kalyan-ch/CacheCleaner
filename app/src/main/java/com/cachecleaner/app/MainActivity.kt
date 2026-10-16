package com.cachecleaner.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var apps by mutableStateOf<List<AppCache>>(emptyList()); private set
    var scanned by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var usageAccess by mutableStateOf(false); private set
    var shizuku by mutableStateOf(ShizukuState.NOT_RUNNING); private set
    var message by mutableStateOf<String?>(null)

    val totalBytes: Long get() = apps.sumOf { it.cacheBytes }

    fun refreshPermissions() {
        usageAccess = Permissions.hasUsageAccess(ctx)
        shizuku = Permissions.shizukuState()
        if (usageAccess && !scanned && !busy) scan()
    }

    fun scan() {
        if (busy || !usageAccess) return
        viewModelScope.launch {
            busy = true
            apps = CacheScanner.scan(ctx)
            scanned = true
            busy = false
        }
    }

    fun clean() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            val before = totalBytes
            message = try {
                ShizukuRunner.trimCaches(ctx)
                if (usageAccess) {
                    delay(500)
                    apps = CacheScanner.scan(ctx)
                    val freed = (before - totalBytes).coerceAtLeast(0)
                    "Freed ${Formatter.formatShortFileSize(ctx, freed)}"
                } else {
                    "Caches cleared"
                }
            } catch (e: Exception) {
                "Couldn't clean: ${e.message}"
            }
            busy = false
        }
    }
}

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val onBinder = Shizuku.OnBinderReceivedListener { runOnUiThread { vm.refreshPermissions() } }
    private val onDead = Shizuku.OnBinderDeadListener { runOnUiThread { vm.refreshPermissions() } }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { vm.refreshPermissions() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)

        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                CleanerScreen(
                    vm = vm,
                    onOpenUsageAccess = ::openUsageAccess,
                    onShizukuAction = ::handleShizuku,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        super.onDestroy()
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun handleShizuku() {
        when (vm.shizuku) {
            ShizukuState.NO_PERMISSION -> Shizuku.requestPermission(SHIZUKU_REQUEST)
            ShizukuState.NOT_RUNNING -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
                startActivity(
                    launch ?: Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG"),
                    ),
                )
            }
            ShizukuState.READY -> Unit
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST = 42
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    }
}

@Composable
fun CleanerScreen(
    vm: MainViewModel,
    onOpenUsageAccess: () -> Unit,
    onShizukuAction: () -> Unit,
) {
    val ctx = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::clean,
                    enabled = vm.shizuku == ShizukuState.READY && !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .height(56.dp),
                ) {
                    Text(if (vm.busy) "Working…" else "Clean all caches")
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Cache Cleaner",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (vm.usageAccess) {
                            TextButton(onClick = vm::scan, enabled = !vm.busy) { Text("Rescan") }
                        }
                    }
                    Text(
                        if (vm.usageAccess) size(vm.totalBytes) else "—",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (vm.usageAccess) "cached across ${vm.apps.size} apps"
                        else "Grant usage access to see cache sizes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (vm.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                }
            }

            if (!vm.usageAccess) {
                item {
                    SetupCard(
                        title = "Usage access",
                        body = "Lets the app read how much cache each app is using.",
                        action = "Grant",
                        onClick = onOpenUsageAccess,
                    )
                }
            }

            when (vm.shizuku) {
                ShizukuState.NOT_RUNNING -> item {
                    SetupCard(
                        title = "Shizuku isn't running",
                        body = "Start Shizuku (via Wireless debugging). It's what lets this app clear other apps' caches.",
                        action = "Open Shizuku",
                        onClick = onShizukuAction,
                    )
                }
                ShizukuState.NO_PERMISSION -> item {
                    SetupCard(
                        title = "Allow Shizuku access",
                        body = "Shizuku is running. Give Cache Cleaner permission to use it.",
                        action = "Allow",
                        onClick = onShizukuAction,
                    )
                }
                ShizukuState.READY -> Unit
            }

            items(vm.apps, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            app.packageName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = { Text(size(app.cacheBytes), style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 12.dp)) { Text(action) }
        }
    }
}
