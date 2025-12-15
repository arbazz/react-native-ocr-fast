package com.margelo.nitro.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.margelo.nitro.core.Promise
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
            recognizeTextFromImage(path, null)
        }
    }

    override fun scanImageWithRegion(
        path: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double
    ): Promise<String> {
        return Promise.async {
            val region = Region(x, y, width, height)
            recognizeTextFromImage(path, region)
        }
    }

    private suspend fun recognizeTextFromFrame(frame: Frame): String {
        if (!frame.isValid) {
            throw Exception("Invalid frame")
        }

        // Get the native buffer from frame
        val nativeBuffer = frame.getNativeBuffer()

        // TODO: Convert native buffer to InputImage
        // This depends on your Frame implementation
        // For now, throwing an exception
        throw Exception("scanFrame not yet implemented for Android")
    }

    private suspend fun recognizeTextFromImage(path: String, region: Region?): String {
        // Remove file:// prefix if present
        val cleanPath = if (path.startsWith("file://")) {
            path.substring(7)
        } else {
            path
        }

        // Load image from file
        val file = File(cleanPath)
        if (!file.exists()) {
            throw Exception("Could not load image from path: $cleanPath")
        }

        val bitmap = BitmapFactory.decodeFile(cleanPath)
            ?: throw Exception("Could not decode bitmap from path: $cleanPath")

        // Handle rotation based on EXIF
        val rotatedBitmap = try {
            val exif = android.media.ExifInterface(cleanPath)
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
            
            if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED) {
                 Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                 bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("HybridOcr", "Error reading EXIF", e)
             bitmap
        }

        return performOCR(rotatedBitmap, region)
    }

    private suspend fun performOCR(bitmap: Bitmap, region: Region?): String = suspendCoroutine { continuation ->
        try {
            val processedBitmap = if (region != null) {
                // Crop the bitmap
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height
                
                android.util.Log.d("HybridOcr", " Original Bitmap: ${imageWidth}x${imageHeight}")
                android.util.Log.d("HybridOcr", " Region Normalized: x=${region.x}, y=${region.y}, w=${region.width}, h=${region.height}")
                
                val startX = (region.x * imageWidth).toInt().coerceAtLeast(0)
                val startY = (region.y * imageHeight).toInt().coerceAtLeast(0)
                val width = (region.width * imageWidth).toInt().coerceAtMost(imageWidth - startX)
                val height = (region.height * imageHeight).toInt().coerceAtMost(imageHeight - startY)
                
                android.util.Log.d("HybridOcr", " Calculated Crop: x=$startX, y=$startY, w=$width, h=$height")
                
                if (width <= 0 || height <= 0) {
                     throw Exception("Invalid crop region dimensions")
                }

                Bitmap.createBitmap(bitmap, startX, startY, width, height)
            } else {
                bitmap
            }

            val image = InputImage.fromBitmap(processedBitmap, 0)
            
            // If we cropped, let's save it to a file to return to JS
            // This is optional but good for debugging/UI as seen in Camera.tsx
             var croppedPath: String? = null
             if (region != null) {
                 val cacheDir = System.getProperty("java.io.tmpdir")
                 val file = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
                 java.io.FileOutputStream(file).use { out ->
                     processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                 }
                 croppedPath = file.absolutePath
             }

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    
                    // Construct a simpler JSON-like string manually or just return text
                    // To support the Camera.tsx logic which expects JSON:
                    if (croppedPath != null) {
                        // Very simple JSON construction to avoid adding JSON library dependency if not present
                        // Escaping might be needed for real apps but for simple OCR text it might suffice
                        val cleanText = text.replace("\"", "\\\"").replace("\n", "\\n")
                        val json = "{\"text\": \"$cleanText\", \"croppedImagePath\": \"file://$croppedPath\"}"
                        continuation.resume(json)
                    } else {
                        continuation.resume(text)
                    }
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    override val memorySize: Long
        get() = 0L

    // Helper data class for region
    private data class Region(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )
}