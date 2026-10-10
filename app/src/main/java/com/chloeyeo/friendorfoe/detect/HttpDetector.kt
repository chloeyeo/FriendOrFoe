package com.chloeyeo.friendorfoe.detect

/**
 * TODO(stream 1 / Chloe): the contingency detector — the laptop runs the same model through
 * `clikart-cli --offline --cache-dir <cache> PekingU/rtdetr_v2_r18vd serve --device cpu --host 0.0.0.0 --port 8129`
 * and this class posts each frame to it. Debug builds only ([Detectors.create] refuses it unless
 * `BuildConfig.ALLOW_HTTP_DETECTOR`), because the demo must run with the network blocked.
 *
 * Verified request/response shape (`.planning/research/STACK.md` section 9, captured from a live server):
 * - `POST {baseUrl}/v1/detections`, `multipart/form-data`, field **`image`** = the JPEG (encode the RGBA frame via a
 *   `Bitmap` and `compress(JPEG, 80)`), optional text fields `confidence` (string float) and `max_detections`.
 * - Response `{"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}, ...]}` in input
 *   pixels; map `label_id` through [LabelMap.accept] and keep the order (score-descending).
 * - OkHttp 5 (`okhttp3.MultipartBody`), call timeout about 2 s; parse with `org.json` (platform).
 * - `GET {baseUrl}/health` for the status strip's `modelReady`.
 *
 * @param baseUrl e.g. `http://192.168.1.2:8129`
 */
class HttpDetector(private val baseUrl: String) : Detector {
    override val name: String = "http:" + baseUrl.removePrefix("http://").trimEnd('/')

    override fun detect(frame: Frame): List<Detection> =
        throw NotImplementedError("stream 1: POST /v1/detections multipart field 'image'")
}
