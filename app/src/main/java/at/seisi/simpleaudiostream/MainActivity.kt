package at.seisi.simpleaudiostream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import at.seisi.simpleaudiostream.log.LogExport
import at.seisi.simpleaudiostream.upnp.Renderer
import at.seisi.simpleaudiostream.upnp.SsdpDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMissingPermissions()
        setContent { AppTheme { MainScreen() } }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val status by CastState.status.collectAsState()
    val volume by CastState.volume.collectAsState()
    val active = status is CastState.Status.Connecting || status is CastState.Status.Casting

    var selected by remember { mutableStateOf(prefs.renderer) }
    var renderers by remember { mutableStateOf(emptyList<Renderer>()) }
    var scanning by remember { mutableStateOf(false) }
    var captureApp by remember { mutableStateOf(prefs.captureApp) }
    var pickingApp by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var batteryRestricted by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }

    fun select(renderer: Renderer) {
        prefs.renderer = renderer
        selected = renderer
        CastTileService.refresh(context)
    }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true
            val found = SsdpDiscovery.discover(context)
            renderers = found
            // Keep the saved speaker's endpoints fresh in case its IP changed.
            selected?.let { saved -> found.firstOrNull { it.udn == saved.udn }?.let(::select) }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_log)) },
                            onClick = {
                                menuOpen = false
                                scope.launch { LogExport.share(context) }
                            },
                        )
                    }
                }
            }
            Text(
                text = when (val s = status) {
                    CastState.Status.Idle -> stringResource(R.string.status_idle)
                    is CastState.Status.Connecting -> stringResource(R.string.status_connecting, s.speaker)
                    is CastState.Status.Casting -> stringResource(R.string.status_casting, s.speaker)
                    is CastState.Status.Error -> stringResource(R.string.status_error, s.message)
                },
                color = if (status is CastState.Status.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Button(
                onClick = {
                    if (active) {
                        CastService.stop(context)
                    } else {
                        context.startActivity(Intent(context, ConsentActivity::class.java))
                    }
                },
                enabled = active || selected != null,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text(
                    when {
                        active -> stringResource(R.string.button_stop)
                        selected != null -> stringResource(R.string.button_cast, selected!!.name)
                        else -> stringResource(R.string.button_pick_speaker)
                    },
                )
            }

            volume?.let { current ->
                var position by remember(current) { mutableFloatStateOf(current.toFloat()) }
                Text(stringResource(R.string.volume, position.roundToInt()))
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    onValueChangeFinished = { CastService.setVolume(context, position.roundToInt()) },
                    valueRange = 0f..100f,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.audio_from), modifier = Modifier.weight(1f))
                TextButton(onClick = { pickingApp = true }) {
                    Text(captureApp?.let { appLabel(context, it) } ?: stringResource(R.string.all_apps))
                }
            }

            if (pickingApp) {
                AppPickerDialog(
                    selected = captureApp,
                    onPick = { pkg ->
                        prefs.captureApp = pkg
                        captureApp = pkg
                        pickingApp = false
                    },
                    onDismiss = { pickingApp = false },
                )
            }

            if (batteryRestricted) {
                TextButton(onClick = {
                    requestIgnoreBatteryOptimizations(context)
                    batteryRestricted = false
                }) { Text(stringResource(R.string.battery_hint)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.speakers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = ::scan) { Text(stringResource(R.string.refresh)) }
                }
            }

            if (renderers.isEmpty() && !scanning) {
                Text(stringResource(R.string.no_speakers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(renderers, key = { it.udn }) { renderer ->
                    ListItem(
                        headlineContent = { Text(renderer.name) },
                        supportingContent = { Text(renderer.model) },
                        leadingContent = { RadioButton(selected = renderer.udn == selected?.udn, onClick = null) },
                        modifier = Modifier.clickable { select(renderer) },
                    )
                }
            }
        }
    }
}

private data class AppEntry(val packageName: String, val label: String)

/** Lets the user cast only one app's audio (e.g. YouTube Music) instead of everything the phone plays. */
@Composable
private fun AppPickerDialog(selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.audio_from)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.audio_from_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = apps
                if (list == null) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        item { AppRow(stringResource(R.string.all_apps), selected == null) { onPick(null) } }
                        items(list, key = { it.packageName }) { app ->
                            AppRow(app.label, app.packageName == selected) { onPick(app.packageName) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AppRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = checked, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
}

private fun appLabel(context: Context, packageName: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}
