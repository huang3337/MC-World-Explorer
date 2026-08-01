param(
    [Parameter(Mandatory = $true)]
    [string]$SamplesPath,
    [int]$StaticSeconds = 60,
    [int]$MotionSeconds = 60,
    [int]$WarmRuns = 5,
    [int]$WarmRunSeconds = 2,
    [switch]$LongOnly
)

$ErrorActionPreference = "Stop"

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$samplesFile = [System.IO.Path]::GetFullPath($SamplesPath)
$reportFolder = if ($LongOnly) { "measurement-stability" } else { "measurement" }
$reportRoot = Join-Path $projectRoot "build\reports\v04-3d\$reportFolder"
$installLib = Join-Path $projectRoot "build\install\MC-World-Explorer\lib"
$java = (Get-Command java.exe -ErrorAction Stop).Source

if ($StaticSeconds -lt 1 -or $MotionSeconds -lt 1) {
    throw "StaticSeconds and MotionSeconds must both be positive"
}
if (-not $LongOnly -and ($WarmRuns -lt 5 -or $WarmRunSeconds -lt 1)) {
    throw "WarmRuns must be at least 5 and WarmRunSeconds must be positive"
}
if (-not (Test-Path -LiteralPath $samplesFile -PathType Leaf)) {
    throw "Samples file not found: $samplesFile"
}

function Quote-Argument([string]$Value) {
    if ($Value.Contains('"')) {
        throw "Argument contains an unsupported quote: $Value"
    }
    return '"' + $Value + '"'
}

