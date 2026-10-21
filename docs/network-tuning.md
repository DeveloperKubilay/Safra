# Canlı alım penceresi ve ağ doğrulaması

## Değişiklik

Kwik 0.11 korunuyor. Upstream temeli `ptrd/kwik` deposundaki `4d5d40f540d70a1b50ea59651f9d36740e77f165`; Safra sürümü `0.11.0-safra.2`. Bakım kaynağı artık [DeveloperKubilay/kwik](https://github.com/DeveloperKubilay/kwik), sabit kaynak commit'i `23bb551b2d5e89a1ab313fb830e981d65eb51f2a`. Eski taşıma motoruna dönüş veya eski 0.12 fork'unun geri yüklenmesi yapılmadı. Motorun kaynak ve test değişiklikleri `gradle/kwik-adaptive.patch` içinde de saklanıyor; yayın betikleri bakım fork'undadır. Kwik'in LGPL lisansı ve mevcut telif bildirimleri korunuyor.

İki taraf da el sıkışmadan önce 64 KiB alım hakkı bildiriyor. Minecraft akışı açılınca otomatik ayar etkinleşiyor. İlk hesap bir internet hız testi değil: 64 KiB ile başlayıp gerçekten tüketilen veri ve QUIC'in ölçtüğü RTT üzerinden öğreniyor. Önceki sertifika pinglerinden sabit 1.5 MB/s varsayımıyla pencere hesaplama kaldırıldı.

Her örnek en az 100 ms ve iki RTT sürüyor. Hedef yaklaşık `2 × tüketilen bayt/s × RTT`; örnek başına büyüme en fazla iki kat, küçülme en fazla dörtte bir. Pencere 64–256 KiB arasında kalıyor. Küçülme için bekleyen veri ve yüzde 12.5'ten büyük hedef farkı gerekiyor; boşta geçen süre kapasite düşüşü sayılmıyor. Bu, hattın bağımsız kapasite ölçümü değildir; gönderen ve tüketen uygulamanın hızından da etkilenir.

QUIC'te önceden verilmiş gönderme hakkı geri alınamaz. Pencere küçülse bile toplam izinli akış ofseti geriye gitmez; yeni hak daha yavaş verilir. Bellek hemen boşalmış gibi davranılmaz. Tanılamadaki pencere hedef değerdir; daha önce verilmiş tüm hakların anlık toplamı değildir. Akış ve bağlantı kredisi bildirimleri alım kilidinin dışından gönderilir. Kaybolan bağlantı kredisi (`MAX_DATA`) güncel sınırla yeniden iletilir.

Gönderim kuyruğunda ayrıca bir yarış düzeltildi: istek sayacı, göndericiye iş verilmeden sonra artırılıyordu. Gönderici daha erken çalışırsa sayacı eksiye indirip tamponda veri olduğu halde devam isteğini atlayabiliyordu. Kuyruk hakkı artık işi yayımlamadan önce atomik olarak alınıyor. Göndericiyi iş verme çağrısı dönmeden çalıştıran regresyon testi eski kodda başarısız, düzeltmeyle başarılı; TCP–QUIC tünelinin paket patlaması ve tek paket kaybı testleri ayrıca tekrarlandı.

## MTU ve TURN

QUIC datagram sınırı 1200 bayt olarak kalır. Safra başlığı 10 bayttır. IPv6 ve UDP'nin temel başlıklarıyla doğrudan paket 1258 bayta çıkar.

Önceki TURN Send indication biçimi, 1210 bayt Safra verisi için IPv4 eş adresinde 1248, IPv6 eş adresinde 1260 bayt UDP içeriği üretir. Dış bağlantı IPv6 ise toplam 1296 veya 1308 bayt olur; 1280 MTU yolunda parçalanma gerekebilir. Hesap kullanılan kodlayıcının çıktısından yapılır.

TURN gönderimi artık ChannelBind ve ChannelData kullanır. Bağlama onaylanmadan veri gönderilmez; sunucunun onaydan önce gönderdiği kanal verisi alınabilir. Kanal bağlama aynı zamanda eş iznini yeniler. Kanal numarası tahsis boyunca başka eşe verilmez; süresi dolan izin yenilenir. UDP'de kaybolan bağlama isteği aynı işlem numarasıyla tekrarlanır. TCP/TLS alıcısı STUN ve dolgulu kanal çerçevelerini aynı akışta ayırır. 1210 bayt Safra verisi, dolgu ve kanal başlığıyla 1216; temel IPv6/UDP başlığıyla 1264 bayttır. Biçim ve süreler [RFC 8656 bölüm 12](https://www.rfc-editor.org/rfc/rfc8656.html#section-12) temelindedir.

Bu çalışma PMTU keşfi eklemez. IPv6 ek başlıkları, VPN kapsüllemesi ve 1258 bayttan küçük kullanılabilir doğrudan yol MTU'su ayrıca değerlendirilmelidir. QUIC'in 1200 bayt alt sınırı [RFC 9000 bölüm 14](https://www.rfc-editor.org/rfc/rfc9000.html#section-14) ile belirlenir; QUIC paketini gelişigüzel küçültmek çözüm değildir.

TURN seçimi değişmedi: iki doğrudan deneme sonrasında son çare; TURN içinde iki UDP denemesi, ardından TCP/TLS. Doğrulama ücretli TURN sunucusu kullanmaz, backend'i değiştirmez.

## Yeniden üretme

```powershell
python tools/prepare_kwik.py
./gradlew :common:test --configure-on-demand
./gradlew :common:test -PnetworkTests=true --tests '*P2pNetworkQualityTest' --configure-on-demand
./gradlew build -Pdiagnostics=true
```

Hazırlık betiği varsayılan olarak kardeş `../kwik` klasörünü kullanır; başka hedef ilk argümanla verilebilir. Safra için `-PkwikSourceDir=...` aynı hedefi seçer. Yeni hedef bakım fork'undan alınır ve sabit kaynak commit'ine geçirilir. Mevcut hedefin commit'i veya çalışma dosyaları farklıysa betik durur; dosyaları ezmez. İlk yamayla hazırlanmış eski çalışma klasörlerini otomatik sıfırlamaz; bunlar korunup yeni hedef seçilebilir. CI build adımlarında aynı betik çalışır. Safra bu sabit kaynaktan composite build alır; `latest` sürümünü otomatik indirmez.

Kwik deposunun Actions akışı Linux ve Windows üzerinde çekirdek testlerini çalıştırır. Sürüm etiketinde iki taraf da başarılı olursa kütüphane, kaynak JAR'ı, tam kaynak arşivi, POM, lisanslar, kaynak commit bilgisi ve SHA-256 özetleri release'e eklenir. İlk yayın ön sürümdür. Bu yayın düzenlemesi yeni bir ağ algoritması eklemez; oyun testinde kullanılan motor sınıfları korunur.

Normal testler ağ matrisini hariç tutar. Matrisin tek profili `-PnetworkProfile=good` gibi seçilebilir. Sonuçlar `build/26.1/common/reports/network-quality.csv` dosyasına eklenir; yeni karşılaştırma için önce bu dosya taşınmalıdır. Minecraft sürümü seçilirse build yolundaki sürüm de değişir. Süreler aynı makinedeki zamanlayıcı ve işlem yükünden etkilenir; tek koşu hız garantisi değildir.

## Test kapsamı

Kwik çekirdek testleri: 1071 test, 1070 başarılı, 1 önceden devre dışı test (`ServerConnectorImplTest.afterCloseNoAdditionalThreadsShouldBePresent`). Yeni testler pencere sınırlarını, küçülürken eski gönderme hakkını, büyümeyi, boşta kalmayı, saat sınırını, 10000 değişken hız/RTT örneğini, kilit kullanımını, kayıp bağlantı kredisi tekrarını ve gönderici yarışını denetler.

Değişiklik öncesindeki Kwik testlerinde bu makinede iki dış DNS/IPv6 bağımlılığı ve bir saat eşitliği kaynaklı kararsızlık görüldü. Yalnız testler düzeltildi: DNS cevapları sabitlendi, ACK zamanı kurtarma zamanından sonra seçildi. Ağ üretim kodu bu ortam sorunlarını gizlemek için değiştirilmedi.

Ağ matrisi gerçek Kwik TLS/QUIC bağlantıları üzerinde yapay gecikme, bant sınırı, kayıp ve paket sırası uygular. İnternet bağlantısının işletim sistemi ayarlarını değiştirmez. Her senaryo indirme/yükleme ve eski RTT varsayımını temsil eden sabit pencere/otomatik pencere ile çalışır: 6 × 2 × 2 = 24 aktarım. İki modda da aynı düzeltilmiş Kwik kullanılır; sabit mod yalnız otomatik pencereyi kapatır. Eski Minecraft motorunun veya eski ikilinin karşılaştırması değildir. Aktarım başına 1 MiB veri, blok sırası ve her bayt doğrulanır.

| Profil | Hat sınırı | RTT | Ek koşul |
| --- | --- | --- | --- |
| good | 20 Mbit/s | 20 ms | ±2 ms gecikme değişimi |
| high-latency | 20 Mbit/s | 120 ms | ±10 ms değişim |
| loss-jitter | 5 Mbit/s | 80 ms | Her 29. pakette kayıp, ±25 ms |
| rate-change | 20 → 1 → 20 Mbit/s | 60 → 180 → 60 ms | Gönderilen tel baytının 256–768 KiB aralığında yavaşlama |
| slow-reader | 20 Mbit/s | 120 ms | İkinci yarıda her 4 KiB okumadan sonra 12 ms bekleme |
| reorder-duplicate | 8 Mbit/s | 60 ms | ±50 ms bağımsız gecikme, her 37. pakette kayıp, her 17. pakette kopya |

Kuyruk 256 KiB ile sınırlıdır; taşan paket kayıp sayılır. Paket boyu kontrolünde Safra ve temel IPv6/UDP başlığı da hesaba katılır. Sıralı profillerde zamanlayıcı kaynaklı yanlış sıralama FIFO teslim ile engellenir; son profil bilinçli olarak sıralamayı bozar. P95 gecikme, uygulama bloğunun yazılmaya başlandığı andan tamamının okunmasına kadardır; ping veya oyun gecikmesi değildir. `lost`, QUIC'in kayıp saydığı paketleri; `dropped`, düzenekte gerçekten atılan paketleri gösterir. Sıra değişimi tekrar iletim üretebildiği için bu sayılar eşit olmak zorunda değildir.

Yerel TURN testleri UDP/TCP üzerinden kanal bağlama, onaydan önce gelen veri, STUN/veri karışımı, dolgu, kayıp isteğin tekrarı, izin yenileme, bilinmeyen/süresi dolmuş/kesik çerçeveler ve MTU hesabını denetler. Gerçek TURN sağlayıcısı, farklı modem/CGNAT, iki açık Minecraft istemcisi ve uzun süreli oyun oturumu bu otomatik testlerin kapsamı dışındadır.

Sertifika testi, bildirilen kimlikle QUIC sunucusunun gerçek sertifikası farklı olduğunda tünelin açılmadığını doğrular. TURN izin yenilemesi en fazla 300 saniyelik protokol süresini esas alır; daha uzun yerel ayar iznin gerçekte daha uzun yaşadığı varsayımına dönüşmez. GitHub `Network tests` kontrolü Kwik testlerini ve Safra ağ matrisini çalıştırıp raporları saklar.

## 21 Ekim 2026 ölçümü

[Ham sonuçlar](network-results-2026-10-21.csv): 24 ağ aktarımının tamamında içerik ve sıra doğrulandı, en büyük QUIC datagramı 1200 bayt kaldı. Safra'nın 50 normal testi ve 24 ağ testi başarılı. TCP–QUIC tünel test grubu sekiz kez ayrıca çalıştırıldı; 24 kontrol başarılı. Minecraft 26.1 için Fabric, Forge ve NeoForge derlemesi ile mevcut mixin paket kontrolü geçti; üç tanılama paketinde `0.11.0-safra.1` bulundu.

İyi bağlantıdaki indirme 16.08 → 16.36 Mbit/s; yüksek gecikmeli indirme 6.30 → 5.25 Mbit/s oldu. Bunlar tek koşunun sabit → otomatik karşılaştırmasıdır. Otomatik pencerenin her koşulda hız kazandırdığı sonucu çıkmaz. Yüksek gecikmede pencere 256 KiB'ye büyüdü; kısa aktarımda büyüme süresi sabit büyük pencereye göre dezavantaj oluşturabilir. Yavaş okuyucuda otomatik pencere indirmede 187.7 → 83.3 KiB, yüklemede 206.1 → 87.0 KiB küçüldü. Ani hız değişiminde veri kesilmeden tamamlandı. Ağır sıra bozulması/kayıp profilinde aktarım yaklaşık 30–32 saniye sürdü; burada dar boğaz pencere boyundan çok kayıp kurtarma ve tıkanıklık kontrolüdür.

Bu sonuçlar bir güvenlik denetiminin tamamlandığı veya her bağlantıda aynı oyun deneyiminin garanti edildiği anlamına gelmez. Oyun içi gerçek kullanıcı testi ayrıca gerekir. Tam matrisin son çalışmasından sonra yalnız sürüm gösterimi ve paket içindeki lisans kaynakları düzenlendi; 50 normal test ve üç loader build'i yeniden geçti.

## Ana Kwik deposuna katkılar

Bakım dalının adı `adaptive-receive-window` olarak değiştirildi. Hazırlık betiği hareketli dal yerine değiştirilmeyen `v0.11.0-safra.2` etiketini klonlar ve yine `23bb551b2d5e89a1ab313fb830e981d65eb51f2a` kaynağını doğrular. Bu düzenleme Minecraft'ta kullanılan motoru veya yayımlanmış sürüm dosyalarını değiştirmez. Ana Kwik deposunda zaten `v0.12` ve `v0.12.1` bulunduğu için 0.11 tabanlı bakım sürümü 0.12 olarak yeniden etiketlenmedi.

Güncel ana kod (`101c733d`) üzerinden birbirinden bağımsız üç PR hazırlandı:

- [#99: Çok hızlı ACK gelmeden önce gönderilen paketi kaydetme](https://github.com/ptrd/kwik/pull/99): regresyon eski kodda başarısız, düzeltmeyle 11 gönderici testi başarılı.
- [#100: Kaybolan MAX_DATA bildirimini güncel bağlantı sınırıyla yeniden gönderme](https://github.com/ptrd/kwik/pull/100): regresyon eski kodda başarısız, düzeltmeyle 51 akış yöneticisi testi başarılı.
- [#101: İsteğe bağlı, sınırlandırılmış canlı alım penceresi](https://github.com/ptrd/kwik/pull/101): API ve hesaplama yaklaşımı için incelemeye açık PR; ilgili 27 test başarılı.

Pencere PR'ının tam çekirdek taramasında 1178 testten 1174'ü başarılı, 3'ü başarısız, önceden kapalı 1 test atlandı. Aynı iki IPv6/DNS testi ve zamanlamaya duyarlı NewReno testi değiştirilmemiş ana kodda da başarısız oldu: 1168 test, 1164 başarılı, aynı 3 hata, 1 atlanan test. NewReno testi tek başına başarılı olabiliyor. İki DNS testini yerel taramadan çıkarınca pencere dalında 1174 başarılı, aynı NewReno hatası ve 1 atlanan test kaldı. Bu durumlar PR açıklamalarında belirtildi; üretim tıkanıklık kodu değiştirilmedi.

Üç katkı dalında da upstream iş akışıyla Linux/JDK 17 üzerinde, test dışlamadan tam `./gradlew build` başarılı oldu. [#99 CI](https://github.com/DeveloperKubilay/kwik/actions/runs/39651941641), [#100 CI](https://github.com/DeveloperKubilay/kwik/actions/runs/39651941690), [#101 CI](https://github.com/DeveloperKubilay/kwik/actions/runs/39651942299). Üç PR da incelemeye açıktır. Dal adı ve sabit etiketle hazırlık değişikliğinin ardından [Safra ağ CI kontrolü](https://github.com/DeveloperKubilay/Safra/actions/runs/39651253079) de başarılı oldu.

Ana depoda zaten bulunan gönderim kuyruğu yarışı ve minimum kayıp süresi düzeltmeleri yeniden gönderilmedi. PR'lar paket pacing, BBR veya PMTU özelliği içermez; kabul edilmeleri ve resmî sürüm numarası ana proje bakımcısının kararıdır.

## Bakım fork'u ve safra.2 düzeltmeleri

Yukarıdaki ilk ağ matrisi ve canlı oyun oturumları `safra.1` motoruyla yapıldı. Ayrı Kwik fork'unun ilk Windows/Linux CI kontrolleri geçti; ancak yayın öncesi Safra tünel testinin tekrarı bir zaman aşımı üretti. İlk `v0.11.0-safra.1` etiketi korunarak yayın akışı iptal edildi; bu etiket için release dosyaları yayımlanmadı.

İncelemede iki bağımsız hata belirlendi. Gönderici, paketi sokete verdikten sonra kurtarma takibine kaydediyordu. Soket gönderimi dönmeden ACK geldiğinde onaylanmış veri yolda görünmeye devam edebiliyordu. Kaydın soket gönderiminden önce yapılması bu yarışı kapatır. Soket gönderimi içinden ACK işleyen yeni test eski kodda 1127 bayt yolda görünmesiyle başarısız, düzeltmeyle başarılıdır.

Diğer hata, 1 ms altı RTT'nin tam sayı ölçümünde 0 ms olmasıdır. Kayıp zaman eşiği 0'a düşüyor, açık assertion testte sunucu bağlantısını durduruyordu. Aynı durum üretimde yanlış kayıp kararı da üretebilir. Eşik artık [RFC 9002 bölüm 6.1.2](https://www.rfc-editor.org/rfc/rfc9002.html#section-6.1.2) doğrultusunda en az 1 ms'dir. Yeni test, 0 ms RTT'de paketin anında kayıp sayılmadığını ve süre geçince doğru kayıp kararını doğrular. Eski kodda assertion ile başarısızdır.

Bu iki düzeltmeyle 1073 çekirdek testinin 1072'si başarılı, önceden kapalı 1 test kapalıdır. Geçici test parametreleriyle Safra'nın gerçek TLS/QUIC tünelinde 100 kayıpsız ve 100 tek paket kayıplı 300000 bayt aktarımın tamamı geçti; her bayt kontrol edildi. Aynı koşullarda düzeltme öncesi tekrarlar zaman aşımı üretiyordu. Geçici paket izleme ve test kodları üretim kaynaklarından kaldırıldı. Bu çalışma pacing, BBR, MTU keşfi veya uygulama jitter tamponu eklemez.

[`safra.2` ağ matrisi](network-results-2026-10-21-safra2.csv) yeniden tamamlandı: 50 normal test ve 24 ağ aktarımı başarılı, içerik ve sıra doğru, en büyük QUIC datagramı 1200 bayt. Yavaş okuyucuda otomatik pencere indirmede 191988 → 82025, yüklemede 178235 → 67902 bayta küçüldü. Ağır sıra bozulması/kopya profilinde aktarım 38.06–38.60 saniye sürdü. Kayıp ve tıkanıklık kurtarması bu koşulda hâlâ belirgin sınırlamadır; sürüm bütün hatlarda hız artışı vaat etmez. Ham sonuçlar tek koşudur ve ilk sürümün ölçümüyle kontrollü hız karşılaştırması sayılmaz.
