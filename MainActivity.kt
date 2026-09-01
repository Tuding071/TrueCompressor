package com.grey.truerescompressor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ImgStatus { QUEUED, PROCESSING, DONE, ERROR }

data class ImgItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    var status: ImgStatus = ImgStatus.QUEUED,
    var origW: Int = 0,
    var origH: Int = 0,
    var outW: Int = 0,
    var outH: Int = 0,
    var outPath: String = "",
    var errorMsg: String = "",
    var thumb: Bitmap? = null
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

    if (compareItem != null) {
        CompareScreen(item = compareItem!!, onBack = { compareItem = null })
    } else {
        MainScreen(
            items = items,
            onPick = { picker.launch("image/*") },
            onItemClick = { item -> if (item.status == ImgStatus.DONE) compareItem = item }
        )
    }
}

@Composable
fun MainScreen(items: List<ImgItem>, onPick: () -> Unit, onItemClick: (ImgItem) -> Unit) {
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
                    ImgRow(item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
fun ImgRow(item: ImgItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF0F0F0))
            .clickable(enabled = item.status == ImgStatus.DONE) { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                ImgStatus.PROCESSING -> "Processing..."
                ImgStatus.DONE -> "${item.origW}x${item.origH} → ${item.outW}x${item.outH}"
                ImgStatus.ERROR -> "Error: ${item.errorMsg}"
            }
            Text(statusText, fontSize = 12.sp, color = Color.Gray)
        }
        if (item.status == ImgStatus.PROCESSING) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun CompareScreen(item: ImgItem, onBack: () -> Unit) {
    var showOriginal by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val bitmap = remember(showOriginal) {
        if (showOriginal) loadBitmapFromUri(context, item.uri, 1600)
        else BitmapFactory.decodeFile(item.outPath)
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
    item.status = ImgStatus.PROCESSING
    try {
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) throw Exception("Cannot read image")

            item.origW = origW
            item.origH = origH

            // Working copy capped at 3000px long side for analysis speed
            val analysisBitmap = loadBitmapFromUri(context, item.uri, 3000)
                ?: throw Exception("Decode failed")

            item.thumb = Bitmap.createScaledBitmap(analysisBitmap, 56, 56, true)

            val bestRatio = findTrueResolutionRatio(analysisBitmap)

            val targetW = max(400, (origW * bestRatio).toInt())
            val targetH = max(400, (origH * bestRatio).toInt())

            val fullBitmap = loadBitmapFromUri(context, item.uri, max(origW, origH))
                ?: throw Exception("Full decode failed")

            val output = if (bestRatio >= 0.99) {
                fullBitmap
            } else {
                Bitmap.createScaledBitmap(fullBitmap, targetW, targetH, true)
            }

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TrueRes")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, item.name)
            FileOutputStream(outFile).use { fos ->
                output.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }

            item.outW = output.width
            item.outH = output.height
            item.outPath = outFile.absolutePath
            item.status = ImgStatus.DONE
        }
    } catch (e: Exception) {
        item.errorMsg = e.message ?: "Unknown error"
        item.status = ImgStatus.ERROR
    }
}

// Downscale ladder: find where sharpness-per-pixel plateaus
fun findTrueResolutionRatio(bitmap: Bitmap): Double {
    val steps = listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.25)
    var prevScore = -1.0
    var bestRatio = 1.0

    for (ratio in steps) {
        val w = max(1, (bitmap.width * ratio).toInt())
        val h = max(1, (bitmap.height * ratio).toInt())
        val scaled = if (ratio == 1.0) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val score = laplacianVariancePerPixel(scaled)

        if (prevScore >= 0) {
            val gain = (score - prevScore) / prevScore
            if (gain < 0.02) {
                // plateaued — previous ratio was the true resolution
                return bestRatio
            }
        }
        prevScore = score
        bestRatio = ratio
    }
    return bestRatio
}

// Laplacian variance (edge energy) normalized per pixel, computed on a grayscale downsample for speed
fun laplacianVariancePerPixel(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 3 || h < 3) return 0.0

    // Sample on a grid to keep this fast (every 2nd pixel) for large images
    val stepX = max(1, w / 400)
    val stepY = max(1, h / 400)

    val gray = IntArray(w * h)
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
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
    return variance / count // normalize per sampled pixel
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
