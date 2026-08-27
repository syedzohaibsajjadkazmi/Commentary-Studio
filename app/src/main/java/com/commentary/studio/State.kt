package com.commentary.studio

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/* ---------------------------------------------------------------
   PHASES
   --------------------------------------------------------------- */

sealed interface Phase {
    data object Idle : Phase
    data class Busy(val label: String, val progress: Float?) : Phase
    data object Done : Phase
    data class Error(val message: String) : Phase

    val kind: String
        get() = when (this) {
            is Idle -> "idle"
            is Busy -> "busy"
            is Done -> "done"
            is Error -> "error"
        }
}

/* ---------------------------------------------------------------
   PREFS
   --------------------------------------------------------------- */

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("cs_prefs_v2", Context.MODE_PRIVATE)

    private fun key(role: Role, field: String) = "${role.name}_$field"

    fun conn(role: Role): Conn {
        val kind = runCatching {
            ProviderKind.valueOf(
                sp.getString(key(role, "kind"), defaultKind(role).name)!!
            )
        }.getOrDefault(defaultKind(role))
        return Conn(
            kind = kind,
            baseUrl = sp.getString(key(role, "base"), kind.defaultBase).orEmpty(),
            apiKey = sp.getString(key(role, "key"), "").orEmpty(),
            model = sp.getString(key(role, "model"), defaultModel(role)).orEmpty()
        )
    }

    fun saveConn(role: Role, c: Conn) {
        sp.edit()
            .putString(key(role, "kind"), c.kind.name)
            .putString(key(role, "base"), c.baseUrl)
            .putString(key(role, "key"), c.apiKey)
            .putString(key(role, "model"), c.model)
            .apply()
    }

    private fun defaultKind(role: Role) = when (role) {
        Role.VISION -> ProviderKind.OPENAI
        Role.TRANSCRIBE -> ProviderKind.OPENAI
        Role.SCRIPT -> ProviderKind.ANTHROPIC
    }

    private fun defaultModel(role: Role) = when (role) {
        Role.TRANSCRIBE -> "whisper-1"
        else -> ""
    }

    var options: AnalysisOptions
        get() = AnalysisOptions(
            preset = runCatching {
                Preset.valueOf(sp.getString("opt_preset", Preset.BALANCED.name)!!)
            }.getOrDefault(Preset.BALANCED),
            fps = sp.getInt("opt_fps", 5),
            secondsPerRequest = sp.getInt("opt_spr", 5),
            cellWidth = sp.getInt("opt_cell", 384),
            jpegQuality = sp.getInt("opt_q", 62),
            detail = sp.getString("opt_detail", "high").orEmpty().ifBlank { "high" },
            useAudio = sp.getBoolean("opt_audio", true),
            maxOutputTokens = sp.getInt("opt_maxout", 16000),
            pricePerMillion = sp.getFloat("opt_price", 0f).toDouble()
        )
        set(v) {
            sp.edit()
                .putString("opt_preset", v.preset.name)
                .putInt("opt_fps", v.fps)
                .putInt("opt_spr", v.secondsPerRequest)
                .putInt("opt_cell", v.cellWidth)
                .putInt("opt_q", v.jpegQuality)
                .putString("opt_detail", v.detail)
                .putBoolean("opt_audio", v.useAudio)
                .putInt("opt_maxout", v.maxOutputTokens)
                .putFloat("opt_price", v.pricePerMillion.toFloat())
                .apply()
        }

    var brandKit: String
        get() = sp.getString("brand", "").orEmpty()
        set(v) = sp.edit().putString("brand", v).apply()

    var twoPass: Boolean
        get() = sp.getBoolean("twopass", true)
        set(v) = sp.edit().putBoolean("twopass", v).apply()

    var scriptMaxTokens: Int
        get() = sp.getInt("script_maxout", 24000)
        set(v) = sp.edit().putInt("script_maxout", v).apply()

    var darkMode: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()
}

