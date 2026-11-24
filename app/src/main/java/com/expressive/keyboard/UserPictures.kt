package com.expressive.keyboard

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pictures the user chooses for a plugin's `image` setting. The picture is copied into the app's private storage
 * (at most 2048 px on the longest side, turned upright by its EXIF tag), so no storage permission is needed and the
 * plugin only ever sees pictures chosen for its own settings. The setting's value is `user:<id>:<stamp>`, see GlTextures.userValue.
 */
internal object UserPictures {
    private const val MAX_SIDE = 2048
    private const val MAX_BYTES = 15 * 1024 * 1024

    /** «p_» keeps an id like «..» inside the folder. */
    fun dir(ctx: Context, pluginId: String): File =
        File(File(ctx.applicationContext.filesDir, "plugin_pictures"), "p_" + pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun file(ctx: Context, pluginId: String, optionId: String): File {
        val safe = optionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "-" + Integer.toHexString(optionId.hashCode())
        return File(dir(ctx, pluginId), "$safe.img")
    }

    /** Copies the chosen picture in; returns the value for the setting, or null when it cannot be read as an image. */
    fun save(ctx: Context, pluginId: String, optionId: String, uri: Uri): String? {
        try {
            val resolver = ctx.applicationContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val m = Matrix()
            turn(m, orientation(resolver, uri))
            val longest = maxOf(decoded.width, decoded.height)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                m.postScale(s, s)
            }
            val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (out !== decoded) decoded.recycle()
            val bytes = encode(out)
            out.recycle()
            val target = file(ctx, pluginId, optionId)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) { tmp.delete(); return null }
            }
            return GlTextures.userValue(optionId, System.currentTimeMillis())
        } catch (_: Throwable) {
            return null
        }
    }

    fun clear(ctx: Context, pluginId: String, optionId: String) {
        file(ctx, pluginId, optionId).delete()
    }

    /** A small copy for the settings card; null when there is no picture. */
    fun thumbnail(ctx: Context, pluginId: String, optionId: String, px: Int): Bitmap? {
        val f = file(ctx, pluginId, optionId)
        if (!f.isFile) return null
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
    }

    /** PNG when the picture has transparency, JPEG otherwise (much smaller for photos). */
    private fun encode(bmp: Bitmap): ByteArray {
        if (bmp.hasAlpha()) {
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= MAX_BYTES) return png
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun orientation(r: ContentResolver, uri: Uri): Int = try {
        r.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    } catch (_: Throwable) {
        1
    }

    private fun turn(m: Matrix, exif: Int) {
        when (exif) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(-90f)
        }
    }
}
