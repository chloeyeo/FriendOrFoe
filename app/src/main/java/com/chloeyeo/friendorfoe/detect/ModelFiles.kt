package com.chloeyeo.friendorfoe.detect

import java.io.File

/**
 * The pushed hub snapshot check for [ClikaRtDetector] (D-04): the directory must exist and hold the three files the
 * runtime reads. The message starts with `model dir missing` (that exact text reaches the status strip) and carries
 * file names only, never the parent path (T-02-05).
 */
object ModelFiles {
    val REQUIRED: List<String> = listOf("config.json", "model.safetensors", "preprocessor_config.json")

    fun check(dir: File) {
        require(dir.isDirectory) { "model dir missing: ${dir.name}" }
        for (name in REQUIRED) {
            require(File(dir, name).isFile) { "model dir missing: $name" }
        }
    }
}
