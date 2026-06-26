# Bu sistem nasıl çalışır?

Omegle ve WhatsApp gibi uygulamalar, genellikle arada sunucu olmadan iletişim kurarlar. Bu da benzer mantıkla çalışır.

Internet sağlayıcıları TCP port açmazken UDP port açmaya izin verebilir.

Bu modda TCP ile calisan Minecraft sunucusunun iletişimi UDP uzerinden sağlanıp karsi tarafa TCP gibi gösterilir.

Böylece arada hiçbir sunucu geçmeden, sınırsız ve ücretsiz şekilde arkadaşınla akıcı oynayabilirsin.

Ornek olarak benzer modlarda:

`Senin bilgisayarın (Istanbul) -> relay sunucusu (Frankfurt) -> arkadaşının bilgisayarı (Istanbul)`

Bu modda ise:

`Senin bilgisayarın (Istanbul) -> arkadaşının bilgisayarı (Istanbul)`

Arada herhangi bir sunucu bulunmaz.

Aradaki iletişim sadece aranızda olur.

Bu mod, birbirinizin IP adresini bulabilmeniz için sadece sunucu kullanir.

## Güvenlik

- CodeQL ile taramalar yapılır
- Yeni versiyon yüklenirken örneğin CurseForge kod taraması yapar
- Buildleri görebilirsiniz
- Kendi bilgisayarımdan alip yüklemek yerine buildler GitHub Actions ile yüklenir
