# Offline build script
#
# Reproduces the release jar without Gradle or network access, using only a JDK 25 and the
# jars already sitting in a Minecraft launcher's `libraries/` directory. This is the exact
# procedure used to build the published artifact.
#
# Usage:
#   .\build-offline.ps1
#   .\build-offline.ps1 -LibraryDir "D:\...\meta\libraries" -GameJar "D:\...\26.3-0.19.5.jar"
#
# Both -LibraryDir and -GameJar default to auto-detection under the Axolotl launcher.

param(
    [string]$LibraryDir = '',
    [string]$GameJar = '',
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Find-Jdk {
    if ($JavaHome) { return $JavaHome }
    $candidates = @()
    foreach ($base in @('D:\MC\Axolotl Launcher\Axolotl Launcher Data\meta\java_versions')) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
                Sort-Object Name -Descending |
                ForEach-Object { $_.FullName }
        }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $candidates += $env:JAVA_HOME
    }
    if ($candidates.Count -eq 0) { throw 'No JDK with javac found. Pass -JavaHome explicitly.' }
    return $candidates[0]
}

function Find-GameJar {
    if ($GameJar) {
        if (-not (Test-Path $GameJar)) { throw "Game jar not found: $GameJar" }
        return $GameJar
    }
    $versions = 'D:\MC\Axolotl Launcher\Axolotl Launcher Data\meta\versions'
    if (-not (Test-Path $versions)) { throw 'Game jar not found. Pass -GameJar explicitly.' }
$hit = Get-ChildItem $versions -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^26\.3-0\.19\.5\.jar$' } |
        Select-Object -First 1
    if (-not $hit) {
        $hit = Get-ChildItem $versions -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^26\.3' -and $_.Length -gt 10MB } |
            Sort-Object Name | Select-Object -First 1
    }
    if (-not $hit) { throw 'Game jar not found. Pass -GameJar explicitly.' }
    return $hit.FullName
}

$jdk = Find-Jdk
$game = Find-GameJar
if (-not $LibraryDir) { $LibraryDir = 'D:\MC\Axolotl Launcher\Axolotl Launcher Data\meta\libraries' }
if (-not (Test-Path $LibraryDir)) { throw "libraries dir not found: $LibraryDir" }

Write-Host "JDK   : $jdk"
Write-Host "Game  : $game"
Write-Host "Libs  : $LibraryDir"

$mixin  = Get-ChildItem $LibraryDir -Recurse -Filter 'sponge-mixin-*.jar' |
    Sort-Object Name -Descending | Select-Object -First 1
$loader = Get-ChildItem $LibraryDir -Recurse -Filter 'fabric-loader-*.jar' |
    Sort-Object Name -Descending | Select-Object -First 1
$dfu    = Get-ChildItem $LibraryDir -Recurse -Filter 'datafixerupper-*.jar' |
    Sort-Object Name -Descending | Select-Object -First 1
$slf4j  = Get-ChildItem $LibraryDir -Recurse -Filter 'slf4j-api-*.jar' |
    Sort-Object Name -Descending | Select-Object -First 1

foreach ($j in @($mixin, $loader, $dfu, $slf4j)) {
    if (-not $j) { throw 'Required library jar missing (sponge-mixin / fabric-loader / datafixerupper / slf4j-api).' }
}

$srcDir   = Join-Path $root 'src\main\java'
$resDir   = Join-Path $root 'src\main\resources'
$outDir   = Join-Path $root 'build\classes'
$jarDir   = Join-Path $root 'build\libs'
$staging  = Join-Path $root 'build\staging'

foreach ($d in @($outDir, $jarDir, $staging)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$sources = Get-ChildItem $srcDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $sources) { throw 'No .java sources found.' }

$cp = @($game, $mixin.FullName, $loader.FullName, $dfu.FullName, $slf4j.FullName) -join ';'
Write-Host "`nCompiling $($sources.Count) source file(s)..."

$javacArgs = @('-encoding', 'UTF-8', '--release', '21', '-classpath', $cp, '-d', $outDir) + $sources
& (Join-Path $jdk 'bin\javac.exe') $javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Copy-Item (Join-Path $outDir '*') $staging -Recurse -Force
Copy-Item (Join-Path $resDir '*') $staging -Recurse -Force

$license = Join-Path $root 'LICENSE'
if (Test-Path $license) { Copy-Item $license $staging -Force }

$version = '1.0.0'
$gp = Join-Path $root 'gradle.properties'
if (Test-Path $gp) {
    $line = Get-Content $gp | Where-Object { $_ -match '^\s*mod_version\s*=' }
    if ($line) { $version = ($line -split '=', 2)[1].Trim() }
}

$jarPath = Join-Path $jarDir "jeitagfix-$version.jar"
& (Join-Path $jdk 'bin\jar.exe') --create --file $jarPath -C $staging .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host "`nBuilt: $jarPath ($((Get-Item $jarPath).Length) bytes)"
