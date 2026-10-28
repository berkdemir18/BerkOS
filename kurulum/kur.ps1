# BerkOS'u Google TV'ye kurar: APK'yı yükler, ekran koruyucuyu ve Ev tuşu yardımcısını açar.
# Çalıştırmak için: "BerkOS'u TV'ye Kur.bat" dosyasına çift tıkla.
$ErrorActionPreference = 'Continue'
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
$apk = Join-Path $burasi "BerkOS.apk"
$paket = "com.berkos.tv"
# Sınıfların paketi; genel sürümde uygulama kimliği (com.berkos.tv) farklı ama sınıflar aynı yerde.
$sinif = "com.demir.salon"
$servis = "$paket/$sinif.EvTusuServisi"

function Baslik($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Yellow }
function Tamam($t) { Write-Host "   [tamam] $t" -ForegroundColor Green }
function Hata($t) { Write-Host "   [hata] $t" -ForegroundColor Red }

Write-Host ""
Write-Host "   BERKOS · Google TV ana ekranı kurulumu" -ForegroundColor Cyan
Write-Host "   TV ile bu bilgisayar aynı Wi-Fi'da olmalı. TV'de 'USB hata ayıklama' açık olmalı."
Write-Host "   (Nasıl açılır: NASIL KURULUR.txt)"

Baslik "1/5  TV'ye bağlanılıyor"
$ip = Read-Host "   TV'nin IP adresi (Ayarlar > Ağ ve İnternet > bağlı Wi-Fi, ör. 192.168.1.20)"
$ip = $ip.Trim()
& $adb disconnect *> $null
$cikti = & $adb connect "$($ip):5555" 2>&1 | Out-String
if ($cikti -notmatch "connected to") {
    Hata "5555 portundan bağlanamadım: $($cikti.Trim())"
    Write-Host "   TV'de 'Kablosuz hata ayıklama' kullanılıyorsa: o ekranda 'Cihazı eşleme kodu ile eşle'ye bas."
    $esle = Read-Host "   Eşleme ekranındaki IP:port (ör. 192.168.1.20:37123)"
    $kod = Read-Host "   Eşleme kodu (6 hane)"
    & $adb pair $esle.Trim() $kod.Trim()
    $port = Read-Host "   Kablosuz hata ayıklama ana ekranındaki IP:port (eşleme portu DEĞİL)"
    $cikti = & $adb connect $port.Trim() 2>&1 | Out-String
}
Start-Sleep 2
$durum = (& $adb get-state 2>&1 | Out-String).Trim()
if ($durum -ne "device") {
    Hata "TV bağlantıyı onaylamadı ($durum). TV ekranında 'USB hata ayıklamaya izin verilsin mi?' çıktıysa 'Her zaman izin ver' + Tamam de, sonra bu dosyayı tekrar çalıştır."
    Read-Host "   Kapatmak için Enter"
    exit 1
}
Tamam "Bağlandı: $(& $adb shell getprop ro.product.model)"

Baslik "2/5  BerkOS yükleniyor"
$y = & $adb install -r $apk 2>&1 | Out-String
if ($y -match "Success") { Tamam "Yüklendi" } else { Hata $y.Trim(); Read-Host "   Kapatmak için Enter"; exit 1 }

Baslik "3/5  Ekran koruyucu (bekleme ekranı) BerkOS Sergisi yapılıyor"
& $adb shell settings put secure screensaver_components "$paket/$sinif.SalonDream"
& $adb shell settings put secure screensaver_enabled 1
Tamam "TV boşta kalınca tablolar ve uzay fotoğrafları gelecek"

# Android, uygulama her güncellendiğinde erişilebilirlik servisini kapatıyor; bu adım her kurulumda geri açar.
Baslik "4/5  Ev tuşu yardımcısı açılıyor (TV açılınca ve Ev tuşunda BerkOS gelsin)"
$mevcut = (& $adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($mevcut -eq "null" -or $mevcut -eq "") { $yeni = $servis }
elseif ($mevcut -like "*$servis*") { $yeni = $mevcut }
else { $yeni = "$mevcut`:$servis" }
& $adb shell settings put secure enabled_accessibility_services $yeni
& $adb shell settings put secure accessibility_enabled 1
Start-Sleep 1
if ((& $adb shell settings get secure enabled_accessibility_services | Out-String) -like "*EvTusuServisi*") { Tamam "Açık" } else { Hata "Açılamadı; TV'de Ayarlar > Erişilebilirlik > BerkOS'tan elle aç" }

Baslik "+    İzlemeye devam et açılıyor (TV'de ne oynatıldığını görsün)"
& $adb shell cmd notification allow_listener "$paket/$sinif.MedyaDinleyici" 0 *> $null
if ((& $adb shell cmd notification list_listeners 2>&1 | Out-String) -like "*MedyaDinleyici*" -or
    (& $adb shell settings get secure enabled_notification_listeners | Out-String) -like "*MedyaDinleyici*") { Tamam "Açık" } else { Hata "Açılamadı" }

# YouTube satırı abone olunan kanallardan gelsin: Google Takeout'un subscriptions.csv'si bu klasöre
# "abonelikler.csv" adıyla konduysa TV'ye atılır (YouTube aboneliği hesap izni olmadan başka yoldan alınamıyor).
$abone = Join-Path $burasi "abonelikler.csv"
if (Test-Path $abone) {
    Baslik "+    YouTube abonelikleri TV'ye atılıyor"
    & $adb push $abone "/sdcard/Android/data/$paket/files/abonelikler.csv" *> $null
    Tamam "Abonelik listesi yüklendi"
}

Baslik "5/5  BerkOS açılıyor"
& $adb shell am start -n "$paket/$sinif.MainActivity" *> $null
# "Arka planı temizle" düğmesi TV'nin kendi kabuğunu 5555'ten kullanır; port TV yeniden başlayınca kapanıyor.
& $adb tcpip 5555 *> $null
Tamam "Arka planı temizle düğmesi için kabuk açıldı (TV yeniden başlarsa bu kurulumu bir kez daha çalıştır)"
Tamam "Bitti. Kumandadan Ev tuşuna basıp dene."
Write-Host ""
Write-Host "   Not: Bekleme ekranının ne kadar sonra geleceğini TV'de Ayarlar > Sistem > Ortam modu" -ForegroundColor DarkGray
Write-Host "   (bazı TV'lerde 'Ekran koruyucu') içinden ayarlayabilirsin." -ForegroundColor DarkGray
& $adb disconnect *> $null
Read-Host "   Kapatmak için Enter"
