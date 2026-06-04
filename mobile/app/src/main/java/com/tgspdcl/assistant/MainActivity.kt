package com.tgspdcl.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.Locale

// ==========================================================================
// RETROFIT API LAYER DEFINITION
// ==========================================================================
data class AssistantState(val is_active: Boolean, val lineman_phone: String)
data class ToggleRequest(val is_active: Boolean)
data class ToggleResponse(val message: String, val is_active: Boolean)
data class Outage(
    val area: String,
    val issue: String?,
    val eta: String?,
    val status: String,
    val staff_name: String?
)
data class OutagesResponse(val outages: List<Outage>)
data class VoiceUpdateRequest(
    val area: String,
    val issue: String,
    val eta: String,
    val status: String,
    val staff_name: String
)
data class ConsumerQueryRequest(val area: String, val query: String)
data class ConsumerQueryResponse(
    val response: String,
    val outage_info: Outage?,
    val forwarded: Boolean,
    val lineman_phone: String?
)

interface TgspdclApiService {
    @GET("api/v1/assistant-state/")
    suspend fun getAssistantState(): AssistantState

    @POST("api/v1/assistant-state/toggle/")
    suspend fun toggleAssistantState(@Body req: ToggleRequest): ToggleResponse

    @GET("api/v1/all-outages/")
    suspend fun getAllOutages(): OutagesResponse

    @POST("api/v1/voice-update/")
    suspend fun syncOutage(@Body req: VoiceUpdateRequest): Any

    @POST("api/v1/consumer-query/")
    suspend fun postConsumerQuery(@Body req: ConsumerQueryRequest): ConsumerQueryResponse
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private var serverHost = "10.0.2.2:8000"
    private var apiService: TgspdclApiService? = null

