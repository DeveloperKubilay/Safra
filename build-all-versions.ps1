param(
    [string]$ModVersion,
    [string]$OutputDir
)

$gradleArgs = @(
    'collectReleaseMatrixArtifacts',
    '--no-daemon',
    '--console=plain'
)

if ($ModVersion) {
    $gradleArgs += "-Pmod_version=$ModVersion"
}

& "$PSScriptRoot\\gradlew.bat" @gradleArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$effectiveModVersion = if ($ModVersion) { $ModVersion } else { '' }
if (-not $effectiveModVersion) {
    $properties = Get-Content "$PSScriptRoot\\gradle.properties"
    $modVersionLine = $properties | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
    if (-not $modVersionLine) {
        Write-Error 'mod_version could not be resolved from gradle.properties.'
        exit 1
    }
    $effectiveModVersion = $modVersionLine.Split('=', 2)[1]
}

$sourceDir = Join-Path $PSScriptRoot "build\\release-matrix\\$effectiveModVersion"
if (-not (Test-Path $sourceDir)) {
    Write-Error "Release matrix output was not found: $sourceDir"
    exit 1
}

if ($OutputDir) {
    if (Test-Path $OutputDir) {
        Remove-Item -LiteralPath $OutputDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
    Copy-Item -Path (Join-Path $sourceDir '*') -Destination $OutputDir -Recurse -Force
    Write-Host "Artifacts copied to $OutputDir"
} else {
    Write-Host "Artifacts ready at $sourceDir"
}
