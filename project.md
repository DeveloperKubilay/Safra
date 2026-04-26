# Safra Project Structure

Bu dosya, projenin kalici yapisini ve surum port rehberini anlatir.
Gecici hata hikayeleri, tek seferlik notlar ve anlik release durumu burada tutulmaz.

## Project Identity

- Mod name: `Safra`
- Mod id: `safra`
- Maven group: `org.developerkubilay`
- Java package: `org.developerkubilay.safra`
- Current base version: `1.21.11`
- Current mod version: `1.0-SNAPSHOT`

## Module Layout

Proje dort ana modulden olusur:

- `common`
  - loader bagimsiz cekirdek mantik
  - P2P flow
  - STUN
  - rendezvous istemcisi
  - reliable UDP tunnel
  - Simple Voice Chat ortak compat mantigi
- `fabric`
  - Fabric entrypoint
  - Fabric client mixin
  - Fabric config path ve loader bagli adapter'lar
  - Fabric ekran entegrasyonu
- `forge`
  - Forge entrypoint
  - Forge event kayitlari
  - Forge client mixin
  - Forge config path ve loader bagli adapter'lar
- `neoforge`
  - NeoForge entrypoint
  - NeoForge event kayitlari
  - NeoForge client mixin
  - NeoForge config path ve loader bagli adapter'lar

## Architecture Rule

Ana kural:

- genel mantik `common` icinde kalir
- loader API'sine bagli kod platform modulunde kalir
- `fabric`, `forge`, `neoforge` katmanlari adapter/donusturucu gibi davranir

Bir sey `common` icine alinacaksa su 3 kosulu saglamali:

1. loader API'sine dogrudan bagli olmamali
2. ayni davranisi birden fazla platformda tekrar ediyor olmali
3. eski surumlere port ederken de anlamini korumali

## Core P2P Flow

Safra, Minecraft'in local TCP portunu dogrudan internete acmaz.
Akis su sekildedir:

1. Host UDP socket acar
2. STUN ile public UDP endpoint bulunur
3. Endpoint rendezvous servisine kaydedilir
4. Joiner short code girer
5. Rendezvous host ve joiner'a uygun endpoint bilgisini verir
6. UDP hole punching denenir
7. Joiner tarafinda `127.0.0.1:<proxyPort>` local proxy acilir
8. Minecraft local proxy'ye baglanir
9. TCP oyun trafigi reliable UDP tunnel ustunden host'a gider

Onemli:

- STUN ve Worker oyun trafigini tasimaz
- Worker sadece signaling/tanistirma icindir
- local proxy kullanimi normaldir
- ozel LAN fallback yoktur

## Network Behavior

Tasarim kararlari:

- sistem once internet uzerinden P2P mantigini hedefler
- IPv4 ve IPv6 adaylari tasinabilir
- ayni public IP durumunda NAT hairpin/self-connect yolu denenebilir
- Worker yoksa direct `ip:port#token` fallback desteklenir

Senaryolar:

1. `worker up + STUN/public UDP var`
   - rendezvous short code ile normal baglanti
2. `worker down + STUN/public UDP var`
   - direct `ip:port#token` fallback
3. `STUN/public UDP yok`
   - gercek internet P2P garanti degil

## Voice Chat

Simple Voice Chat desteklenir.

- ayri ikinci share code yoktur
- ayni Safra session altinda opsiyonel `voice` kanali vardir
- voice verisi reliable tunnel ustunden gitmez
- voice kendi UDP yolunu kullanir
- SVC yoksa bu katman hic devreye girmez

## Worker and STUN

Rendezvous worker:

- `https://safra.developerkubilay.workers.dev`

Local worker repo:

- `C:\Users\kubil\Desktop\p2p`

STUN sirasi:

- `stun.l.google.com:19302`
- `stun1.l.google.com:19302`
- `stun2.l.google.com:19302`
- `stun.cloudflare.com:3478`
- `global.stun.twilio.com:3478`

## Important Common Files

Ortak network cekirdegi:

- `common/src/main/java/org/developerkubilay/safra/p2p/P2pClientProxy.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pHostService.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pHostSupport.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pStunClient.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/SafraRendezvousClient.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/ReliableTunnelConnection.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pConstants.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/P2pShareCode.java`

Ortak voice compat:

- `common/src/main/java/org/developerkubilay/safra/p2p/SafraVoiceServerSocket.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/SafraVoiceClientSocket.java`
- `common/src/main/java/org/developerkubilay/safra/p2p/SafraVoiceTransportManager.java`
- `common/src/main/java/org/developerkubilay/safra/voicechat/SafraVoicechatPluginBase.java`

## Important Platform Files

Platform glue:

- `fabric/src/client/java/org/developerkubilay/safra/client/p2p/P2pManager.java`
- `forge/src/main/java/org/developerkubilay/safra/client/p2p/P2pManager.java`
- `neoforge/src/main/java/org/developerkubilay/safra/client/p2p/P2pManager.java`