/* ---------------------------------------------------------------
   UI STATE
   --------------------------------------------------------------- */

data class UiState(
    val conns: Map<Role, Conn> = emptyMap(),
    val models: Map<Role, List<String>> = emptyMap(),
    val modelPhases: Map<Role, Phase> = emptyMap(),

    val videoUri: Uri? = null,
    val videoName: String = "",
    val videoSeconds: Int = 0,
    val videoMb: Float = 0f,
    val videoHasAudio: Boolean = false,
    val videoWarning: String = "",

    val analysis: String = "",
    val analysisPhase: Phase = Phase.Idle,

    val script: String = "",
    val scriptPhase: Phase = Phase.Idle,

    val options: AnalysisOptions = AnalysisOptions(),
    val brandKit: String = "",
    val twoPass: Boolean = true,
    val darkMode: Boolean = true,
    val online: Boolean = true,
    val toast: String? = null
) {
    fun conn(role: Role): Conn = conns[role] ?: Conn()
    fun models(role: Role): List<String> = models[role] ?: emptyList()
    fun modelPhase(role: Role): Phase = modelPhases[role] ?: Phase.Idle
}

/* ---------------------------------------------------------------
   VIEW MODEL
   --------------------------------------------------------------- */

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val modelJobs = mutableMapOf<Role, Job>()
    private var analysisJob: Job? = null
    private var scriptJob: Job? = null

    init {
        _state.value = _state.value.copy(
            conns = Role.entries.associateWith { prefs.conn(it) },
            options = prefs.options,
            brandKit = prefs.brandKit,
            twoPass = prefs.twoPass,
            darkMode = prefs.darkMode,
            online = FileFacts.isOnline(app)
        )
    }

    private fun ctx(): Context = getApplication<Application>()

    private fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }

    /* ---------------- generic ---------------- */

    fun refreshOnline() = update { it.copy(online = FileFacts.isOnline(ctx())) }

    fun consumeToast() = update { it.copy(toast = null) }

    fun toggleDark() {
        val v = !_state.value.darkMode
        prefs.darkMode = v
        update { it.copy(darkMode = v) }
    }

    fun setBrandKit(v: String) {
        prefs.brandKit = v
        update { it.copy(brandKit = v) }
    }

    fun setTwoPass(v: Boolean) {
        prefs.twoPass = v
        update { it.copy(twoPass = v) }
    }

    /* ---------------- connections ---------------- */

    fun setKind(role: Role, kind: ProviderKind) {
        val next = _state.value.conn(role).copy(
            kind = kind,
            baseUrl = kind.defaultBase,
            model = if (role == Role.TRANSCRIBE) "whisper-1" else ""
        )
        prefs.saveConn(role, next)
        update {
            it.copy(
                conns = it.conns + (role to next),
                models = it.models + (role to emptyList()),
                modelPhases = it.modelPhases + (role to Phase.Idle)
            )
        }
    }

    fun setBaseUrl(role: Role, v: String) {
        val next = _state.value.conn(role).copy(baseUrl = v)
        prefs.saveConn(role, next)
        update { it.copy(conns = it.conns + (role to next)) }
    }

    fun setApiKey(role: Role, v: String) {
        val next = _state.value.conn(role).copy(apiKey = v)
        prefs.saveConn(role, next)
        update { it.copy(conns = it.conns + (role to next)) }
    }

    fun setModel(role: Role, v: String) {
        val next = _state.value.conn(role).copy(model = v)
        prefs.saveConn(role, next)
        update { it.copy(conns = it.conns + (role to next)) }
    }

    fun copyConnFrom(target: Role, source: Role) {
        val src = _state.value.conn(source)
        val next = _state.value.conn(target).copy(
            kind = src.kind,
            baseUrl = src.baseUrl,
            apiKey = src.apiKey
        )
        prefs.saveConn(target, next)
        update {
            it.copy(
                conns = it.conns + (target to next),
                toast = "Copied ${source.label} credentials into ${target.label}"
            )
        }
    }

    fun fetchModels(role: Role) {
        refreshOnline()
        val s = _state.value
        val conn = s.conn(role)

        if (!s.online) {
            update {
                it.copy(
                    modelPhases = it.modelPhases +
                        (role to Phase.Error("You are offline. Reconnect and retry."))
                )
            }
            return
        }
        if (conn.baseUrl.isBlank() || conn.apiKey.isBlank()) {
            update {
                it.copy(
                    modelPhases = it.modelPhases +
                        (role to Phase.Error("Enter both a Base URL and an API key."))
                )
            }
            return
        }

        modelJobs[role]?.cancel()
        modelJobs[role] = viewModelScope.launch {
            update {
                it.copy(
                    modelPhases = it.modelPhases +
                        (role to Phase.Busy("Contacting your provider", null)),
                    models = it.models + (role to emptyList())
                )
            }
            try {
                val list = withContext(Dispatchers.IO) { Ai.listModels(conn) }
                update { st ->
                    st.copy(
                        models = st.models + (role to list),
                        modelPhases = st.modelPhases + (role to Phase.Done)
                    )
                }
            } catch (e: CancellationException) {
                update { it.copy(modelPhases = it.modelPhases + (role to Phase.Idle)) }
            } catch (e: Exception) {
                update {
                    it.copy(
                        modelPhases = it.modelPhases +
                            (role to Phase.Error(e.message ?: "Could not fetch models."))
                    )
                }
            }
        }
    }

    fun cancelModels(role: Role) {
        modelJobs[role]?.cancel()
        Ai.cancelAll()
        update { it.copy(modelPhases = it.modelPhases + (role to Phase.Idle)) }
    }

    /* ---------------- options ---------------- */

    fun applyPreset(p: Preset) {
        val next = AnalysisOptions.of(p, _state.value.options)
        prefs.options = next
        update { it.copy(options = next) }
    }

    fun setOption(transform: (AnalysisOptions) -> AnalysisOptions) {
        val next = transform(_state.value.options).copy(preset = Preset.CUSTOM)
        prefs.options = next
        update { it.copy(options = next) }
    }

    fun setPrice(v: Double) {
        val next = _state.value.options.copy(pricePerMillion = v)
        prefs.options = next
        update { it.copy(options = next) }
    }

    /* ---------------- video ---------------- */

    fun setVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { VideoProbe.read(ctx(), uri) }
                val name = FileFacts.displayName(ctx(), uri)
                val bytes = FileFacts.sizeBytes(ctx(), uri)
                val mb = if (bytes <= 0) 0f else (bytes / 1_000_000.0).toFloat()
                val warn = when {
                    !facts.hasAudio ->
                        "This video has no audio track. Speech will be unavailable."
                    facts.durationSec > 300 ->
                        "This clip is ${facts.durationSec}s. Long clips cost more and may " +
                            "hit context limits."
                    else -> ""
                }
                update {
                    it.copy(
                        videoUri = uri,
                        videoName = name,
                        videoSeconds = facts.durationSec,
                        videoMb = mb,
                        videoHasAudio = facts.hasAudio,
                        videoWarning = warn,
                        analysis = "",
                        analysisPhase = Phase.Idle,
                        script = "",
                        scriptPhase = Phase.Idle
                    )
                }
            } catch (e: Exception) {
                update {
                    it.copy(
                        analysisPhase = Phase.Error(e.message ?: "Could not read this video."),
                        videoUri = null
                    )
                }
            }
        }
    }

    fun clearVideo() = update {
        it.copy(
            videoUri = null, videoName = "", videoSeconds = 0, videoMb = 0f,
            videoHasAudio = false, videoWarning = "",
            analysis = "", analysisPhase = Phase.Idle,
            script = "", scriptPhase = Phase.Idle
        )
    }

    fun editAnalysis(v: String) = update { it.copy(analysis = v) }

    /* ---------------- analysis ---------------- */

    fun startAnalysis() {
        refreshOnline()
        val s = _state.value
        val uri = s.videoUri
        val vision = s.conn(Role.VISION)

        if (uri == null) {
            update { it.copy(analysisPhase = Phase.Error("Upload a video first.")) }
            return
        }
        if (!vision.ready) {
            update {
                it.copy(
                    analysisPhase = Phase.Error(
                        "The Vision connection needs a Base URL, an API key and a model."
                    )
                )
            }
            return
        }
        if (!s.online) {
            update { it.copy(analysisPhase = Phase.Error("You are offline. Reconnect and retry.")) }
            return
        }

        val transcribe = s.conn(Role.TRANSCRIBE).takeIf { it.ready && s.options.useAudio }

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            update { it.copy(analysisPhase = Phase.Busy("Starting", 0f), analysis = "") }
            try {
                val out = AnalysisPipeline.run(
                    context = ctx(),
                    vision = vision,
                    transcribe = transcribe,
                    uri = uri,
                    options = s.options
                ) { label, progress ->
                    update { it.copy(analysisPhase = Phase.Busy(label, progress)) }
                }
                update { it.copy(analysis = out, analysisPhase = Phase.Done) }
            } catch (e: CancellationException) {
                update { it.copy(analysisPhase = Phase.Idle) }
            } catch (e: Exception) {
                update { it.copy(analysisPhase = Phase.Error(e.message ?: "Analysis failed.")) }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        Ai.cancelAll()
        update { it.copy(analysisPhase = Phase.Idle) }
    }

    /* ---------------- script ---------------- */

    fun generateScript() {
        refreshOnline()
        val s = _state.value
        val script = s.conn(Role.SCRIPT)

        if (s.analysis.isBlank()) {
            update { it.copy(scriptPhase = Phase.Error("There is no analysis to work from yet.")) }
            return
        }
        if (!script.ready) {
            update {
                it.copy(
                    scriptPhase = Phase.Error(
                        "The Script connection needs a Base URL, an API key and a model."
                    )
                )
            }
            return
        }
        if (!s.online) {
            update { it.copy(scriptPhase = Phase.Error("You are offline. Reconnect and retry.")) }
            return
        }

        scriptJob?.cancel()
        scriptJob = viewModelScope.launch {
            update { it.copy(scriptPhase = Phase.Busy("Preparing", 0f), script = "") }
            try {
                val out = ScriptPipeline.run(
                    script = script,
                    analysis = s.analysis,
                    brandKit = s.brandKit,
                    twoPass = s.twoPass,
                    maxOutputTokens = prefs.scriptMaxTokens
                ) { label, progress ->
                    update { it.copy(scriptPhase = Phase.Busy(label, progress)) }
                }
                update { it.copy(script = out, scriptPhase = Phase.Done) }
            } catch (e: CancellationException) {
                update { it.copy(scriptPhase = Phase.Idle) }
            } catch (e: Exception) {
                update {
                    it.copy(scriptPhase = Phase.Error(e.message ?: "Script generation failed."))
                }
            }
        }
    }

    fun cancelScript() {
        scriptJob?.cancel()
        Ai.cancelAll()
        update { it.copy(scriptPhase = Phase.Idle) }
    }

    /* ---------------- labels ---------------- */

    fun runtimeLabel(): String {
        val s = _state.value.videoSeconds
        return if (s <= 0) "unknown length" else "%d:%02d".format(s / 60, s % 60)
    }

    fun sizeLabel(): String {
        val mb = _state.value.videoMb
        return if (mb <= 0f) "" else "${(mb * 10).roundToInt() / 10f} MB"
    }

    fun estimate(): CostEstimate =
        AnalysisPipeline.estimate(_state.value.videoSeconds, _state.value.options)
}
