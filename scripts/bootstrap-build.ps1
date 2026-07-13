param([switch]$SkipAndroidTests)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Tooling = Join-Path $Root '.tooling'
New-Item -ItemType Directory -Force -Path $Tooling | Out-Null

function Find-Jdk17 {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Microsoft\jdk-17*',
        (Join-Path $Tooling 'jdk-17')
    )
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        foreach ($path in (Get-Item $candidate -ErrorAction SilentlyContinue)) {
            if (Test-Path (Join-Path $path.FullName 'bin\java.exe')) { return $path.FullName }
        }
    }
    return $null
}

$Jdk = Find-Jdk17
if (-not $Jdk) {
    $JdkZip = Join-Path $Tooling 'microsoft-jdk-17.zip'
    Write-Host 'Downloading portable JDK 17...'
    Invoke-WebRequest 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' -OutFile $JdkZip
    $JdkExtract = Join-Path $Tooling 'jdk-extract'
    Expand-Archive $JdkZip $JdkExtract -Force
    $JdkFolder = Get-ChildItem $JdkExtract -Directory | Select-Object -First 1
    Move-Item $JdkFolder.FullName (Join-Path $Tooling 'jdk-17') -Force
    $Jdk = Join-Path $Tooling 'jdk-17'
}
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

$Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms\android-36')) } |
    Select-Object -First 1
if (-not $Sdk) {
    $Sdk = Join-Path $Tooling 'android-sdk'
    $Tools = Join-Path $Sdk 'cmdline-tools\latest'
    if (-not (Test-Path (Join-Path $Tools 'bin\sdkmanager.bat'))) {
        $ToolsZip = Join-Path $Tooling 'android-command-line-tools.zip'
        $ToolsExtract = Join-Path $Tooling 'android-command-line-tools'
        Write-Host 'Downloading Android command-line tools...'
        Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip' -OutFile $ToolsZip
        Expand-Archive $ToolsZip $ToolsExtract -Force
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Tools) | Out-Null
        Move-Item (Join-Path $ToolsExtract 'cmdline-tools') $Tools -Force
    }
    $SdkManager = Join-Path $Tools 'bin\sdkmanager.bat'
    1..30 | ForEach-Object { 'y' } | & $SdkManager --sdk_root=$Sdk --licenses | Out-Host
    & $SdkManager --sdk_root=$Sdk 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
}
$env:ANDROID_HOME = $Sdk
$escapedSdk = $Sdk.Replace('\', '\\')
Set-Content -LiteralPath (Join-Path $Root 'local.properties') -Value "sdk.dir=$escapedSdk" -Encoding ASCII

$WrapperJar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $WrapperJar)) {
    Write-Host 'Downloading Gradle Wrapper 8.11.1...'
    Invoke-WebRequest 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile $WrapperJar
}

Push-Location $Root
try {
    & .\gradlew.bat --no-daemon :crystal-core:test :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    $Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $Apk)) { throw "Gradle reported success but APK is missing: $Apk" }
    Write-Host "APK: $Apk" -ForegroundColor Green
} finally {
    Pop-Location
}
