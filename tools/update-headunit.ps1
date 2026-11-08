<#
.SYNOPSIS
  Собрать E90 OS и поставить на магнитолу KSW по Wi-Fi ADB (с бэкапом и откатом).

.EXAMPLE
  .\tools\update-headunit.ps1                    # собрать и поставить на 192.168.0.22
  .\tools\update-headunit.ps1 -Ip 192.168.43.120 # магнитола в сети раздачи телефона
  .\tools\update-headunit.ps1 -Check             # только проверить окружение и связь
  .\tools\update-headunit.ps1 -SkipBuild         # поставить уже собранный APK
  .\tools\update-headunit.ps1 -Rollback          # вернуть предыдущую версию из tools\backup

  Скрипт не меняет настройки ADB и магнитолы, не удаляет приложения и не трогает
  лаунчер KSW. Если подпись APK не совпадает, он останавливается, а не удаляет лаунчер.
#>
param(
    [string]$Ip = "192.168.0.22",
    [int]$Port = 5555,
    [switch]$Check,
    [switch]$SkipBuild,
    [switch]$Rollback
)

# Continue: adb writes progress to stderr, and in Windows PowerShell 5.1 "Stop" would turn it into an error.
$ErrorActionPreference = "Continue"
$Pkg = "com.e90os.launcher"
$Root = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$BackupDir = Join-Path $PSScriptRoot "backup"
$Serial = "${Ip}:${Port}"

function Step($t) { Write-Host "`n== $t" -ForegroundColor Yellow }
function Ok($t) { Write-Host "   OK  $t" -ForegroundColor Green }
function Fail($t) { Write-Host "   ОШИБКА  $t" -ForegroundColor Red; exit 1 }

# ---------- окружение ----------
Step "Проверка окружения"

$sdk = $null
$lp = Join-Path $Root "local.properties"
if (Test-Path $lp) {
    $line = Get-Content $lp | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':' }
}
if (-not $sdk) {
    foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($c -and (Test-Path "$c\platforms")) { $sdk = $c; break }
    }
    if ($sdk) {
        "sdk.dir=$($sdk -replace '\\', '/')" | Set-Content -Encoding ASCII $lp
        Ok "создан local.properties: $sdk"
    }
}
if (-not $SkipBuild -and -not $Rollback) {
    if (-not $sdk -or -not (Test-Path "$sdk\platforms")) { Fail "Android SDK не найден. Установите Android Studio и откройте его один раз (он скачает SDK)." }
    Ok "Android SDK: $sdk"
}

$adb = $null
foreach ($c in @("$sdk\platform-tools\adb.exe", "$env:USERPROFILE\platform-tools\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
    if ($c -and (Test-Path $c)) { $adb = $c; break }
}
if (-not $adb) { $cmd = Get-Command adb -ErrorAction SilentlyContinue; if ($cmd) { $adb = $cmd.Source } }
if (-not $adb) { Fail "adb не найден. Установите platform-tools (Android Studio → SDK Manager → SDK Tools)." }
Ok "adb: $adb"

if (-not $SkipBuild -and -not $Rollback) {
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:ProgramFiles\Android\Android Studio\jre")) {
            if (Test-Path "$c\bin\java.exe") { $env:JAVA_HOME = $c; break }
        }
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) { Fail "Java не найдена. Она идёт вместе с Android Studio." }
    Ok "Java: $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'из PATH' })"
}

$ks = "$env:USERPROFILE\.android\debug.keystore"
if (Test-Path $ks) { Ok "ключ подписи: $ks" }
else { Write-Host "   ВНИМАНИЕ  нет $ks. Скопируйте его с ПК, где собирался установленный лаунчер, иначе установка поверх не пройдёт." -ForegroundColor Magenta }

