# Safra — Política de Privacidad

**Última actualización:** 14 de noviembre de 2026

> ℹ️ **Aviso legal:** La versión oficial y jurídicamente vinculante de esta Política de Privacidad es la [versión en inglés](https://github.com/DeveloperKubilay/Safra/blob/assets/PRIVACY.md). Las traducciones a otros idiomas se proporcionan únicamente con fines informativos y de conveniencia. En caso de discrepancias o inconsistencias entre una traducción y el texto en inglés, prevalecerá la versión en inglés.

Safra es una modificación para Minecraft que permite a los jugadores jugar juntos utilizando conexiones directas de punto a punto (P2P), sin necesidad de alquilar ni configurar un servidor central de juego dedicado. **Nunca vendemos, alquilamos ni compartimos tus datos con terceros para publicidad, análisis o fines comerciales.**

---

## 1. Qué datos procesamos y por qué los registramos

Para que el mod funcione técnicamente, empareje a los jugadores y **proteja y defienda legalmente nuestra infraestructura frente a ataques DDoS y abusos**, únicamente se procesan los siguientes datos técnicos:

- **Dirección IP:** Se procesa para el emparejamiento P2P, para limitar el exceso de solicitudes desde una misma IP (rate-limiting) y para defender la seguridad del servidor en caso de ataques DDoS o abuso; se guarda en los registros de acceso al servidor.
- **Información de puerto efímero:** Se procesa durante la vinculación de la sesión para que el anfitrión y los jugadores que se unen puedan establecer una conexión directa.
- **Código de sesión y World Code opcional:** Se procesan para conectar a los jugadores correctos a la sesión correspondiente; los códigos de sesión se registran en los logs de acceso.
- **Solicitudes de Relay y RelayId:** Se procesan cuando falla la conexión directa P2P (por ejemplo, NAT estricto / CGNAT) para establecer un puente TURN / Bedrock Relay cifrado y controlar posibles abusos.
- **User-Agent (versión de Safra, Minecraft, Mod Loader y Java):** Se registra para verificar que las solicitudes provienen de versiones oficiales y compatibles del mod, ayudar en la depuración de errores y distinguir el tráfico legítimo de bots o ataques.
- **Marca de tiempo e ID `Ray` de Cloudflare:** Se guardan en los registros de acceso del servidor para analizar el tráfico durante ataques DDoS o fallos técnicos.

Los registros de acceso al servidor mantenidos por motivos de seguridad y protección contra ataques DDoS se conservan durante el tiempo que sea necesario para garantizar la seguridad del sistema y la defensa legal ante ataques a la red.

---

## 2. Lo que nunca recopilamos ni registramos

Safra **no** recopila, no lee ni registra nada de lo siguiente:
- Contraseñas, credenciales de cuenta de Minecraft / Microsoft o tokens de autenticación
- Información de identidad personal o de contacto (nombre, correo electrónico, número de teléfono, etc.)
- Mensajes del chat del juego, comandos o contenidos de voz del chat de voz
- Tu lista de otros mods instalados, archivos de mundos guardados (saves), datos de inventario ni eventos del juego

---

## 3. Conexiones P2P, exposición de IP y seguridad del código (Descargo de responsabilidad)

- **La transmisión de IP y puerto es técnicamente obligatoria:** Safra enruta el tráfico de la partida directamente entre los jugadores (P2P). Para que las personas con las que compartes tu código de sesión puedan conectarse a tu partida, es lógico y técnicamente indispensable que tu dirección IP y puerto se compartan con ellas.
- **Juega únicamente con amigos de confianza:** Debido a la naturaleza de las redes P2P, los compañeros a los que te conectas o a los que invitas a tu mundo pueden ver tu dirección IP. **Safra y sus desarrolladores no se hacen responsables de la exposición de IP/puerto ni de posibles ataques de red** derivados de compartir tu código con personas que no sean de tu confianza o de publicarlo abiertamente. Por favor, juega únicamente con amigos en quienes confíes.
- **Seguridad y predictibilidad del código:** Los códigos de sesión se generan mediante generadores aleatorios criptográficamente seguros con 12 caracteres (o 16 caracteres en el modo de código fijo), ofreciendo más de $32^{12}$ combinaciones posibles. A menos que compartas tu código, es teórica y prácticamente imposible que terceros adivinen o descubran tu sesión.

---

## 4. Infraestructura de terceros (Descargo de responsabilidad)

Tus datos nunca se transfieren a terceros con fines comerciales; sin embargo, el mod necesita comunicarse con los siguientes proveedores de infraestructura técnica para funcionar:
- **Cloudflare:** Utilizado para la protección contra DDoS en el servidor de señalización y para la retransmisión cifrada mediante TURN Relay cuando falla la conexión directa P2P.
- **Servidores STUN (Google, Cloudflare, Twilio):** Utilizados únicamente para que el mod descubra tu propia dirección IP pública y puerto.
- **GitHub / jsDelivr:** Utilizados al iniciar el mod para comprobar si existen actualizaciones y consultar la configuración del servidor (`config.json`).

Los registros conservados por estos servicios de terceros en sus propios sistemas y sus prácticas de privacidad están sujetos a sus respectivas políticas de privacidad; **Safra y sus desarrolladores no asumen responsabilidad alguna por las prácticas de tratamiento de datos de estos proveedores externos.**

---

## 5. Contacto

Si tienes preguntas sobre esta Política de Privacidad, puedes ponerte en contacto con nosotros en:
- **Discord:** https://discord.gg/NHjBvRxDXP
- **GitHub:** https://github.com/DeveloperKubilay/Safra
