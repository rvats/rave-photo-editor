package com.ravemanagementllc.ravephotoeditor.edit

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Filter(val label: String) {
    Original("Original"), Vivid("Vivid"), Warm("Warm"), Cool("Cool"),
    Fade("Fade"), Mono("Mono"), Noir("Noir"), Sepia("Sepia")
}

sealed interface Backdrop {
    data object Keep : Backdrop
    data object Transparent : Backdrop
    data class Blur(val strength: Int) : Backdrop
    data class Color(val argb: Int) : Backdrop
}

data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f
}

data class EditState(
    val backdrop: Backdrop = Backdrop.Keep,
    val enhance: Boolean = false,
    val filter: Filter = Filter.Original,
    val adjust: Adjust = Adjust()
) {
    val isOriginal: Boolean get() = this == EditState()
}

data class UiState(
    val source: Bitmap? = null,
    val output: Bitmap? = null,
    val edit: EditState = EditState(),
    val busy: String? = null,
    val thumbnails: Map<Filter, Bitmap> = emptyMap()
)

fun filterMatrix(f: Filter): ColorMatrix? = when (f) {
    Filter.Original -> null
    Filter.Vivid -> ImageOps.chain(ImageOps.saturation(1.35f), ImageOps.contrast(1.08f))
    Filter.Warm -> ImageOps.chain(ImageOps.scale(1.08f, 1.0f, 0.90f), ImageOps.saturation(1.05f))
    Filter.Cool -> ImageOps.chain(ImageOps.scale(0.92f, 1.0f, 1.08f), ImageOps.saturation(0.98f))
    Filter.Fade -> ImageOps.chain(ImageOps.saturation(0.8f), ImageOps.contrast(0.85f), ImageOps.brightness(12f))
    Filter.Mono -> ImageOps.saturation(0f)
    Filter.Noir -> ImageOps.chain(ImageOps.saturation(0f), ImageOps.contrast(1.35f))
    Filter.Sepia -> ImageOps.sepia()
}

fun adjustMatrix(a: Adjust): ColorMatrix = ImageOps.chain(
    ImageOps.brightness(a.brightness * 60f),
    ImageOps.contrast(1f + a.contrast * 0.5f),
    ImageOps.saturation(1f + a.saturation),
    ImageOps.scale(1f + a.warmth * 0.12f, 1f, 1f - a.warmth * 0.12f)
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val masker = SubjectMasker(app)

    init {
        // Start fetching the on-device AI model early, so it's ready by the time a photo is open.
        masker.prefetch()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var sourceUri: Uri? = null
    private var previewMask: FloatArray? = null
    private var previewEnhance: ColorMatrix? = null
    private var renderJob: Job? = null

    fun open(uri: Uri) {
        renderJob?.cancel()
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Opening photo…") }
            try {
                val bmp = withContext(Dispatchers.IO) { ImageOps.decode(getApplication(), uri, PREVIEW_MAX) }
                val thumbs = withContext(Dispatchers.Default) { buildThumbnails(bmp) }
                sourceUri = uri
                previewMask = null
                previewEnhance = null
                _ui.value = UiState(source = bmp, output = bmp, thumbnails = thumbs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null) }
                _messages.tryEmit("Couldn't open that photo.")
            }
        }
    }

    fun setBackdrop(b: Backdrop) = update { it.copy(backdrop = b) }
    fun setEnhance(on: Boolean) = update { it.copy(enhance = on) }
    fun setFilter(f: Filter) = update { it.copy(filter = f) }
    fun setAdjust(a: Adjust) = update { it.copy(adjust = a) }
    fun resetAll() = update { EditState() }

    private fun update(change: (EditState) -> EditState) {
        val src = _ui.value.source ?: return
        val edit = change(_ui.value.edit)
        _ui.update { it.copy(edit = edit) }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            try {
                val out = render(
                    src, edit,
                    maskOf = { bmp ->
                        previewMask ?: run {
                            _ui.update { it.copy(busy = "Finding the subject…") }
                            masker.mask(bmp).also { previewMask = it }
                        }
                    },
                    enhanceOf = { bmp -> previewEnhance ?: ImageOps.autoEnhanceMatrix(bmp).also { previewEnhance = it } }
                )
                _ui.update { if (it.edit == edit) it.copy(output = out, busy = null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null) }
                _messages.tryEmit(SubjectMasker.friendlyMessage(e))
                if (edit.backdrop != Backdrop.Keep) update { it.copy(backdrop = Backdrop.Keep) }
            }
        }
    }

    private suspend fun render(
        src: Bitmap,
        edit: EditState,
        maskOf: suspend (Bitmap) -> FloatArray,
        enhanceOf: (Bitmap) -> ColorMatrix
    ): Bitmap {
        val mask = if (edit.backdrop != Backdrop.Keep) maskOf(src) else null
        return withContext(Dispatchers.Default) {
            val parts = mutableListOf<ColorMatrix>()
            if (edit.enhance) parts += enhanceOf(src)
            filterMatrix(edit.filter)?.let { parts += it }
            if (!edit.adjust.isNeutral) parts += adjustMatrix(edit.adjust)
            val colored = if (parts.isEmpty()) src else ImageOps.applyColor(src, ImageOps.chain(*parts.toTypedArray()))
            ensureActive()
            when (val b = edit.backdrop) {
                Backdrop.Keep -> colored
                Backdrop.Transparent -> ImageOps.composite(colored, mask!!, null, null)
                is Backdrop.Blur -> {
                    val blurred = ImageOps.blurred(colored, b.strength)
                    ensureActive()
                    ImageOps.composite(colored, mask!!, blurred, null)
                }
                is Backdrop.Color -> ImageOps.composite(colored, mask!!, null, b.argb)
            }
        }
    }

    /** Renders the edit at full size (up to [FULL_MAX] px). */
    private suspend fun renderFull(): Pair<Bitmap, Boolean> {
        val uri = sourceUri ?: error("No photo is open")
        val edit = _ui.value.edit
        val full = withContext(Dispatchers.IO) { ImageOps.decode(getApplication(), uri, FULL_MAX) }
        val out = render(full, edit, maskOf = { masker.mask(it) }, enhanceOf = { ImageOps.autoEnhanceMatrix(it) })
        return out to (edit.backdrop == Backdrop.Transparent)
    }

    fun save() {
        if (sourceUri == null || _ui.value.busy != null) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Saving…") }
            try {
                val (bmp, png) = renderFull()
                withContext(Dispatchers.IO) { ImageOps.saveToGallery(getApplication(), bmp, png) }
                _messages.tryEmit("Saved to Pictures/RAVE Photo Editor")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _messages.tryEmit("Couldn't save. " + SubjectMasker.friendlyMessage(e))
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    fun share(onReady: (Intent) -> Unit) {
        if (sourceUri == null || _ui.value.busy != null) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Preparing to share…") }
            try {
                val (bmp, png) = renderFull()
                val intent = withContext(Dispatchers.IO) { ImageOps.shareIntent(getApplication(), bmp, png) }
                onReady(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _messages.tryEmit("Couldn't share. " + SubjectMasker.friendlyMessage(e))
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    private fun buildThumbnails(src: Bitmap): Map<Filter, Bitmap> {
        val s = min(1f, 160f / max(src.width, src.height).toFloat())
        val small = Bitmap.createScaledBitmap(
            src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true
        )
        return Filter.entries.associateWith { f -> filterMatrix(f)?.let { ImageOps.applyColor(small, it) } ?: small }
    }

    override fun onCleared() {
        masker.close()
    }

    companion object {
        const val PREVIEW_MAX = 1280
        const val FULL_MAX = 2560
    }
}
