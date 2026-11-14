package com.hotaro.mascot.ui.challenges

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class YoloDetection(
    val label: String,
    val confidence: Float,
    val box: RectF
)

class YoloV8Detector(context: Context) {
    private val interpreter: Interpreter
    private val inputSize: Int
    private val isInputQuantized: Boolean
    private val isInputSignedInt8: Boolean
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val numChannels: Int
    private val numAnchors: Int
    private val isOutputQuantized: Boolean
    private val isOutputUint8: Boolean
    private val outputScale: Float
    private val outputZeroPoint: Int

    private val pixels: IntArray
    private val inputBuffer: ByteBuffer
    private val outBytes: ByteBuffer?
    private val outRaw: ByteArray?
    private val outFloat: Array<Array<FloatArray>>?

    private val cocoLabels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        val afd = context.assets.openFd("yolov8n.tflite")
        val modelBuffer = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(modelBuffer, options)

        val inTensor = interpreter.getInputTensor(0)
        inputSize = inTensor.shape()[1]
        isInputQuantized = inTensor.dataType() != DataType.FLOAT32
        isInputSignedInt8 = inTensor.dataType() == DataType.INT8
        inputScale = inTensor.quantizationParams().scale.takeIf { it > 0f } ?: (1f / 255f)
        inputZeroPoint = inTensor.quantizationParams().zeroPoint

        val outTensor = interpreter.getOutputTensor(0)
        numChannels = outTensor.shape()[1]
        numAnchors = outTensor.shape()[2]
        isOutputQuantized = outTensor.dataType() != DataType.FLOAT32
        isOutputUint8 = outTensor.dataType() == DataType.UINT8
        outputScale = outTensor.quantizationParams().scale.takeIf { it > 0f } ?: 1f
        outputZeroPoint = outTensor.quantizationParams().zeroPoint

        pixels = IntArray(inputSize * inputSize)
        val bytesPerChannel = if (isInputQuantized) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(bytesPerChannel * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())

        if (isOutputQuantized) {
            outBytes = ByteBuffer.allocateDirect(numChannels * numAnchors).order(ByteOrder.nativeOrder())
            outRaw = ByteArray(numChannels * numAnchors)
            outFloat = null
        } else {
            outBytes = null
            outRaw = null
            outFloat = Array(1) { Array(numChannels) { FloatArray(numAnchors) } }
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): YoloDetection? {
        val scaled = if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        }
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()

        if (isInputQuantized) {
            val minQ = if (isInputSignedInt8) -128 else 0
            val maxQ = if (isInputSignedInt8) 127 else 255
            val invScale = 1f / (255f * inputScale)
            for (px in pixels) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                inputBuffer.put(((r * invScale) + inputZeroPoint).toInt().coerceIn(minQ, maxQ).toByte())
                inputBuffer.put(((g * invScale) + inputZeroPoint).toInt().coerceIn(minQ, maxQ).toByte())
                inputBuffer.put(((b * invScale) + inputZeroPoint).toInt().coerceIn(minQ, maxQ).toByte())
            }
        } else {
            val norm = 1f / 255f
            for (px in pixels) {
                inputBuffer.putFloat(((px shr 16) and 0xFF) * norm)
                inputBuffer.putFloat(((px shr 8) and 0xFF) * norm)
                inputBuffer.putFloat((px and 0xFF) * norm)
            }
        }
        inputBuffer.rewind()

        val numClasses = min(cocoLabels.size, numChannels - 4)
        var bestClass = -1
        var bestAnchor = -1
        var bestConf = 0.25f

        if (isOutputQuantized && outBytes != null && outRaw != null) {
            outBytes.rewind()
            interpreter.run(inputBuffer, outBytes)
            outBytes.rewind()
            outBytes.get(outRaw)

            var bestRaw = ((bestConf / outputScale) + outputZeroPoint).toInt()
            for (c in 0 until numClasses) {
                val rowOffset = (4 + c) * numAnchors
                for (a in 0 until numAnchors) {
                    val b = outRaw[rowOffset + a]
                    val v = if (isOutputUint8) (b.toInt() and 0xFF) else b.toInt()
                    if (v > bestRaw) {
                        bestRaw = v
                        bestClass = c
                        bestAnchor = a
                    }
                }
            }
            if (bestClass == -1) return null
            bestConf = (bestRaw - outputZeroPoint) * outputScale

            fun readCoord(ch: Int): Float {
                val b = outRaw[ch * numAnchors + bestAnchor]
                val v = if (isOutputUint8) (b.toInt() and 0xFF) else b.toInt()
                return (v - outputZeroPoint) * outputScale
            }
            val cx = readCoord(0)
            val cy = readCoord(1)
            val w = readCoord(2)
            val h = readCoord(3)
            val scaleNorm = if (cx > 2f) 1f / inputSize else 1f
            return YoloDetection(
                cocoLabels[bestClass],
                bestConf.coerceIn(0f, 1f),
                RectF(
                    max(0f, (cx - w / 2f) * scaleNorm),
                    max(0f, (cy - h / 2f) * scaleNorm),
                    min(1f, (cx + w / 2f) * scaleNorm),
                    min(1f, (cy + h / 2f) * scaleNorm)
                )
            )
        } else if (outFloat != null) {
            interpreter.run(inputBuffer, outFloat)
            val data = outFloat[0]
            for (c in 0 until numClasses) {
                val row = data[4 + c]
                for (a in 0 until numAnchors) {
                    val conf = row[a]
                    if (conf > bestConf) {
                        bestConf = conf
                        bestClass = c
                        bestAnchor = a
                    }
                }
            }
            if (bestClass == -1) return null
            val cx = data[0][bestAnchor]
            val cy = data[1][bestAnchor]
            val w = data[2][bestAnchor]
            val h = data[3][bestAnchor]
            val scaleNorm = if (cx > 2f) 1f / inputSize else 1f
            return YoloDetection(
                cocoLabels[bestClass],
                bestConf.coerceIn(0f, 1f),
                RectF(
                    max(0f, (cx - w / 2f) * scaleNorm),
                    max(0f, (cy - h / 2f) * scaleNorm),
                    min(1f, (cx + w / 2f) * scaleNorm),
                    min(1f, (cy + h / 2f) * scaleNorm)
                )
            )
        }
        return null
    }

    fun close() {
        interpreter.close()
    }
}

