# Builds the GazeScroll Android project with the toolchain in GazeScroll-v5.65\toolchain.
#
#   .\build-apk.ps1                 # debug APK
#   .\build-apk.ps1 -Variant release
#   .\build-apk.ps1 -Clean
#
param(
    [string]$Variant = 'debug',
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'

$ws      = 'D:\ruanjian\deepseek harness'
$root    = Join-Path $ws 'GazeScroll-v5.65\toolchain'
$project = Join-Path $ws 'GazeScroll'

$jdkHome = Join-Path $root 'jdk\jdk-17.0.20.1+1'
$sdkHome = Join-Path $root 'android-sdk'
$gradle  = Join-Path $root 'gradle\gradle-8.9\bin\gradle.bat'

if (-not (Test-Path $jdkHome))  { throw "JDK missing at $jdkHome — run provision-toolchain.ps1 first" }
if (-not (Test-Path $sdkHome))  { throw "SDK missing at $sdkHome — run provision-toolchain.ps1 first" }
if (-not (Test-Path $gradle))   { throw "Gradle missing at $gradle — run provision-toolchain.ps1 first" }

$env:JAVA_HOME        = $jdkHome
$env:ANDROID_HOME     = $sdkHome
$env:ANDROID_SDK_ROOT = $sdkHome
# Keep every Gradle cache inside the workspace instead of the user profile.
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-user-home'
$env:Path             = "$jdkHome\bin;$env:Path"

$cap  = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$task = "assemble$cap"

$gradleArgs = @('-p', $project, '--console=plain', '--stacktrace')
if ($Clean) { $gradleArgs += 'clean' }
$gradleArgs += $task

Write-Output "==> JAVA_HOME  = $env:JAVA_HOME"
Write-Output "==> ANDROID_HOME = $env:ANDROID_HOME"
Write-Output "==> task       = $task"
Write-Output ''

& $gradle @gradleArgs 2>&1 | ForEach-Object { $_ }
$code = $LASTEXITCODE

Write-Output ''
Write-Output "==> gradle exit code: $code"
exit $code
