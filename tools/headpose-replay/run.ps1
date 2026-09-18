# v5.44 offline replay verification for HeadPoseDetector (the near-tier "suddenness" window).
#
# Compiles the REAL HeadPoseDetector.kt (with a printing stub for android.util.Log) plus
# HeadPoseReplay.kt using the kotlin compiler already present in the local Gradle cache.
# No network needed. Output goes to result.txt (UTF-8).
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
# NOTE: keep this file ASCII-only - Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\source'
$jdk  = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\toolchain\jdk\jdk-17.0.20.1+1'
$cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin"
$cachex = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx"

$compiler = (Get-ChildItem "$cache\kotlin-compiler-embeddable" -Recurse -Filter 'kotlin-compiler-embeddable-2.0.21.jar' | Select-Object -First 1).FullName
$stdlib   = (Get-ChildItem "$cache\kotlin-stdlib" -Recurse -Filter 'kotlin-stdlib-2.0.21.jar' | Select-Object -First 1).FullName
$corout   = (Get-ChildItem "$cachex\kotlinx-coroutines-core-jvm" -Recurse -Filter 'kotlinx-coroutines-core-jvm-1.7.3.jar' | Select-Object -First 1).FullName
$trove    = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.intellij.deps\trove4j" -Recurse -Filter 'trove4j-*.jar' | Select-Object -First 1).FullName
$annot    = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains\annotations\23.0.0" -Recurse -Filter 'annotations-*.jar' | Select-Object -First 1).FullName
if (-not $compiler -or -not $stdlib) { throw 'kotlin compiler/stdlib not found in the Gradle cache' }
if (-not $corout) { throw 'kotlinx-coroutines-core-jvm not found in the Gradle cache' }
if (-not $trove) { throw 'trove4j not found in the Gradle cache' }
if (-not $annot) { throw 'org.jetbrains annotations not found in the Gradle cache' }
$compilerCp = "$compiler;$stdlib;$corout;$trove;$annot"

$out   = Join-Path $root 'out'
$stubc = Join-Path $root 'stub-classes'
$res   = Join-Path $root 'result.txt'
Remove-Item $out, $stubc -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out, $stubc | Out-Null

# 1) compile the Log stub (javac needs -encoding: the machine default is GBK)
& "$jdk\bin\javac.exe" -encoding UTF-8 -d $stubc (Join-Path $root 'stub\android\util\Log.java')

# 2) compile the real detector + the replay harness
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -cp "$stdlib;$stubc" -d $out `
    (Join-Path $proj 'app\src\main\java\com\example\gazescroll\HeadPoseDetector.kt') `
    (Join-Path $root 'HeadPoseReplay.kt')

# 3) run it; UTF-8 -> result.txt. Continue: a failing run exits non-zero on purpose.
$ErrorActionPreference = 'Continue'
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" "-Dsun.stderr.encoding=UTF-8" `
    -cp "$out;$stubc;$stdlib" com.example.gazescroll.HeadPoseReplayKt 2>&1 |
    Out-File -FilePath $res -Encoding utf8

Write-Output "exit=$LASTEXITCODE  -> $res"
Select-String -Path $res -Pattern 'PASS|FAIL|===' | ForEach-Object { $_.Line }
