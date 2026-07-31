<#
.SYNOPSIS
    Copies the portable (Android-free) source files shared with CameraSync3D into this repo.

.DESCRIPTION
    CameraSync3D (the companion Android app) is the source of truth for these files: the EXIF3D
    metadata format, EXIF formatting helpers, the playlist YAML format, the Playlist/PlaylistItem
    domain model + storage abstraction, and the OpenCV auto-align algorithm core. Each is kept free
    of android.* imports on the Android side specifically so it can be copied here verbatim.

    Run this manually after changing any of those files in CameraSync3D. It is a plain file copy
    (no text rewriting) - both copies must declare the same package, so the destination mirrors the
    source's package-relative path.

    Also warns if these have drifted between the two projects:
      - Jackson (tools.jackson.*): Desc3d.kt/YamlPlaylistFormat.kt are only wire-compatible
        between apps if both sides serialize/deserialize with the same version.
      - OpenCV: AutoAlignCore.kt calls OpenCV 5-specific packages (org.opencv.features,
        org.opencv.geometry); a version drift could mean mismatched APIs or algorithm behavior
        between the two apps' auto-align results.
      - Kotlin: the synced .kt files must compile under both projects' Kotlin toolchains.

.PARAMETER AndroidProjectPath
    Path to the CameraSync3D checkout. Defaults to the path used throughout this port.
#>
param(
    [string]$AndroidProjectPath = "C:\Users\frank\Documents\AndroidStudioProjects\CameraSync3D"
)

$ErrorActionPreference = "Stop"

$androidSrc = Join-Path $AndroidProjectPath "app\src\main\java"
$repoRoot = Split-Path -Parent $PSScriptRoot

# Each entry: source path (relative to $androidSrc), destination source set (commonMain/desktopMain).
# Destination path always mirrors the source's package-relative path under that source set.
# AutoAlignCore.kt needs org.opencv.* types, only available where OpenCV is vendored (desktopMain);
# everything else is plain Kotlin/Jackson and belongs in commonMain (this project's one real target
# today is desktop, but commonMain is where future KMP targets would pick it up too).
$filesToSync = @(
    @{ Path = "fr\camera3d\camera\shared\Desc3d.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\shared\ExifFormatting.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\service\yamlImporter\YamlPlaylistFormat.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\Playlist.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\PlaylistItem.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\PlaylistStorage.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_edit\autoalign\AutoAlignCore.kt"; SourceSet = "desktopMain" }
)

if (-not (Test-Path $androidSrc)) {
    throw "CameraSync3D source root not found at '$androidSrc'. Pass -AndroidProjectPath if it's elsewhere."
}

