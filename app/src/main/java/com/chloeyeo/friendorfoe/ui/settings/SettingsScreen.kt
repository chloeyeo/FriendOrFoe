package com.chloeyeo.friendorfoe.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chloeyeo.friendorfoe.detect.DetectorConfig
import com.chloeyeo.friendorfoe.ui.ChromeButton
import com.chloeyeo.friendorfoe.ui.theme.FriendOrFoeTheme
import com.chloeyeo.friendorfoe.ui.theme.IffRed
import com.chloeyeo.friendorfoe.ui.theme.OnSurface
import com.chloeyeo.friendorfoe.ui.theme.OnSurfaceMuted
import com.chloeyeo.friendorfoe.ui.theme.Surface0

/**
 * Detector, gadget, demo mode, demo position and heading, per-class thresholds, the physical re-check limits
 * and the dashboard URL. Edits stay a draft until Save; invalid fields are marked and nothing is saved.
 */
@Composable
fun SettingsScreen(
    current: SettingsValues,
    onSave: (SettingsValues) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var form by remember(current) { mutableStateOf(SettingsForm.from(current)) }
    var errors by remember(current) { mutableStateOf(emptyMap<Field, String>()) }
    val dirty = form != SettingsForm.from(current)

    Column(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeButton("← Camera", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Settings", color = OnSurface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { form = SettingsForm.from(current); errors = emptyMap() }, enabled = dirty) {
                Text("Revert")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    when (val r = form.parse()) {
                        is SettingsForm.Result.Valid -> { errors = emptyMap(); onSave(r.values) }
                        is SettingsForm.Result.Invalid -> { errors = r.errors }
                    }
                },
                enabled = dirty,
            ) { Text("Save") }
        }
        if (errors.isNotEmpty()) {
            Text("Not saved: fix the marked fields.", color = IffRed, style = MaterialTheme.typography.bodyLarge)
        }

        Row(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Mode")
                Toggle("Demo mode (scripted scenario, fake signal source)", form.demoMode) { form = form.copy(demoMode = it) }

                Section("Detector")
                Choice(DetectorKind.entries, form.detectorKind, { it.label }) { form = form.copy(detectorKind = it) }
                when (form.detectorKind) {
                    DetectorKind.DUAL -> {
                        TextInput("Drone model directory", form.droneModelDir, errors[Field.MODEL_DIR], KeyboardType.Uri) {
                            form = form.copy(droneModelDir = it)
                        }
                        TextInput("Person model directory", form.personModelDir, errors[Field.MODEL_DIR], KeyboardType.Uri) {
                            form = form.copy(personModelDir = it)
                        }
                    }
                    DetectorKind.CLIKART -> TextInput("Model directory", form.modelDir, errors[Field.MODEL_DIR], KeyboardType.Uri) {
                        form = form.copy(modelDir = it)
                    }
                    DetectorKind.HTTP -> TextInput("Laptop serve URL (debug builds)", form.httpBaseUrl, errors[Field.HTTP_URL], KeyboardType.Uri) {
                        form = form.copy(httpBaseUrl = it)
                    }
                    DetectorKind.FAKE -> {}
                }

                Section("Gadget")
                Text(
                    if (form.demoMode) "Fake signal source (demo mode)" else "USB gadget (reader on USB OTG)",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextInput("challengerId", form.challengerId, errors[Field.CHALLENGER_ID], KeyboardType.Number) {
                    form = form.copy(challengerId = it)
                }
                TextInput("Accepted droneIds (comma-separated)", form.acceptedDroneIds, errors[Field.DRONE_IDS]) {
                    form = form.copy(acceptedDroneIds = it)
                }

                Section("Laptop dashboard")
                TextInput("Dashboard URL (blank = off)", form.dashboardBaseUrl, errors[Field.DASHBOARD_URL], KeyboardType.Uri) {
                    form = form.copy(dashboardBaseUrl = it)
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Demo position (no GPS indoors)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("Latitude", form.demoLatitude, errors[Field.LAT], modifier = Modifier.weight(1f)) {
                        form = form.copy(demoLatitude = it)
                    }
                    TextInput("Longitude", form.demoLongitude, errors[Field.LON], modifier = Modifier.weight(1f)) {
                        form = form.copy(demoLongitude = it)
                    }
                    TextInput("Altitude m", form.demoAltitudeM, errors[Field.ALT], modifier = Modifier.weight(1f)) {
                        form = form.copy(demoAltitudeM = it)
                    }
                    TextInput("Heading °", form.demoHeadingDeg, errors[Field.HEADING], KeyboardType.Decimal, Modifier.weight(1f)) {
                        form = form.copy(demoHeadingDeg = it)
                    }
                }

                Section("Thresholds")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("person", form.personThreshold, errors[Field.PERSON_THRESHOLD], KeyboardType.Decimal, Modifier.weight(1f)) {
                        form = form.copy(personThreshold = it)
                    }
                    TextInput("drone", form.droneThreshold, errors[Field.DRONE_THRESHOLD], KeyboardType.Decimal, Modifier.weight(1f)) {
                        form = form.copy(droneThreshold = it)
                    }
                }

                Section("Physical re-checks")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput("Round-trip budget (ms)", form.rttBudgetMs, errors[Field.RTT_BUDGET], KeyboardType.Number, Modifier.weight(1f)) {
                        form = form.copy(rttBudgetMs = it)
                    }
                    TextInput("RSSI floor (dBm)", form.rssiFloorDbm, errors[Field.RSSI_FLOOR], modifier = Modifier.weight(1f)) {
                        form = form.copy(rssiFloorDbm = it)
                    }
                }
                Toggle("Strict physical checks (no RSSI → FOE)", form.strictPhysical) {
                    form = form.copy(strictPhysical = it)
                }

                Text(
                    "No keys are stored here: the IFF key lives on the gadget, the ClikaRT license in BuildConfig or app-private storage.",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = OnSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = OnSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One-of choice as a row of buttons; the selected one is filled. */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            if (o == selected) {
                Button(onClick = { onSelect(o) }) { Text(label(o)) }
            } else {
                OutlinedButton(onClick = { onSelect(o) }) { Text(label(o)) }
            }
        }
    }
}

/** Signed numbers use the text keyboard: several number pads have no minus key. */
@Composable
private fun TextInput(
    label: String,
    value: String,
    error: String?,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

@Preview(device = "spec:parent=pixel_5,orientation=landscape")
@Composable
private fun SettingsScreenPreview() {
    FriendOrFoeTheme {
        SettingsScreen(
            SettingsValues(
                detector = DetectorConfig.Http(SettingsForm.DEFAULT_SERVE_URL),
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
            ),
            onSave = {},
            onBack = {},
        )
    }
}
