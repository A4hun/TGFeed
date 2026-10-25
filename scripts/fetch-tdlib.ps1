# 下载 TDLib 预编译 AAR 到 app/libs
# 来源：https://github.com/AkashPriyadarshii/tdlib-android （4 ABI，16KB 页对齐，Android 15 兼容）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root 'app\libs'
New-Item -ItemType Directory -Force $libs | Out-Null
$base = 'https://github.com/AkashPriyadarshii/tdlib-android/releases/download/v0.1.1'
Invoke-WebRequest "$base/core-release.aar" -OutFile (Join-Path $libs 'core-release.aar') -UseBasicParsing
Invoke-WebRequest "$base/ktx-release.aar"  -OutFile (Join-Path $libs 'ktx-release.aar')  -UseBasicParsing
Write-Host "TDLib AARs downloaded to $libs"
