package com.chloeyeo.friendorfoe.cross.fof

import java.nio.ByteBuffer

/** One small upright grayscale frame for the 10 fps motion tracker in TypeScript (`src/track/grayFrame.ts`). */
class GraySample(val width: Int, val height: Int, val luma: ByteArray, val step: Int, val frameWidth: Int, val frameHeight: Int)

/**
 * The native app's `camera/GraySampler` (same arithmetic): a small, upright grayscale frame sampled straight from a
 * CameraX RGBA plane, honouring `rowStride` and `pixelStride`, without a full-size RGBA copy. The step is
 * `uprightWidth / targetWidth`; each gray pixel is the mean luma (`(77 R + 150 G + 29 B) >> 8`) of the 2x2 block at
 * the start of its step cell. The plane buffer's position is never touched (a duplicate is read).
 *
 * The default target is 160 px wide, half the native app's 320: here every gray frame crosses into JavaScript, ten
 * times a second, so the smaller frame keeps that bridge traffic at about 14 KB per frame.
 */
object GraySampler {
  fun sample(
    src: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    rotationDegrees: Int,
    targetWidth: Int = 160,
  ): GraySample {
    require(width > 0 && height > 0) { "frame size must be positive: ${width}x$height" }
    require(pixelStride >= 3) { "RGBA expects pixelStride >= 3, got $pixelStride" }
    require(rowStride >= width * pixelStride) { "rowStride $rowStride < width*pixelStride (${width * pixelStride})" }
    require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
      "rotation $rotationDegrees"
    }
    val sideways = rotationDegrees == 90 || rotationDegrees == 270
    val upW = if (sideways) height else width
    val upH = if (sideways) width else height
    val step = maxOf(1, upW / targetWidth.coerceAtLeast(1))
    val gw = upW / step
    val gh = upH / step
    val out = ByteArray(gw * gh)
    val buf = src.duplicate()
    val block = if (step >= 2) 2 else 1

    if (!sideways) {
      val rowBytes = width * pixelStride
      val rows = Array(block) { ByteArray(rowBytes) }
      for (gy in 0 until gh) {
        val uy = gy * step
        for (r in 0 until block) {
          val sy = if (rotationDegrees == 0) uy + r else height - 1 - (uy + r)
          buf.position(sy * rowStride)
          buf.get(rows[r], 0, rowBytes)
        }
        val o = gy * gw
        for (gx in 0 until gw) {
          val ux = gx * step
          var sum = 0
          for (r in 0 until block) {
            val row = rows[r]
            for (c in 0 until block) {
              val sx = if (rotationDegrees == 0) ux + c else width - 1 - (ux + c)
              sum += luma(row, sx * pixelStride)
            }
          }
          out[o + gx] = (sum / (block * block)).toByte()
        }
      }
    } else {
      for (gy in 0 until gh) {
        for (gx in 0 until gw) {
          var sum = 0
          for (r in 0 until block) {
            for (c in 0 until block) {
              val ux = gx * step + c
              val uy = gy * step + r
              // 90 cw out(x, y) = src(y, h-1-x); 270 out(x, y) = src(w-1-y, x).
              val sx = if (rotationDegrees == 90) uy else width - 1 - uy
              val sy = if (rotationDegrees == 90) height - 1 - ux else ux
              val i = sy * rowStride + sx * pixelStride
              sum += (77 * (buf.get(i).toInt() and 0xFF) + 150 * (buf.get(i + 1).toInt() and 0xFF) +
                29 * (buf.get(i + 2).toInt() and 0xFF)) shr 8
            }
          }
          out[gy * gw + gx] = (sum / (block * block)).toByte()
        }
      }
    }
    return GraySample(gw, gh, out, step, upW, upH)
  }

  private fun luma(row: ByteArray, i: Int): Int =
    (77 * (row[i].toInt() and 0xFF) + 150 * (row[i + 1].toInt() and 0xFF) + 29 * (row[i + 2].toInt() and 0xFF)) shr 8
}
