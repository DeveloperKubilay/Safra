# Safra — Privacy Policy

**Last Updated:** November 14, 2026

Safra is a Minecraft modification that allows players to play together using direct Peer-to-Peer (P2P) connections without renting or setting up a dedicated central game server. We **never sell, rent, or share your data with third parties for advertising, analytics, or commercial purposes.**

---

## 1. What Data We Process & Why We Log It

To operate the mod, match players together, and **protect and legally defend our infrastructure against DDoS attacks and abuse**, only the following technical data is processed:

- **IP Address:** Processed for P2P matchmaking, rate-limiting excessive requests from the same IP, and defending server security in the event of DDoS attacks or abuse; recorded in server access logs.
- **Ephemeral Port Information:** Processed during session matchmaking so the host and joining players can establish a direct connection.
- **Session Code & Optional World Code:** Processed to connect the right players to the right session; session codes are included in server access logs.
- **Relay Requests & RelayId:** Processed when direct P2P hole-punching fails (e.g., strict NAT/CGNAT) in order to establish encrypted TURN / Bedrock Relay fallback and track potential abuse.
- **User-Agent (Safra, Minecraft, Mod Loader, and Java Version):** Logged to verify requests come from official and compatible mod builds, assist with debugging, and distinguish legitimate traffic from bots or attacks.
- **Timestamp & Cloudflare `Ray` ID:** Recorded in server access logs to analyze traffic during DDoS attacks or technical failures.

Server access logs maintained for security and DDoS protection are retained for as long as necessary to ensure system security and legal defense against network attacks.

---

## 2. What We Never Collect or Log

Safra **does not** collect, read, or log any of the following:
- Passwords, Minecraft / Microsoft account credentials, or authentication tokens
- Personal identity or contact information (name, email address, phone number, etc.)
- In-game chat messages, commands, or voice chat audio/contents
- Your list of other installed mods, world save files, inventory data, or any in-game events

---

## 3. P2P Connections, IP Exposure & Code Security (Disclaimer)

- **IP and Port Sharing Is Technically Required:** Safra routes gameplay traffic directly between players (P2P). For the people you share your session code with to connect to your game, your IP address and port must logically and technically be shared with them.
- **Play Only With Trusted Friends:** Due to the nature of P2P networking, peers you connect to or invite into your world can see your IP address. **Safra and its developers cannot be held responsible for IP/port exposure or potential network attacks** resulting from sharing your code with untrusted individuals or posting it publicly. Please play only with friends you trust.
- **Code Predictability:** Session codes are generated using cryptographically secure random generators with 12 characters (or 16 characters in Fixed Code mode), yielding over $32^{12}$ possible combinations. Unless you share your code, it is theoretically and practically impossible for others to guess or discover your session.

---

## 4. Third-Party Infrastructure (Disclaimer)

Your data is never transferred to third parties for commercial purposes; however, the mod must communicate with the following technical infrastructure providers to function:
- **Cloudflare:** Used for DDoS protection on the signaling server and encrypted TURN Relay fallback when direct P2P fails.
- **STUN Servers (Google, Cloudflare, Twilio):** Used solely so the mod can discover your own public IP and port.
- **GitHub / jsDelivr:** Used on startup to check for mod updates and current server configuration (`config.json`).

Any records kept by these third-party services on their own systems and their respective privacy practices are governed by their own privacy policies; **Safra and its developers cannot be held responsible for the data processing practices of these third-party providers.**

---

## 5. Contact

If you have any questions regarding this Privacy Policy, you can reach us at:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra



-----


# Safra — Gizlilik Politikası

**Son Güncelleme:** 14 Kasım 2026

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