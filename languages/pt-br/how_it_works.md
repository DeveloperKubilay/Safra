# Como esse sistema funciona?

Aplicativos como WhatsApp (durante chamadas), Omegle ou BitTorrent usam tecnologia **P2P (Peer-to-Peer / Ponto a Ponto)** em vez de passar todo o tráfego por um servidor central. Passar os dados de jogo de milhões de jogadores por servidores centrais causaria alta latência (lag) e custos astronômicos com servidores.

### Como o sistema realmente funciona?

1. Normalmente, conexões de internet residenciais não têm portas públicas abertas (devido ao esgotamento de endereços IPv4 e ao uso de CGNAT pelos provedores, não é possível abrir portas diretamente de fora).
2. No entanto, sempre que você acessa um site ou faz uma solicitação de saída, seu roteador/provedor abre temporariamente uma porta de saída para o mundo externo.
3. Neste sistema, o mod se conecta como se fosse acessar um site como o Google, mas sem enviar mensagens; ele apenas pega essa porta temporária aberta e a compartilha com seu amigo, enquanto entrega a porta do seu amigo para você. É como dizer *"vou acessar um site"* e usar essa porta aberta para se conectar diretamente com seu amigo.
4. Depois que a conexão é estabelecida, o tráfego do jogo flui **sem nenhum servidor intermediário**, diretamente entre o seu computador e o do seu amigo (P2P).

---

### Diferença de Conexão

Por exemplo, em mods parecidos:

`Seu computador (Istambul) -> servidor relay (Frankfurt) -> computador do seu amigo (Istambul)`

Neste mod:

`Seu computador (Istambul) -> computador do seu amigo (Istambul)`

Não há nenhum servidor relay intermediário; a comunicação ocorre exclusivamente entre vocês dois.

## Seguranca

- Scans do CodeQL sao usados
- Plataformas como CurseForge podem escanear novas versoes
- Voce pode ver as builds
- As builds sao enviadas com GitHub Actions em vez de serem enviadas manualmente do meu computador
