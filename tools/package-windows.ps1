param(
    [switch]$Offline,
    [switch]$SkipTests,
    [switch]$Replace
)

# Reusable local Windows packaging. Never commits, uploads, or changes user data.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $projectRoot 'build\windows-package'
$appName = 'MC World Explorer'
$appImage = Join-Path $outputRoot $appName
$taskJdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Split-Path -Parent (Split-Path -Parent (Get-Command javac -ErrorAction Stop).Source)
}
$jpackage = Join-Path $taskJdk 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) { throw 'A Windows JDK 21 with jpackage is required.' }
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Windows x64 packaging must run on Windows.' }
$javaInfo = New-Object Diagnostics.ProcessStartInfo
$javaInfo.FileName = Join-Path $taskJdk 'bin\java.exe'
$javaInfo.Arguments = '-XshowSettings:properties -version'
$javaInfo.UseShellExecute = $false
$javaInfo.CreateNoWindow = $true
$javaInfo.RedirectStandardError = $true
$javaInfo.RedirectStandardOutput = $true
$javaCheck = New-Object Diagnostics.Process
$javaCheck.StartInfo = $javaInfo
try {
    [void]$javaCheck.Start()
    $stderrTask = $javaCheck.StandardError.ReadToEndAsync()
    $stdoutTask = $javaCheck.StandardOutput.ReadToEndAsync()
    if (-not $javaCheck.WaitForExit(15000)) { $javaCheck.Kill(); throw 'Timed out while checking packaging JDK.' }
    $jdkProperties = $stderrTask.Result + $stdoutTask.Result
    $javaExitCode = $javaCheck.ExitCode
} finally { $javaCheck.Dispose() }
if ($javaExitCode -ne 0 -or $jdkProperties -notmatch '(?m)^\s*java.specification.version\s*=\s*21\s*$' -or
    $jdkProperties -notmatch '(?m)^\s*os.arch\s*=\s*(amd64|x86_64)\s*$') { throw 'Packaging requires the selected JDK to be Java 21 x64.' }

# Check existing parents and descendants before Gradle can write into the output tree.
$buildRoot = Join-Path $projectRoot 'build'
if (Test-Path -LiteralPath $buildRoot) {
    if ((Get-Item -LiteralPath $buildRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing linked build directory.' }
}
foreach ($tree in @((Join-Path $buildRoot 'core-0.7.4'), $outputRoot)) {
    if (Test-Path -LiteralPath $tree) {
        if ((Get-Item -LiteralPath $tree -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing linked tree: $tree" }
        if (Get-ChildItem -LiteralPath $tree -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw "Refusing linked contents: $tree" }
    }
}

function Assert-PackageOutput([string]$Target) {
    $resolved = [IO.Path]::GetFullPath($Target)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $outputRoot) { throw "Unexpected packaging target: $resolved" }
    if (Test-Path -LiteralPath $resolved) {
        if (-not $Replace) { throw "Output exists: $resolved. Use -Replace to replace only this package." }
        if ((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing linked output: $resolved" }
        if ((Get-Item -LiteralPath $resolved -Force).PSIsContainer -and
            (Get-ChildItem -LiteralPath $resolved -Recurse -Force -ErrorAction Stop | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })) { throw "Refusing linked contents: $resolved" }
    }
}

Push-Location -LiteralPath $projectRoot
try {
    $gradleArgs = @('--console=plain', 'prepareWindowsPackaging', 'verifyCoreIsolation')
    if (-not $SkipTests) { $gradleArgs += 'test' }
    if ($Offline) { $gradleArgs += '--offline' }
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)." }

    $inputDir = Join-Path $projectRoot 'build\core-0.7.4\install\MC-World-Explorer\lib'
    $expected = Get-Content -LiteralPath (Join-Path $buildRoot 'core-0.7.4\packaging\runtime-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (Get-ChildItem -LiteralPath $inputDir -Directory -Force) { throw 'Unexpected nested directory in distribution inputs.' }
    $actual = @(Get-ChildItem -LiteralPath $inputDir -File -Force)
    $names = @($expected.PSObject.Properties.Name | Sort-Object)
    if (Compare-Object $names @($actual.Name | Sort-Object)) { throw 'Distribution files differ from current resolved inputs.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($inputFile in $actual) {
        if ((Get-FileHash -LiteralPath $inputFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.($inputFile.Name)) {
            throw "Distribution content differs: $($inputFile.Name)"
        }
        $archive = [IO.Compression.ZipFile]::OpenRead($inputFile.FullName)
        try {
            if ($archive.Entries | Where-Object { $_.FullName -like '*mcwe-model-plugin.json' -or $_.FullName -like 'tutorial/*' -or $_.FullName -like 'org/mcwe/plugins/*' }) {
                throw "Plugin content leaked into distribution: $($inputFile.Name)"
            }
        } finally { $archive.Dispose() }
    }
    $mainJars = @(Get-ChildItem -LiteralPath $inputDir -Filter 'MC-World-Explorer-*.jar' -File)
    if ($mainJars.Count -ne 1) { throw 'Expected exactly one current application JAR.' }
    $mainJar = $mainJars[0]
    $version = $mainJar.BaseName.Substring('MC-World-Explorer-'.Length)
    if ($version -notmatch '^\d+\.\d+\.\d+(-SNAPSHOT)?$') { throw "Unexpected application version: $version" }
    $nativeVersion = $version -replace '-SNAPSHOT$', ''
    $zipPath = Join-Path $outputRoot "MC-World-Explorer-$version-windows-x64.zip"
    $checksumPath = "$zipPath.sha256"
    if ((Get-Item -LiteralPath $buildRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing linked build directory.' }
    if (Test-Path -LiteralPath $outputRoot) {
        if ((Get-Item -LiteralPath $outputRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing linked output directory.' }
    }
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    # Preflight all output collisions before removing anything.
    foreach ($target in @($appImage, $zipPath, $checksumPath)) {
        Assert-PackageOutput $target
    }
    foreach ($target in @($appImage, $zipPath, $checksumPath)) {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    }
    & $jpackage --type app-image --name $appName --app-version $nativeVersion `
        --vendor 'MC World Explorer' --description 'Read-only Minecraft world explorer' `
        --input $inputDir --main-jar $mainJar.Name --main-class 'com.mcworldexplorer.Launcher' `
        --icon (Join-Path $PSScriptRoot 'windows\mc-world-explorer.ico') --dest $outputRoot `
        --add-modules 'java.se,jdk.unsupported' --jlink-options '--strip-debug --no-header-files --no-man-pages' `
        --java-options '-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)." }
    if (-not (Test-Path -LiteralPath (Join-Path $appImage 'runtime\bin\java.exe') -PathType Leaf)) {
        throw 'The packaged runtime must retain java.exe for isolated plugin workers.'
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'windows\README.txt'), (Join-Path $projectRoot 'LICENSE') -Destination $appImage
    if (Test-Path -LiteralPath (Join-Path $appImage 'config\model-plugins')) { throw 'A new package must not contain installed plugins.' }
    if (Get-ChildItem -LiteralPath $appImage -Recurse -Filter 'mcwe-model-plugin.json' -File) { throw 'A plugin manifest leaked into the package.' }
    Compress-Archive -LiteralPath $appImage -DestinationPath $zipPath -CompressionLevel Optimal
    $checksum = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath $checksumPath -Value "$checksum  $([IO.Path]::GetFileName($zipPath))" -Encoding ASCII
    Write-Output "Package: $zipPath"
    Write-Output "SHA-256: $checksum"
} finally {
    Pop-Location
}
