# How does this system work?

Apps like Omegle and WhatsApp usually communicate without keeping a server in the middle. This works with a similar idea.

Internet providers may not allow opening TCP ports, but they may allow UDP ports.

In this mod, the communication of the Minecraft server, which normally works with TCP, is carried over UDP and shown to the other side like TCP.

This lets you play smoothly with your friend for free, without traffic going through a relay server.

For example, in similar mods:

`Your computer (Istanbul) -> relay server (Frankfurt) -> your friend's computer (Istanbul)`

In this mod:

`Your computer (Istanbul) -> your friend's computer (Istanbul)`

There is no server in the middle for the game traffic.

The communication is only between you and your friend.

This mod only uses a server so both sides can find each other's IP address.

## Security

- CodeQL scans are used
- Platforms like CurseForge may scan new versions
- You can see the builds
- Builds are uploaded with GitHub Actions instead of being uploaded manually from my computer
