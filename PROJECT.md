# Safra Project Notes

Bu dosya, yeni bir chat acildiginda projeye hizli geri donmek icin tutuluyor.

## Proje Kimligi

- Mod name: `Safra`
- Mod id: `safra`
- Maven group: `org.developerkubilay`
- Java package: `org.developerkubilay.safra`
- Minecraft target: `1.21.11`
- Current mod version: `1.0-SNAPSHOT`

## Su Anki Mimari

Proje coklu modullu:

- `common`
  Ortak P2P, tunnel, share code, STUN, rendezvous ve reliable UDP mantigi.
- `fabric`
  Fabric entrypoint, mixin, config ve ekran entegrasyonu.
- `neoforge`
  NeoForge entrypoint, event kayitlari, config ve ekran entegrasyonu.
- `forge`
  Forge entrypoint, event kayitlari, config ve ekran entegrasyonu.

Temel kural:

- Loader bagimsiz mantik `common` icinde.
- Loader API, lifecycle, mixin, UI glue ve config path gibi seyler platform modullerinde.

## Ana Calisma Mantigi

Safra, Minecraft istemcisinin veya integrated server'in yerel TCP portunu dogrudan internete acmaz.
Bunun yerine:

1. Host bir UDP socket acar.
2. STUN ile public UDP endpoint bulunur.
3. Endpoint Cloudflare Worker rendezvous servisine kaydedilir.
4. Joiner kisa kodu girer.
5. Worker iki tarafa uygun endpoint bilgisini verir.
6. Iki taraf UDP hole punching dener.
7. Joiner tarafinda `127.0.0.1:<proxyPort>` loopback proxy acilir.
8. Minecraft local proxy'ye baglanir, Safra TCP akisini reliable UDP tunnel ustunden hosta tasir.

Onemli:

- Local proxy hala `127.0.0.1` kullanir. Bu normaldir.
- Bu LAN fallback degildir.
- Gercek oyun trafigi STUN sunucusundan veya Worker'dan gecmez.
- Worker sadece tanistirma/sinyalleme icindir.

## Network Kararlari

Su anki tasarim bilerek soyle:

- Ozel LAN fallback yok.
- Ayni agdaysa bile sistem once public P2P / NAT hairpin mantigiyla calismayi dener.
- Worker endpoint seciminde uygun adres ailesini secmeye yardim eder.
- Sistem hem IPv4 hem IPv6 adaylarini tasiyabilecek hale getirildi.
- Worker yoksa ve STUN/public endpoint varsa eski tip direct code (`ip:port#token`) hala fallback olarak destekli.

Bu yuzden su 3 senaryo vardir:

1. `worker up + STUN/public UDP var`
   Kisa kodla normal rendezvous P2P.
2. `worker down + STUN/public UDP var`
   Direct `ip:port#token` fallback.
3. `STUN/public UDP yok`
   Gercek internet P2P garanti degil.

## Su Anki STUN Sirasi

STUN sunuculari bu sirayla deneniyor:

- `stun.l.google.com:19302`
- `stun1.l.google.com:19302`
- `stun2.l.google.com:19302`
- `stun.cloudflare.com:3478`
- `global.stun.twilio.com:3478`

## Su Anki Tuning Degerleri

`common/src/main/java/org/developerkubilay/safra/p2p/P2pConstants.java`

- `MAX_PAYLOAD_SIZE = 1000`
- `SEND_WINDOW_SIZE = 32`
- `RESEND_MS = 350`
- `OPEN_RESEND_MS = 500`
- `MAINTENANCE_TICK_MS = 100`
- `KEEP_ALIVE_MS = 10000`

Bu ayarlar su an "stabil baseline" olarak kabul edildi. Simdilik adaptif tuning yok.

## Onemli Dosyalar

Ortak network cekirdegi:

- `common/src/main/java/org/developerkubilay/safra/p2p/P2pClientProxy.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pHostService.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pStunClient.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/SafraRendezvousClient.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/ReliableTunnelConnection.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pConstants.java`

Platform glue:

- `fabric/src/client/java/org/developerkubilay/safra/client/p2p/P2pManager.java`
- `neoforge/src/main/java/org/developerkubilay/safra/client/p2p/P2pManager.java`
- `forge/src/main/java/org/developerkubilay/safra/client/p2p/P2pManager.java`

Client mixin alanlari:

- `fabric/src/client/java/org/developerkubilay/safra/mixin/client/`
- `neoforge/src/main/java/org/developerkubilay/safra/mixin/client/`
- `forge/src/main/java/org/developerkubilay/safra/mixin/client/`

## Build ve Run

Root task'lar:

- `gradlew build`
  Uc loader'i de build eder ve artifact'lari root `build/libs` altinda toplar.
- `gradlew runClient`
  Root'tan Fabric dev client acar.
- `gradlew :neoforge:runClient`
  NeoForge dev client.
- `gradlew :forge:runClient`
  Forge dev client.

Current root artifact isimleri:

- `Safra-fabric-<version>.jar`
- `Safra-neoforge-<version>.jar`
- `Safra-forge-<version>.jar`

## GitHub Actions

Workflow'lar:

- `.github/workflows/build.yml`
  Manuel build. `version_number` input'u var.
