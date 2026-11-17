package dev.mightys.pulse

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** How a picture is sized and encoded: longest side at most 2048 px, PNG, JPEG at quality 80 when the PNG is over 2 MiB. */
internal object ShotEncoding {
    const val MAX_SIDE = 2048
    const val MAX_BYTES = 2 * 1024 * 1024
    const val JPEG_QUALITY = 80

    /** The size after scaling the longest side down to [MAX_SIDE]; the same size when it already fits. */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_SIDE) return width to height
        val factor = MAX_SIDE.toDouble() / longest
        return maxOf(1, Math.round(width * factor).toInt()) to maxOf(1, Math.round(height * factor).toInt())
    }
}

/** One debug screenshot of the window: pixel copy, scale and encode off the main thread, then the SDK uploads it. */
@SuppressLint("UseKtx") // the SDK has no androidx.core dependency
internal class HeatmapShot(private val client: PulseClient) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "pulse-heatmap-shot").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Main thread. Asks the SDK whether this view is wanted (debug build, capture on, not sent yet, not rate limited) and takes the picture. */
    fun request(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT < 26 || activity.isFinishing || activity.isDestroyed) return
            val window = activity.window ?: return
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return
            val decor = window.decorView
            val density = activity.resources.displayMetrics.density
            if (decor.width <= 0 || decor.height <= 0 || !(density > 0f) || Keyboard.visible(decor)) return
            client.heatmapShotTicket(decor.width / density.toDouble(), decor.height / density.toDouble()) { ticket ->
                if (ticket != null) main.post { copy(activity, ticket) }
            }
        } catch (_: Throwable) { /* never reaches the app */ }
    }

    private fun copy(activity: Activity, ticket: HeatmapShotTicket) {
        try {
            if (Build.VERSION.SDK_INT < 26 || activity.isFinishing || activity.isDestroyed) return
            val window = activity.window ?: return
            val decor = window.decorView
            val width = decor.width; val height = decor.height
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS || Keyboard.visible(decor)) return
            // The bitmap is allocated off the main thread; only the copy request is made here.
            worker.execute {
                try {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    main.post {
                        try {
                            PixelCopy.request(window, bitmap, { result ->
                                if (result == PixelCopy.SUCCESS) worker.execute { encodeAndSend(bitmap, ticket) } else bitmap.recycle()
                            }, main)
                        } catch (_: Throwable) { bitmap.recycle() }
                    }
                } catch (_: Throwable) { /* out of memory or no window: no picture this time */ }
            }
        } catch (_: Throwable) { /* never reaches the app */ }
    }

    private fun encodeAndSend(source: Bitmap, ticket: HeatmapShotTicket) {
        try {
            val (width, height) = ShotEncoding.scaled(source.width, source.height)
            val bitmap = if (width == source.width && height == source.height) source else Bitmap.createScaledBitmap(source, width, height, true)
            val bytes = encode(bitmap)
            if (bitmap !== source) bitmap.recycle()
            source.recycle()
            if (bytes != null) client.heatmapScreenshot(ticket, bytes.first, bytes.second)
        } catch (_: Throwable) { /* silent */ }
    }

    private fun encode(bitmap: Bitmap): Pair<ByteArray, String>? {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= ShotEncoding.MAX_BYTES) return png to "image/png"
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, ShotEncoding.JPEG_QUALITY, it) }.toByteArray()
        return if (jpeg.size <= ShotEncoding.MAX_BYTES) jpeg to "image/jpeg" else null
    }

    private companion object { const val MAX_PIXELS = 40_000_000L }
}
