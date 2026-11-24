package app.eatwat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Meal photos are copied into app storage (picker URIs do not last) and downscaled. */
object Photos {
    private const val MAX_EDGE = 1600

    fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** A temp file for the camera to write into (shared through FileProvider). */
    fun cameraTemp(ctx: Context): File = File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "capture.jpg") }

    fun importFrom(ctx: Context, uri: Uri): String? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val name = "${UUID.randomUUID()}.jpg"
        file(ctx, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }.getOrNull()

    fun delete(ctx: Context, name: String?) {
        if (name != null && Regex("""^[\w-]+\.\w+$""").matches(name)) file(ctx, name).delete()
    }

    fun loadThumb(ctx: Context, name: String, maxEdge: Int): Bitmap? = runCatching {
        val f = file(ctx, name)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxEdge) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
}
