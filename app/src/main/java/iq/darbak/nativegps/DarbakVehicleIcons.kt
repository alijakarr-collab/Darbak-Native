package iq.darbak.nativegps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import kotlin.math.roundToInt

/**
 * Vector-drawn vehicle markers that require no image files or external licenses.
 * Bearings are rendered relative to camera orientation because MapLibre annotation
 * icons are billboarded. Cache is bounded to 36 ten-degree bearings.
 */
class DarbakVehicleIcons(private val context: Context) {
    private val cache = HashMap<Int, Icon>()
    private var previousVehicle = -1
    private var previousAccent = 0
    private val width = 112

    fun clear() { cache.clear() }
    fun icon(vehicle: Int, accent: Int, bearing: Float): Icon {
        if (vehicle != previousVehicle || accent != previousAccent) {
            previousVehicle = vehicle
            previousAccent = accent
            cache.clear()
        }
        val bucket = ((bearing.roundToInt() % 360 + 360) % 360 / 10) % 36
        return cache.getOrPut(bucket) { draw(vehicle, accent, (bucket * 10).toFloat()) }
    }
    private fun draw(vehicle: Int, tint: Int, degrees: Float): Icon {
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fill(color: Int) {
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 1f
        }
        fill(Color.argb(40, 9, 19, 35))
        canvas.drawCircle(56f, 56f, 53f, paint)
        fill(Color.argb(88, Color.red(tint), Color.green(tint), Color.blue(tint)))
        canvas.drawCircle(56f, 56f, 47f, paint)
        fill(Color.WHITE)
        canvas.drawCircle(56f, 56f, 39f, paint)
        fill(Color.rgb(21, 30, 43))
        canvas.drawCircle(56f, 56f, 35f, paint)

        canvas.save()
        canvas.rotate(degrees, 56f, 56f)
        when (vehicle) {
            1 -> {
                // Premium top-down car silhouette
                fill(Color.rgb(9, 12, 18))
                canvas.drawRoundRect(RectF(32f, 26f, 42f, 44f), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(70f, 26f, 80f, 44f), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(32f, 66f, 42f, 86f), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(70f, 66f, 80f, 86f), 4f, 4f, paint)
                fill(tint)
                canvas.drawRoundRect(RectF(40f, 17f, 72f, 94f), 15f, 15f, paint)
                fill(Color.argb(230, 14, 34, 49))
                canvas.drawRoundRect(RectF(44f, 36f, 68f, 52f), 6f, 6f, paint)
                canvas.drawRoundRect(RectF(44f, 63f, 68f, 78f), 5f, 5f, paint)
                fill(Color.WHITE)
                canvas.drawRoundRect(RectF(43f, 21f, 51f, 26f), 2f, 2f, paint)
                canvas.drawRoundRect(RectF(61f, 21f, 69f, 26f), 2f, 2f, paint)
                fill(Color.rgb(255, 92, 92))
                canvas.drawRoundRect(RectF(44f, 87f, 50f, 92f), 2f, 2f, paint)
                canvas.drawRoundRect(RectF(62f, 87f, 68f, 92f), 2f, 2f, paint)
                fill(Color.argb(180, 255, 255, 255))
                canvas.drawRoundRect(RectF(42f, 29f, 70f, 32f), 2f, 2f, paint)
            }
            2 -> {
                // Motorcycle viewed from above: front wheel at top, handlebar and seat.
                fill(Color.rgb(15, 20, 30))
                canvas.drawOval(RectF(47f, 10f, 65f, 36f), paint)
                canvas.drawOval(RectF(47f, 77f, 65f, 101f), paint)
                fill(tint)
                canvas.drawRoundRect(RectF(49f, 26f, 63f, 89f), 8f, 8f, paint)
                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE
                paint.strokeWidth = 5.5f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(30f, 38f, 82f, 38f, paint)
                paint.style = Paint.Style.FILL
                fill(Color.rgb(25, 34, 51))
                canvas.drawOval(RectF(42f, 44f, 70f, 67f), paint)
                fill(Color.WHITE)
                canvas.drawOval(RectF(50f, 18f, 62f, 31f), paint)
                fill(Color.argb(220, 220, 233, 255))
                canvas.drawRoundRect(RectF(49f, 69f, 63f, 83f), 4f, 4f, paint)
            }
            else -> {
                // Direction arrow with a clean sharp tip and dark edging.
                val path = Path().apply {
                    moveTo(56f, 11f); lineTo(91f, 87f); lineTo(57f, 75f)
                    lineTo(21f, 89f); close()
                }
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = 8f
                paint.color = Color.rgb(12, 20, 31)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                paint.color = tint
                canvas.drawPath(path, paint)
                val flare = Path().apply {
                    moveTo(56f, 20f); lineTo(56f, 67f); lineTo(35f, 75f); close()
                }
                paint.color = Color.argb(130, 255, 255, 255)
                canvas.drawPath(flare, paint)
            }
        }
        canvas.restore()
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }
}
