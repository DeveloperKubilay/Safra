# Bu sistem nasil calisir?

Omegle ve WhatsApp gibi uygulamalar, genellikle arada sunucu olmadan iletisim kurarlar. Bu da benzer mantikla calisir.

Internet saglayicilari TCP port acmazken UDP port acmaya izin verebilir.

Bu modda TCP ile calisan Minecraft sunucusunun iletisimi UDP uzerinden saglanip karsi tarafa TCP gibi gosterilir.

Boylece arada hicbir sunucu gecmeden, sinirsiz ve ucretsiz sekilde arkadasinla akici oynayabilirsin.

Ornek olarak benzer modlarda:

`Senin bilgisayarin (Istanbul) -> relay sunucusu (Frankfurt) -> arkadasinin bilgisayari (Istanbul)`

Bu modda ise:

`Senin bilgisayarin (Istanbul) -> arkadasinin bilgisayari (Istanbul)`

Arada herhangi bir sunucu bulunmaz.

Aradaki iletisim sadece aranizda olur.

Bu mod, birbirinizin IP adresini bulabilmeniz icin sadece sunucu kullanir.

## Guvenlik

- CodeQL ile taramalar yapilir
- Yeni version yuklenirken ornegin CurseForge kod taramasi yapar
- Buildleri gorebilirsiniz
- Kendi bilgisayarimdan alip yuklemek yerine buildler GitHub Actions ile yuklenir
