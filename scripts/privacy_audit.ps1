# Privacy & permission audit gate (PRD 7.14 / A-20).
#
# Hard rules:
#   - No INTERNET permission, ever.
#   - Only the PRD 7.14 minimal permission set may appear.
#   - No location/camera/contacts/phone/bluetooth.
#
# Optional device mode:
#   ./privacy_audit.ps1 -Device
# prints the runtime permission state via `adb shell dumpsys package com.sertum.player`.
param(
    [switch]$Device,
    [string]$Apk,
    [string]$Aapt
)

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$manifest = Join-Path $root 'app\src\main\AndroidManifest.xml'
if (-not (Test-Path $manifest)) {
    Write-Error "Manifest not found: $manifest"
    exit 1
}

$allowed = @(
    'android.permission.READ_MEDIA_AUDIO'
    'android.permission.READ_EXTERNAL_STORAGE'
    'android.permission.POST_NOTIFICATIONS'
    'android.permission.FOREGROUND_SERVICE'
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'
    # MANAGE_EXTERNAL_STORAGE used to be listed here, with the argument that declaring it is not
    # the same as requesting it. That argument held while the optional full-disk scan existed.
    # The scan no longer walks the file system - the switch now gates the system media index - so
    # nothing reads files directly and the declaration was removed. It is a forbidden marker below
    # now, so reintroducing it fails this gate instead of passing silently.
)

# These two are merged in by AndroidX dependencies, not by our manifest:
# - WAKE_LOCK: media3-session foreground-media plumbing (PRD 7.14
#   "foreground media service related permissions").
# - DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION: androidx.core signature-level
#   internal permission for ContextCompat.registerReceiver (RECEIVER_NOT_EXPORTED).
$allowedApkOnly = @(
    'android.permission.WAKE_LOCK'
    'com.sertum.player.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
)

# Matched as substrings of the permission name, so nothing may be listed here that also appears
# inside a permission the PRD permits. EXTERNAL_STORAGE is the sharp edge and cannot be used:
# it is a substring of READ_EXTERNAL_STORAGE, which the PRD permits. The full name is safe - it is
# not a substring of anything allowed. The entries below are checked against the allow list for
# that reason - a marker that collides fails every allowed permission it appears in.
$forbiddenMarkers = @(
    'INTERNET'
    'MANAGE_EXTERNAL_STORAGE'
    'ACCESS_NETWORK'
    'CAMERA'
    'LOCATION'
    'CONTACTS'
    'READ_PHONE'
    'CALL_PHONE'
    'RECORD_AUDIO'
    'BLUETOOTH'
    'ACTIVITY_RECOGNITION'
    'BODY_SENSORS'
    'CALENDAR'
    'SMS'
)

[xml]$xml = Get-Content $manifest -Raw
$androidNs = 'http://schemas.android.com/apk/res/android'
$toolsNs = 'http://schemas.android.com/tools'
$nodes = $xml.SelectNodes('//uses-permission')
$present = @()
foreach ($node in $nodes) {
    $name = $node.GetAttribute('name', $androidNs)
    $toolsNode = $node.GetAttribute('node', $toolsNs)
    if ($toolsNode -eq 'remove') { continue } # intentionally stripped at manifest merge
    if ($name) { $present += $name }
}

Write-Output ('Declared permissions: ' + (($present | Sort-Object) -join ', '))
if (-not $present) { Write-Output '(none)' }

foreach ($permission in $present) {
    foreach ($marker in $forbiddenMarkers) {
        if ($permission -match [regex]::Escape($marker)) {
            Write-Error "FAIL: forbidden permission marker '$marker' in '$permission' (PRD 7.14 / A-20)"
            exit 1
        }
    }
    if ($allowed -notcontains $permission) {
        Write-Error "FAIL: permission '$permission' is outside the PRD 7.14 minimal set"
        exit 1
    }
}

if ($present -contains 'android.permission.INTERNET') {
    Write-Error 'FAIL: INTERNET permission found (offline boundary violated)'
    exit 1
}

Write-Output 'OK: permission set is within the PRD 7.14 minimal set and contains no INTERNET.'

if ($Apk) {
    $aaptPath = $Aapt
    if (-not $aaptPath) {
        $candidates = @()
        if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'build-tools\36.0.0\aapt.exe') }
        if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt.exe') }
        $candidates += 'E:\You and I - Gods Creation\tools\Android\Sdk\build-tools\36.0.0\aapt.exe'
        $aaptPath = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    }
    if (-not $aaptPath -or -not (Test-Path $aaptPath)) {
        Write-Error 'FAIL: aapt not found; pass -Aapt <path> for the APK audit.'
        exit 1
    }
    $apkPath = $Apk
    if (-not (Test-Path $apkPath)) {
        Write-Error "FAIL: APK not found: $apkPath"
        exit 1
    }
    Write-Output ''
    Write-Output "=== merged APK permission audit: $apkPath ==="
    $dump = & $aaptPath dump permissions $apkPath
    $apkPermissions = @()
    foreach ($line in $dump) {
        if ($line -match "uses-permission: name='([^']+)'") {
            $apkPermissions += $Matches[1]
        }
    }
    Write-Output ('Merged permissions: ' + (($apkPermissions | Sort-Object) -join ', '))
    foreach ($permission in $apkPermissions) {
        foreach ($marker in $forbiddenMarkers) {
            if ($permission -match [regex]::Escape($marker)) {
                Write-Error "FAIL: merged APK contains forbidden permission '$permission'"
                exit 1
            }
        }
        if ($allowed -notcontains $permission -and $allowedApkOnly -notcontains $permission) {
            Write-Error "FAIL: merged APK permission '$permission' is outside the PRD 7.14 minimal set"
            exit 1
        }
    }
    if ($apkPermissions -contains 'android.permission.ACCESS_NETWORK_STATE') {
        Write-Error 'FAIL: merged APK still contains ACCESS_NETWORK_STATE (must be stripped via tools:node=remove)'
        exit 1
    }
    Write-Output 'OK: merged APK permission set is within the PRD 7.14 minimal set (plus AndroidX internal entries).'
}

if ($Device) {
    Write-Output ''
    Write-Output '=== device runtime permission state ==='
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adb) {
        Write-Warning 'adb not found on PATH; skipping device check.'
        exit 0
    }
    adb shell dumpsys package com.sertum.player | Select-String -Pattern 'requested permissions|runtime permissions|android.permission'
}

exit 0
