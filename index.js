require("dotenv").config({ quiet: true });
const Fastify = require("fastify");
const { randomBytes } = require("node:crypto");

const app = Fastify({ logger: false });
const sessions = new Map();
const ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const RelayWaiters = [];
const SessionWaiters = [];//Zaman aşımı için koruma (ana sistemde işlevi yoktur)
const activeSessionsByIp = new Map();//Zaman aşımı için koruma (ana sistemde işlevi yoktur)
const config = require("./config.json");

function networkControl(network) {
    if (!Array.isArray(network) || network.length !== 3) return "Network must be in the format protocol:ip:port";
    const [protocol, ip, port] = network;
    if (protocol !== "ipv4" && protocol !== "ipv6") return "Invalid protocol"
    if (protocol === "ipv4" && !/^(\d{1,3}\.){3}\d{1,3}$/.test(ip)) return "Invalid IPv4 address"
    if (protocol === "ipv6" && (!ip || !ip.includes(":"))) return "Invalid IPv6 address"
    if (isNaN(port) || port < 1 || port > 65535) return "Invalid port"
    return null;
}

function codeCheck(code) {
    if (typeof code !== "string" || code.length < 8 || code.length > 16) return true;
    return null
}

async function getTurnCredentials() {
    const response = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${process.env.TURN_KEY_ID}/credentials/generate-ice-servers`,
        {
            method: "POST",
            headers: {
                "Authorization": `Bearer ${process.env.TURN_KEY_API_TOKEN}`,
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ ttl: config.TURN_TTL })
        }
    );

    if (!response.ok) return null;
    const data = (await response.json()).iceServers[1];
    data.urls = data.urls.filter(url => url.endsWith("udp"));
    return data;
}

function eventStream(res) {
    res.raw.setHeader("Content-Type", "text/event-stream");
    res.raw.setHeader("Cache-Control", "no-cache");
    res.raw.setHeader("Connection", "keep-alive");
    res.hijack();
}

const eventMessage = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

app.post("/session-create", async (req, res) => {//Voicechat ve stunipsi ile beraber yollanır
    if (activeSessionsByIp.get(req.ip) >= config.MAX_ACTIVE_SESSIONS_PER_IP) return res.code(429).send("Too many active sessions from your IP");
    if (req.body.network != null) {
        const networkValidation = networkControl(req.body.network);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    if (req.body.hasOwnProperty("voicechat")) {
        const networkValidation = networkControl(req.body.voicechat);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    if (codeCheck(req.body.code) || sessions.has(req.body.code)) {
        for (let i = 0; i < 10; i++) {//Eğer sabit kod seçili değilse code bilgisi iletilmicek
            const code = [...randomBytes(12)].map(b => ABC[b % ABC.length]).join("");
            if (!sessions.has(code)) {
                req.body.code = code;
                break;
            }
        }
        if (!req.body.code) return res.code(500).send("Failed to generate unique session code");
    }
    const state = { alive: true, ip: req.ip };

    sessions.set(req.body.code,
        {
            host: req.body.network,
            voiceHost: req.body.voicechat || null,
            relay: null,
            relayWaiters: [],
            write: (data) => res.raw.write(data)
        });
    activeSessionsByIp.set(req.ip, (activeSessionsByIp.get(req.ip) || 0) + 1);

    SessionWaiters.push({
        time: Date.now() + config.MAX_SESSION_TIME,
        end: (x) => {
            if (x) state.sessionipsetted = true;
            res?.raw.end()
        },
        state
    });

    eventStream(res);
    res.raw.write(eventMessage("session-created", { code: req.body.code, relayRequired: req.body.network == null }));
    res.raw.on("close", () => {
        state.alive = false;
        sessions.delete(req.body.code);
        if (!state.sessionipsetted) activeSessionsByIp.set(req.ip, (activeSessionsByIp.get(req.ip) || 1) - 1);
    });
})

app.post("/voicechat-update", async (req, res) => {
    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    const networkValidation = networkControl(req.body.voicechat);
    if (networkValidation) return res.code(400).send(networkValidation);
    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");
    session.write(eventMessage("voicechat-updated", { voiceHost: req.body.voicechat }));
    res.send({ ok: true });
});

app.post("/session-join", async (req, res) => {
    const networkValidation = networkControl(req.body.network);
    if (networkValidation) return res.code(400).send(networkValidation);

    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");

    session.write(eventMessage("session-joined", {//Hosta joinerin datası iletilir
        host: req.body.network,
    }));

    res.send({//Joinere hostun datası iletilir
        host: session.host,
        relay: session.relay,
        voiceHost: session.voiceHost
    });
});

app.post("/relay-request", async (req, res) => {
    if (activeSessionsByIp.get(req.ip) >= config.MAX_ACTIVE_SESSIONS_PER_IP) return res.code(429).send("Too many active sessions from your IP");
    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");
    if (session.relay) return res.code(409).send("Relay already assigned for this session");
    const turnCredentials = await getTurnCredentials();
    session.write(eventMessage("relay-assigned", turnCredentials));
    session.relay = turnCredentials;
    eventStream(res);
    const state = { alive: true, ip: req.ip };
    function writeandClose(data) {
        state.alive = false;
        res.raw.write(data);
        session.relayWaiters.splice(session.relayWaiters.indexOf(writeandClose), 1);
        activeSessionsByIp.set(req.ip, (activeSessionsByIp.get(req.ip) || 1) - 1);
        res.raw.end();
    }
    RelayWaiters.push({ time: Date.now() + 20000, write: writeandClose, state });//20sn ttl
    activeSessionsByIp.set(req.ip, (activeSessionsByIp.get(req.ip) || 0) + 1);
    session.relayWaiters.push(writeandClose);
});

app.post("/relay-accept", async (req, res) => {
    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    if (req.body.network != null) {
        const networkValidation = networkControl(req.body.network);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");//server dicek açtım ben yeni ip bu
    session.relay.network = req.body.network;
    session.relayWaiters.forEach(write => write(eventMessage("relay-accepted", session)));
    session.relayWaiters.length = 0;
    res.send({ ok: true });
});

setInterval(() => {//gc
    const now = Date.now();
    for (let i = RelayWaiters.length - 1; i >= 0; i--) {
        const waiter = RelayWaiters[i];
        if (waiter.state.alive === false || waiter.time < now) {
            if (waiter.state.alive != false) waiter.write(eventMessage("relay-timeout", { message: "Relay request timed out" }));
            RelayWaiters.splice(i, 1);
        }
    }
    for (let i = SessionWaiters.length - 1; i >= 0; i--) {
        const waiter = SessionWaiters[i];
        if (waiter.state.alive === false || waiter.time < now) {
            if (waiter.state.alive !== false) {
                waiter.end(true);
                activeSessionsByIp.set(waiter.state.ip, (activeSessionsByIp.get(waiter.state.ip) || 1) - 1);
            }
            SessionWaiters.splice(i, 1);
        }
    }
    activeSessionsByIp.forEach((count, ip) => {
        if (count <= 0) {
            activeSessionsByIp.delete(ip);
        }
    });
    const turnAssigned = [...sessions.values()].filter(s => s.relay).length;
    console.log(`[${new Date().toISOString()}] Waiters: ${RelayWaiters.length}, Active sessions: ${SessionWaiters.length}, Turn assigned: ${turnAssigned}`);
}, 10 * 1000)

app.listen({ port: process.env.PORT || 3000 }, (err, address) => {
    if (err) console.error(err);
    console.log(`Server is running at ${address}`);
});