package com.chloeyeo.friendorfoe.detect

import io.clika.runtime.CompileOptions
import io.clika.runtime.DefaultsOptions
import io.clika.runtime.ModelGraph
import io.clika.runtime.OnnxModel
import io.clika.runtime.OptimizeOptions
import io.clika.runtime.Tensors
import java.io.File

/**
 * A drone-trained YOLO (v8/11 head: `TomSmail/drone-yolo-v1` YOLOv8n, `marie-kjelberg/drone-detector` YOLO11n) exported to a fixed-size fp32 ONNX
 * file, run in-process through ClikaRT's low-level graph runtime (still ClikaRT, still offline):
 * `OnnxModel.open` -> `compile(sampleInputs = images[1,3,S,S])` -> `optimize(specialize = true)` (2-4x faster on
 * the CPU) -> `finalizeGraph`, then `run` per frame. The input H x W is read from the model's input spec (a square,
 * or a 16:9-ish rectangle such as 256x416 that wastes less of a landscape frame on padding).
 *
 * Per frame: letterbox the RGBA frame to H x W (grey 114, nearest sampling), RGB, /255, NCHW; the output
 * `[1, 4 + nc, N]` holds cx, cy, w, h in model pixels and already-sigmoided class scores; [YoloDecode] keeps rows
 * >= [MIN_SCORE], runs greedy NMS, maps the boxes back to frame pixels and drops boxes over [MAX_AREA] of the frame
 * (the benchmark's whole-frame false alarms on grey clutter; a close drone filled 27%). Every class is a drone type
 * (TomSmail: quadcopter, fixed-wing), so rows leave as label 0. Blocking; runtime lane only.
 */
