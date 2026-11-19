param(
    [switch]$Install,
    [string]$IdeaPluginsRoot = (Join-Path $env:APPDATA "JetBrains\IntelliJIdea2026.3\plugins")
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SourcePluginDir = Join-Path $ProjectRoot "build\plugin-dist\anyreader"
$ZipPath = Join-Path $ProjectRoot "build\distributions\anyreader-1.0.0.zip"
$TargetPluginDir = Join-Path $IdeaPluginsRoot "anyreader"
$LegacyPluginDir = Join-Path $IdeaPluginsRoot "doc-inspector"

$ExpectedJars = @(
    "anyreader-1.0.0.jar",
    "pdfbox-3.0.5.jar",
    "pdfbox-io-3.0.5.jar",
    "fontbox-3.0.5.jar",
    "commons-logging-1.3.5.jar",
    "jsoup-1.15.4.jar"
)

Write-Host "=== AnyReader IDEA Plugin Installer ==="
Write-Host "Project Root    : $ProjectRoot"
Write-Host "Source Dist Dir : $SourcePluginDir"
Write-Host "Plugin Zip      : $ZipPath"
Write-Host "Target IDEA Dir : $TargetPluginDir"

$SourceLibDir = Join-Path $SourcePluginDir "lib"
$DistReady = Test-Path $SourceLibDir
$ZipReady = Test-Path $ZipPath

Write-Host "Dist Dir Exists : $DistReady"
Write-Host "Zip Exists      : $ZipReady"

if ($DistReady) {
    $ActualJars = Get-ChildItem -Path $SourceLibDir -Filter "*.jar" -File | Select-Object -ExpandProperty Name
    $MissingJars = $ExpectedJars | Where-Object { $_ -notin $ActualJars }
    Write-Host ("Dist Jar Count  : {0}" -f $ActualJars.Count)
    if ($MissingJars.Count -gt 0) {
        Write-Warning ("Missing expected jars: {0}" -f ($MissingJars -join ", "))
    }
} else {
    Write-Warning "Source distribution directory not found. Run 'gradle.bat clean test buildPluginZip' first."
}

$IdeaRunning = @(Get-Process -Name "idea64" -ErrorAction SilentlyContinue).Count -gt 0
Write-Host "IDEA Running    : $IdeaRunning"

if (-not $Install) {
    Write-Host ""
    Write-Host "Dry-run summary only (no files modified). Pass -Install to deploy into IDEA plugins directory."
    exit 0
}

if (-not $DistReady) {
    throw "Cannot install: '$SourceLibDir' does not exist. Build the plugin first."
}

$ActualJars = Get-ChildItem -Path $SourceLibDir -Filter "*.jar" -File | Select-Object -ExpandProperty Name
$MissingJars = $ExpectedJars | Where-Object { $_ -notin $ActualJars }
if ($MissingJars.Count -gt 0) {
    throw ("Cannot install: missing required jar(s): {0}" -f ($MissingJars -join ", "))
}

if ($IdeaRunning) {
    Write-Warning "IntelliJ IDEA (idea64) is currently running; replacing jars while loaded may fail if files are locked."
}

if (-not (Test-Path $IdeaPluginsRoot)) {
    New-Item -ItemType Directory -Path $IdeaPluginsRoot -Force | Out-Null
}

if (Test-Path $LegacyPluginDir) {
    $LegacyBackup = Join-Path $env:TEMP ("doc-inspector.legacy-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    Write-Host "Moving legacy dev plugin '$LegacyPluginDir' to '$LegacyBackup' to avoid duplicate Docs toolWindow conflict."
    Move-Item -Path $LegacyPluginDir -Destination $LegacyBackup -ErrorAction Stop
}

$BackupDir = $null
if (Test-Path $TargetPluginDir) {
    $Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $BackupDir = Join-Path $env:TEMP ("anyreader.bak-" + $Timestamp)
    Write-Host "Existing plugin detected; safely moving to: $BackupDir"
    try {
        Move-Item -Path $TargetPluginDir -Destination $BackupDir -ErrorAction Stop
    } catch {
        throw "Failed to move existing plugin directory '$TargetPluginDir'. Close IntelliJ IDEA and retry. Error: $_"
    }
}

try {
    Copy-Item -Path $SourcePluginDir -Destination $TargetPluginDir -Recurse -Force -ErrorAction Stop
    Write-Host "Successfully deployed AnyReader to: $TargetPluginDir"
    if ($BackupDir -and (Test-Path $BackupDir)) {
        Remove-Item -Path $BackupDir -Recurse -Force -ErrorAction SilentlyContinue
    }
} catch {
    if ($BackupDir -and (Test-Path $BackupDir) -and -not (Test-Path $TargetPluginDir)) {
        Write-Warning "Deployment failed; restoring previous plugin directory from backup."
        Move-Item -Path $BackupDir -Destination $TargetPluginDir -Force -ErrorAction SilentlyContinue
    }
    throw
}
