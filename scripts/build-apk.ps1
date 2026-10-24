$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "未找到 Java。请安装 JDK 17，或从 Android Studio 内置终端运行。"
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw "未找到 Android SDK 环境变量 ANDROID_HOME 或 ANDROID_SDK_ROOT。"
}

$wrapper = Join-Path $projectRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $wrapper)) {
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $gradle) {
        throw "缺少 Gradle Wrapper。请先在 Android Studio 中打开项目，或安装 Gradle 8.13 后运行：gradle wrapper --gradle-version 8.13"
    }
    Push-Location $projectRoot
    try { & $gradle.Source wrapper --gradle-version 8.13 } finally { Pop-Location }
}

Push-Location $projectRoot
try {
    & $wrapper clean testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Debug 构建或测试失败。" }

    if (Test-Path -LiteralPath (Join-Path $projectRoot "signing/signing.properties")) {
        & $wrapper assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "Release APK 构建失败。" }
        Write-Output "Release APK: app/build/outputs/apk/release/app-release.apk"
    } else {
        Write-Output "未检测到专用签名，仅生成 Debug APK。"
    }
    Write-Output "Debug APK: app/build/outputs/apk/debug/app-debug.apk"
} finally {
    Pop-Location
}

