package com.chloeyeo.friendorfoe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.chloeyeo.friendorfoe.detect.ClikaRtDetector
import com.chloeyeo.friendorfoe.detect.Frame
import com.chloeyeo.friendorfoe.detect.RawDetection
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug-only hooks for the phone checkpoints (frame dump, parity run, raw-row log); every caller gates on
 * `BuildConfig.DEBUG`. Paths are fixed under the app-private `filesDir/dumps/` and the parity input comes from
 * APK assets only, so no intent extra can name an arbitrary file (T-02-04). Pull with
 * `adb exec-out run-as com.chloeyeo.friendorfoe cat files/dumps/frame.png > frame.png`.
 */
object DebugHooks {
    private const val TAG = "FoF"

    /** Set by the `log_raw` launch extra; the dual bring-up hooks both models' raw rows when it is true. */
    @Volatile
    var logRawRows: Boolean = false

    /**
     * Set by the `still_frame` launch extra: the pixels of `filesDir/dumps/still.png`, analysed instead of every
     * camera frame (with the camera frame's timestamp), so crop rounds can be timed on a known drone photo.
     */
    @Volatile
    var stillFrame: Frame? = null

    /** Decodes `filesDir/dumps/still.png` (pushed with `run-as`) for [stillFrame]; null when missing or unreadable. */
    fun loadStill(context: Context): Frame? {
        val file = File(context.filesDir, "dumps/still.png")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Frame? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val rgba = ByteArray(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
            return Frame(bitmap.width, bitmap.height, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    /** Set to dump the next frame [FofApplication.runTick] sees. */
    @Volatile
    var dumpNextFrame: Boolean = false

    /** Writes [frame] as `filesDir/dumps/frame.png` (overwritten each time) and returns the file. */
    fun dumpPng(context: Context, frame: Frame): File {
        val out = File(context.filesDir, "dumps/frame.png")
        out.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        Log.i(TAG, "dumped ${frame.width}x${frame.height} -> dumps/frame.png")
        return out
    }

    /** Decodes the APK asset [assetPath] into a tightly packed RGBA [Frame]. */
    fun assetFrame(context: Context, assetPath: String): Frame {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return decode(bytes) ?: throw IllegalArgumentException("not an image: $assetPath")
    }

    /** One `FoF` log line per raw row (`prefix id label score l t r b`), then `prefix rows=N`; returns the lines. */
    fun logRows(prefix: String, rows: List<RawDetection>): List<String> {
        val lines = rows.map { r ->
            "$prefix ${r.labelId} ${r.label} ${"%.3f".format(r.score)} ${r.box.left} ${r.box.top} ${r.box.right} ${r.box.bottom}"
        } + "$prefix rows=${rows.size}"
        lines.forEach { Log.i(TAG, it) }
        return lines
    }

    /**
     * The parity harness (runs on the caller's thread = the lane): the asset through [ClikaRtDetector.detectRaw],
     * logged and written to `filesDir/dumps/parity.out` for the comparison with `clikart-cli detect`.
     */
    fun runParity(context: Context, assetPath: String, detector: ClikaRtDetector) {
        try {
            val frame = assetFrame(context, assetPath)
            val rows = detector.detectRaw(frame)
            val lines = logRows("parity", rows)
            val out = File(context.filesDir, "dumps/parity.out")
            out.parentFile?.mkdirs()
            out.writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Throwable) {
            Log.w(TAG, "parity failed: ${FofApplication.noteFor(e)}")
        }
    }
}
