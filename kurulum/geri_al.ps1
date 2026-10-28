# BerkOS'u TV'den kaldırır; Google TV'nin kendi ekranı ve ekran koruyucusu geri gelir.
$burasi = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = Join-Path $burasi "adb\adb.exe"
# adb pakette yoksa (herkese açık indirmede yok) Google'ın kendi sunucusundan platform-tools indirilir.
if (-not (Test-Path $adb)) {
    $adb = Join-Path $burasi "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "   adb indiriliyor (Google platform-tools, ~7 MB)..." -ForegroundColor DarkGray
        $zip = Join-Path $burasi "platform-tools.zip"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $burasi -Force
        Remove-Item $zip
    }
}
$ip = (Read-Host "TV'nin IP adresi").Trim()
& $adb connect "$($ip):5555"
Start-Sleep 2
& $adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
& $adb uninstall com.berkos.tv
& $adb disconnect *> $null
Write-Host "BerkOS kaldırıldı, Google TV eski hâline döndü." -ForegroundColor Green
Read-Host "Kapatmak için Enter"
