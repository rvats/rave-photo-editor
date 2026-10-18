package com.ravemanagementllc.ravephotoeditor.edit

import android.graphics.Bitmap
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/** Finds the main subject of a photo with ML Kit's on-device subject segmentation model. */
class SubjectMasker {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )

    /** Returns, for every pixel, the model's confidence (0..1) that it belongs to the subject. */
    suspend fun mask(bitmap: Bitmap): FloatArray {
        val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val buffer = result.foregroundConfidenceMask
            ?: throw IllegalStateException("No subject was found in this photo.")
        buffer.rewind()
        val size = bitmap.width * bitmap.height
        if (buffer.remaining() < size) throw IllegalStateException("The AI mask didn't match the photo size.")
        val out = FloatArray(size)
        buffer.get(out)
        return out
    }

    fun close() {
        segmenter.close()
    }

    companion object {
        fun friendlyMessage(e: Throwable): String = when {
            e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE ->
                "The AI model is still downloading through Google Play services. Please try again in a minute."
            e is OutOfMemoryError -> "This photo is too large for this phone. Try a smaller one."
            e.message.isNullOrBlank() -> "That didn't work on this photo. Please try another one."
            else -> e.message!!
        }
    }
}
