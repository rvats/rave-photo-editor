package com.ravemanagementllc.ravephotoeditor.edit

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Finds the main subject of a photo with ML Kit's on-device subject segmentation model. */
class SubjectMasker(context: Context) {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )

    private val modules = ModuleInstall.getClient(context.applicationContext)

    /** Asks Google Play services to download the on-device model now if it isn't on the phone yet. */
    fun prefetch() {
        modules.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build())
    }

    /** Returns, for every pixel, the model's confidence (0..1) that it belongs to the subject. */
    suspend fun mask(bitmap: Bitmap): FloatArray {
        val ready = try {
            modules.areModulesAvailable(segmenter).await().areModulesAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true // Play services couldn't say; try anyway and report its error if it fails
        }
        if (!ready) {
            prefetch()
            throw ModelDownloadingException()
        }
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
        private const val DOWNLOADING =
            "The AI model is still downloading through Google Play services. Please try again in a minute."

        fun friendlyMessage(e: Throwable): String = when {
            e is ModelDownloadingException -> DOWNLOADING
            e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE -> DOWNLOADING
            e is OutOfMemoryError -> "This photo is too large for this phone. Try a smaller one."
            e.message.isNullOrBlank() -> "That didn't work on this photo. Please try another one."
            else -> e.message!!
        }
    }
}

/** The subject segmentation model isn't on the phone yet; Google Play services is fetching it. */
class ModelDownloadingException : Exception("The AI model is still downloading.")
