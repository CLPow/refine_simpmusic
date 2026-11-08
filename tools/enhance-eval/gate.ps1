# "Enhance audio" release gate: run before every signed build (there is no CI).
#
#   .\tools\enhance-eval\gate.ps1 -Flac "C:\path\to\lossless\tracks" [-Eval "C:\path\to\work\folder"]
#
# 1. Unit tests (domain chain rules + the Android processor, incl. the 2.3.8 null test).
# 2. Builds the evaluation set from the lossless tracks (once; delete the folder to rebuild).
# 3. Renders every clip through the phone's own processor in each variant.
# 4. Measures and checks the gates (off bit-exact, true peak, style loudness, idle limiter).
# Exits non-zero if anything fails. Needs JDK 21, Python with numpy/scipy, and ffmpeg with libopus.
param(
    [Parameter(Mandatory = $true)] [string] $Flac,
    [string] $Eval = (Join-Path $env:TEMP "refine-enhance-eval")
)
$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$tools = $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Users\USER\.jdks\jdk-21" }

Push-Location $repo
try {
    & .\gradlew.bat :domain:jvmTest --tests "com.maxrave.domain.audio.*" :media3:testDebugUnitTest --tests "com.maxrave.media3.audio.*" -PisFullBuild=false --no-daemon --rerun
    if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

    if (-not (Test-Path (Join-Path $Eval "in\loudness.json"))) {
        python (Join-Path $tools "prepare.py") $Flac $Eval
        if ($LASTEXITCODE -ne 0) { throw "prepare failed" }
    }
    $out = Join-Path $Eval "out"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    $env:ENHANCE_RENDER_IN = Join-Path $Eval "in"
    $env:ENHANCE_RENDER_OUT = $out
    & .\gradlew.bat :media3:testDebugUnitTest --tests "com.maxrave.media3.audio.EnhanceRenderTest" -PisFullBuild=false --no-daemon --rerun
    if ($LASTEXITCODE -ne 0) { throw "render failed" }

    python (Join-Path $tools "measure.py") $Eval --check
    if ($LASTEXITCODE -ne 0) { throw "gates failed" }
    Write-Host "Enhance audio gate: PASS"
}
finally {
    Pop-Location
}