    // Call state variables
    private val isCallActiveState = mutableStateOf(false)
    private val callerNumberState = mutableStateOf("")
    private val callTranscriptState = mutableStateListOf<Pair<String, String>>() // Pair of (Sender, Message)
    private val isForwardedToOperatorState = mutableStateOf(false)
    private val isTtsSpeakingState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SharedPreferences and host IP settings
        val sharedPrefs = getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
        val savedHost = sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000"
        updateApiService(savedHost)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            TgspdclMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isCallActive by remember { isCallActiveState }
                    if (isCallActive) {
                        CallScreeningOverlay(
                            callerNumber = callerNumberState.value,
                            transcript = callTranscriptState,
                            isForwarded = isForwardedToOperatorState.value,
                            onHangUp = { endCall() }
                        )
                    } else {
                        MainLinemanScreen()
                    }
                }
            }
        }
    }

    fun updateApiService(host: String) {
        serverHost = host
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
        val protocol = if (host.contains("localhost") || host.contains("10.0.2.2") || host.contains("192.168.")) "http" else "https"
        apiService = try {
            Retrofit.Builder()
                .baseUrl("$protocol://$host/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TgspdclApiService::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("te", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra("EXTRA_INCOMING_CALL", false)) {
            val number = intent.getStringExtra("EXTRA_CALLER_NUMBER") ?: "Unknown Number"
            startCallScreening(number)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = mutableListOf<String>()
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1001)
        }
    }

    fun startCallScreening(callerNumber: String) {
        isCallActiveState.value = true
        isForwardedToOperatorState.value = false
        callerNumberState.value = callerNumber
        callTranscriptState.clear()

        callTranscriptState.add("System" to "Incoming call from unknown number $callerNumber intercepted.")

        val greeting = "TGSPDCL automated assistant ki swagatham. Ramanapet mariyu Cherlapally power updates kosam matladandi."
        callTranscriptState.add("AI Assistant" to greeting)

        speakText(greeting) {
            listenToCaller()
        }
    }

    private fun speakText(text: String, onDone: () -> Unit = {}) {
        isTtsSpeakingState.value = true
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isTtsSpeakingState.value = false
                    onDone()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isTtsSpeakingState.value = false
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_id")
    }

    private fun listenToCaller() {
        if (isForwardedToOperatorState.value || !isCallActiveState.value) return

        runOnUiThread {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("STT", "Ready for speech")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.e("STT", "Error code: $error")
                    if (isCallActiveState.value && !isForwardedToOperatorState.value && !isTtsSpeakingState.value) {
                        listenToCaller()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val query = matches[0]
                        callTranscriptState.add("Consumer" to query)
                        processQueryWithBackend(query)
                    } else {
                        listenToCaller()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
        }
    }

    private fun processQueryWithBackend(query: String) {
        val service = apiService ?: return
        mainScope.launch {
            try {
                var area = "Cherlapally"
                val lowerQuery = query.lowercase()
                if (lowerQuery.contains("ramanapet")) area = "Ramanapet"
                else if (lowerQuery.contains("siddipet")) area = "Siddipet"
                else if (lowerQuery.contains("narketpally")) area = "Narketpally"

                val res = withContext(Dispatchers.IO) {
                    service.postConsumerQuery(ConsumerQueryRequest(area = area, query = query))
                }

                callTranscriptState.add("AI Assistant" to res.response)

                speakText(res.response) {
                    if (res.forwarded) {
                        triggerOperatorForward()
                    } else {
                        listenToCaller()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending query to server: ${e.message}", e)
                val errorMsg = "Substation network loading error. Operator connection standby."
                callTranscriptState.add("AI Assistant" to errorMsg)
                speakText(errorMsg) {
                    triggerOperatorForward()
                }
            }
        }
    }

    private fun triggerOperatorForward() {
        isForwardedToOperatorState.value = true
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = false
        Log.d("MainActivity", "Speakerphone disabled. Switch to earpiece.")
        playForwardAlert()
    }

    private fun playForwardAlert() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun endCall() {
        isCallActiveState.value = false
        isForwardedToOperatorState.value = false
        callTranscriptState.clear()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        speechRecognizer?.stopListening()
        Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}

// ==========================================================================
// THEME & COLOR PALETTE
// ==========================================================================
val BgMain = Color(0xFF060B16)
val BgCard = Color(0xAA0D162B)
val BorderGlass = Color(0xFF1E2640)
val ColorElectric = Color(0xFF00D2FF)
val ColorAmber = Color(0xFFFFB300)
val ColorGreen = Color(0xFF00E676)
val ColorRed = Color(0xFFFF3E55)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFFCBD5E1)
val TextMuted = Color(0xFF64748B)

@Composable
fun TgspdclMobileTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        background = BgMain,
        surface = BgCard,
        primary = ColorElectric,
        secondary = ColorAmber,
        error = ColorRed
    )
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// ==========================================================================
// MAIN SCREEN LAYOUT
// ==========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLinemanScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = context.getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
    
    // Server IP Config for easy debugging/demo
    var serverHost by remember { mutableStateOf(sharedPrefs.getString("server_host", "10.0.2.2:8000") ?: "10.0.2.2:8000") }
    var isEditingHost by remember { mutableStateOf(false) }

    // Backend States
    var isAssistantActive by remember { mutableStateOf(sharedPrefs.getBoolean("ai_assistant_active", true)) }
    var linemanPhone by remember { mutableStateOf("+91 9876543210") }
    var outagesList by remember { mutableStateOf(listOf<Outage>()) }
    var isLoading by remember { mutableStateOf(false) }

    // Speech simulation states
    var isRecording by remember { mutableStateOf(false) }
    var parsedText by remember { mutableStateOf("") }
    var hasParsedResult by remember { mutableStateOf(false) }

    // Extracted Fields for sync confirm dialog
    var editArea by remember { mutableStateOf("Cherlapally") }
    var editIssue by remember { mutableStateOf("Line Breakdown") }
    var editEta by remember { mutableStateOf("1 hour") }
    var editStatus by remember { mutableStateOf("In Progress") }

    // Build Retrofit Client dynamically based on serverHost
    val apiService = remember(serverHost) {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
        val protocol = if (serverHost.contains("localhost") || serverHost.contains("10.0.2.2") || serverHost.contains("192.168.")) "http" else "https"

        try {
            Retrofit.Builder()
                .baseUrl("$protocol://$serverHost/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TgspdclApiService::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // Refresh Data helper
    val refreshData: () -> Unit = {
        if (apiService != null) {
            coroutineScope.launch {
                isLoading = true
                try {
                    val state = apiService.getAssistantState()
                    isAssistantActive = state.is_active
                    linemanPhone = state.lineman_phone
                    sharedPrefs.edit().putBoolean("ai_assistant_active", state.is_active).apply()

                    val outagesRes = apiService.getAllOutages()
                    outagesList = outagesRes.outages
                } catch (e: Exception) {
                    Toast.makeText(context, "Network Error: Couldn't sync with server.", Toast.LENGTH_SHORT).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Load initial data
    LaunchedEffect(apiService) {
        refreshData()
    }

    // Mic permissions launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
        } else {
            Toast.makeText(context, "Permission needed to record", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(ColorElectric, ColorAmber)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⚡",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "TGSPDCL Assistant",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Lineman Control Center",
                        fontSize = 11.sp,
                        color = ColorElectric,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(onClick = refreshData) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Sync",
                    tint = ColorElectric
                )
            }
        }

        // Host IP Address Settings modifier
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F172A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isEditingHost) {
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    singleLine = true,
                    label = { Text("Server Host IP", fontSize = 10.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        isEditingHost = false
                        sharedPrefs.edit().putString("server_host", serverHost).apply()
                        (context as? MainActivity)?.updateApiService(serverHost)
                        refreshData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorElectric),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("OK", fontSize = 11.sp, color = Color.Black)
                }
            } else {
                Text(
                    text = "Server IP: http://$serverHost",
                    fontSize = 11.sp,
                    color = TextMuted
                )
                Text(
                    text = "Edit IP",
                    fontSize = 11.sp,
                    color = ColorAmber,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { isEditingHost = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // FATHER'S AI PERMISSION TOGGLE CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🤖 AI CALL ANSWERING PERMISSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isAssistantActive) ColorGreen else ColorRed)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAssistantActive) "Answering Mode: Active" else "Answering Mode: Bypass",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAssistantActive)
                                "AI answers electricity query calls in Telugu."
                            else
                                "Calls bypass AI and route to your phone.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    Switch(
                        checked = isAssistantActive,
                        onCheckedChange = { isChecked ->
                            if (apiService != null) {
                                coroutineScope.launch {
                                    try {
                                        val res = apiService.toggleAssistantState(ToggleRequest(isChecked))
                                        isAssistantActive = res.is_active
                                        sharedPrefs.edit().putBoolean("ai_assistant_active", res.is_active).apply()
                                        Toast.makeText(
                                            context,
                                            if (res.is_active) "AI Answering Activated!" else "Bypass Active: Call Forwarding On!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to toggle status", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ColorElectric,
                            checkedTrackColor = ColorElectric.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Color.Black
                        )
                    )
                }

                AnimatedVisibility(visible = !isAssistantActive) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ColorRed.copy(alpha = 0.08f))
                            .border(1.dp, ColorRed.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📞",
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Call bypass activated. Forwarding direct to: $linemanPhone",
                            fontSize = 11.sp,
                            color = ColorRed
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TAP-TO-TALK OUTAGE UPDATER SECTION
        Text(
            text = "🎙️ Voice Outage Reporter",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Wave Animation simulated when recording
                val infiniteTransition = rememberInfiniteTransition()
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.25f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                val scaleGlow by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.8f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(50.dp * scaleGlow)
                                .clip(CircleShape)
                                .background(ColorAmber.copy(alpha = 0.15f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(60.dp * scale)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(ColorAmber, Color(0xFFFF8C00))
                                )
                            )
                            .clickable {
                                if (isRecording) {
                                    // Stop Recording simulation
                                    isRecording = false
                                    // Simulate NLP Telugu extraction
                                    parsedText =
                                        "Cherlapally area lo line breakdown ayyindi. Oka ganta (1 hour) padutundi. Staff work chestunnaru."
                                    editArea = "Cherlapally"
                                    editIssue = "Line Breakdown"
                                    editEta = "1 hour"
                                    editStatus = "In Progress"
                                    hasParsedResult = true
                                } else {
                                    // Request mic recording permissions
                                    val status = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                    if (status == PackageManager.PERMISSION_GRANTED) {
                                        isRecording = true
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRecording) "⏹️" else "🎤",
                            fontSize = 26.sp
                        )
                    }
                }

                Text(
                    text = if (isRecording) "Recording... Tap to stop" else "Hold or Tap to Record Update in Telugu",
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                // Render Simulation presets buttons for quick testing inside Kotlin app
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            parsedText = "Cherlapally lo breakdown full lines repair work padutundi, 30 min ETA."
                            editArea = "Cherlapally"
                            editIssue = "Line Breakdown"
                            editEta = "30 minutes"
                            hasParsedResult = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Cherlapally Test", fontSize = 9.sp, color = TextPrimary)
                    }

                    Button(
                        onClick = {
                            parsedText = "Ramanapet lo panel transformer problem clean replacement, 2 hours ganta."
                            editArea = "Ramanapet"
                            editIssue = "Transformer Problem"
                            editEta = "2 hours"
                            hasParsedResult = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Ramanapet Test", fontSize = 9.sp, color = TextPrimary)
                    }
                }
            }
        }

        // CONFIRM ENTITY CARDS AFTER SPEECH TRANSCRIPTION
        AnimatedVisibility(visible = hasParsedResult) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ColorAmber.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = ColorAmber.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🤖 AI PARSED DETAILS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorAmber
                        )
                        Text(
                            text = "Telugu Speech Extracted",
                            fontSize = 9.sp,
                            color = TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "\"$parsedText\"",
                        fontSize = 11.sp,
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.2f))
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editArea,
                            onValueChange = { editArea = it },
                            label = { Text("Area", fontSize = 8.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editIssue,
                            onValueChange = { editIssue = it },
                            label = { Text("Issue", fontSize = 8.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editEta,
                            onValueChange = { editEta = it },
                            label = { Text("ETA", fontSize = 8.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editStatus,
                            onValueChange = { editStatus = it },
                            label = { Text("Status", fontSize = 8.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (apiService != null) {
                                coroutineScope.launch {
                                    try {
                                        apiService.syncOutage(
                                            VoiceUpdateRequest(
                                                area = editArea,
                                                issue = editIssue,
                                                eta = editEta,
                                                status = editStatus,
                                                staff_name = "LM Raju"
                                            )
                                        )
                                        hasParsedResult = false
                                        Toast.makeText(context, "Outage Database Updated!", Toast.LENGTH_SHORT).show()
                                        refreshData()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to update db", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Save Outage to database",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LIVE OUTAGES TICKER HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚡ Active Outages Grid",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorElectric.copy(alpha = 0.08f))
                    .border(1.dp, ColorElectric.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${outagesList.filter { it.status != "Restored" }.size} Outages",
                    fontSize = 9.sp,
                    color = ColorElectric,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // OUTAGES GRID ITEMS FEED
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (outagesList.isEmpty()) {
                item {
                    Text(
                        text = "No active outages. Grid is healthy.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(outagesList) { outage ->
                    OutageItemCard(outage)
                }
            }
        }
    }
}

@Composable
fun OutageItemCard(outage: Outage) {
    val accentColor = when (outage.status) {
        "Restored" -> ColorGreen
        "In Progress" -> ColorAmber
        else -> ColorRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                BorderGlass,
                RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawLeftBorder(accentColor, 4.dp)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = outage.area,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = outage.issue ?: "Fuse Failure",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(
                    text = "ETA: ${outage.eta ?: "Pending"} • Staff: ${outage.staff_name ?: "ALM"}",
                    fontSize = 9.sp,
                    color = TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = outage.status,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
}

// Custom helper modifier to draw left border
fun Modifier.drawLeftBorder(color: Color, width: androidx.compose.ui.unit.Dp): Modifier {
    return this.drawBehind {
        val strokeWidth = width.toPx()
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(strokeWidth / 2, 0f),
            end = androidx.compose.ui.geometry.Offset(strokeWidth / 2, size.height),
            strokeWidth = strokeWidth
        )
    }
}

// ==========================================================================
// CALL SCREENING FULL-SCREEN OVERLAY
// ==========================================================================
@Composable
fun CallScreeningOverlay(
    callerNumber: String,
    transcript: List<Pair<String, String>>,
    isForwarded: Boolean,
    onHangUp: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // Call State Header
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isForwarded) ColorGreen.copy(alpha = 0.08f) else ColorElectric.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.border(1.dp, if (isForwarded) ColorGreen.copy(alpha = 0.2f) else ColorElectric.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isForwarded) ColorGreen else ColorElectric)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isForwarded) "DIRECT HANDSET LINE ACTIVE" else "EQUAL AI CALL SCREENING ACTIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isForwarded) ColorGreen else ColorElectric,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Glowing Caller Avatar
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp * pulseScale)
                    .clip(CircleShape)
                    .background(if (isForwarded) ColorGreen.copy(alpha = 0.15f) else ColorElectric.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            if (isForwarded) listOf(ColorGreen, Color(0xFF00C853)) else listOf(ColorElectric, Color(0xFF0077FF))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isForwarded) "📞" else "🤖",
                    fontSize = 28.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = callerNumber,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = if (isForwarded) "Speaking with Operator (LM Raju)" else "Interception screening in progress...",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Scrolling Transcript Pane
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "LIVE SCREEN TRANSCRIPT",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transcript) { item ->
                        val isUser = item.first == "Consumer"
                        val isSystem = item.first == "System"
                        
                        if (isSystem) {
                            Text(
                                text = item.second,
                                fontSize = 10.sp,
                                color = ColorAmber,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) Color(0xFF1E293B) else ColorElectric.copy(alpha = 0.08f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.widthIn(max = 240.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = item.first,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isUser) ColorAmber else ColorElectric
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.second,
                                            fontSize = 12.sp,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Red Hang Up Button
        Button(
            onClick = onHangUp,
            colors = ButtonDefaults.buttonColors(containerColor = ColorRed),
            shape = CircleShape,
            modifier = Modifier
                .size(64.dp)
                .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("❌", fontSize = 24.sp)
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "HANG UP CALL",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted
        )
    }
}
