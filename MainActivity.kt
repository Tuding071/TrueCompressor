package com.grey.truerescompressor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max

enum class ImgStatus { QUEUED, ANALYZING, DOWNSCALING, SAVING, DONE, ERROR }

data class ImgItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    var status: ImgStatus = ImgStatus.QUEUED,
    var progress: Int = 0,
    var origW: Int = 0,
    var origH: Int = 0,
    var outW: Int = 0,
    var outH: Int = 0,
    var outUri: Uri? = null,
    var errorMsg: String = "",
    var thumb: Bitmap? = null,
    var logs: MutableList<String> = mutableStateListOf()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ImgItem>() }
    var compareItem by remember { mutableStateOf<ImgItem?>(null) }
    var logItem by remember { mutableStateOf<ImgItem?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newItems = uris.map { uri ->
                ImgItem(uri = uri, name = queryFileName(context, uri) ?: "image_${System.currentTimeMillis()}.jpg")
            }
            items.addAll(newItems)
            scope.launch {
                for (item in newItems) {
                    processImage(context, item)
                }
            }
        }
    }

    when {
        compareItem != null -> CompareScreen(item = compareItem!!, onBack = { compareItem = null })
        logItem != null -> LogScreen(item = logItem!!, onBack = { logItem = null })
        else -> MainScreen(
            items = items,
            onPick = { picker.launch("image/*") },
            onItemClick = { item -> if (item.status == ImgStatus.DONE) compareItem = item },
            onLogClick = { item -> logItem = item }
        )
    }
}

@Composable
fun MainScreen(items: List<ImgItem>, onPick: () -> Unit, onItemClick: (ImgItem) -> Unit, onLogClick: (ImgItem) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("True Resolution Compressor", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Auto-detects real detail and downscales to match.", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Select Images")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No images yet", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    ImgRow(item, onClick = { onItemClick(item) }, onLogClick = { onLogClick(item) })
                }
            }
        }
    }
}

@Composable
fun ImgRow(item: ImgItem, onClick: () -> Unit, onLogClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF0F0F0))
            .clickable {
                if (item.status == ImgStatus.DONE) onClick() else onLogClick()
            }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFDDDDDD)),
                contentAlignment = Alignment.Center
            ) {
                item.thumb?.let {
                    Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(modifier = Modifier.height(2.dp))
                val statusText = when (item.status) {
                    ImgStatus.QUEUED -> "Queued"
                    ImgStatus.ANALYZING -> "Analyzing... ${item.progress}%"
                    ImgStatus.DOWNSCALING -> "Downscaling..."
                    ImgStatus.SAVING -> "Saving..."
                    ImgStatus.DONE -> "${item.origW}x${item.origH} → ${item.outW}x${item.outH}"
                    ImgStatus.ERROR -> "Error: ${item.errorMsg}"
                }
                Text(statusText, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
            }
            if (item.status !in listOf(ImgStatus.DONE, ImgStatus.ERROR, ImgStatus.QUEUED)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onLogClick) { Text("Logs", fontSize = 11.sp) }
        }
        if (item.status == ImgStatus.ANALYZING) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }
    }
}

@Composable
fun LogScreen(item: ImgItem, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(modifier = Modifier.height(8.dp))
        Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Status: ${item.status}", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(item.logs) { line ->
                Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun CompareScreen(item: ImgItem, onBack: () -> Unit) {
    var showOriginal by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val bitmap = remember(showOriginal) {
        if (showOriginal) loadBitmapFromUri(context, item.uri, 1600)
        else item.outUri?.let { loadBitmapFromUri(context, it, 1600) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showOriginal = !showOriginal },
                contentScale = ContentScale.Fit
            )
        }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            TextButton(onClick = onBack) { Text("← Back", color = Color.White) }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val label = if (showOriginal) "ORIGINAL  ${item.origW}x${item.origH}" else "OUTPUT  ${item.outW}x${item.outH}"
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Tap image to toggle",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
}

// ---------- Processing ----------

