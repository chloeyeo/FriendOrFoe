package com.chloeyeo.friendorfoe.detect

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parser for the `clikart-cli serve` `POST /v1/detections` response (laptop/README.md; the `HttpDetector`
 * contingency path, DET-05):
 *
 * ```
 * {"detections":[{"box":{"xmin":..,"ymin":..,"xmax":..,"ymax":..},"score":0.61,"label_id":4,"label":"aeroplane"}, ...], ...}
 * ```
 *
 * The body is untrusted LAN input (threat T-02-03): a body that is not JSON or has no `detections` array throws
 * [IllegalArgumentException]; a row that is malformed, has non-finite numbers, an inverted box or a score outside
 * 0..1 is skipped, never thrown; unknown fields are ignored; row order is preserved (the server answers
 * score-descending). The body is never logged — only its first 120 characters go into the exception message.
 */
object DetectionsJson {
    fun parse(body: String): List<RawDetection> {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IllegalArgumentException("detections body is not JSON: " + body.take(120), e)
        }
        val rows: JSONArray = try {
            root.getJSONArray("detections")
        } catch (e: JSONException) {
            throw IllegalArgumentException("detections body has no detections array", e)
        }
        val out = ArrayList<RawDetection>(rows.length())
        for (i in 0 until rows.length()) {
            parseRow(rows.optJSONObject(i))?.let { out += it }
        }
        return out
    }

    private fun parseRow(row: JSONObject?): RawDetection? {
        if (row == null) return null
        return try {
            val box = row.getJSONObject("box")
            val xmin = box.getDouble("xmin")
            val ymin = box.getDouble("ymin")
            val xmax = box.getDouble("xmax")
            val ymax = box.getDouble("ymax")
            val score = row.getDouble("score")
            val labelId = row.getLong("label_id")
            val label = row.optString("label", "")
            when {
                !listOf(xmin, ymin, xmax, ymax, score).all { it.isFinite() } -> null
                xmax < xmin || ymax < ymin -> null
                score < 0.0 || score > 1.0 -> null
                else -> RawDetection(
                    labelId,
                    score.toFloat(),
                    BoxF(xmin.toFloat(), ymin.toFloat(), xmax.toFloat(), ymax.toFloat()),
                    label,
                )
            }
        } catch (_: JSONException) {
            null
        }
    }
}