function Get-WorldManifest([string]$WorldPath) {
    $world = [System.IO.Path]::GetFullPath($WorldPath)
    if (-not (Test-Path -LiteralPath $world -PathType Container)) {
        throw "World directory not found: $world"
    }
    $files = @(Get-ChildItem -LiteralPath $world -Recurse -File | Sort-Object FullName)
    $entries = foreach ($file in $files) {
        [pscustomobject]@{
            path = $file.FullName.Substring($world.Length).TrimStart('\')
            length = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    [pscustomobject]@{
        world = $world
        fileCount = $files.Count
        totalBytes = ($files | Measure-Object -Property Length -Sum).Sum
        files = @($entries)
    }
}

function Save-Json([object]$Value, [string]$Path, [int]$Depth = 8) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $json = $Value | ConvertTo-Json -Depth $Depth
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Get-JavaVersionText {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $text = $process.StandardError.ReadToEnd().Trim()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "java -version failed with exit code $($process.ExitCode)"
    }
    return $text
}

function Start-MeasuredTrial(
    [string]$Backend,
    [object]$Sample,
    [string]$RunName,
    [int]$AutoCloseSeconds,
    [int]$MotionAfterSeconds,
    [switch]$Screenshot
) {
    $mainClass = if ($Backend -eq "javafx") {
        "com.mcworldexplorer.experimental.v04.render.javafx.JavaFxV04Launcher"
    } else {
        "com.mcworldexplorer.experimental.v04.render.lwjgl.LwjglV04Launcher"
    }
    $sampleDirectory = Join-Path $reportRoot $Sample.id
    $report = Join-Path $sampleDirectory "$Backend-$RunName.json"
    $screenshotPath = Join-Path $sampleDirectory "$Backend-$RunName.png"
    New-Item -ItemType Directory -Force -Path $sampleDirectory | Out-Null

    $arguments = @(
        "-Dfile.encoding=UTF-8",
        "-cp", (Quote-Argument (Join-Path $installLib "*")),
        $mainClass,
        "--world", (Quote-Argument ([System.IO.Path]::GetFullPath([string]$Sample.world))),
        "--dimension", [string]$Sample.dimension,
        "--chunk-x", [string]$Sample.chunkX,
        "--chunk-z", [string]$Sample.chunkZ,
        "--report", (Quote-Argument $report),
        "--auto-close-seconds", [string]$AutoCloseSeconds
    )
    if ($MotionAfterSeconds -ge 0) {
        $arguments += @("--motion-after-seconds", [string]$MotionAfterSeconds)
    }
    if ($Screenshot) {
        $arguments += @("--screenshot", (Quote-Argument $screenshotPath))
    }

    $process = Start-Process -FilePath $java `
        -ArgumentList ($arguments -join ' ') `
        -PassThru `
        -WindowStyle Hidden
    $peakWorkingSet = 0L
    $workingSetSamples = [System.Collections.Generic.List[long]]::new()
    while (-not $process.HasExited) {
        try {
            $process.Refresh()
            $peakWorkingSet = [Math]::Max($peakWorkingSet, $process.WorkingSet64)
            $workingSetSamples.Add($process.WorkingSet64)
        } catch [System.InvalidOperationException] {
            break
        }
        Start-Sleep -Milliseconds 250
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "$Backend $RunName failed for sample $($Sample.id): exit $($process.ExitCode)"
    }
    if (-not (Test-Path -LiteralPath $report -PathType Leaf)) {
        throw "$Backend $RunName did not produce report: $report"
    }
    if ($Screenshot -and -not (Test-Path -LiteralPath $screenshotPath -PathType Leaf)) {
        throw "$Backend $RunName did not produce screenshot: $screenshotPath"
    }
    $metrics = Get-Content -LiteralPath $report -Raw -Encoding UTF8 | ConvertFrom-Json
    $windowSize = [Math]::Min(20, $workingSetSamples.Count)
    $firstWindow = @($workingSetSamples | Select-Object -First $windowSize)
    $lastWindow = @($workingSetSamples | Select-Object -Last $windowSize)
    $quarterAverages = @()
    if ($workingSetSamples.Count -ge 4) {
        for ($quarter = 0; $quarter -lt 4; $quarter++) {
            $start = [int][Math]::Floor($workingSetSamples.Count * $quarter / 4.0)
            $end = [int][Math]::Floor($workingSetSamples.Count * ($quarter + 1) / 4.0)
            $count = [Math]::Max(1, $end - $start)
            $values = @($workingSetSamples | Select-Object -Skip $start -First $count)
            $quarterAverages += [long](($values | Measure-Object -Average).Average)
        }
    }
    [pscustomobject]@{
        sampleId = $Sample.id
        backend = $Backend
        run = $RunName
        report = $report
        screenshot = if ($Screenshot) { $screenshotPath } else { $null }
        peakWorkingSetBytes = $peakWorkingSet
        workingSetSampleCount = $workingSetSamples.Count
        workingSetFirstWindowAverageBytes = if ($windowSize -gt 0) {
            [long](($firstWindow | Measure-Object -Average).Average)
        } else { 0L }
        workingSetLastWindowAverageBytes = if ($windowSize -gt 0) {
            [long](($lastWindow | Measure-Object -Average).Average)
        } else { 0L }
        workingSetQuarterAverageBytes = @($quarterAverages)
        metrics = $metrics
    }
}

$samplesDocument = Get-Content -LiteralPath $samplesFile -Raw -Encoding UTF8 | ConvertFrom-Json
$samples = @($samplesDocument.samples)
$requiredSampleCount = if ($LongOnly) { 1 } else { 3 }
if ($samples.Count -lt $requiredSampleCount) {
    throw "At least $requiredSampleCount samples are required"
}
foreach ($sample in $samples) {
    if ([string]::IsNullOrWhiteSpace([string]$sample.id) -or
            [string]::IsNullOrWhiteSpace([string]$sample.world) -or
            [string]::IsNullOrWhiteSpace([string]$sample.dimension)) {
        throw "Each sample requires id, world and dimension"
    }
}

Push-Location $projectRoot
try {
    & ".\gradlew.bat" installDist
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle installDist failed with exit code $LASTEXITCODE"
    }
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

    $worlds = @($samples | ForEach-Object {
        [System.IO.Path]::GetFullPath([string]$_.world)
    } | Sort-Object -Unique)
    $before = @($worlds | ForEach-Object { Get-WorldManifest $_ })
    Save-Json $before (Join-Path $reportRoot "world-manifest-before.json") 12

    $runs = [System.Collections.Generic.List[object]]::new()
    $longSeconds = $StaticSeconds + $MotionSeconds
    foreach ($sample in $samples) {
        foreach ($backend in @("javafx", "lwjgl")) {
            $runs.Add((Start-MeasuredTrial `
                $backend $sample "cold-long" $longSeconds $StaticSeconds -Screenshot))
            if (-not $LongOnly) {
                for ($index = 1; $index -le $WarmRuns; $index++) {
                    $runs.Add((Start-MeasuredTrial `
                        $backend $sample ("warm-{0:d2}" -f $index) $WarmRunSeconds -1))
                }
            }
        }
    }

    $after = @($worlds | ForEach-Object { Get-WorldManifest $_ })
    Save-Json $after (Join-Path $reportRoot "world-manifest-after.json") 12
    $beforeCompact = $before | ConvertTo-Json -Depth 12 -Compress
    $afterCompact = $after | ConvertTo-Json -Depth 12 -Compress
    $worldsUnchanged = $beforeCompact -ceq $afterCompact
    Save-Json ([pscustomobject]@{
        measuredAt = [DateTimeOffset]::Now.ToString("o")
        java = Get-JavaVersionText
        gpu = @(Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion)
        displayScale = "not captured"
        staticSeconds = $StaticSeconds
        motionSeconds = $MotionSeconds
        warmRuns = $WarmRuns
        warmRunSeconds = $WarmRunSeconds
        longOnly = [bool]$LongOnly
        worldsUnchanged = $worldsUnchanged
        runs = @($runs)
    }) (Join-Path $reportRoot "summary.json") 12
    if (-not $worldsUnchanged) {
        throw "World SHA-256 manifests changed during V0.4 measurement"
    }
    Write-Output "SUMMARY=$(Join-Path $reportRoot 'summary.json')"
    Write-Output "WORLDS_UNCHANGED=$worldsUnchanged"
    Write-Output "RUNS=$($runs.Count)"
} finally {
    Pop-Location
}
