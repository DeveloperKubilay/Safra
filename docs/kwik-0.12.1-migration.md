# Kwik 0.12.1 bakım tabanına geçiş

## Sonuç ve kapsam

Resmî `ptrd/kwik` etiketi `v0.12.1`, commit `1be72817017d45dd3f7686b253a9a8343e9681de` temel alındı. Bakım sürümü `0.12.1-maintenance.1`, sabit kaynak `47c9557879bbac8873d8b17940fac5884805371c`. Bu ad resmî 0.12.2 sürümü iddiası taşımaz. Önceki motor `0.11.0-safra.2` etiketi ve Safra `adaptive-receive-window` dalında korunur.

| Konu | Resmî 0.12.1 | Bakım sürümünde yapılan |
|---|---|---|
| Gönderim kuyruğu yarışı | Düzeltilmiş | Aynı düzeltme yeniden eklenmedi |
| Çok düşük RTT'de en az 1 ms kayıp eşiği | Düzeltilmiş | Aynı düzeltme yeniden eklenmedi |
| Erken ACK gelmeden önce paketi kaydetme | Eksik | Korundu; upstream PR #99 |
| Kaybolan MAX_DATA için güncel krediyle tekrar gönderme | Eksik | Korundu; upstream PR #100 |
| Tüketim ve RTT ile isteğe bağlı dinamik pencere | Eksik | Korundu; upstream PR #101 |
| TLS bağımlılığı | agent15 4.0 | Bütün loader bağımlılıkları 4.0'a taşındı |

0.12.1 tabanı ayrıca Windows UDP hata dayanıklılığı, bağlantı kimliği düzeltmeleri ve bağlantı oluşturmadan retry token doğrulaması getirir. Yeni çekirdeğin soket yönetimi Safra'nın özel DatagramSocket adaptörü üzerinden çalışır. Ek şifreleme katmanı, BBR, pacing, uygulama jitter tamponu veya PMTU keşfi eklenmedi; QUIC datagram sınırı 1200 bayt kaldı. Ağ göçü özelliği açılmadı. İki direct denemesi ve ardından iki TURN UDP denemesi politikası değiştirilmedi.

## Kaynak ve derleme

`python tools/prepare_kwik.py` bakım fork'unun sabit sürüm etiketini alır ve kaynak commit'ini doğrular. Mevcut checkout farklı veya kirliyse dosyaları koruyarak durur. Tam kaynak farkı `gradle/kwik-adaptive.patch` içinde, lisans ve kaynak bilgileri paket içindeki Kwik NOTICE dosyasındadır. `agent15_version=4.0` bütün loaderların ortak sürüm kaynağıdır.

`common` testleri JDK 21 üzerinde, Minecraft 26.1 loader paketleri Java 25 toolchain ile derlenir. Test paketleri `-Pdiagnostics=true`, normal bağlantı akışı için `-Pbuild_mode=off` kullanır.

## Doğrulama

Motorun yerel Windows/JDK 21 taraması: 1178 test, 1177 başarılı, önceden kapatılmış 1 test atlandı. DNS testleri dış ağa bağlanmadan çalışır; hiçbir test bu geçişi geçirmek amacıyla dışlanmadı. NewReno zamanlama testi gerçek saat sınırına bağlı olmayacak biçimde önceki bakım fork'undaki düzeltmeyi kullanır.

Safra'nın 50 normal testi hem JDK 21 hem Java 25 test JVM'i ile başarılı: doğru sertifikayla bağlantı, farklı sabitlenmiş sertifikayı reddetme, kayıplı/kayıpsız gerçek QUIC tünel aktarımı ve TURN çerçeve/taşıma testleri dahildir. Bu testler ücretli haricî TURN sunucusunda canlı Minecraft oturumu yerine geçmez.

[Windows ve Linux motor CI kontrolü](https://github.com/DeveloperKubilay/kwik/actions/runs/39653988007) de başarılıdır.

Altı ağ profili × iki yön × sabit/otomatik pencere: 24 aktarım ve 50 normal kontrolün tamamı başarılıdır. [Ham sonuçlar](network-results-2026-10-21-kwik0121.csv) bu koşunun son 24 kaydını içerir; önceki çalıştırmalardan biriken CSV kayıtları alınmadı. Her aktarımda içerik ve sıra doğrulandı, en büyük QUIC datagramı 1200 bayt kaldı ve benzetilen kuyruk 256 KiB sınırını aşmadı.

Yüksek gecikmede otomatik hedef her iki yönde 64 KiB'den 256 KiB'ye çıktı. Yavaş okuyucuda indirmede 154940 → 75508, yüklemede 205426 → 86665 bayta küçüldü. Ağır sıra bozulması/kopya profilinde aktarımlar 32.160–40.884 saniye sürdü. Kayıp/jitter profilinde otomatik ayar belirgin hız artışı sağlamadı; tek koşu bütün ağlara veya önceki motorla kontrollü karşılaştırmaya genellenmez.

Minecraft 26.1 için Fabric, Forge ve NeoForge derlemeleri ve `verifyMixinArtifacts` geçti. Üç pakette bakım motoru, kaynak/lisans bilgileri ve tanılama ayarları doğrulandı. Fabric içinde yalnızca `kwik-0.12.1-maintenance.1` ve `agent15-4.0` bulunur. Test modu `off`, tanılama `true` değerindedir.

Java 25 üzerinde 100 kayıpsız ve 100 tek datagram kayıplı tünel aktarımı tekrarlandı; her aktarımda 300000 bayt doğrulandı. Yanlış sertifikayı reddetme kontrolüyle toplam 201 test başarılıdır. Tekrar sayısı `-PtunnelTestRepetitions=100` ile seçilebilir; normal taramada varsayılan değer 1'dir. Java 25 test JVM'i Gradle init betiğinde `javaLauncher` seçilerek doğrulandı.

## Yorumlama sınırı

Yeni taban ile katkılarımız birlikte daha fazla düzeltme ve özellik içerir. Bu, bütün ağlarda daha düşük ping veya daha yüksek hız kanıtı değildir. Pencere tahmini fiziksel hat kapasitesini ölçmez; tüketilen veri ve RTT'den sınırlı bir hedef öğrenir. QUIC önceden verilen krediyi geri alamadığı için hedef küçülse de eski izin anında iptal edilmez. Önceki canlı oyun deneyimi 0.11 tabanında yapılmıştır; 0.12.1 tabanı için yeni canlı oyuncu testi gerekir.
