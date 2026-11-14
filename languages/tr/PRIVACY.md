# Safra — Gizlilik Politikası

**Son Güncelleme:** 14 Kasım 2026

> ℹ️ **Yasal Uyarı:** Bu Gizlilik Politikasının resmi ve hukuken bağlayıcı olan metni [İngilizce sürümdür](https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md). Diğer dillere yapılan çeviriler yalnızca kolaylık ve bilgilendirme amacıyla sunulmuştur. Çeviri ile İngilizce metin arasında herhangi bir çelişki olması halinde İngilizce metin esas alınır.

Safra, merkezi bir oyun sunucusu kiralamaya gerek kalmadan, oyuncuların doğrudan eşler arası (P2P) bağlantı kurarak Minecraft oynamasını sağlayan bir moddur. Kullanıcı verilerini **hiçbir şekilde üçüncü taraflara satmaz, kiralamaz veya reklam/pazarlama amacıyla paylaşmayız.**

---

## 1. Hangi Verileri İşliyoruz ve Neden Kaydediyoruz?

Modun teknik olarak çalışabilmesi, oyuncuların eşleştirilebilmesi ve sunucularımızın **DDoS saldırılarına ve kötüye kullanıma karşı korunabilmesi / hukuken savunulabilmesi** amacıyla yalnızca aşağıdaki veriler işlenir:

- **IP Adresi:** P2P bağlantısı için oyuncuları eşleştirmek, aynı IP'den aşırı istek gelmesini engellemek (rate-limiting) ve DDoS/saldırı durumlarında sunucu güvenliğini savunmak amacıyla işlenir ve sunucu loglarına kaydedilir.
- **Geçici Port Bilgisi:** Ev sahibi (Host) ile katılımcının (Joiner) doğrudan birbirine bağlanabilmesi amacıyla oturum eşleştirmesi sırasında işlenir.
- **Oturum Kodu ve Opsiyonel World Code:** Doğru oyuncuların doğru oturuma bağlanmasını sağlamak amacıyla işlenir ve oturum kodları erişim loglarında yer alır.
- **Relay İstekleri ve RelayId:** Doğrudan P2P bağlantısı kurulamadığı durumlarda (ör. katı NAT/CGNAT) şifreli aktarım (TURN / Bedrock Relay) başlatabilmek ve olası kötüye kullanımları takip edebilmek amacıyla işlenir.
- **User-Agent (Safra, Minecraft, Mod Loader ve Java Sürümü):** İsteklerin resmi ve uyumlu mod sürümlerinden geldiğini doğrulamak, hata ayıklama (debugging) yapmak ve bot/saldırı trafiğini ayırt etmek amacıyla loglanır.
- **Zaman Damgası ve Cloudflare `Ray` Kimliği:** Sunucu erişim kayıtlarında (loglarda) olası bir DDoS saldırısı veya teknik arıza anında trafiği analiz edebilmek amacıyla kaydedilir.

Güvenlik ve DDoS koruması amacıyla tutulan sunucu erişim kayıtları (loglar), sistem güvenliği ve olası saldırılara karşı hukuki savunma gerekliliği devam ettiği sürece saklanır.

---

## 2. Neleri Kesinlikle Toplamıyor ve Loglamıyoruz?

Safra aşağıdaki verilerin **hiçbirini** toplamaz, okumaz veya loglamaz:
- Şifreler, Minecraft / Microsoft hesap bilgileri veya oturum tokenları
- Ad, soyad, e-posta, telefon gibi kimlik veya iletişim bilgileri
- Oyun içi sohbet (chat) yazışmaları, komutlar veya sesli sohbet içerikleri
- Kullandığınız diğer modların listesi, dünya dosyaları (save), envanter veya oyun içi hiçbir olay (event)

---

## 3. P2P Bağlantı, IP Sızması ve Kod Güvenliği (Sorumluluk Reddi)

- **IP ve Port İletimi Zorunludur:** Safra oyun trafiğini doğrudan oyuncular arasında (P2P) taşır. Oturum kodunuzu verdiğiniz kişilerin mantıken sizin oyununuza bağlanabilmesi için IP adresiniz ve port bilginizin karşı tarafa iletilmesi teknik olarak zorunludur.
- **Güvendiğiniz Kişilerle Oynayın:** P2P bağlantı doğası gereği, bağlandığınız veya dünyanıza davet ettiğiniz kişiler IP adresinizi görebilir. Kodu paylaştığınız kişilerden veya kodu herkese açık yerlerde paylaşmanızdan kaynaklanan **IP/port sızmalarından ve olası ağ saldırılarından Safra veya geliştiricileri sorumlu tutulamaz.** Lütfen yalnızca güvendiğiniz arkadaşlarınızla oynayın.
- **Kodların Tahmin Edilmesi:** Oturum kodları güvenli rastgele üreteçlerle 12 haneli (sabit kod modunda 16 haneli) olarak oluşturulur ($32^{12}$'den fazla olasılık). Bu büyüklükteki bir kodun sizin paylaşımınız dışında başkaları tarafından tahmin edilmesi veya bulunması teorik ve pratik olarak imkansıza yakındır.

---

## 4. Üçüncü Taraf Altyapılar (Sorumluluk Reddi)

Verileriniz ticari amaçla kimseye aktarılmaz; ancak modun teknik işleyişi gereği şu altyapılarla zorunlu bağlantı kurulur:
- **Cloudflare:** Sinyalleşme sunucusunun DDoS koruması ve doğrudan P2P bağlantısının kurulamadığı durumlarda şifreli TURN Relay aktarımı için kullanılır.
- **STUN Sunucuları (Google, Cloudflare, Twilio):** Modun kendi dış IP ve port bilginizi öğrenebilmesi için kullanılır.
- **GitHub / jsDelivr:** Mod açılışında güncel sürüm ve sunucu yapılandırmasını (`config.json`) kontrol etmek için kullanılır.

Bu üçüncü taraf servislerin kendi sistemlerinde tuttukları kayıtlar ve kendi gizlilik uygulamaları ilgili sağlayıcıların kendi gizlilik politikalarına tabidir; **Safra ve geliştiricileri bu üçüncü taraf altyapıların veri işleme süreçlerinden sorumlu tutulamaz.**

---

## 5. İletişim

Gizlilik politikamızla ilgili sorularınız için bize ulaşabilirsiniz:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra
