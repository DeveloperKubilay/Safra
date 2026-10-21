# Fabric 26.1 canlı ağ testi

## Doğrudan P2P, 19:10–19:15

SafraHost ve SafraJoin aynı bilgisayarda, ayrı çalışma klasörleriyle çalıştırıldı. Bağlantı aynı genel IP üzerinden NAT dönüş yolunu kullandı; farklı iki ev bağlantısının testi değildir. Kullanıcı durma, yavaş/hızlı hareket ve yeni chunk yükleme denemeleri yaptı. Kullanıcı önceki sürüme göre daha akıcı bir deneyim ve arada kısa takılmalar bildirdi; eski ikiliyle eşzamanlı ölçüm yapılmadığından bu bir performans karşılaştırması sayılmaz.

SafraJoin 19:10:03'te katıldı, 19:15:00'te ayrıldı. İki istemci de normal kapandı ve Gradle çalıştırmaları başarılı tamamlandı. Oturumda TURN tahsisi, beklenmeyen bağlantı kesilmesi, tünel kurtarma veya akış kontrolü hatası görülmedi.

İstemci tarafındaki 10 örnek 19:10:30–19:15:00 arasında 30 saniye aralıklıdır. Yumuşatılmış RTT 5–10 ms, ortalama 6.1 ms; RTT değişim ölçüsü 3–9 ms idi. Son gönderilen paket sayacı 44934, kayıp sayacı ilk ve son örnekte 1 idi. Bunlar istemcinin gönderim sayaçlarıdır; iki yönün toplam kaybını veya indirme kayıp oranını göstermez.

Örneklerin tamamında alım penceresi hedefi 65536 bayttı. Bu düşük RTT'de alt sınırda kalması olağandır. Otuz saniyelik örnekler aradaki kısa büyüme/küçülmeleri dışlamaz. Kare süreleri kaydedilmedi; kullanıcının hissettiği kısa takılmaların ağ veya çizim kaynaklı olduğu bu loglardan belirlenemez.

Ham loglar, sayaçlar ve özet yerel olarak `build/live-p2p-sessions/direct-2026-10-21-1910/` altında saklandı. Bu klasör Git'e eklenmez.

## VPN ve TURN denemesi

İkinci oturum `directThenTurn` geliştirme profiliyle açıldı. Bu profil, başarılı olabilecek doğrudan yolu bilerek reddederek TURN yolunu sınar; normal moddaki iki gerçek doğrudan denemeyi doğrulamaz. Normal dağıtım profili `off` olmalıdır. Pencere ve RTT ölçümleri iki yönde beş saniyede bir alınır; aktarılan QUIC ve akış bayt sayaçları da kaydedilir. Bu sayaçlar tüm dış ağ başlıklarını içeren hat kullanımı ölçümü değildir.

VPN'i kullanıcı açar. VPN açıkken gerçek TURN aktarımı ve rota loglardan doğrulanmadan kötü internet testi tamamlanmış sayılmaz. Yalnız yüksek gecikme de bant daralması, paket kaybı veya bağlantının aniden bozulmasının tamamını temsil etmez.

19:36:45'te host Cloudflare TURN UDP tahsisini açtı; 19:36:55'te SafraJoin dünyaya girdi. İlk dakikada iki yönde yumuşatılmış RTT yaklaşık 145–177 ms, kayıp sayaçları 0 idi. İstemcinin alım penceresi 65536 → 77351 → 83036 bayta büyüdü; canlı otomatik ayar gerçek TURN trafiğinde gözlendi. Hostun alım penceresi 65536 baytta kaldı; iki yönün trafik ihtiyacı farklıdır. Bu ara gözlem oturumun tamamının sonucu değildir. VPN durumu henüz kullanıcı tarafından teyit edilmedi. Ham kayıt dizini `build/live-p2p-sessions/vpn-turn-20261021-193226/`.

Oturum 19:40:04'te hostun normal kapatılmasıyla sona erdi; iki Gradle çalıştırması başarılı, tüm dünyalar kaydedildi. Bağlantı kurulurken istemcinin UDP 3478 isteği zaman aşımına uğradı, ardından UDP 443 üzerinden TURN açıldı. Host UDP 3478 kullandı. Bu ilk bağlantı seçimi dışında oyun sırasında yeniden bağlantı veya beklenmeyen kopma görülmedi.

