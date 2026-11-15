package ai.upet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.LruCache
import android.view.View

/** Card PNGs stay local. An unfound card uses the exact same PNG alpha as its silhouette. */
internal class DogamArtView(context: Context, private val no: Int, private val silhouette: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val image: Bitmap? = ArtCache.load(context, no)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = image
        if (image != null) {
            val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
            val w = image.width * scale; val h = image.height * scale
            paint.colorFilter = if (silhouette) PorterDuffColorFilter(context.getColor(R.color.dogam_silhouette), PorterDuff.Mode.SRC_IN) else null
            canvas.drawBitmap(image, null, RectF((width - w) / 2f, (height - h) / 2f,
                (width + w) / 2f, (height + h) / 2f), paint)
            return
        }
        // The supplied mock explicitly uses simple temporary shapes, until local artwork arrives.
        paint.colorFilter = null
        val scale = minOf(width, height) / 200f
        canvas.save()
        canvas.translate((width - 200f * scale) / 2f, (height - 200f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.color = context.getColor(if (silhouette) R.color.dogam_silhouette else when (no) {
            1 -> R.color.dogam_art_peach
            2 -> R.color.dogam_art_teal
            4 -> R.color.dogam_art_cushion
            else -> R.color.dogam_art_teal
        })
        if (silhouette) {
            canvas.drawRoundRect(RectF(40f, 56f, 160f, 166f), 46f, 46f, paint)
        } else when (no) {
            1 -> {
                val ring = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addCircle(100f, 108f, 68f, Path.Direction.CW)
                    addCircle(100f, 108f, 26f, Path.Direction.CW)
                }
                canvas.drawPath(ring, paint)
            }
            4 -> canvas.drawRoundRect(RectF(34f, 62f, 166f, 164f), 40f, 40f, paint)
            else -> {
                canvas.drawOval(RectF(30f, 50f, 170f, 174f), paint)
                canvas.drawRoundRect(RectF(90f, 36f, 110f, 54f), 6f, 6f, paint)
            }
        }
        if (!silhouette) {
            val eyeY = if (no == 1) 72f else 104f
            paint.color = context.getColor(R.color.dogam_art_face)
            canvas.drawCircle(80f, eyeY, 6f, paint); canvas.drawCircle(120f, eyeY, 6f, paint)
            paint.color = context.getColor(R.color.dogam_art_cheek)
            canvas.drawOval(RectF(57f, eyeY + 9f, 75f, eyeY + 19f), paint)
            canvas.drawOval(RectF(125f, eyeY + 9f, 143f, eyeY + 19f), paint)
        }
        canvas.restore()
    }

    private object ArtCache {
        private val cache = object : LruCache<Int, Bitmap>(16 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
        fun load(context: Context, no: Int): Bitmap? = synchronized(this) {
            cache.get(no)?.let { return it }
            val file = "dogam/art/card_" + no.toString().padStart(3, '0') + ".png"
            val bitmap = runCatching { context.assets.open(file).use(BitmapFactory::decodeStream) }.getOrNull()
            if (bitmap != null) cache.put(no, bitmap)
            bitmap
        }
    }
}
