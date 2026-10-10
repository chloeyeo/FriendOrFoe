package com.chloeyeo.friendorfoe.detect

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The contingency detector (the H4 flip) — the laptop runs the same model through
 * `clikart-cli --offline --cache-dir C:/Users/C/clika/models PekingU/rtdetr_v2_r18vd serve --device cpu --host 0.0.0.0 --port 8000`
 * and this class posts each frame to it. Debug builds only ([Detectors.create] refuses it unless
 * `BuildConfig.ALLOW_HTTP_DETECTOR`), because the demo must run with the network blocked.
 *
 * Host and port ride in `DetectorConfig.Http.baseUrl` (no port constant anywhere); set it with
 * `adb shell am start -n com.chloeyeo.friendorfoe/.MainActivity --es detector http:http://<laptop-ip>:8000`
 * (wired in plan 02-04). The serve default port is 8000.
 *
 * Request/response shape (`laptop/README.md`, captured from a live server):
 * - `POST {baseUrl}/v1/detections`, `multipart/form-data`, field **`image`** = the JPEG (RGBA frame via a `Bitmap`,
 *   `compress(JPEG, 80)`), text fields `confidence` = `0.25` and `max_detections` = `20`.
 * - Response `{"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}, ...]}` in input
 *   pixels, parsed by [DetectionsJson.parse] and mapped by [RawDetections.toDetections] (the same path as
 *   [ClikaRtDetector]).
 * - 1 s connect / 2 s call timeout (the app's 2.5 s round-trip budget is the ceiling); a non-2xx answer throws
 *   `IllegalStateException("serve NNN")`, I/O failures throw `IOException`. Both propagate to
 *   `FofApplication.runTick`, the single error sink that puts a note on the strip. The body is never logged.
 *
 * @param baseUrl e.g. `http://192.168.1.2:8000`
 */
class HttpDetector(private val baseUrl: String) : Detector {
    override val name: String = "http:" + baseUrl.removePrefix("http://").trimEnd('/')

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    /** Blocking; runtime lane only. */
    override fun detect(frame: Frame): List<Detection> {
        val jpeg = encodeJpeg(frame)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("confidence", "0.25")
            .addFormDataPart("max_detections", "20")
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/detections")
            .post(body)
            .build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "serve ${r.code}" }
            RawDetections.toDetections(DetectionsJson.parse(r.body.string()))
        }
    }

    private fun encodeJpeg(frame: Frame): ByteArray {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))
        }
        try {
            val out = ByteArrayOutputStream(frame.width * frame.height / 4)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