suspend fun processImage(context: Context, item: ImgItem) {
    fun log(msg: String) {
        item.logs.add(msg)
    }

    item.status = ImgStatus.ANALYZING
    item.progress = 0
    log("Starting: ${item.name}")

    try {
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) throw Exception("Cannot read image dimensions")

            item.origW = origW
            item.origH = origH
            log("Original size: ${origW}x${origH}")

            val analysisBitmap = loadBitmapFromUri(context, item.uri, 3000)
                ?: throw Exception("Decode failed for analysis")

            item.thumb = Bitmap.createScaledBitmap(analysisBitmap, 56, 56, true)
            log("Analysis copy: ${analysisBitmap.width}x${analysisBitmap.height}")

            val bestRatio = findTrueResolutionRatio(analysisBitmap) { step, ratio, score, progress ->
                log("Step $step: ${(ratio * 100).toInt()}% scale -> sharpness score ${"%.4f".format(score)}")
                item.progress = progress
            }

            log("Chosen ratio: ${(bestRatio * 100).toInt()}%")

            item.status = ImgStatus.DOWNSCALING

            val targetW = max(400, (origW * bestRatio).toInt())
            val targetH = max(400, (origH * bestRatio).toInt())

            val fullBitmap = loadBitmapFromUri(context, item.uri, max(origW, origH))
                ?: throw Exception("Full decode failed")

            val output = if (bestRatio >= 0.99) {
                log("No downscale needed — image is already at true resolution")
                fullBitmap
            } else {
                log("Downscaling to ${targetW}x${targetH}")
                Bitmap.createScaledBitmap(fullBitmap, targetW, targetH, true)
            }

            item.status = ImgStatus.SAVING
            val savedUri = saveToMediaStore(context, item.name, output)
                ?: throw Exception("MediaStore save failed")
            log("Saved to: $savedUri")

            item.outW = output.width
            item.outH = output.height
            item.outUri = savedUri
            item.status = ImgStatus.DONE
            log("Done.")
        }
    } catch (e: Exception) {
        item.errorMsg = e.message ?: "Unknown error"
        item.status = ImgStatus.ERROR
        log("ERROR: ${item.errorMsg}")
    }
}

fun saveToMediaStore(context: Context, name: String, bitmap: Bitmap): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TrueRes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val uri = resolver.insert(collection, values) ?: return null

    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    return uri
}

/**
 * Downscale ladder: compute sharpness-per-pixel at each scale step.
 * As we shrink a blurry image, sharpness-per-pixel RISES while we're only removing
 * "empty" upscaled pixels, then FLATTENS once we start cutting into real detail.
 * We want the ratio just BEFORE it flattens.
 *
 * Noise handling: single-step deltas are unreliable (resize artifacts can cause a
 * step to dip even mid-climb). Instead of comparing consecutive steps, we compare
 * each step against the MAX score seen in the previous 2 steps (a rolling window),
 * which smooths out one-off dips while still catching a genuine plateau.
 *
 * If growth never plateaus across the whole ladder (still climbing at the smallest
 * step tested), the true resolution is at or below that smallest step — so we return
 * the smallest tested ratio rather than falling back to "no downscale".
 */
fun findTrueResolutionRatio(
    bitmap: Bitmap,
    onStep: (step: Int, ratio: Double, score: Double, progress: Int) -> Unit
): Double {
    val steps = listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.25, 0.20, 0.15, 0.10)
    val scores = DoubleArray(steps.size)

    for ((i, ratio) in steps.withIndex()) {
        val w = max(1, (bitmap.width * ratio).toInt())
        val h = max(1, (bitmap.height * ratio).toInt())
        val scaled = if (ratio == 1.0) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        scores[i] = laplacianVariancePerPixel(scaled)
        onStep(i + 1, ratio, scores[i], ((i + 1) * 100) / steps.size)
    }

    var weakStreak = 0
    for (i in 2 until steps.size) {
        val windowMax = max(scores[i - 1], scores[i - 2])
        val curr = scores[i]
        if (windowMax <= 0.0) continue
        val gain = (curr - windowMax) / windowMax
        if (gain < 0.05) {
            weakStreak++
            if (weakStreak >= 2) {
                return steps[i - weakStreak]
            }
        } else {
            weakStreak = 0
        }
    }

    return steps.last()
}

fun laplacianVariancePerPixel(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 3 || h < 3) return 0.0

    val stepX = max(1, w / 400)
    val stepY = max(1, h / 400)

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val gray = IntArray(w * h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        gray[i] = (r * 299 + g * 587 + b * 114) / 1000
    }

    var sum = 0.0
    var sumSq = 0.0
    var count = 0

    var y = 1
    while (y < h - 1) {
        var x = 1
        while (x < w - 1) {
            val center = gray[y * w + x]
            val up = gray[(y - 1) * w + x]
            val down = gray[(y + 1) * w + x]
            val left = gray[y * w + (x - 1)]
            val right = gray[y * w + (x + 1)]
            val lap = (up + down + left + right - 4 * center).toDouble()
            sum += lap
            sumSq += lap * lap
            count++
            x += stepX
        }
        y += stepY
    }

    if (count == 0) return 0.0
    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    return variance
}

fun loadBitmapFromUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val longSide = max(bounds.outWidth, bounds.outHeight)
        while (longSide / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}

fun queryFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        null
    }
}