# ---------- связь ----------
Step "Подключение к магнитоле $Serial"
& $adb connect $Serial | Out-Host
$state = (& $adb -s $Serial get-state 2>&1) | Out-String
if ($state.Trim() -ne "device") {
    Fail "магнитола не отвечает ($($state.Trim())). Проверьте: магнитола включена, ноутбук в той же Wi-Fi сети, VPN выключен, IP верный."
}
$model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
$sdkVer = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
Ok "подключено: $model, Android API $sdkVer"

function InstalledVersion {
    $d = & $adb -s $Serial shell dumpsys package $Pkg | Select-String -Pattern 'versionName=|lastUpdateTime=' | ForEach-Object { $_.Line.Trim() }
    if ($d) { $d -join '  ' } else { "не установлен" }
}
Ok "сейчас на магнитоле: $(InstalledVersion)"
if ($Check) { Write-Host "`nПроверка пройдена." -ForegroundColor Green; exit 0 }

function InstallApk($file, [switch]$Downgrade) {
    $a = @("-s", $Serial, "install", "-r")
    if ($Downgrade) { $a += "-d" }
    $out = (& $adb @a $file 2>&1) | Out-String
    Write-Host $out.Trim()
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        Fail "подпись APK не совпадает с установленным лаунчером. Нужен тот же debug.keystore, что на ПК, где собирался установленный лаунчер. Лаунчер я не удалял."
    }
    if ($out -notmatch "Success") { Fail "установка не прошла." }
}

function StartAndVerify {
    & $adb -s $Serial shell am start -n "$Pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    $procId = (& $adb -s $Serial shell pidof $Pkg) | Out-String
    if (-not $procId.Trim()) {
        Write-Host (& $adb -s $Serial logcat -d -t 80 AndroidRuntime:E '*:S' | Out-String)
        Fail "лаунчер не запустился (выше лог падения). Откат: .\tools\update-headunit.ps1 -Rollback -Ip $Ip"
    }
    Ok "лаунчер запущен (pid $($procId.Trim()))"
    Ok "версия: $(InstalledVersion)"
}

# ---------- откат ----------
if ($Rollback) {
    Step "Откат"
    $last = Get-ChildItem $BackupDir -Filter "launcher-*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $last) { Fail "в $BackupDir нет бэкапов." }
    Ok "ставлю $($last.Name)"
    InstallApk $last.FullName -Downgrade
    StartAndVerify
    Write-Host "`nОткат выполнен." -ForegroundColor Green
    exit 0
}

# ---------- сборка ----------
if (-not $SkipBuild) {
    Step "Сборка (gradlew assembleDebug)"
    Push-Location $Root
    try { & .\gradlew.bat assembleDebug; if ($LASTEXITCODE -ne 0) { Fail "сборка упала (ошибки выше)." } }
    finally { Pop-Location }
}
if (-not (Test-Path $Apk)) { Fail "нет APK: $Apk" }
Ok "APK: $Apk ($([math]::Round((Get-Item $Apk).Length / 1MB, 1)) МБ)"

# ---------- бэкап ----------
Step "Бэкап текущей версии"
$path = ((& $adb -s $Serial shell pm path $Pkg) | Select-Object -First 1)
if ($path -and $path -match '^package:(.+)$') {
    New-Item -ItemType Directory -Force $BackupDir | Out-Null
    $dest = Join-Path $BackupDir ("launcher-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".apk")
    & $adb -s $Serial pull $Matches[1].Trim() $dest | Out-Host
    if (-not (Test-Path $dest)) { Fail "бэкап не сохранился, установку не начинаю." }
    Ok "сохранён $dest"
    Get-ChildItem $BackupDir -Filter "launcher-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -Skip 5 | Remove-Item
} else {
    Ok "лаунчер ещё не установлен, бэкап не нужен"
}

# ---------- установка ----------
Step "Установка"
InstallApk $Apk
StartAndVerify
Write-Host "`nГотово. Откат при необходимости: .\tools\update-headunit.ps1 -Rollback -Ip $Ip" -ForegroundColor Green
