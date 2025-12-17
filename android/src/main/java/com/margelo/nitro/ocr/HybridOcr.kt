package com.margelo.nitro.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.margelo.nitro.core.Promise
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

class HybridOcr : HybridOcrSpec() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun scan(input: String): String {
        return "scanned $input"
    }

    override fun scanFrame(frame: Frame): Promise<String> {
        return Promise.async {
            recognizeTextFromFrame(frame)
        }
    }

    override fun scanImage(path: String): Promise<String> {
        return Promise.async {
            recognizeTextFromImage(path, null, false, 1.0, false)
        }
    }

    override fun scanImageWithRegion(
        path: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        digitsOnly: Boolean?,
        contrast: Double?,
        useTesseract: Boolean?
    ): Promise<String> {
        return Promise.async {
            val region = Region(x, y, width, height)
            recognizeTextFromImage(path, region, digitsOnly ?: false, contrast ?: 1.0, useTesseract ?: false)
        }
    }

    private suspend fun recognizeTextFromFrame(frame: Frame): String {
        if (!frame.isValid) {
            throw Exception("Invalid frame")
        }

        val nativeBuffer = frame.getNativeBuffer()
        throw Exception("scanFrame not yet implemented for Android")
    }

    private suspend fun recognizeTextFromImage(
        path: String, 
        region: Region?, 
        digitsOnly: Boolean, 
        contrast: Double,
        useTesseract: Boolean
    ): String {
        val cleanPath = if (path.startsWith("file://")) {
            path.substring(7)
        } else {
            path
        }

        val file = File(cleanPath)
        if (!file.exists()) {
            throw Exception("Could not load image from path: $cleanPath")
        }

        // Load with better options for quality
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        
        val bitmap = BitmapFactory.decodeFile(cleanPath, options)
            ?: throw Exception("Could not decode bitmap from path: $cleanPath")

        val rotatedBitmap = handleRotation(bitmap, cleanPath)

        return performOCR(rotatedBitmap, region, digitsOnly, contrast, useTesseract)
    }

    private fun handleRotation(bitmap: Bitmap, path: String): Bitmap {
        return try {
            val exif = android.media.ExifInterface(path)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_UNDEFINED
            )

            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            
            if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL && 
                orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("HybridOcr", "Error reading EXIF", e)
            bitmap
        }
    }

    private suspend fun performOCR(
        bitmap: Bitmap, 
        region: Region?, 
        digitsOnly: Boolean, 
        contrast: Double,
        useTesseract: Boolean
    ): String = suspendCoroutine { continuation ->
        try {
            // Step 1: Crop if region specified
            val croppedBitmap = if (region != null) {
                cropBitmap(bitmap, region)
            } else {
                bitmap
            }
            
            // Step 2: Upscale if image is too small (critical for accuracy)
            val upscaledBitmap = upscaleIfNeeded(croppedBitmap)
            
            // Step 3: Preprocessing for better OCR accuracy
            val preprocessedBitmap = preprocessForOCR(upscaledBitmap, contrast, digitsOnly)
            
            // Save processed image for debugging/return
            val processedPath = if (region != null || contrast != 1.0) {
                saveProcessedImage(preprocessedBitmap)
            } else {
                null
            }

            if (useTesseract) {
                // Perform Tesseract OCR
                try {
                    val tesseract = com.googlecode.tesseract.android.TessBaseAPI()
                    // Assuming tessdata is in external storage or we can't Init. 
                    // For library usage, this path usually comes from the user or default location
                    val dataPath = "/storage/emulated/0/Download/tessdata" // Simplification for demo
                    // In a real app, you'd copy assets to filesDir. 
                    // Since we can't access context easily here to copy assets, we assume user provided it
                    // Or we fallback to MLKit if init fails
                    
                    // Simple logic: We will assume specific path or fail
                    if (!File(dataPath).exists()) {
                         throw Exception("Tessdata not found at $dataPath. Please ensure tessdata/eng.traineddata exists.")
                    }
                    
                    if (!tesseract.init(File(dataPath).parent, "eng")) {
                        throw Exception("Tesseract initialization failed")
                    }
                    
                    if (digitsOnly) {
                        tesseract.setVariable(com.googlecode.tesseract.android.TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789.,-")
                    }
                    
                    tesseract.setImage(preprocessedBitmap)
                    val text = tesseract.utF8Text
                    tesseract.stop()
                    tesseract.recycle()
                    
                    android.util.Log.d("HybridOcr", "Tesseract result: $text")

                    if (processedPath != null) {
                        val cleanText = text.replace("\"", "\\\"").replace("\n", "\\n")
                        val json = "{\"text\": \"$cleanText\", \"croppedImagePath\": \"file://$processedPath\"}"
                        continuation.resume(json)
                    } else {
                        continuation.resume(text)
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            } else {
                // Perform MLKit OCR (default)
                val image = InputImage.fromBitmap(preprocessedBitmap, 0)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        var text = visionText.text
                        
                        android.util.Log.d("HybridOcr", "Raw OCR result: $text")
                        
                        if (digitsOnly) {
                            text = text.filter { it.isDigit() || it == '\n' || it == '.' || it == ',' || it == '-' }
                        }
                        
                        if (processedPath != null) {
                            val cleanText = text.replace("\"", "\\\"").replace("\n", "\\n")
                            val json = "{\"text\": \"$cleanText\", \"croppedImagePath\": \"file://$processedPath\"}"
                            continuation.resume(json)
                        } else {
                            continuation.resume(text)
                        }
                    }
                    .addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    private fun cropBitmap(bitmap: Bitmap, region: Region): Bitmap {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        
        android.util.Log.d("HybridOcr", "Original Bitmap: ${imageWidth}x${imageHeight}")
        android.util.Log.d("HybridOcr", "Region: x=${region.x}, y=${region.y}, w=${region.width}, h=${region.height}")
        
        val startX = (region.x * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val startY = (region.y * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
        val width = (region.width * imageWidth).roundToInt().coerceAtMost(imageWidth - startX)
        val height = (region.height * imageHeight).roundToInt().coerceAtMost(imageHeight - startY)
        
        android.util.Log.d("HybridOcr", "Crop: x=$startX, y=$startY, w=$width, h=$height")
        
        if (width <= 0 || height <= 0) {
            throw Exception("Invalid crop region dimensions")
        }

        return Bitmap.createBitmap(bitmap, startX, startY, width, height)
    }

    private fun upscaleIfNeeded(bitmap: Bitmap): Bitmap {
        // ML Kit works best with images at least 640px on the smallest side
        val minDimension = 640
        val currentMin = minOf(bitmap.width, bitmap.height)
        
        if (currentMin < minDimension) {
            val scale = minDimension.toFloat() / currentMin
            val newWidth = (bitmap.width * scale).roundToInt()
            val newHeight = (bitmap.height * scale).roundToInt()
            
            android.util.Log.d("HybridOcr", "Upscaling from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
            
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
        
        return bitmap
    }

    private fun preprocessForOCR(bitmap: Bitmap, contrast: Double, digitsOnly: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint()
        
        // Apply image enhancements
        val colorMatrix = ColorMatrix()
        
        // 1. Adjust contrast (more aggressive for digits)
        val contrastValue = if (digitsOnly) {
            (contrast * 1.3).toFloat().coerceIn(1.0f, 2.5f)
        } else {
            contrast.toFloat().coerceIn(1.0f, 2.0f)
        }
        
        // 2. Increase sharpness
        val sharpnessMatrix = ColorMatrix(floatArrayOf(
            0f, -1f, 0f, 0f, 0f,
            -1f, 5f, -1f, 0f, 0f,
            0f, -1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        
        // 3. Apply contrast
        val scale = contrastValue
        val translate = (-.5f * scale + .5f) * 255.0f
        
        colorMatrix.set(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        
        // 4. Increase brightness slightly for better recognition
        val brightnessAdjust = if (digitsOnly) 10f else 5f
        val brightnessMatrix = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, brightnessAdjust,
            0f, 1f, 0f, 0f, brightnessAdjust,
            0f, 0f, 1f, 0f, brightnessAdjust,
            0f, 0f, 0f, 1f, 0f
        ))
        
        colorMatrix.postConcat(brightnessMatrix)
        
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        paint.isAntiAlias = true
        paint.isDither = false
        paint.isFilterBitmap = false
        
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        android.util.Log.d("HybridOcr", "Applied preprocessing with contrast=$contrastValue")
        
        return outputBitmap
    }

    private fun saveProcessedImage(bitmap: Bitmap): String {
        val cacheDir = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
        val file = File(cacheDir, "processed_${System.currentTimeMillis()}.jpg")
        
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        
        android.util.Log.d("HybridOcr", "Saved processed image to: ${file.absolutePath}")
        
        return file.absolutePath
    }

    override val memorySize: Long
        get() = 0L

    private data class Region(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )
}