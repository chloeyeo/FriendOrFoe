package com.chloeyeo.friendorfoe.ui.settings

import com.chloeyeo.friendorfoe.detect.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormTest {
    private val values = SettingsValues(
        detector = DetectorConfig.ClikaRt(SettingsForm.DEFAULT_MODEL_DIR),
        challengerId = 7,
        acceptedDroneIds = listOf("RQ-07"),
        demoLatitude = 37.5665,
        demoLongitude = 126.978,
        demoAltitudeM = 30.0,
        demoHeadingDeg = 0f,
        personThreshold = 0.5f,
        droneThreshold = 0.28f,
        rttBudgetMs = 2_500,
        rssiFloorDbm = -70,
        strictPhysical = false,
        demoMode = true,
        dashboardBaseUrl = "",
    )
    private val form = SettingsForm.from(values)

    @Test
    fun untouchedFormParsesBackToTheSameValues() {
        assertEquals(SettingsForm.Result.Valid(values), form.parse())
        val fake = values.copy(detector = DetectorConfig.Fake)
        assertEquals(SettingsForm.Result.Valid(fake), SettingsForm.from(fake).parse())
    }

    @Test
    fun switchingDetectorKeepsBothTargets() {
        val http = form.copy(detectorKind = DetectorKind.HTTP, httpBaseUrl = "http://10.0.0.5:8000")
        assertEquals(DetectorConfig.Http("http://10.0.0.5:8000"), (http.parse() as SettingsForm.Result.Valid).values.detector)
        val back = http.copy(detectorKind = DetectorKind.CLIKART)
        assertEquals(DetectorConfig.ClikaRt(SettingsForm.DEFAULT_MODEL_DIR), (back.parse() as SettingsForm.Result.Valid).values.detector)
    }

    @Test
    fun droneIdsAreSplitTrimmedAndDeduplicated() {
        val r = form.copy(acceptedDroneIds = " RQ-07,RQ-08  RQ-07 ,").parse() as SettingsForm.Result.Valid
        assertEquals(listOf("RQ-07", "RQ-08"), r.values.acceptedDroneIds)
    }

    @Test
    fun headingOf360WrapsToZero() {
        val r = form.copy(demoHeadingDeg = "360").parse() as SettingsForm.Result.Valid
        assertEquals(0f, r.values.demoHeadingDeg, 0f)
    }

    @Test
    fun everyBadFieldIsReportedAndNothingIsReturned() {
        val badClika = form.copy(
            modelDir = "  ",
            dashboardBaseUrl = "10.0.0.5:8081",
            challengerId = "70000",
            acceptedDroneIds = " , ",
            demoLatitude = "91",
            demoLongitude = "abc",
            demoAltitudeM = "20000",
            demoHeadingDeg = "-1",
            personThreshold = "1.5",
            droneThreshold = "",
            rttBudgetMs = "0",
            rssiFloorDbm = "5",
        )
        val r = badClika.parse() as SettingsForm.Result.Invalid
        assertEquals(Field.entries.toSet() - Field.HTTP_URL, r.errors.keys)

        val badHttp = form.copy(detectorKind = DetectorKind.HTTP, httpBaseUrl = "10.0.0.5:8000")
        assertEquals(setOf(Field.HTTP_URL), (badHttp.parse() as SettingsForm.Result.Invalid).errors.keys)
    }

    @Test
    fun dualDetectorSurvivesTheForm() {
        val dual = values.copy(detector = DetectorConfig.Dual("/data/x/models/drone", "/data/x/models/person"))
        val f = SettingsForm.from(dual)
        assertEquals(DetectorKind.DUAL, f.detectorKind)
        assertEquals(SettingsForm.Result.Valid(dual), f.parse())
        val blank = f.copy(personModelDir = " ").parse()
        assertEquals(true, blank is SettingsForm.Result.Invalid)
    }
}