Her tarafta 38 örnek alındı. Host RTT ortalaması 148.08 ms (145–163), istemci ortalaması 146.82 ms (145–177). Son gönderim sayaçları hostta 31397 paket / 0 kayıp, istemcide 21731 paket / 2 kayıp idi. Hostun QUIC akış verisi gönderim sayacı 19689855 bayta ulaştı. İstemci pencere hedefi en fazla 83036 bayta çıktı. Kullanıcı TURN oturumunu doğrudan oturumdan daha akıcı hissettiğini bildirdi. Aynı rota, aynı sahne ve kare süresi karşılaştırması yapılmadığından bunun sebebi belirlenmedi. VPN durumu teyit edilmeden bu kayıt VPN açık ölçümü olarak etiketlenmez.

## Tekrar doğrudan P2P, 19:45–19:49

İki istemci normal `off` profiliyle yeniden açıldı. Yeni paylaşım 19:45:07'de hazırlandı; SafraJoin 19:45:14'te katıldı. Öncesinde önceki oturumun VPN adresine giden iki bağlantı girişimi sertifika beklerken zaman aşımına uğradı; ikinci doğrudan denemeler ve başarısız TURN fallback istekleri (`closed`, HTTP 409) kaydedildi. Yeni paylaşım öncesindeki bu girişimler, aşağıdaki başarılı oturumun aktarım ölçümüne dahil değildir. Eski paylaşım/adres kullanılmasıyla uyumludur; kullanıcı işlemi doğrudan gözlenmediği için sebep kesinleştirilmez.

Başarılı bağlantı doğrudan UDP kullandı; bu bağlantı için TURN tahsisi görülmedi. 19:47:00'de SafraJoin Spectator moduna geçti. Kullanıcı hızlı hareketle chunk yüklediğini ve deneyimin iyi olduğunu bildirdi. Oturum 19:49:12'de hostun normal kapanmasıyla sona erdi; dünyalar kaydedildi ve iki Gradle çalıştırması başarılı tamamlandı.

| Ölçüm | Host | İstemci |
| --- | --- | --- |
| Örnek sayısı | 47 | 48 |
| Yumuşatılmış RTT | 5–10 ms | 5–7 ms |
| Ortalama RTT | 6.15 ms | 5.21 ms |
| RTT değişim tahmini (`rttVar`) | 2–5 ms | 2–5 ms |
| Örneklenen son RTT | 1–14 ms | 1–7 ms |
| Son gönderim / kayıp sayacı | 40741 / 0 | 27277 / 1 |
| Alım penceresi hedefi | 65536 bayt | 65536 bayt |

Hostun akış verisi gönderim sayacı 25293615 bayta ulaştı. Spectator hareketi sırasında tünel kurtarma veya beklenmeyen kopma görülmedi. 19:47:24'te Minecraft `Received passengers for unknown entity` uyarısı verdi; bu tek başına ağ jitter'ı veya kaybının kanıtı değildir.

`rttVar`, QUIC kayıp kurtarmasında kullanılan yumuşatılmış RTT sapma tahminidir. Uygulama verisinin geliş aralıkları, her paket veya kare süreleri ölçülmedi. Beş saniyelik anlık kayıtlar bütün kısa sıçramaları yakalamaz; bu test sıfır jitter veya sıfır görüntü takılması kanıtlamaz. Ham kayıtlar `build/live-p2p-sessions/direct-repeat-20261021-194308/` altında saklanır.

## Özelliklerin sınırı

Safra'nın kullandığı özel Kwik 0.11.0-safra.1, canlı alım penceresi, kayıp bağlantı kredisi bildiriminin yeniden gönderimi ve gönderici kuyruğu yarışı düzeltmelerini içerir. NewReno tıkanıklık kontrolü, ACK/RTT takibi, sıralama ve kayıp kurtarma Kwik'ten gelir. Bu çalışma ayrı bir packet pacing katmanı, uygulama jitter tamponu, BBR veya dinamik yol MTU keşfi eklemedi. Sabit 1200 bayt QUIC sınırı ve TURN ChannelData biçimi aynı şey değildir.

Alım penceresi bekleyen veri varken, hesaplanan hedef mevcut pencereden yüzde 12.5'ten fazla düşükse örnek başına en fazla yüzde 25 küçülür; alt sınır 64 KiB'dir. Trafiksiz beklemek tek başına küçülme nedeni değildir. Önceden verilmiş QUIC gönderme hakkı geri alınmaz. Canlı TURN testinde büyüme gözlendi, küçülme gözlenmedi; küçülme otomatik yavaş okuyucu senaryosunda doğrulandı.
