package com.example.plantplacement

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode.Restart
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val Forest = Color(0xFF285B3D)
private val Leaf = Color(0xFF4D8058)
private val Sage = Color(0xFFEAF1E8)
private val Cream = Color(0xFFF8F7F1)
private val Ink = Color(0xFF20352A)
private val Muted = Color(0xFF64756A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlantPlacementApp() }
    }
}

@Serializable
private data class ImageInput(val mimeType: String, val data: String)

@Serializable
private data class SuggestionPreferences(
    val careLevel: String,
    val plantSize: String,
    val petFriendly: Boolean,
    val likedPlantTypes: String,
)

@Serializable
private data class RecommendationRequest(
    val mode: String,
    val spacePhoto: ImageInput,
    val preferences: SuggestionPreferences? = null,
    val plantName: String? = null,
    val plantPhoto: ImageInput? = null,
)

@Serializable
private data class HighlightArea(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
)

@Serializable
private data class PlantRecommendation(
    val id: String,
    val title: String,
    val summary: String,
    val explanation: String,
    val highlight: HighlightArea,
)

@Serializable
private data class RecommendationResponse(
    val unclear: Boolean = false,
    val recommendations: List<PlantRecommendation> = emptyList(),
)

@Serializable
private data class ErrorResponse(val error: String)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlantPlacementApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        secondary = Leaf,
        background = Cream,
        surface = Color.White,
        onSurface = Ink,
        onBackground = Ink,
        surfaceVariant = Sage,
    )
    MaterialTheme(colorScheme = colors) {
        var roomPhoto by remember { mutableStateOf<Uri?>(null) }
        var photoVersion by remember { mutableStateOf(0) }
        var careLevel by remember { mutableStateOf("easy") }
        var plantSize by remember { mutableStateOf("medium") }
        var petSafe by remember { mutableStateOf(true) }
        var likedPlants by remember { mutableStateOf("") }
        var selectedMode by remember { mutableStateOf<String?>(null) }
        var existingPlantName by remember { mutableStateOf("") }
        var existingPlantPhoto by remember { mutableStateOf<Uri?>(null) }
        var result by remember { mutableStateOf<RecommendationResponse?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val roomPhotoBringIntoViewRequester = remember { BringIntoViewRequester() }
        val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                roomPhoto = uri
                photoVersion++
                result = null
                error = null
            }
        }
        val plantPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) existingPlantPhoto = uri
        }
        val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
                    output.toByteArray()
                }
                val savedUri = context.storePhoto(bytes)
                roomPhoto = savedUri
                photoVersion++
                result = null
                error = null
            }
        }

        Scaffold(
            containerColor = Cream,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Spa, contentDescription = null, tint = Forest)
                            Spacer(Modifier.width(9.dp))
                            Text("Plant Placement Helper", fontWeight = FontWeight.Bold, color = Forest, style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
                )
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                val screenWidth = maxWidth
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (screenWidth < 360.dp) 14.dp else 22.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                Text("Find the right plants for your home", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "Use a room photo to get plant suggestions or find a good spot for a plant you already own.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
                HowItWorksCard(
                    roomPhotoAdded = roomPhoto != null,
                    helpChosen = selectedMode != null,
                    resultReady = result != null,
                    resultLabel = if (selectedMode == "existing") "See a marked spot" else "See marked suggestions",
                )
                StepHeading("1", "Add a photo of your room")
                Text("Show the area where you would like to place a plant.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                PhotoCard(
                    uri = roomPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (screenWidth < 360.dp) 185.dp else 230.dp)
                        .bringIntoViewRequester(roomPhotoBringIntoViewRequester),
                    label = "Room photo",
                    showScan = loading,
                    mode = selectedMode ?: "suggest",
                    highlight = result?.recommendations?.firstOrNull()?.highlight,
                    photoVersion = photoVersion,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { cameraPicker.launch(null) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.AddAPhoto, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take a photo")
                    }
                    OutlinedButton(onClick = { galleryPicker.launch("image/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Collections, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose a photo")
                    }
                }
                StepHeading("2", "What would you like help with?")
                Text("Choose one option. You can change it any time.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (screenWidth < 390.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        HelpPathCard(
                            title = "Suggest plants for this room",
                            subtitle = "Get ideas based on the room photo and your preferences.",
                            icon = Icons.Rounded.LocalFlorist,
                            selected = selectedMode == "suggest",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedMode = "suggest"; result = null; error = null },
                        )
                        HelpPathCard(
                            title = "Find a spot for my plant",
                            subtitle = "Tell us its name or add a plant photo.",
                            icon = Icons.Rounded.Spa,
                            selected = selectedMode == "existing",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedMode = "existing"; result = null; error = null },
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        HelpPathCard(
                            title = "Suggest plants for this room",
                            subtitle = "Get ideas based on the room photo and your preferences.",
                            icon = Icons.Rounded.LocalFlorist,
                            selected = selectedMode == "suggest",
                            modifier = Modifier.weight(1f),
                            onClick = { selectedMode = "suggest"; result = null; error = null },
                        )
                        HelpPathCard(
                            title = "Find a spot for my plant",
                            subtitle = "Tell us its name or add a plant photo.",
                            icon = Icons.Rounded.Spa,
                            selected = selectedMode == "existing",
                            modifier = Modifier.weight(1f),
                            onClick = { selectedMode = "existing"; result = null; error = null },
                        )
                    }
                }
                if (roomPhoto == null || selectedMode == null) {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Text(
                            if (roomPhoto == null) "Next: add a room photo, then choose how you want help."
                            else "Next: choose one of the two help options above.",
                            color = Forest,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (roomPhoto != null && selectedMode != null) {
                    StepHeading("3", if (selectedMode == "suggest") "Set your plant preferences" else "Tell us about your plant")
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (selectedMode == "suggest") Icons.Rounded.LocalFlorist else Icons.Rounded.Spa, contentDescription = null, tint = Leaf)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (selectedMode == "suggest") "Your room, new plant ideas" else "Tell us about your plant",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (selectedMode == "suggest") {
                            Text("These preferences help tailor the suggestions. We’ll mark a possible spot on your room photo.", color = Muted)
                            PreferenceChoices(title = "Care", choices = listOf("easy" to "Easy care", "more-care" to "More care"), selected = careLevel, onSelect = { careLevel = it })
                            PreferenceChoices(title = "Size", choices = listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), selected = plantSize, onSelect = { plantSize = it })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Pets, contentDescription = null, tint = Leaf, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Require pet-safe suggestions", modifier = Modifier.weight(1f))
                                Switch(checked = petSafe, onCheckedChange = { petSafe = it })
                            }
                            OutlinedTextField(
                                value = likedPlants,
                                onValueChange = { likedPlants = it.take(300) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Plants or types you like") },
                                placeholder = { Text("e.g. ferns, trailing plants") },
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                            )
                        } else {
                            Text("Enter the plant name or add a photo. We’ll suggest a possible place in your room.", color = Muted)
                            OutlinedTextField(
                                value = existingPlantName,
                                onValueChange = { existingPlantName = it.take(120) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Plant name") },
                                placeholder = { Text("e.g. Monstera") },
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                            )
                            OutlinedButton(onClick = { plantPhotoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.AddAPhoto, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (existingPlantPhoto == null) "Add a plant photo (optional)" else "Plant photo added · Change")
                            }
                        }
                        Button(
                            onClick = {
                                val photo = roomPhoto
                                if (photo == null) {
                                    error = "Choose a room photo first."
                                } else if (selectedMode == "existing" && existingPlantName.isBlank() && existingPlantPhoto == null) {
                                    error = "Enter the plant name or add a photo of the plant."
                                } else {
                                    loading = true
                                    error = null
                                    result = null
                                    scope.launch {
                                        roomPhotoBringIntoViewRequester.bringIntoView()
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                requestRecommendation(
                                                    context = context,
                                                    uri = photo,
                                                    mode = selectedMode ?: "suggest",
                                                    preferences = if (selectedMode == "suggest") SuggestionPreferences(careLevel, plantSize, petSafe, likedPlants) else null,
                                                    plantName = existingPlantName.takeIf { selectedMode == "existing" && it.isNotBlank() },
                                                    plantPhotoUri = existingPlantPhoto.takeIf { selectedMode == "existing" },
                                                )
                                            }
                                        }.onSuccess { result = it }
                                            .onFailure { error = it.message ?: "Plant analysis could not finish. Please try again." }
                                        loading = false
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (loading) "Analyzing your photos…" else if (selectedMode == "suggest") "Get plant suggestions" else "Find a spot for my plant")
                        }
                    }
                }
                }
                error?.let { ErrorCard(it) }
                result?.let { response ->
                    if (response.unclear) {
                        ErrorCard("That photo is hard to read. Try a brighter, clearer photo and analyze again.")
                    } else {
                        response.recommendations.forEach { RecommendationCard(it) }
                        Text("These are AI-generated ideas based on the photo. A single photo cannot show daily sunlight, so light guidance is only an estimate.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { roomPhoto = null; result = null; error = null; selectedMode = null; existingPlantPhoto = null; existingPlantName = "" }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("New analysis")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun HowItWorksCard(
    roomPhotoAdded: Boolean,
    helpChosen: Boolean,
    resultReady: Boolean,
    resultLabel: String,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Sage),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Forest)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HowItWorksStep("1", "Add room photo", roomPhotoAdded, !roomPhotoAdded, Modifier.weight(1f))
                HowItWorksStep("2", "Choose help", helpChosen, roomPhotoAdded && !helpChosen, Modifier.weight(1f))
                HowItWorksStep("3", resultLabel, resultReady, roomPhotoAdded && helpChosen && !resultReady, Modifier.weight(1f))
            }
            Text(
                "The app analyzes your photo and returns a suggested plant or spot, marked on the image. Light guidance is an estimate.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HowItWorksStep(
    number: String,
    label: String,
    complete: Boolean,
    current: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = if (complete || current) Forest else Color(0xFFD8E4D7), shape = CircleShape, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = if (complete || current) Color.White else Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(label, color = if (current || complete) Ink else Muted, style = MaterialTheme.typography.labelMedium, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun StepHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Forest, shape = CircleShape, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
private fun HelpPathCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Forest else Color(0xFFD8E4D7),
            shape = RoundedCornerShape(20.dp),
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Sage else Color.White),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Forest, modifier = Modifier.size(24.dp))
            Text(title, color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreferenceChoices(title: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(title, color = Muted, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun PhotoCard(
    uri: Uri?,
    modifier: Modifier = Modifier,
    label: String,
    showScan: Boolean,
    mode: String,
    highlight: HighlightArea?,
    photoVersion: Int,
) {
    val context = LocalContext.current
    var bitmap by remember(uri, photoVersion) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri, photoVersion) {
        bitmap = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
        }
    }
    val scanProgress = rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(tween(4200), Restart),
        label = "scan-position",
    )
    val analysisMessage = when {
        scanProgress.value < 0.34f -> "Reading the room photo…"
        scanProgress.value < 0.68f && mode == "existing" -> "Checking where your plant may thrive…"
        scanProgress.value < 0.68f -> "Looking for plants that may suit this space…"
        mode == "existing" -> "Preparing a marked spot for your plant…"
        else -> "Preparing plant ideas and photo markers…"
    }
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Sage).border(1.dp, Color(0xFFD8E4D7), RoundedCornerShape(24.dp))) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddAPhoto, contentDescription = null, tint = Leaf, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your room photo will appear here", color = Forest, fontWeight = FontWeight.Medium)
                Text("Choose Take a photo or Choose a photo below", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        highlight?.let { marker ->
            Canvas(Modifier.fillMaxSize()) {
                val left = marker.x * size.width
                val top = marker.y * size.height
                val width = marker.width * size.width
                val height = marker.height * size.height
                drawRect(Color(0xFFB8E1A9).copy(alpha = 0.2f), Offset(left, top), Size(width, height))
                drawRect(Color(0xFF4B8D4E), Offset(left, top), Size(width, height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Forest,
                shape = RoundedCornerShape(99.dp),
                shadowElevation = 4.dp,
            ) { Text(marker.label, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.SemiBold) }
        }
        if (showScan) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.24f))
                val y = scanProgress.value * size.height
                drawLine(Color(0xFF8BE28A).copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 30.dp.toPx())
                drawLine(Color(0xFF8BE28A).copy(alpha = 0.58f), Offset(0f, y), Offset(size.width, y), strokeWidth = 12.dp.toPx())
                drawLine(Color(0xFFE0FFD1), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(99.dp),
                shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color(0xFFDAF3D4), shape = CircleShape, modifier = Modifier.size(9.dp)) {}
                    Text("ANALYZING PHOTO", color = Forest, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                color = Forest.copy(alpha = 0.97f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFFBDE8A8), strokeWidth = 2.5.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Analyzing your room", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(analysisMessage, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: PlantRecommendation) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Leaf)
                Spacer(Modifier.width(8.dp))
                Text(recommendation.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(recommendation.summary, color = Forest, fontWeight = FontWeight.Medium)
            Text(recommendation.explanation, color = Muted)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E7))) {
        Text(message, color = Color(0xFF7A4C23), modifier = Modifier.padding(16.dp))
    }
}