class OnnxYoloDetector(
    private val modelPath: String,
    override val classMap: ClassMap = ClassMap.DRONE_YOLO,
    override val name: String = "clikart:" + File(modelPath).nameWithoutExtension,
) : ModelDetector {

    @Volatile
    private var model: OnnxModel? = null

    @Volatile
    private var graph: ModelGraph? = null

    @Volatile
    private var timing: OpenTiming? = null

    private var inputName = "images"
    private var inH = 0
    private var inW = 0
    private var input = FloatArray(0)

    override val isOpen: Boolean get() = graph != null

    @Volatile
    override var rawListener: ((List<RawDetection>) -> Unit)? = null

    override fun open(): OpenTiming {
        timing?.let { if (graph != null) return it }
        val file = File(modelPath)
        require(file.isFile) { "model file missing: ${file.name}" }
        val t0 = System.nanoTime()
        val m = OnnxModel.open(file.absolutePath)
        val g = try {
            val spec = m.inputs().first()
            inputName = spec.name
            inH = spec.dims[2].toInt()
            inW = spec.dims[3].toInt()
            check(inH in 64..1280 && inW in 64..1280) { "${file.name}: input must be fixed, got ${spec.dims.toList()}" }
            input = FloatArray(3 * inH * inW)
            val shape = longArrayOf(1, 3, inH.toLong(), inW.toLong())
            val graph = Tensors.zeros(shape).use { sample -> m.compile(CompileOptions(sampleInputs = mapOf(inputName to sample))) }
            graph.optimize(OptimizeOptions(DefaultsOptions(specialize = true)))
            graph.finalizeGraph()
            graph
        } catch (e: Exception) {
            runCatching { m.close() }
            throw e
        }
        model = m
        graph = g
        val t1 = System.nanoTime()
        run(ByteArray(inW * inH * 4), inW, inH) // warm-up
        val t2 = System.nanoTime()
        return OpenTiming((t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000).also { timing = it }
    }

    override fun detectRaw(frame: Frame): List<RawDetection> {
        if (graph == null) open()
        val rows = run(frame.rgba, frame.width, frame.height)
        rawListener?.invoke(rows)
        return rows
    }

    private fun run(rgba: ByteArray, w: Int, h: Int): List<RawDetection> {
        val g = graph ?: error("not open")
        val lb = YoloDecode.letterbox(rgba, w, h, inW, inH, input)
        val out = Tensors.of(input, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { x ->
            val outs = g.run(listOf(x))
            try {
                outs[0].shape() to outs[0].toFloatArray()
            } finally {
                outs.forEach { it.close() }
            }
        }
        val n = out.first.last().toInt()
        return YoloDecode.decode(out.second, n, lb, MIN_SCORE, w, h, maxArea = MAX_AREA)
    }

    override fun close() {
        graph?.close()
        model?.close()
        graph = null
        model = null
        timing = null
    }

    companion object {
        /** Rows below this never leave the detector; the [ClassMap] floor decides what the app keeps. */
        const val MIN_SCORE = 0.15f

        /** Boxes covering more than this share of the frame are dropped (whole-frame false alarms). */
        const val MAX_AREA = 0.6f
    }
}

/** Letterbox geometry: frame pixel = (model pixel - pad) / scale. */
data class Letterbox(val scale: Float, val padX: Float, val padY: Float)

/** Pure-Kotlin pre/post-processing for [OnnxYoloDetector] (unit-tested on the JVM). */
object YoloDecode {
    /** Writes the letterboxed RGB/255 NCHW image of the [w] x [h] RGBA frame into [dst] (3 x [outH] x [outW]). */
    fun letterbox(rgba: ByteArray, w: Int, h: Int, outW: Int, outH: Int, dst: FloatArray): Letterbox {
        val scale = minOf(outW.toFloat() / w, outH.toFloat() / h)
        val nw = Math.round(w * scale).coerceIn(1, outW)
        val nh = Math.round(h * scale).coerceIn(1, outH)
        val left = (outW - nw) / 2
        val top = (outH - nh) / 2
        val plane = outW * outH
        java.util.Arrays.fill(dst, 0, 3 * plane, 114f / 255f)
        val xs = IntArray(nw) { minOf(w - 1, ((it + 0.5f) / scale).toInt()) }
        for (y in 0 until nh) {
            val sy = minOf(h - 1, ((y + 0.5f) / scale).toInt())
            val rowIn = sy * w
            val rowOut = (top + y) * outW + left
            for (x in 0 until nw) {
                val p = (rowIn + xs[x]) * 4
                val o = rowOut + x
                dst[o] = (rgba[p].toInt() and 0xFF) / 255f
                dst[plane + o] = (rgba[p + 1].toInt() and 0xFF) / 255f
                dst[2 * plane + o] = (rgba[p + 2].toInt() and 0xFF) / 255f
            }
        }
        return Letterbox(scale, left.toFloat(), top.toFloat())
    }

    /**
     * Rows of the `[1, 4 + nc, n]` output [o] with a best class score >= [minScore], greedy NMS per class at
     * [nmsIou], boxes mapped back through [lb] and clamped to the [w] x [h] frame, boxes over [maxArea] of the frame
     * dropped; every class leaves as label 0 (all classes are drone types); score-descending.
     */
    fun decode(
        o: FloatArray, n: Int, lb: Letterbox, minScore: Float, w: Int, h: Int,
        nmsIou: Float = 0.45f, maxRows: Int = 20, maxArea: Float = 1f,
    ): List<RawDetection> {
        val nc = o.size / n - 4
        val cands = ArrayList<RawDetection>()
        for (i in 0 until n) {
            var c = 0
            for (k in 1 until nc) if (o[(4 + k) * n + i] > o[(4 + c) * n + i]) c = k
            val sc = o[(4 + c) * n + i]
            if (sc < minScore) continue
            val cx = o[i]
            val cy = o[n + i]
            val bw = o[2 * n + i]
            val bh = o[3 * n + i]
            val box = BoxF(
                ((cx - bw / 2 - lb.padX) / lb.scale).coerceIn(0f, w.toFloat()),
                ((cy - bh / 2 - lb.padY) / lb.scale).coerceIn(0f, h.toFloat()),
                ((cx + bw / 2 - lb.padX) / lb.scale).coerceIn(0f, w.toFloat()),
                ((cy + bh / 2 - lb.padY) / lb.scale).coerceIn(0f, h.toFloat()),
            )
            if (box.area > maxArea * w * h) continue
            cands += RawDetection(0L, sc, box, "drone")
        }
        cands.sortByDescending { it.score }
        val keep = ArrayList<RawDetection>()
        for (d in cands) {
            if (keep.size >= maxRows) break
            if (keep.none { iou(it.box, d.box) > nmsIou }) keep += d
        }
        return keep
    }

    fun iou(a: BoxF, b: BoxF): Float {
        val iw = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val ih = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = iw * ih
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