# --- Jackson version check ---
# Desc3d.kt/YamlPlaylistFormat.kt are only wire-compatible between the two apps if both serialize
# with the same Jackson version, so a drift here is worth flagging even though it can't break the
# sync/copy itself.
function Get-JacksonVersions([string]$buildFilePath) {
    $versions = @{}
    if (-not (Test-Path $buildFilePath)) { return $versions }
    $content = Get-Content -Path $buildFilePath -Raw
    foreach ($m in [regex]::Matches($content, '(tools\.jackson\.[\w.]+:[\w-]+):(\d+(?:\.\d+)+)')) {
        $versions[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $versions
}

$androidBuildFile = Join-Path $AndroidProjectPath "app\build.gradle"
$desktopBuildFile = Join-Path $repoRoot "build.gradle.kts"
$androidJackson = Get-JacksonVersions $androidBuildFile
$desktopJackson = Get-JacksonVersions $desktopBuildFile

$jacksonCoords = @($androidJackson.Keys) + @($desktopJackson.Keys) | Select-Object -Unique
if ($jacksonCoords.Count -eq 0) {
    Write-Warning "Could not find any tools.jackson.* dependency declarations in either build file - skipping version check."
}
foreach ($coord in $jacksonCoords) {
    $androidVersion = $androidJackson[$coord]
    $desktopVersion = $desktopJackson[$coord]
    if ($androidVersion -and $desktopVersion -and $androidVersion -ne $desktopVersion) {
        Write-Warning "Jackson version mismatch for ${coord}: CameraSync3D uses $androidVersion, sbs3Dfullscreen uses $desktopVersion. Update build.gradle.kts to match - otherwise EXIF3D/playlist YAML data written by one app may not round-trip through the other."
    } elseif ($androidVersion -and -not $desktopVersion) {
        Write-Warning "${coord} ($androidVersion) is declared in CameraSync3D but not in sbs3Dfullscreen's build.gradle.kts."
    } elseif ($desktopVersion -and -not $androidVersion) {
        Write-Warning "${coord} ($desktopVersion) is declared in sbs3Dfullscreen but not found in CameraSync3D's build.gradle."
    } else {
        Write-Host "Jackson version OK: $coord = $androidVersion"
    }
}

# --- OpenCV version check ---
# Android declares a real Maven version; desktop vendors a local jar (no Maven coordinate to
# read), so this compares Android's declared version against the vendored jar's filename, which
# encodes the version by OpenCV's own convention (5.0.0 -> opencv-500.jar - see libs/opencv/README.md).
function Get-AndroidOpenCvVersion([string]$buildFilePath) {
    if (-not (Test-Path $buildFilePath)) { return $null }
    $content = Get-Content -Path $buildFilePath -Raw
    $m = [regex]::Match($content, 'org\.opencv:opencv:(\d+)\.(\d+)\.(\d+)(?:\.\d+)?')
    if (-not $m.Success) { return $null }
    return [PSCustomObject]@{
        Full  = $m.Value.Substring($m.Value.LastIndexOf(':') + 1)
        Major = $m.Groups[1].Value
        Minor = $m.Groups[2].Value
        Patch = $m.Groups[3].Value
    }
}

$androidOpenCv = Get-AndroidOpenCvVersion (Join-Path $AndroidProjectPath "app\build.gradle")
$opencvDir = Join-Path $repoRoot "libs\opencv"
if (-not $androidOpenCv) {
    Write-Warning "Could not find an org.opencv:opencv:<version> declaration in CameraSync3D's app\build.gradle - skipping OpenCV version check."
} else {
    $expectedJarName = "opencv-$($androidOpenCv.Major)$($androidOpenCv.Minor)$($androidOpenCv.Patch).jar"
    $expectedJarPath = Join-Path $opencvDir $expectedJarName
    $existingJars = if (Test-Path $opencvDir) { Get-ChildItem $opencvDir -Filter "opencv-*.jar" } else { @() }

    if (Test-Path $expectedJarPath) {
        Write-Host "OpenCV version OK: CameraSync3D uses $($androidOpenCv.Full), found matching $expectedJarName"
    } elseif ($existingJars.Count -gt 0) {
        Write-Warning "OpenCV version mismatch: CameraSync3D uses $($androidOpenCv.Full) (expects $expectedJarName), but libs\opencv\ has $(($existingJars.Name) -join ', ') instead. Re-vendor the matching OpenCV Windows build - see libs/opencv/README.md."
    } else {
        Write-Host "OpenCV not vendored yet ($expectedJarName expected once you do - see libs/opencv/README.md)."
    }
}

# --- Kotlin version check ---
function Get-AndroidKotlinVersion([string]$rootBuildFilePath) {
    if (-not (Test-Path $rootBuildFilePath)) { return $null }
    $content = Get-Content -Path $rootBuildFilePath -Raw
    $m = [regex]::Match($content, "kotlin_version\s*=\s*['""]([\d.]+)['""]")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Get-DesktopKotlinVersion([string]$buildFileKtsPath) {
    if (-not (Test-Path $buildFileKtsPath)) { return $null }
    $content = Get-Content -Path $buildFileKtsPath -Raw
    $m = [regex]::Match($content, 'kotlin\("multiplatform"\)\s*version\s*"([\d.]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

$androidKotlin = Get-AndroidKotlinVersion (Join-Path $AndroidProjectPath "build.gradle")
$desktopKotlin = Get-DesktopKotlinVersion $desktopBuildFile
if (-not $androidKotlin -or -not $desktopKotlin) {
    Write-Warning "Could not determine Kotlin version for one or both projects - skipping Kotlin version check."
} elseif ($androidKotlin -ne $desktopKotlin) {
    Write-Warning "Kotlin version mismatch: CameraSync3D uses $androidKotlin, sbs3Dfullscreen uses $desktopKotlin. The synced files should still compile across adjacent versions, but keeping them aligned avoids subtle language/stdlib differences."
} else {
    Write-Host "Kotlin version OK: $androidKotlin"
}

$copied = 0
foreach ($entry in $filesToSync) {
    $sourcePath = Join-Path $androidSrc $entry.Path
    $destPath = Join-Path $repoRoot "src\$($entry.SourceSet)\kotlin\$($entry.Path)"

    if (-not (Test-Path $sourcePath)) {
        Write-Warning "Skipping missing source file: $sourcePath"
        continue
    }

    # Fail loudly rather than silently sync a file that picked up an Android-only import -
    # these files are shared verbatim, so this would break the desktop build.
    $forbiddenImports = Select-String -Path $sourcePath -Pattern '^\s*import\s+android(x)?\.'
    if ($forbiddenImports) {
        Write-Error "Refusing to sync $($entry.Path): contains android.*/androidx.* imports:`n$($forbiddenImports -join "`n")"
        continue
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $destPath) | Out-Null
    Copy-Item -Path $sourcePath -Destination $destPath -Force
    Write-Host "Synced $($entry.Path) -> src\$($entry.SourceSet)\kotlin\$($entry.Path)"
    $copied++
}

Write-Host "`nDone: $copied/$($filesToSync.Count) files synced."
