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
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val numChannels: Int
    private val numAnchors: Int
    private val isOutputQuantized: Boolean
    private val outputScale: Float
    private val outputZeroPoint: Int

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
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })

        val inTensor = interpreter.getInputTensor(0)
        inputSize = inTensor.shape()[1]
        isInputQuantized = inTensor.dataType() != DataType.FLOAT32
        inputScale = inTensor.quantizationParams().scale.takeIf { it > 0f } ?: (1f / 255f)
        inputZeroPoint = inTensor.quantizationParams().zeroPoint

        val outTensor = interpreter.getOutputTensor(0)
        numChannels = outTensor.shape()[1]
        numAnchors = outTensor.shape()[2]
        isOutputQuantized = outTensor.dataType() != DataType.FLOAT32
        outputScale = outTensor.quantizationParams().scale.takeIf { it > 0f } ?: 1f
        outputZeroPoint = outTensor.quantizationParams().zeroPoint
    }

    fun detect(bitmap: Bitmap): YoloDetection? {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val inputBuffer = if (isInputQuantized) {
            ByteBuffer.allocateDirect(inputSize * inputSize * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    put(((r / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte())
                    put(((g / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte())
                    put(((b / inputScale) + inputZeroPoint).toInt().coerceIn(-128, 255).toByte())
                }
                rewind()
            }
        } else {
            ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    putFloat(((px shr 16) and 0xFF) / 255f)
                    putFloat(((px shr 8) and 0xFF) / 255f)
                    putFloat((px and 0xFF) / 255f)
                }
                rewind()
            }
        }

        val numClasses = min(cocoLabels.size, numChannels - 4)
        var bestConf = 0.45f
        var bestClass = -1
        var bestBox = RectF()

        if (isOutputQuantized) {
            val outBytes = ByteBuffer.allocateDirect(numChannels * numAnchors).order(ByteOrder.nativeOrder())
            interpreter.run(inputBuffer, outBytes)
            outBytes.rewind()
            val raw = ByteArray(numChannels * numAnchors)
            outBytes.get(raw)
            val isUint8 = interpreter.getOutputTensor(0).dataType() == DataType.UINT8

            fun readVal(c: Int, a: Int): Float {
                val b = raw[c * numAnchors + a]
                val v = if (isUint8) (b.toInt() and 0xFF) else b.toInt()
                return (v - outputZeroPoint) * outputScale
            }

            for (a in 0 until numAnchors) {
                for (c in 0 until numClasses) {
                    val conf = readVal(4 + c, a)
                    if (conf > bestConf) {
                        bestConf = conf
                        bestClass = c
                        val cx = readVal(0, a)
                        val cy = readVal(1, a)
                        val w = readVal(2, a)
                        val h = readVal(3, a)
                        val scaleNorm = if (cx > 2f) 1f / inputSize else 1f
                        bestBox = RectF(
                            max(0f, (cx - w / 2f) * scaleNorm),
                            max(0f, (cy - h / 2f) * scaleNorm),
                            min(1f, (cx + w / 2f) * scaleNorm),
                            min(1f, (cy + h / 2f) * scaleNorm)
                        )
                    }
                }
            }
        } else {
            val outArr = Array(1) { Array(numChannels) { FloatArray(numAnchors) } }
            interpreter.run(inputBuffer, outArr)
            val data = outArr[0]
            for (a in 0 until numAnchors) {
                for (c in 0 until numClasses) {
                    val conf = data[4 + c][a]
                    if (conf > bestConf) {
                        bestConf = conf
                        bestClass = c
                        val cx = data[0][a]
                        val cy = data[1][a]
                        val w = data[2][a]
                        val h = data[3][a]
                        val scaleNorm = if (cx > 2f) 1f / inputSize else 1f
                        bestBox = RectF(
                            max(0f, (cx - w / 2f) * scaleNorm),
                            max(0f, (cy - h / 2f) * scaleNorm),
                            min(1f, (cx + w / 2f) * scaleNorm),
                            min(1f, (cy + h / 2f) * scaleNorm)
                        )
                    }
                }
            }
        }

        if (bestClass == -1) return null
        return YoloDetection(cocoLabels[bestClass], bestConf, bestBox)
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
    val detector = remember { runCatching { YoloV8Detector(context) }.getOrNull() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val currentMatch = remember { mutableStateOf<YoloDetection?>(null) }
    var triggered by remember { mutableStateOf(false) }

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
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor) { imageProxy ->
                                processYoloFrame(detector, imageProxy, targetObject, { det ->
                                    currentMatch.value = det
                                }, {
                                    if (!triggered) {
                                        triggered = true
                                        mainExecutor.execute { onObjectDetected() }
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
                    if (match != null) {
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
                        Text("YOLOv8 Scanning...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
    onDetectionUpdate: (YoloDetection?) -> Unit,
    onSuccess: () -> Unit
) {
    try {
        if (detector == null) return
        val rawBmp = imageProxy.toBitmap()
        val rot = imageProxy.imageInfo.rotationDegrees.toFloat()
        val bmp = if (rot != 0f) {
            val m = Matrix().apply { postRotate(rot) }
            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
        } else {
            rawBmp
        }

        val det = detector.detect(bmp)
        onDetectionUpdate(det)

        if (det != null && matchesTarget(det.label, targetObject) && det.confidence >= 0.55f) {
            onSuccess()
        }
    } catch (_: Exception) {
    } finally {
        imageProxy.close()
    }
}
