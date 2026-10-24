param(
    [string]$Alias = "car-audio-trigger",
    [string]$DistinguishedName = "CN=Car Audio Trigger, OU=Personal, O=Polaris, C=CN"
)

$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$signingDirectory = Join-Path $projectRoot "signing"
$keystorePath = Join-Path $signingDirectory "car-audio-trigger-release.jks"
$propertiesPath = Join-Path $signingDirectory "signing.properties"

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw "签名配置已存在于 $signingDirectory。为避免破坏后续升级，请不要覆盖它。"
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw "未找到 keytool。请安装 JDK 17，或在 Android Studio 的终端中运行本脚本。"
}

$securePassword = Read-Host "请为签名文件设置密码（至少 6 位）" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ($plainPassword.Length -lt 6) { throw "密码至少需要 6 位。" }
    [IO.Directory]::CreateDirectory($signingDirectory) | Out-Null
    & $keytool.Source -genkeypair -v `
        -keystore $keystorePath `
        -storepass $plainPassword `
        -keypass $plainPassword `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname $DistinguishedName
    if ($LASTEXITCODE -ne 0) { throw "keytool 创建签名失败。" }

    $relativeStorePath = "signing/car-audio-trigger-release.jks"
    @(
        "storeFile=$relativeStorePath"
        "storeType=JKS"
        "storePassword=$plainPassword"
        "keyAlias=$Alias"
        "keyPassword=$plainPassword"
    ) | Set-Content -LiteralPath $propertiesPath -Encoding UTF8
    Write-Output "已创建专用签名：$keystorePath"
    Write-Output "请把 signing 文件夹安全备份；丢失后无法覆盖安装未来版本。"
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
}