Dedicated server adapter'lari:

- `fabric/src/main/java/org/developerkubilay/safra/server/DedicatedP2pServerManager.java`
- `forge/src/main/java/org/developerkubilay/safra/server/DedicatedP2pServerManager.java`
- `neoforge/src/main/java/org/developerkubilay/safra/server/DedicatedP2pServerManager.java`

LAN settings:

- `fabric/src/client/java/org/developerkubilay/safra/client/p2p/FabricLanSessionState.java`
- `fabric/src/client/java/org/developerkubilay/safra/client/p2p/FabricLanGameRules.java`
- `fabric/src/client/java/org/developerkubilay/safra/client/p2p/SafraLanServerSettingsScreen.java`
- `forge/src/main/java/org/developerkubilay/safra/client/p2p/ForgeLanSessionState.java`
- `forge/src/main/java/org/developerkubilay/safra/client/p2p/ForgeLanGameRules.java`
- `forge/src/main/java/org/developerkubilay/safra/client/p2p/SafraLanServerSettingsScreen.java`
- `neoforge/src/main/java/org/developerkubilay/safra/client/p2p/NeoForgeLanSessionState.java`
- `neoforge/src/main/java/org/developerkubilay/safra/client/p2p/NeoForgeLanGameRules.java`
- `neoforge/src/main/java/org/developerkubilay/safra/client/p2p/SafraLanServerSettingsScreen.java`

Mixin alanlari:

- `fabric/src/client/java/org/developerkubilay/safra/mixin/client/`
- `forge/src/main/java/org/developerkubilay/safra/mixin/client/`
- `neoforge/src/main/java/org/developerkubilay/safra/mixin/client/`

## Build and Release

Ana komutlar:

- `gradlew build`
- `gradlew runClient`
- `gradlew :forge:runClient`
- `gradlew :neoforge:runClient`

Root artifact'lar:

- `build/libs/Safra-fabric-<version>.jar`
- `build/libs/Safra-forge-<version>.jar`
- `build/libs/Safra-neoforge-<version>.jar`

GitHub Actions:

- `.github/workflows/build.yml`
- `.github/workflows/release.yml`
- `.github/workflows/codeql.yml`

## UI and Config

Config dosyasi:

- `config/safra-client.json`

Tutulan tercihler:

- Open to LAN `P2P`
- Open to LAN `Online Mode`
- Open to LAN `Allow Commands`
- Open to LAN `Game Rules` snapshot
- Direct Connect `P2P`

## Metadata and Assets

- mod icon: `common/src/main/resources/assets/safra/icon.png`
- source icon: `icon1024.png`
- `README.md` su an minimaldir ve `readme.gif` gosterir

## Version Port Strategy

Bu repo, sonraki surumlere port icin base kabul edilir.

Kurallar:

- ayni Minecraft surumunde loader farklari ayni branch'te kalir
- farkli Minecraft surumleri ayri branch olur
- loader adapter kodu platform modulunde kalir
- genel mantik firsat bulundukca `common` icine cekilir

Onerilen branch mantigi:

- `main` = `1.21.11`
- sonra ihtiyaca gore `mc/<version>`

## Planned Version Roadmap

Onerilen sira:

1. `mc/26.1`
2. `mc/1.20.1`
3. `mc/1.19.2`
4. `mc/1.18.2`
5. `mc/1.16.5`
6. `mc/1.14.4`
7. `mc/1.12.2-forge`

Uzun vadeli hedef ailesi:

- `26.1`
- `1.21.11`
- `1.20.1`
- `1.19.2`
- `1.18.2`
- `1.16.5`
- `1.14.4`
- `1.12.2`

## Porting Notes by Era

Modern surumler:

- mevcut coklu-loader mimariye daha yakin
- `common` katmaninin buyuk kismi korunabilir
- mapping ve toolchain guncellemesi ana maliyettir

Legacy surumler:

- daha fazla loader-ozel uyarlama ister
- ekran, mixin ve lifecycle glue tarafinda daha cok fark cikar
- ozellikle `1.12.2` icin ilk hedef `Forge only` olmali

## Porting Checklist

Yeni bir surume gecerken ilk bakilacak yerler:

- `gradle.properties`
- root `build.gradle`
- `fabric/build.gradle`
- `forge/build.gradle`
- `neoforge/build.gradle`
- `fabric.mod.json`
- `META-INF/mods.toml`
- `META-INF/neoforge.mods.toml`
- `common/src/main/java/org/developerkubilay/safra/p2p/`
- `common/src/main/java/org/developerkubilay/safra/voicechat/`
- platform `P2pManager` siniflari
- LAN / Direct Connect / Connect mixin ekranlari
- tum `lang` dosyalari
- worker repo:
  - `C:\Users\kubil\Desktop\p2p\src\index.ts`
  - `C:\Users\kubil\Desktop\p2p\wrangler.jsonc`
