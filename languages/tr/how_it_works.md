# Bu sistem nasıl çalışır?

WhatsApp (arama yaparken), Omegle veya Torrent gibi platformlar trafiği tek bir sunucuya yüklemek yerine **P2P (Peer-to-Peer / Uçtan Uca)** teknolojisi kullanır. Çünkü milyonlarca oyuncunun verisini merkezi sunuculardan geçirmek hem yüksek gecikmeye (lag) yol açar hem de devasa sunucu maliyetleri doğurur.

### Sistem tam olarak nasıl işliyor?

1. Normalde ev internetlerinde sabit/açık bir IP ve port bulunmaz (dünyada IPv4 adresleri bittiği ve servis sağlayıcıları CGNAT kullandığı için dışarıdan doğrudan port açılamaz).
2. Ancak siz bir web sitesine girerken veya dışarıya bir istek attığınızda modeminiz/sağlayıcınız dış dünyaya geçici bir çıkış kapısı (port) açar.
3. Bu sistemde ise mod, tıpkı web sitesine girecekmiş gibi Google'a bağlanır ancak mesaj iletmez; sadece o açılan portu alır ve arkadaşınıza iletir, arkadaşınızın portunu da size verir. Böylece siz resmen *"web sitesine gireceğim"* deyip açtığınız port üzerinden gidip arkadaşınızla bağlantı kurmuş olursunuz.
4. Bağlantı bir kez kurulduktan sonra oyun trafiği **arada hiçbir sunucu olmadan**, tamamen sizinle arkadaşınızın bilgisayarı arasında doğrudan (P2P) akar.

---

### Bağlantı Farkı

Örnek olarak benzer modlarda:

`Senin bilgisayarın (Istanbul) -> relay sunucusu (Frankfurt) -> arkadaşının bilgisayarı (Istanbul)`

Bu modda ise:

`Senin bilgisayarın (Istanbul) -> arkadaşının bilgisayarı (Istanbul)`

Arada herhangi bir aktarma sunucusu bulunmaz; iletişim sadece ikinizin arasındadır.

## Güvenlik

- CodeQL ile taramalar yapılır
- Yeni versiyon yüklenirken örneğin CurseForge kod taraması yapar
- Buildleri görebilirsiniz
- Kendi bilgisayarımdan alıp yüklemek yerine buildler GitHub Actions ile yüklenir
