# Runs the USB-exclusive digital-path audit and prints what it measured.
#
# The audit lives in app/src/androidTest/.../ExclusivePathAuditTest.kt and skips itself unless a
# USB audio device is attached. This script exists because the two obvious ways to run it both
# fail: `connectedDebugAndroidTest` reports the task as FAILED even when the suite passes (UTP
# records test_status PASSED with exit code 0, then the Gradle task fails anyway), and the report
# it writes into the app's external files directory cannot be read back with `run-as`, so the
# numbers are lost. `am instrument` bypasses the first problem and the test's internal copy solves
# the second.
#
# Exit codes: 0 = audit passed, 1 = adb missing, 2 = no device, 3 = no USB audio device,
#             4 = instrumentation failed, 5 = a measured value is not what the audit requires.
$ErrorActionPreference = 'Stop'

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCommand) {
    Write-Output 'adb not found on PATH; install platform-tools first.'
    exit 1
}
$adb = $adbCommand.Source
$deviceArgs = @()
if ($env:ANDROID_SERIAL) { $deviceArgs = @('-s', $env:ANDROID_SERIAL) }

$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_.Trim() -match '\sdevice$' }
if (-not $devices) {
    Write-Output 'No device attached. Enable USB debugging and plug the phone in.'
    exit 2
}

$audioDump = & $adb @deviceArgs shell 'dumpsys audio' 2>&1
$usbAttached = ($audioDump | Select-String -Pattern 'Devices:.*usb' -Quiet)
if (-not $usbAttached) {
    Write-Output 'No USB audio device attached; the audit would skip itself.'
    Write-Output 'Attach the DAC and run again.'
    exit 3
}
Write-Output 'USB audio device attached.'

$instrument = & $adb @deviceArgs shell `
    'am instrument -w -e class com.sertum.player.audio.ExclusivePathAuditTest com.sertum.player.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
$instrument | Select-Object -Last 6 | ForEach-Object { Write-Output "  $_" }
if (-not ($instrument | Select-String -Pattern 'OK \(1 test\)' -Quiet)) {
    Write-Output 'Instrumentation did not report a pass.'
    exit 4
}

$json = & $adb @deviceArgs exec-out run-as com.sertum.player cat files/exclusive-path-audit.json 2>&1
if (-not $json) {
    Write-Output 'The audit wrote no report.'
    exit 4
}
$report = ($json -join "`n") | ConvertFrom-Json

Write-Output ''
Write-Output 'Measured:'
Write-Output ("  requested            {0} Hz, {1} ch, {2}-bit, {3} frames" -f $report.sampleRate, $report.channels, $report.bitDepth, $report.framesRequested)
Write-Output ("  bytes expected       {0}" -f $report.expectedBytes)
Write-Output ("  bytes tapped         {0}" -f $report.tappedBytes)
Write-Output ("  bytes submitted      {0}" -f $report.submittedBytes)
Write-Output ("  adapter unchanged    {0}" -f $report.adapterUnchanged)
Write-Output ("  stream rate          {0} Hz" -f $report.streamActualRate)
Write-Output ("  stream format        {0}" -f $report.streamActualFormat)
Write-Output ("  stream sharing mode  {0} (1 = exclusive)" -f $report.streamSharingMode)
Write-Output ("  stream device id     {0}" -f $report.streamDeviceId)
Write-Output ("  frames per burst     {0}" -f $report.streamFramesPerBurst)
Write-Output ("  system music volume  {0}/{1}" -f $report.systemMusicIndex, $report.systemMusicMax)

$failures = @()
if (-not $report.adapterUnchanged) { $failures += 'the adapter changed the samples' }
if ($report.tappedBytes -ne $report.expectedBytes) { $failures += 'the tapped byte count does not match the request' }
if ($report.streamSharingMode -ne 1) { $failures += 'the stream is not exclusive' }
if ($report.streamActualRate -ne $report.sampleRate) { $failures += 'the stream rate differs from the source rate' }

if ($failures) {
    Write-Output ''
    foreach ($f in $failures) { Write-Output "FAIL: $f" }
    exit 5
}
Write-Output ''
Write-Output 'PASS: samples delivered unchanged, on an exclusive stream, at the source rate.'
exit 0
