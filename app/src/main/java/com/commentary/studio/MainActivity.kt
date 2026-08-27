package com.commentary.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsState()
            AppTheme(dark = state.darkMode) {
                AppRoot(vm)
            }
        }
    }
}

private enum class Screen(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector
) {
    CONNECT("Connect", Icons.Filled.Hub, Icons.Outlined.Hub),
    ANALYZE("Analyze", Icons.Filled.Movie, Icons.Outlined.Movie),
    SCRIPT("Script", Icons.Filled.Description, Icons.Outlined.Description),
    TUNING("Tuning", Icons.Filled.Tune, Icons.Outlined.Tune),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.CONNECT) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        val message = state.toast
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.consumeToast()
        }
    }
    LaunchedEffect(Unit) { vm.refreshOnline() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Commentary Studio",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                AnimatedVisibility(visible = !state.online) {
                    Surface(color = MaterialTheme.colorScheme.error) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onError
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Offline. Every AI step is unavailable.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Screen.entries.forEach { s ->
                    val selected = screen == s
                    NavigationBarItem(
                        selected = selected,
                        onClick = { screen = s },
                        icon = {
                            Icon(
                                if (selected) s.selectedIcon else s.icon,
                                contentDescription = s.label
                            )
                        },
                        label = { Text(s.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 14 })
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "screen"
        ) { current ->
            when (current) {
                Screen.CONNECT -> ConnectScreen(vm) { screen = Screen.ANALYZE }
                Screen.ANALYZE -> AnalyzeScreen(vm) { screen = Screen.SCRIPT }
                Screen.SCRIPT -> ScriptScreen(vm)
                Screen.TUNING -> TuningScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

/* ================= SCREEN 1: CONNECT ================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectScreen(vm: AppViewModel, onContinue: () -> Unit) {
    val s by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        StepHeading(
            "STEP 1 OF 3",
            "Connect your AI",
            "Three separate jobs, three separate models. Use a cheap vision model to " +
                "describe frames and save your strongest model for the script."
        )
        Spacer(Modifier.height(20.dp))

        Role.entries.forEach { role ->
            RoleCard(vm, role)
            Spacer(Modifier.height(16.dp))
        }

        val visionReady = s.conn(Role.VISION).ready
        val scriptReady = s.conn(Role.SCRIPT).ready

        SectionCard {
            Text("Readiness", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            ReadyLine("Vision", visionReady, s.conn(Role.VISION).model)
            Spacer(Modifier.height(8.dp))
            ReadyLine("Transcribe", s.conn(Role.TRANSCRIBE).ready, s.conn(Role.TRANSCRIBE).model)
            Spacer(Modifier.height(8.dp))
            ReadyLine("Script", scriptReady, s.conn(Role.SCRIPT).model)
            Spacer(Modifier.height(18.dp))
            PrimaryAction("Continue", Icons.Filled.CheckCircle, enabled = visionReady) {
                onContinue()
            }
            if (!s.conn(Role.TRANSCRIBE).ready) {
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    Icons.Outlined.Info,
                    "Without a transcription model the analysis will be visuals only.",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ReadyLine(name: String, ready: Boolean, model: String) {
    StatusRow(
        if (ready) Icons.Filled.CheckCircle else Icons.Outlined.ErrorOutline,
        if (ready) "$name ready: $model" else "$name not configured",
        if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleCard(vm: AppViewModel, role: Role) {
    val s by vm.state.collectAsState()
    val conn = s.conn(role)
    val phase = s.modelPhase(role)
    val busy = phase is Phase.Busy
    val models = s.models(role)

    SectionCard {
        Text(role.label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            role.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        if (role == Role.TRANSCRIBE) {
            StatusRow(
                Icons.Outlined.Info,
                "Needs an OpenAI-compatible endpoint. Only whisper-1 returns " +
                    "word-level timestamps.",
                MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val kinds = if (role == Role.TRANSCRIBE) {
                listOf(ProviderKind.OPENAI)
            } else {
                ProviderKind.entries.toList()
            }
            kinds.forEach { kind ->
                FilterChip(
                    selected = conn.kind == kind,
                    onClick = { vm.setKind(role, kind) },
                    enabled = !busy,
                    label = {
                        Text(
                            kind.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = if (conn.kind == kind) {
                        {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    shape = MaterialTheme.shapes.small
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = conn.baseUrl,
            onValueChange = { vm.setBaseUrl(role, it) },
            label = { Text("Base URL") },
            supportingText = { Text(conn.kind.keyHint) },
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = conn.apiKey,
            onValueChange = { vm.setApiKey(role, it) },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )

        if (role != Role.VISION) {
            Spacer(Modifier.height(10.dp))
            SecondaryAction("Copy credentials from Vision") {
                vm.copyConnFrom(role, Role.VISION)
            }
        }

        Spacer(Modifier.height(16.dp))
        PrimaryAction(
            text = if (busy) "Fetching models..." else "Fetch Models",
            icon = Icons.Outlined.Refresh,
            enabled = !busy && conn.baseUrl.isNotBlank() &&
                conn.apiKey.isNotBlank() && s.online
        ) { vm.fetchModels(role) }

        Spacer(Modifier.height(14.dp))
        PhasePanel(
            phase = phase,
            busyTitle = "Talking to your provider",
            onCancel = { vm.cancelModels(role) },
            onRetry = { vm.fetchModels(role) }
        )

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${models.size} models available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                models.forEach { m ->
                    val selected = conn.model == m
                    Surface(
                        onClick = { vm.setModel(role, m) },
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { vm.setModel(role, m) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                m,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else if (conn.model.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = conn.model,
                onValueChange = { vm.setModel(role, it) },
                label = { Text("Model name") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ================= SCREEN 2: ANALYZE ================= */

@Composable
private fun AnalyzeScreen(vm: AppViewModel, onNext: () -> Unit) {
    val s by vm.state.collectAsState()
    val busy = s.analysisPhase is Phase.Busy
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.setVideo(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        StepHeading(
            "STEP 2 OF 3",
            "Upload and analyze",
            "Frames are sampled on this phone, the audio is transcribed with real " +
                "timestamps, and both are merged second by second before anything is sent."
        )
        Spacer(Modifier.height(20.dp))

        if (!s.conn(Role.VISION).ready) {
            SectionCard {
                StatusRow(
                    Icons.Outlined.ErrorOutline,
                    "The Vision connection is not configured. Go to Connect first.",
                    MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionCard {
            Text("Footage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (s.videoUri == null) {
                EmptyState(
                    Icons.Outlined.UploadFile,
                    "No video selected",
                    "Pick a clip from this phone to begin."
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.videoName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${vm.runtimeLabel()}   ${vm.sizeLabel()}   " +
                                    (if (s.videoHasAudio) "has audio" else "no audio"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.clearVideo() }, enabled = !busy) {
                            Text("Remove")
                        }
                    }
                }

                if (s.videoWarning.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    StatusRow(
                        Icons.Outlined.Info,
                        s.videoWarning,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val est = vm.estimate()
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Estimated cost of this run",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${s.options.fps} FPS  |  ${est.images} mosaic images  |  " +
                                "${est.requests} requests",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${est.tokensLabel()}  |  ${est.costLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A planning figure only. Adjust it on the Tuning tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryAction(
                text = if (s.videoUri == null) "Upload Footage" else "Change Video",
                icon = Icons.Outlined.UploadFile,
                enabled = !busy
            ) { picker.launch("video/*") }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Text("Analysis", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            PrimaryAction(
                text = if (busy) "Analyzing..." else "Start Analysis",
                icon = Icons.Outlined.PlayArrow,
                enabled = !busy && s.videoUri != null &&
                    s.conn(Role.VISION).ready && s.online
            ) { vm.startAnalysis() }

            Spacer(Modifier.height(14.dp))
            PhasePanel(
                phase = s.analysisPhase,
                busyTitle = "Analyzing footage",
                onCancel = { vm.cancelAnalysis() },
                onRetry = { vm.startAnalysis() }
            )

            if (s.analysis.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Editable. Correct anything the model misread before you generate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                BigTextBox(
                    value = s.analysis,
                    placeholder = "",
                    onChange = { vm.editAnalysis(it) },
                    minHeight = 300
                )
                Spacer(Modifier.height(16.dp))
                PrimaryAction("Next", Icons.Filled.CheckCircle) { onNext() }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/* ================= SCREEN 3: SCRIPT ================= */

@Composable
private fun ScriptScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val busy = s.scriptPhase is Phase.Busy
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        StepHeading(
            "STEP 3 OF 3",
            "Generate the script",
            "The analysis is run through the 12-step formula and timed to the real footage."
        )
        Spacer(Modifier.height(20.dp))

        if (!s.conn(Role.SCRIPT).ready) {
            SectionCard {
                StatusRow(
                    Icons.Outlined.ErrorOutline,
                    "The Script connection is not configured. Go to Connect first.",
                    MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionCard {
            Text("Source analysis", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (s.analysis.isBlank()) {
                EmptyState(
                    Icons.Outlined.Description,
                    "Nothing to work from",
                    "Run an analysis on the Analyze tab first."
                )
            } else {
                Text(
                    "${s.analysis.length} characters   |   " +
                        (if (s.twoPass) "two-pass generation on" else "single pass"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                BigTextBox(
                    value = s.analysis,
                    placeholder = "",
                    onChange = { vm.editAnalysis(it) },
                    minHeight = 220
                )
            }

            Spacer(Modifier.height(16.dp))
            PrimaryAction(
                text = if (busy) "Generating..." else "Generate Script",
                icon = Icons.Outlined.Description,
                enabled = !busy && s.analysis.isNotBlank() &&
                    s.conn(Role.SCRIPT).ready && s.online
            ) { vm.generateScript() }

            Spacer(Modifier.height(14.dp))
            PhasePanel(
                phase = s.scriptPhase,
                busyTitle = "Writing your script",
                onCancel = { vm.cancelScript() },
                onRetry = { vm.generateScript() }
            )
        }

        if (s.script.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            SectionCard {
                Text("Finished script", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check section C against section D. Word counts are the part " +
                        "models get wrong most often.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                BigTextBox(value = s.script, placeholder = "", minHeight = 380)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryAction("Copy", Icons.Outlined.ContentCopy) {
                            clipboard.setText(AnnotatedString(s.script))
                        }
                    }
                    SecondaryAction("Regenerate", Icons.Outlined.Refresh) {
                        vm.generateScript()
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/* ================= SCREEN 4: TUNING ================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TuningScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    val o = s.options
    val est = vm.estimate()

    var priceText by remember {
        mutableStateOf(if (o.pricePerMillion > 0.0) o.pricePerMillion.toString() else "")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        StepHeading(
            "COST AND QUALITY",
            "Tuning",
            "Frames per second controls detail. Seconds per request controls how many " +
                "times the instructions are re-sent, which is where waste hides."
        )
        Spacer(Modifier.height(20.dp))

        SectionCard {
            Text("Preset", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Preset.ECONOMY, Preset.BALANCED, Preset.MAXIMUM).forEach { p ->
                    FilterChip(
                        selected = o.preset == p,
                        onClick = { vm.applyPreset(p) },
                        label = { Text(p.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = if (o.preset == p) {
                            {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        shape = MaterialTheme.shapes.small
                    )
                }
            }
            if (o.preset == Preset.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                StatusRow(
                    Icons.Outlined.Info,
                    "Custom settings in use.",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Economy sends one small frame per second at low detail. Balanced sends " +
                    "five frames composited into one image per second. Maximum uses larger " +
                    "cells for reading on-screen text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Text("Sampling", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            SliderRow(
                label = "Frames per second",
                value = "${o.fps} FPS",
                position = o.fps.toFloat(),
                range = 1f..10f,
                steps = 8
            ) { v -> vm.setOption { it.copy(fps = v.toInt()) } }

            SliderRow(
                label = "Seconds per request",
                value = "${o.secondsPerRequest}s per call",
                position = o.secondsPerRequest.toFloat(),
                range = 1f..15f,
                steps = 13
            ) { v -> vm.setOption { it.copy(secondsPerRequest = v.toInt()) } }

            SliderRow(
                label = "Frame cell width",
                value = "${o.cellWidth} px",
                position = o.cellWidth.toFloat(),
                range = 192f..768f,
                steps = 8
            ) { v -> vm.setOption { it.copy(cellWidth = (v / 64f).toInt() * 64) } }

            SliderRow(
                label = "JPEG quality",
                value = "${o.jpegQuality}",
                position = o.jpegQuality.toFloat(),
                range = 40f..90f,
                steps = 9
            ) { v -> vm.setOption { it.copy(jpegQuality = v.toInt()) } }

            Spacer(Modifier.height(8.dp))
            Text("Image detail", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low", "high").forEach { d ->
                    FilterChip(
                        selected = o.detail == d,
                        onClick = { vm.setOption { it.copy(detail = d) } },
                        label = {
                            Text(
                                if (d == "low") "low (cheap, 512px)" else "high (full detail)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        shape = MaterialTheme.shapes.small
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use the audio track", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (o.useAudio) "Speech will be transcribed and merged per second"
                        else "Visuals only, no speech",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = o.useAudio,
                    onCheckedChange = { checked ->
                        vm.setOption { it.copy(useAudio = checked) }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Text("Cost estimate", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = priceText,
                onValueChange = { raw ->
                    val cleaned = raw.filter { c -> c.isDigit() || c == '.' }
                    priceText = cleaned
                    vm.setPrice(cleaned.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Your vision model input price per 1M tokens (USD)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            if (s.videoSeconds <= 0) {
                StatusRow(
                    Icons.Outlined.Info,
                    "Select a video on the Analyze tab to see a real estimate.",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("${s.videoSeconds}s of footage", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${est.requests} requests   |   ${est.images} images   |   " +
                        "${o.fps * s.videoSeconds} frames sampled",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${est.tokensLabel()}   |   ${est.costLabel()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Image token pricing differs between providers, so treat this as a " +
                        "planning figure rather than a bill.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

/* ================= SCREEN 5: SETTINGS ================= */

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        StepHeading(
            "PREFERENCES",
            "Settings",
            "Brand identity, generation behaviour and appearance."
        )
        Spacer(Modifier.height(20.dp))

        SectionCard {
            Text("Brand kit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste your signature opening cadence, verdict catchphrase, audience name, " +
                    "closing tag line, running bit and stance. Leave it empty and the model " +
                    "proposes a set in section G that you can paste back here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            BigTextBox(
                value = s.brandKit,
                placeholder = "Signature opening cadence: ...\n" +
                    "Verdict catchphrase: ...\n" +
                    "Audience name: ...\n" +
                    "Closing tag line: ...\n" +
                    "Running bit: ...\n" +
                    "Stance: prosecutor",
                onChange = { vm.setBrandKit(it) },
                minHeight = 200
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Two-pass generation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.twoPass)
                            "Pass 1 locks the angle and runtime maths. Pass 2 writes " +
                                "against those fixed numbers. Better timing, two calls."
                        else "One call. Cheaper, looser timing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = s.twoPass, onCheckedChange = { vm.setTwoPass(it) })
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.darkMode) "On" else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = s.darkMode, onCheckedChange = { vm.toggleDark() })
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard {
            Text("Connections", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Role.entries.forEach { role ->
                val c = s.conn(role)
                Text("${role.label}: ${c.kind.label}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "   model ${c.model.ifBlank { "not set" }}   |   " +
                        "key ${if (c.apiKey.isBlank()) "not set" else "set"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }
            StatusRow(
                Icons.Outlined.Lock,
                "Keys are stored in this app's private storage on this device only, " +
                    "unencrypted. Do not use a shared team key.",
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}
