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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import at.seisi.simpleaudiostream.log.LogExport
import at.seisi.simpleaudiostream.upnp.Renderer
import at.seisi.simpleaudiostream.upnp.SsdpDiscovery
import kotlinx.coroutines.launch
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

// Brand colours, matching the launcher icon's indigo-to-violet gradient.
private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFFA855F7)
private val BrandGradient = Brush.linearGradient(listOf(Indigo, Violet))

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E3FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF1C1B22),
    onSurfaceVariant = Color(0xFF5E5B73),
    surfaceContainerLow = Color(0xFFF4F2FE),
    surfaceContainer = Color(0xFFEEEBFB),
    surfaceContainerHigh = Color(0xFFE8E5F8),
    surfaceContainerHighest = Color(0xFFE1DEF3),
    outlineVariant = Color(0xFFD9D6EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4B8FF),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3B348F),
    onPrimaryContainer = Color(0xFFE4E3FF),
    secondary = Color(0xFFC9B6FF),
    onSecondary = Color(0xFF2E1065),
    background = Color(0xFF111018),
    surface = Color(0xFF111018),
    onSurface = Color(0xFFE6E4F0),
    onSurfaceVariant = Color(0xFFADA9C3),
    surfaceContainerLow = Color(0xFF18171F),
    surfaceContainer = Color(0xFF1D1C25),
    surfaceContainerHigh = Color(0xFF27262F),
    surfaceContainerHighest = Color(0xFF32313B),
    outlineVariant = Color(0xFF3B3948),
)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
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

    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, contentColor = colors.onSurface, modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.6f), colors.surface))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
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

            CastHero(
                status = status,
                speaker = selected?.name,
                onClick = {
                    if (active) {
                        CastService.stop(context)
                    } else {
                        context.startActivity(Intent(context, ConsentActivity::class.java))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            AnimatedVisibility(
                visible = volume != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                VolumeCard(volume ?: 0) { CastService.setVolume(context, it) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)) {
                Text(
                    stringResource(R.string.speakers).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = ::scan) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (renderers.isEmpty() && !scanning) {
                    item {
                        Text(
                            stringResource(R.string.no_speakers),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(renderers, key = { it.udn }) { renderer ->
                    SpeakerCard(renderer, renderer.udn == selected?.udn) { select(renderer) }
                }
            }

            if (batteryRestricted) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            stringResource(R.string.battery_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            requestIgnoreBatteryOptimizations(context)
                            batteryRestricted = false
                        }) { Text(stringResource(R.string.allow)) }
                    }
                }
            }
        }
    }
    }
}

/** The big round cast button with the speaker name and status underneath. */
@Composable
private fun CastHero(status: CastState.Status, speaker: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val casting = status is CastState.Status.Casting
    val connecting = status is CastState.Status.Connecting
    val idleColor by animateColorAsState(
        if (casting) Color.Transparent else colors.primaryContainer, label = "button",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            if (casting || connecting) PulseRings(colors.primary)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(152.dp)
                    .clip(CircleShape)
                    .background(idleColor)
                    .then(if (casting) Modifier.background(BrandGradient) else Modifier),
            ) {
                Surface(
                    onClick = onClick,
                    enabled = speaker != null || casting || connecting,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (connecting) {
                            CircularProgressIndicator(
                                color = colors.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                            )
                        }
                        Icon(
                            painterResource(if (casting) R.drawable.ic_stop else R.drawable.ic_cast_audio),
                            contentDescription = stringResource(if (casting || connecting) R.string.button_stop else R.string.button_cast),
                            tint = if (casting) Color.White else colors.onPrimaryContainer,
                            modifier = Modifier.size(if (casting) 48.dp else 64.dp),
                        )
                    }
                }
            }
        }
        Text(
            speaker ?: stringResource(R.string.no_speaker),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when (status) {
                CastState.Status.Idle ->
                    stringResource(if (speaker == null) R.string.hint_pick_speaker else R.string.hint_tap_to_cast)
                is CastState.Status.Connecting -> stringResource(R.string.status_connecting)
                is CastState.Status.Casting -> stringResource(R.string.status_casting)
                is CastState.Status.Error -> status.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is CastState.Status.Error) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** Soft rings expanding from the button while casting. */
@Composable
private fun PulseRings(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
    )
    Canvas(Modifier.fillMaxSize()) {
        val inner = 76.dp.toPx()
        val outer = size.minDimension / 2
        for (i in 0 until 3) {
            val f = (progress + i / 3f) % 1f
            drawCircle(color.copy(alpha = 0.22f * (1f - f)), radius = inner + f * (outer - inner))
        }
    }
}

@Composable
private fun VolumeCard(volume: Int, onChange: (Int) -> Unit) {
    var position by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Icon(
                painterResource(R.drawable.ic_volume_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onChange(position.roundToInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Icon(
                painterResource(R.drawable.ic_volume_up),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                position.roundToInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

@Composable
private fun SpeakerCard(renderer: Renderer, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerLow, label = "card",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) BrandGradient else SolidColor(colors.surfaceContainerHighest)),
            ) {
                Icon(
                    painterResource(R.drawable.ic_speaker),
                    contentDescription = null,
                    tint = if (selected) Color.White else colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(renderer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (renderer.model.isNotEmpty()) {
                    Text(renderer.model, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = colors.primary)
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}
