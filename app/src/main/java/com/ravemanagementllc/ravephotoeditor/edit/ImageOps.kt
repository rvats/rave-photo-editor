package com.ravemanagementllc.ravephotoeditor.edit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel operations used by the editor. Everything runs on the device. */
object ImageOps {

    /** Decodes a photo, honouring its EXIF rotation, scaled so the longer side is at most [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxSide.toFloat() / max(w, h).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
        return if (decoded.config == Bitmap.Config.ARGB_8888) decoded
        else decoded.copy(Bitmap.Config.ARGB_8888, true)
    }

    // ---------------------------------------------------------------- colour matrices

    fun scale(r: Float, g: Float, b: Float) = ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    fun brightness(offset: Float) = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /** Multiplies distance from mid-grey by [c]. */
    fun contrast(c: Float): ColorMatrix {
        val t = 127.5f * (1f - c)
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    fun sepia() = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /** Combines matrices so they apply in the order given. */
    fun chain(vararg matrices: ColorMatrix): ColorMatrix {
        val out = ColorMatrix()
        for (m in matrices) out.postConcat(m)
        return out
    }

    fun applyColor(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * One-tap enhance: stretches the tonal range found in the photo (ignoring the darkest and
     * brightest 0.5% of pixels), lifts dark photos a little and adds a touch of colour.
     */
    fun autoEnhanceMatrix(src: Bitmap): ColorMatrix {
        val s = min(1f, 256f / max(src.width, src.height).toFloat())
        val small = if (s < 1f) {
            Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
        } else src
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== src) small.recycle()

        val hist = IntArray(256)
        var sum = 0L
        var count = 0
        for (c in px) {
            if ((c ushr 24) < 16) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1000
            hist[y]++
            sum += y
            count++
        }
        if (count == 0) return ColorMatrix()

        val loTarget = count * 0.005
        val hiTarget = count * 0.995
        var acc = 0
        var lo = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= loTarget) { lo = i; break }
        }
        acc = 0
        var hi = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= hiTarget) { hi = i; break }
        }
        val range = max(1, hi - lo)
        val stretch = (255f / range).coerceIn(1f, 1.6f)
        val translate = 127.5f - stretch * (lo + hi) / 2f
        val levels = ColorMatrix(
            floatArrayOf(
                stretch, 0f, 0f, 0f, translate,
                0f, stretch, 0f, 0f, translate,
                0f, 0f, stretch, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val meanAfter = stretch * (sum.toFloat() / count) + translate
        val lift = ((110f - meanAfter) * 0.5f).coerceIn(0f, 25f)
        return chain(levels, brightness(lift), saturation(1.12f), contrast(1.04f))
    }

    // ---------------------------------------------------------------- background effects

    /** Soft background blur: shrink, box-blur three times, scale back up. [strength] is 1..3. */
    fun blurred(src: Bitmap, strength: Int): Bitmap {
        val factor = when (strength) {
            1 -> 6
            2 -> 9
            else -> 13
        }
        val sw = max(1, src.width / factor)
        val sh = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        repeat(3) { boxBlur(px, sw, sh, 2) }
        val blurredSmall = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
        if (out !== blurredSmall) blurredSmall.recycle()
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        boxPass(px, tmp, w, h, r, horizontal = true)
        boxPass(tmp, px, w, h, r, horizontal = false)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[at(i)]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[at(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[at(i - r)]
                val pIn = src[at(i + r + 1)]
                sa += (pIn ushr 24) - (pOut ushr 24)
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    /**
     * Places the subject of [fg] (where [mask] is 1) over a new background: [bg] if given,
     * otherwise the solid [bgColor], otherwise transparency.
     */
    fun composite(fg: Bitmap, mask: FloatArray, bg: Bitmap?, bgColor: Int?): Bitmap {
        val w = fg.width
        val h = fg.height
        val f = IntArray(w * h)
        fg.getPixels(f, 0, w, 0, 0, w, h)
        val b = bg?.let { IntArray(w * h).also { arr -> it.getPixels(arr, 0, w, 0, 0, w, h) } }
        val out = IntArray(w * h)
        val transparent = b == null && bgColor == null
        for (i in out.indices) {
            val m = mask[i].coerceIn(0f, 1f)
            val fp = f[i]
            if (transparent) {
                val a = ((fp ushr 24) * m).roundToInt()
                out[i] = (a shl 24) or (fp and 0x00FFFFFF)
            } else {
                val bp = b?.get(i) ?: bgColor!!
                val inv = 1f - m
                val r = (((fp shr 16) and 0xFF) * m + ((bp shr 16) and 0xFF) * inv).roundToInt()
                val g = (((fp shr 8) and 0xFF) * m + ((bp shr 8) and 0xFF) * inv).roundToInt()
                val bl = ((fp and 0xFF) * m + (bp and 0xFF) * inv).roundToInt()
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- output

    private fun fileName(png: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "RAVE_$stamp" + if (png) ".png" else ".jpg"
    }

    private fun write(bmp: Bitmap, png: Boolean, out: java.io.OutputStream) {
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!bmp.compress(format, 95, out)) error("Could not encode the image")
    }

    /** Saves to Pictures/RAVE Photo Editor. No storage permission is needed on Android 10+. */
    fun saveToGallery(context: Context, bmp: Bitmap, png: Boolean): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(png))
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RAVE Photo Editor")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create the gallery entry")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Could not open the gallery entry")
            stream.use { write(bmp, png, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Writes the image to the app's cache and returns a share sheet for it. */
    fun shareIntent(context: Context, bmp: Bitmap, png: Boolean): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(png))
        file.outputStream().use { write(bmp, png, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (png) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}