- `.github/workflows/release.yml`
  Manuel GitHub Release. `version_number` ve `prerelease` input'lari var.
- `.github/workflows/codeql.yml`
  Push spam yapmayacak sekilde guvenlik taramasi.

Timeout'lar ekli:

- Build: `25 dakika`
- Release: `35 dakika`
- CodeQL: `45 dakika`

## Worker / Rendezvous

Cloudflare Worker adresi:

- `https://safra.developerkubilay.workers.dev`

Worker local repo yolu:

- `C:\Users\kubil\Desktop\p2p`

Onemli not:

- Worker degisikligi yapilirsa local repoda typecheck/deploy gerekebilir.
- Mod kodu ile Worker protokolu birlikte dusunulmeli.

## UI ve Config

Config dosyasi:

- `config/safra-client.json`

Orada kullanici tercihleri tutuluyor:

- Open to LAN `P2P`
- Open to LAN `Online Mode`
- Direct Connect `P2P`

Dev testlerde `Online Mode: OFF` kullanildi.

## Icon ve Metadata

Aktif mod iconu:

- `common/src/main/resources/assets/safra/icon.png`

Kaynak buyuk icon:

- `icon1024.png`

Icon su an `256x256` optimize edilmis halde kullaniliyor.

## README Durumu

Kullanici istegiyle README minimal tutuldu.
Su an:

- `README.md` sadece `readme.gif` gosteriyor.

Bunu bilerek boyle biraktik.

## Son Temizlikte Yapilanlar

Release oncesi kucuk kod temizligi yapildi:

- Ortak endpoint toplama mantigi `P2pStunClient` icine cekildi.
- Kullanilmayan helper ve parametreler temizlendi.
- Fabric metadata bos aciklama yerine ortak `mod_description` kullanacak hale getirildi.
- README degisikligi geri alindi.

Buyuk davranis degisimi yapilmadi.

## Su Anki Durum

Genel durum:

- Fabric build OK
- NeoForge build OK
- Forge build OK
- Common ayrimi temiz
- Worker tabanli kisa kod sistemi var
- Direct `ip:port#token` fallback var

En son dogrulanan artifact boyutlari yaklasik:

- Fabric jar: `241 KB`
- NeoForge jar: `240 KB`
- Forge jar: `241 KB`

## Release Oncesi Kalanlar

Teknik olarak su an release alinabilir gorunuyor.
Yayina cikmadan once kalan mantikli adimlar:

1. `mod_version` belirlemek
2. `release.yml` ile release almak
3. GitHub release notunu kontrol etmek
4. Son bir gercek oyun testi yapmak istenirse yapmak

## Sonraki Port Plani

Bu repo, sonraki surumlere port icin base olarak dusunuluyor.
Su anki tavsiye edilen branch stratejisi:

- `main` = `1.21.11`
- `mc/26.1` = yeni modern hat
- sonra ihtiyaca gore surum branch'i

Onerilen siralama:

1. `mc/26.1`
2. `mc/1.20.1`
3. `mc/1.19.2`
4. `mc/1.18.2`
5. `mc/1.16.5`
6. `mc/1.14.4`
7. `mc/1.12.2-forge`

Not:

- Her loader icin ayri branch acilmayacak.
- Branch'ler Minecraft surum ailesine gore acilacak.
- `1.12.2` icin ilk dusunce `Forge only`.
- `26.1` ayni proje mantigiyla yapilabilir ama ayri branch ister.
- `26.1` siradan bir patch bump degildir; mapping/toolchain tarafi ayri migration ister.

## 26.1 Notlari

`26.1` hattina gecis, `1.21.11` ile ayni branch icinde yapilmayacak.
Sebep:

- yeni Minecraft surumleme sistemi
- modern toolchain farklari
- official mappings zorunlulugu
- Java 25 gereksinimi

Bu yuzden `26.1` icin plan:

1. `main` uzerinden `mc/26.1` branch ac
2. Once build/toolchain gecisini yap
3. Sonra Fabric / NeoForge / Forge glue katmanlarini tek tek duzelt
4. `common` katmanini mümkün oldugunca ayni mantikta tut

Kisa karar:

- `1.21.11` = stabil base
- `26.1` = bir sonraki buyuk modern hedef

## Uzun Vadeli Destek Fikri

Kullanici istegi, zamani geldikce bircok surume cikmak.
Su an dusunulen genis yol haritasi:

- `26.1`
- `1.21.11`
- `1.20.1`
- `1.19.2`
- `1.18.2`
- `1.16.5`
- `1.14.4`
- `1.12.2`

Pratik not:

- modern surumler mevcut coklu-loader mimariye daha yakin
- eski legacy surumler daha fazla ozel port ister
- ozellikle `1.12.2` tarafinda bakim maliyeti yuksek

## Yeni Chat Acildiginda

Yeni bir chat'te hizli baslamak icin:

1. Once bu dosyayi oku.
2. Sonra `gradle.properties` surumlerini kontrol et.
3. Sonra `common/src/main/java/org/developerkubilay/safra/p2p/` altindaki son network mantigina bak.
4. Worker gerekiyorsa `C:\Users\kubil\Desktop\p2p` reposunu birlikte degerlendir.

Bu dosya "nerede kaldik" ozeti olarak tutuluyor.
