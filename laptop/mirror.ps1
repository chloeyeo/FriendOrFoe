# Mirror phone A on the laptop over USB (JDG-01). No network involved: scrcpy talks to adb over the cable,
# so it works with the phone in airplane mode.
#
#   .\laptop\mirror.ps1            # mirror only
#   .\laptop\mirror.ps1 -Record    # mirror and record the backup video to recordings\demo-<time>.mp4
#
# Needs scrcpy 2.x or 3.x on PATH (https://github.com/Genymobile/scrcpy, the Windows zip ships adb too)
# and USB debugging enabled on phone A.
param([switch]$Record)

$ErrorActionPreference = "Stop"
$scrcpyArgs = @(
    "--window-title", "FriendOrFoe - phone A",
    "--no-audio",            # nothing to hear; keeps the stream lean
    "--stay-awake",          # phone screen stays on while plugged in
    "--show-touches",        # judges (and the backup video) see the tap on the drone box
    "--max-size", "1920",
    "--video-bit-rate", "8M"
)
if ($Record) {
    $dir = Join-Path $PSScriptRoot "..\recordings"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $file = Join-Path $dir ("demo-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".mp4")
    $scrcpyArgs += "--record=$file"
    Write-Host "Recording to $file (stop with Ctrl+C or by closing the window)"
}
scrcpy @scrcpyArgs
