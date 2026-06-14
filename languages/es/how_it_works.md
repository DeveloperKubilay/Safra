# Como funciona este sistema?

Apps como Omegle y WhatsApp normalmente se comunican sin mantener un servidor en medio. Este sistema funciona con una idea parecida.

Los proveedores de internet pueden no permitir abrir puertos TCP, pero si pueden permitir puertos UDP.

En este mod, la comunicacion del servidor de Minecraft, que normalmente funciona con TCP, se transporta sobre UDP y se muestra al otro lado como si fuera TCP.

Esto te permite jugar de forma fluida con tu amigo gratis, sin que el trafico pase por un servidor relay.

Por ejemplo, en mods similares:

`Tu ordenador (Estambul) -> servidor relay (Frankfurt) -> ordenador de tu amigo (Estambul)`

En este mod:

`Tu ordenador (Estambul) -> ordenador de tu amigo (Estambul)`

No hay un servidor en medio para el trafico del juego.

La comunicacion es solo entre tu y tu amigo.

Este mod solo usa un servidor para que ambos lados puedan encontrar la direccion IP del otro.

## Seguridad

- Se usan escaneos de CodeQL
- Plataformas como CurseForge pueden escanear nuevas versiones
- Puedes ver las builds
- Las builds se suben con GitHub Actions en lugar de subirse manualmente desde mi ordenador
