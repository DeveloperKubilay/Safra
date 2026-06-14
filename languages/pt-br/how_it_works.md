# Como esse sistema funciona?

Apps como Omegle e WhatsApp normalmente se comunicam sem manter um servidor no meio o tempo todo. Esse sistema funciona com uma ideia parecida.

Provedores de internet podem nao permitir abrir portas TCP, mas podem permitir portas UDP.

Neste mod, a comunicacao do servidor de Minecraft, que normalmente funciona com TCP, e transportada por UDP e mostrada para o outro lado como se fosse TCP.

Isso permite jogar de forma suave com seu amigo de graca, sem que o trafego passe por um servidor de relay.

Por exemplo, em mods parecidos:

`Seu computador (Istambul) -> servidor relay (Frankfurt) -> computador do seu amigo (Istambul)`

Neste mod:

`Seu computador (Istambul) -> computador do seu amigo (Istambul)`

Nao existe servidor no meio para o trafego do jogo.

A comunicacao acontece apenas entre voce e seu amigo.

Este mod usa um servidor apenas para que os dois lados encontrem o endereco IP um do outro.

## Seguranca

- Scans do CodeQL sao usados
- Plataformas como CurseForge podem escanear novas versoes
- Voce pode ver as builds
- As builds sao enviadas com GitHub Actions em vez de serem enviadas manualmente do meu computador
