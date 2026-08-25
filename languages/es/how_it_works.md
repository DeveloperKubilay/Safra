# ¿Cómo funciona este sistema?

Aplicaciones como WhatsApp (durante llamadas), Omegle o BitTorrent utilizan tecnología **P2P (Peer-to-Peer / Punto a Punto)** en lugar de enrutar todo el tráfico a través de un servidor central. Pasar los datos de juego de millones de jugadores por servidores centrales generaría un alto retraso (lag) y costos gigantescos de infraestructura.

### ¿Cómo funciona exactamente el sistema?

1. Normalmente, las conexiones de internet domésticas no tienen puertos públicos abiertos (debido al agotamiento de direcciones IPv4 y al uso de CGNAT por parte de los proveedores, no es posible abrir puertos directamente desde el exterior).
2. Sin embargo, cuando visitas un sitio web o realizas una solicitud saliente, tu router/proveedor abre temporalmente un puerto de salida hacia el exterior.
3. En este sistema, el mod se conecta como si fuera a entrar a un sitio web como Google, pero sin enviar mensajes; simplemente toma ese puerto temporal que se abrió y se lo entrega a tu amigo, a la vez que te da el puerto de tu amigo. Es prácticamente como decir *"voy a entrar a un sitio web"* y usar ese puerto abierto para conectarte directamente con tu amigo.
4. Una vez establecida la conexión, el tráfico del juego fluye **sin ningún servidor intermediario**, directamente entre tu ordenador y el de tu amigo (P2P).

---

### Diferencia de Conexión

Por ejemplo, en mods similares:

`Tu ordenador (Estambul) -> servidor relay (Frankfurt) -> ordenador de tu amigo (Estambul)`

En este mod:

`Tu ordenador (Estambul) -> ordenador de tu amigo (Estambul)`

No hay ningún servidor relay de por medio; la comunicación es directamente entre tú y tu amigo.

## Seguridad

- Se usan escaneos de CodeQL
- Plataformas como CurseForge pueden escanear nuevas versiones
- Puedes ver las builds
- Las builds se suben con GitHub Actions en lugar de subirse manualmente desde mi ordenador