private fun Context.storePhoto(bytes: ByteArray): Uri {
    val file = java.io.File(cacheDir, "room-${UUID.randomUUID()}.jpg")
    file.writeBytes(bytes)
    return Uri.fromFile(file)
}

private fun requestRecommendation(
    context: Context,
    uri: Uri,
    mode: String,
    preferences: SuggestionPreferences?,
    plantName: String?,
    plantPhotoUri: Uri?,
): RecommendationResponse {
    val spacePhoto = encodeImage(context, uri)
    val plantPhoto = plantPhotoUri?.let { encodeImage(context, it) }
    val requestBody = Json.encodeToString(
        RecommendationRequest.serializer(),
        RecommendationRequest(mode, spacePhoto, preferences, plantName, plantPhoto),
    )
    val connection = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/recommendations").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 100_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
    }
    return try {
        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (responseCode !in 200..299) {
            val message = runCatching { Json.decodeFromString<ErrorResponse>(responseBody).error }.getOrNull()
            error(message ?: "Plant analysis could not finish. Please try again.")
        }
        Json.decodeFromString<RecommendationResponse>(responseBody).also { response ->
            require(response.unclear || response.recommendations.isNotEmpty()) { "No recommendations were returned. Please try again." }
        }
    } finally {
        connection.disconnect()
    }
}

private fun encodeImage(context: Context, uri: Uri): ImageInput {
    val originalBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not read the selected photo.")
    val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
    require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG, or WebP photo." }
    val maxBytes = 2 * 1024 * 1024
    val bytes = if (originalBytes.size <= maxBytes) originalBytes else resizeJpeg(originalBytes)
    require(bytes.size <= maxBytes) { "The photo is too large. Choose a smaller image." }
    val finalMimeType = if (bytes === originalBytes) mimeType else "image/jpeg"
    return ImageInput(finalMimeType, Base64.encodeToString(bytes, Base64.NO_WRAP))
}

private fun resizeJpeg(bytes: ByteArray): ByteArray {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("That photo format could not be opened.")
    val maxDimension = 1280
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
    val resized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
    return ByteArrayOutputStream().use { output ->
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, output)
        if (resized !== source) resized.recycle()
        source.recycle()
        output.toByteArray()
    }
}