private fun matchesTarget(detected: String, target: String): Boolean {
    val d = detected.trim().lowercase()
    val t = target.trim().lowercase()
    if (d == t || d.contains(t) || t.contains(d)) return true
    return when (t) {
        "television" -> d == "tv"
        "mug" -> d == "cup"
        "plate" -> d == "bowl"
        "phone" -> d == "cell phone"
        else -> false
    }
}

@Composable
fun CameraChallengeView(targetObject: String, onObjectDetected: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val detectorResult = remember { runCatching { YoloV8Detector(context) } }
    val detector = detectorResult.getOrNull()
    val initError = detectorResult.exceptionOrNull()?.message
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val currentMatch = remember { mutableStateOf<YoloDetection?>(null) }
    var triggered by remember { mutableStateOf(false) }
    var streak by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(320, 320))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor) { imageProxy ->
                                processYoloFrame(detector, imageProxy, targetObject, { det ->
                                    currentMatch.value = det
                                    if (det != null && matchesTarget(det.label, targetObject)) {
                                        streak++
                                        if (!triggered && (det.confidence >= 0.45f || (det.confidence >= 0.35f && streak >= 3))) {
                                            triggered = true
                                            mainExecutor.execute { onObjectDetected() }
                                        }
                                    } else {
                                        streak = 0
                                    }
                                })
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (_: Exception) {}
                }, mainExecutor)
                previewView
            }
        )

        val match = currentMatch.value
        if (match != null) {
            val isTarget = matchesTarget(match.label, targetObject)
            val boxColor = if (isTarget) Color(0xFF4CAF50) else Color(0xFFFFC107)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = match.box.left * size.width
                val top = match.box.top * size.height
                val w = (match.box.right - match.box.left) * size.width
                val h = (match.box.bottom - match.box.top) * size.height
                drawRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    style = Stroke(width = 6f)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.75f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    if (initError != null) {
                        Text("AI Error: $initError", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    } else if (match != null) {
                        val isTarget = matchesTarget(match.label, targetObject)
                        val color = if (isTarget) Color(0xFF4CAF50) else Color.White
                        val pct = (match.confidence * 100).toInt()
                        Text(
                            text = "${match.label.replaceFirstChar { it.uppercase() }}: $pct%",
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    } else {
                        Text("Point at object...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Find: $targetObject",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

private fun processYoloFrame(
    detector: YoloV8Detector?,
    imageProxy: ImageProxy,
    targetObject: String,
    onDetectionUpdate: (YoloDetection?) -> Unit
) {
    try {
        if (detector == null) return
        val rawBmp = imageProxy.toBitmap()
        val rot = imageProxy.imageInfo.rotationDegrees.toFloat()
        val bmp = if (rot != 0f) {
            val m = Matrix().apply { postRotate(rot) }
            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, false)
        } else {
            rawBmp
        }
        onDetectionUpdate(detector.detect(bmp))
    } catch (_: Exception) {
    } finally {
        imageProxy.close()
    }
}
