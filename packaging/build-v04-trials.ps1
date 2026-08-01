param(
    [switch]$SkipTests,
    [string]$Version = "0.4"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$packagingRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$outputRoot = Join-Path $packagingRoot "v04-output"
$tempRoot = Join-Path $packagingRoot "v04-temp"
$noticePath = Join-Path $packagingRoot "V0.4-TRIAL-NOTICE.txt"
$iconPath = Join-Path $packagingRoot "icons\mc-world-explorer.ico"

function Reset-TrialPath([string]$Path) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $requiredPrefix = $packagingRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside packaging directory: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function New-TrialImage(
    [string]$Name,
    [string]$MainClass,
    [string]$InputDir,
    [string]$MainJar
) {
    $backendTemp = Join-Path $tempRoot ($Name -replace '[^A-Za-z0-9.-]', '-')
    New-Item -ItemType Directory -Force -Path $backendTemp | Out-Null
    & jpackage `
        --type app-image `
        --name $Name `
        --app-version $Version `
        --vendor "MC World Explorer" `
        --description "V0.4 3D feasibility trial - not a release" `
        --input $InputDir `
        --main-jar $MainJar `
        --main-class $MainClass `
        --icon $iconPath `
        --dest $outputRoot `
        --temp $backendTemp `
        --add-modules "java.se,jdk.unsupported" `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed for $Name with exit code $LASTEXITCODE"
    }

    $appImage = Join-Path $outputRoot $Name
    Copy-Item -LiteralPath $noticePath -Destination (Join-Path $appImage "V0.4-TRIAL-NOTICE.txt")
    Copy-Item -LiteralPath (Join-Path $projectRoot "LICENSE") -Destination (Join-Path $appImage "LICENSE")
    Write-Output "APP_IMAGE=$appImage"
}

Push-Location $projectRoot
try {
    if (-not $SkipTests) {
        & ".\gradlew.bat" clean test installDist
    } else {
        & ".\gradlew.bat" clean installDist
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $inputDir = Join-Path $projectRoot "build\install\MC-World-Explorer\lib"
    $mainJarCandidates = @(Get-ChildItem -LiteralPath $inputDir -Filter "MC-World-Explorer-*.jar" -File)
    if ($mainJarCandidates.Count -ne 1) {
        throw "Expected exactly one MC World Explorer JAR in ${inputDir}, found $($mainJarCandidates.Count)"
    }
    $mainJar = $mainJarCandidates[0].Name
    foreach ($requiredPath in @($iconPath, $noticePath, (Join-Path $projectRoot "LICENSE"))) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "Required packaging input is missing: $requiredPath"
        }
    }
    $jarNames = @(Get-ChildItem -LiteralPath $inputDir -Filter "*.jar" -File | ForEach-Object Name)
    foreach ($nativePattern in @(
        '^lwjgl-[0-9][^-]*-natives-windows\.jar$',
        '^lwjgl-glfw-[0-9][^-]*-natives-windows\.jar$',
        '^lwjgl-opengl-[0-9][^-]*-natives-windows\.jar$'
    )) {
        if (@($jarNames | Where-Object { $_ -match $nativePattern }).Count -lt 1) {
            throw "Required LWJGL native dependency is missing: $nativePattern"
        }
    }

    Reset-TrialPath $outputRoot
    Reset-TrialPath $tempRoot
    New-Item -ItemType Directory -Force -Path $outputRoot, $tempRoot | Out-Null

    New-TrialImage `
        "MC World Explorer V0.4 JavaFX Trial" `
        "com.mcworldexplorer.experimental.v04.render.javafx.JavaFxV04Launcher" `
        $inputDir `
        $mainJar
    New-TrialImage `
        "MC World Explorer V0.4 LWJGL Trial" `
        "com.mcworldexplorer.experimental.v04.render.lwjgl.LwjglV04Launcher" `
        $inputDir `
        $mainJar

    Write-Output "TRIAL_OUTPUT=$outputRoot"
    Write-Output "NOTICE=Both images intentionally contain the same complete runtime classpath."
} finally {
    Pop-Location
}
