# Safra — Política de Privacidade

**Última atualização:** 14 de novembro de 2026

> ℹ️ **Aviso Legal:** A versão oficial e legalmente vinculativa desta Política de Privacidade é a [versão em inglês](https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md). As traduções para outros idiomas são fornecidas exclusivamente para conveniência e fins informativos. Em caso de discrepâncias ou divergências entre uma tradução e o texto em inglês, a versão em inglês prevalecerá.

O Safra é uma modificação para Minecraft que permite que jogadores joguem juntos utilizando conexões diretas ponto a ponto (P2P), sem a necessidade de alugar ou configurar um servidor central dedicado. Nós **nunca vendemos, alugamos ou compartilhamos seus dados com terceiros para publicidade, análise ou fins comerciais.**

---

## 1. Quais dados processamos e por que os registramos

Para que o mod funcione tecnicamente, conecte os jogadores e **proteja e defenda juridicamente nossa infraestrutura contra ataques DDoS e abusos**, apenas os seguintes dados técnicos são processados:

- **Endereço IP:** Processado para o emparelhamento P2P, para limitar o excesso de requisições provenientes de um mesmo IP (rate-limiting) e defender a segurança do servidor em caso de ataques DDoS ou abuso; registrado nos logs de acesso do servidor.
- **Informações de porta efêmera:** Processadas durante o emparelhamento da sessão para que o anfitrião e os participantes possam estabelecer uma conexão direta.
- **Código de sessão e World Code opcional:** Processados para direcionar os jogadores corretos à sessão certa; os códigos de sessão ficam registrados nos logs de acesso.
- **Requisições de Relay e RelayId:** Processadas quando a conexão direta P2P falha (ex.: NAT restrito / CGNAT), viabilizando um túnel criptografado de contingência via TURN / Bedrock Relay e permitindo monitorar abusos.
- **User-Agent (versão do Safra, Minecraft, Mod Loader e Java):** Registrado para verificar se as solicitações se originam de versões oficiais e compatíveis do mod, auxiliar na depuração e distinguir o tráfego legítimo de bots ou ataques.
- **Carimbo de data/hora (Timestamp) e ID `Ray` da Cloudflare:** Gravados nos registros de acesso ao servidor para analisar o tráfego durante ataques DDoS ou falhas técnicas.

Os logs de acesso ao servidor mantidos para segurança e proteção contra DDoS são conservados pelo tempo necessário para garantir a segurança dos sistemas e a defesa jurídica contra ataques de rede.

---

## 2. O que nunca coletamos ou registramos

O Safra **não** coleta, lê ou registra nada do seguinte:
- Senhas, credenciais de conta Minecraft / Microsoft ou tokens de autenticação
- Informações de identidade pessoal ou de contato (nome, e-mail, telefone, etc.)
- Mensagens de chat no jogo, comandos ou áudio/conteúdo de chat de voz
- Sua lista de outros mods instalados, arquivos de mundo salvo (saves), inventário ou eventos no jogo

---

## 3. Conexões P2P, exposição de IP e segurança do código (Isenção de responsabilidade)

- **O compartilhamento de IP e porta é tecnicamente obrigatório:** O Safra encaminha o tráfego do jogo diretamente entre os jogadores (P2P). Para que os amigos com quem você compartilha seu código de sessão consigam entrar no seu mundo, seu endereço IP e porta precisam ser logicamente e tecnicamente compartilhados com eles.
- **Jogue apenas com amigos confiáveis:** Pela natureza das redes P2P, outros jogadores com os quais você se conecta ou que você convida para o seu mundo conseguem ver o seu endereço IP. **O Safra e seus desenvolvedores não podem ser responsabilizados por exposições de IP/porta ou possíveis ataques de rede** resultantes do compartilhamento do seu código com desconhecidos ou de publicações abertas na internet. Jogue apenas com amigos em quem você confia.
- **Segurança e imprevisibilidade dos códigos:** Os códigos de sessão são gerados usando algoritmos criptograficamente seguros com 12 caracteres (ou 16 caracteres no modo de código fixo), oferecendo mais de $32^{12}$ combinações possíveis. A menos que você divulgue seu código, é teórica e praticamente impossível adivinhar ou encontrar sua sessão.

---

## 4. Infraestrutura de terceiros (Isenção de responsabilidade)

Seus dados nunca são transferidos a terceiros para fins comerciais; todavia, o mod precisa se comunicar com os seguintes provedores de infraestrutura técnica para operar:
- **Cloudflare:** Utilizado para proteção DDoS no servidor de sinalização e para retransmissão criptografada (TURN Relay) quando o P2P direto falha.
- **Servidores STUN (Google, Cloudflare, Twilio):** Utilizados unicamente para que o mod identifique seu próprio IP público e porta.
- **GitHub / jsDelivr:** Utilizados na inicialização para checar por atualizações do mod e verificar a configuração do servidor (`config.json`).

Quaisquer registros mantidos por esses serviços de terceiros e suas políticas de privacidade são regidos por seus respectivos termos; **o Safra e seus desenvolvedores não podem ser responsabilizados pelas práticas de tratamento de dados desses serviços externos.**

---

## 5. Contato

Se você tiver alguma dúvida sobre esta Política de Privacidade, entre em contato conosco em:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra
