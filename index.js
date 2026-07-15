require("dotenv").config({ quiet: true });
const Fastify = require("fastify");
const elenora = require('elenora');
const { randomBytes } = require("node:crypto");
const config = require("./config.json");

const app = Fastify({
    logger: false,
    trustProxy: true,
    connectionTimeout: 120000,
    requestTimeout: config.TCP_CONNECTION_TIMEOUT
});
const sessions = new Map();
const ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const RelayWaiters = [];
const SessionWaiters = [];//Zaman aşımı için koruma (ana sistemde işlevi yoktur)
const activeSessionsByIp = new Map();//Zaman aşımı için koruma (ana sistemde işlevi yoktur)
let nextSseKeepAlive = Date.now() + config.SSE_KEEPALIVE_INTERVAL;
let bedrockLatestServer = 0;

function changeActiveSessionCount(ip, delta) {
    const count = Math.max(0, (activeSessionsByIp.get(ip) || 0) + delta);
    if (count === 0) activeSessionsByIp.delete(ip);
    else activeSessionsByIp.set(ip, count);
}

elenora.connect(console, {
    filename: 'logs/app.log',
    maxSize: 5 * 1024 * 1024, // 5 MB
    backupCount: 3,
    continueFromLast: false,
    interval: 10000,
    timestamp: false
});

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
    try {
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
        return data;
    } catch {
        return null;
    }
}

function eventStream(res) {
    res.raw.setHeader("Content-Type", "text/event-stream");
    res.raw.setHeader("Cache-Control", "no-cache");
    res.raw.setHeader("Connection", "keep-alive");
    res.hijack();
    res.raw.flushHeaders();
}


const eventMessage = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

app.post("/session-create", async (req, res) => {//Voicechat ve stunipsi ile beraber yollanır
    req.ip = req.headers["cf-connecting-ip"] || req.ip;
    if (req.body.network != null) {
        const networkValidation = networkControl(req.body.network);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    if (req.body.hasOwnProperty("voicechat")) {
        const networkValidation = networkControl(req.body.voicechat);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    const oldSession = sessions.get(req.body.code);
    if (oldSession) {
        if (oldSession.ip === req.ip) oldSession.end(true);
        else req.body.code = null;
    }

    if (activeSessionsByIp.get(req.ip) >= config.MAX_ACTIVE_SESSIONS_PER_IP) {
        return res.code(429).send("Too many active sessions from your IP");
    }

    if (codeCheck(req.body.code)) {
        let generatedCode = null;
        for (let i = 0; i < 10; i++) {//Eğer sabit kod seçili değilse code bilgisi iletilmicek
            const bytes = randomBytes(12);
            let code = "";

            for (let i = 0; i < bytes.length; i++) {
                code += ABC[bytes[i] % ABC.length];
            }
            if (!sessions.has(code)) {
                generatedCode = code;
                break;
            }
        }
        if (!generatedCode) return res.code(500).send("Failed to generate unique session code");
        req.body.code = generatedCode;
    }

    const sessionCode = req.body.code;
    const state = { alive: true, ip: req.ip };

    const session = {
        ip: req.ip,
        host: req.body.network,
        voiceHost: req.body.voicechat || null,
        relay: null,
        relayWaiters: [],
        write: res.raw.write.bind(res.raw),
        end: endSession
    };
    sessions.set(sessionCode, session);
    changeActiveSessionCount(req.ip, 1);

    function endSession(disconnect = false) {
        if (state.alive === false) return;
        console.slientlog(`[${new Date().toISOString()}] Session closed from IP: ${req.ip} with code: ${sessionCode}`);
        state.alive = false;//SessionWaiters silinmesi için alive false yapıyoz
        sessions.delete(sessionCode);
        changeActiveSessionCount(req.ip, -1);
        if (disconnect) res?.raw.end();
    }

    if (config.MAX_SESSION_TIME > 0) SessionWaiters.push({
        time: Date.now() + config.MAX_SESSION_TIME,
        end: endSession,
        state
    });

    console.slientlog(`[${new Date().toISOString()}] Session create request from IP: ${req.ip} with code: ${sessionCode}`);

    eventStream(res);
    res.raw.write(eventMessage("session-created", { code: sessionCode, relayRequired: req.body.network == null }));
    res.raw.once("close", endSession);
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

if (config.BEDROCK_SERVERS && config.BEDROCK_SERVERS.length > 0) {
    app.post("/bedrock-request", async (req, res) => {
        if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
        const session = sessions.get(req.body.code);
        if (!session) return res.code(404).send("Session not found");
        if (session.bedrockServer) return res.code(409).send("Bedrock server already assigned for this session");
        req.ip = req.headers["cf-connecting-ip"] || req.ip;
        if (req.ip !== session.ip) return res.code(403).send("Only host can request Bedrock relay");

        const server = config.BEDROCK_SERVERS[bedrockLatestServer % config.BEDROCK_SERVERS.length];
        bedrockLatestServer++;

        try {
            const hostip = session.host?.[0] === 'ipv4' ? session.host[1] : req.ip;
            const response = await fetch(`${server}/create-session`, {
                method: 'POST',
                headers: {
                    authorization: `Bearer ${process.env.BEDROCK_PASSWORD}`,
                    'content-type': 'application/json'
                },
                body: JSON.stringify({ hostip })
            });
            const data = await response.json();
            if (!response.ok) {
                console.slientlog(`[${new Date().toISOString()}] bedrock-request failed for session code: ${data.error}`);
                return res.send({ ok: false });
            }

            session.bedrockServer = server;
            return res.send({ ok: true, bedrockServer: data.ip, bedrockPort: data.port });
        } catch (error) {
            console.slientlog(`[${new Date().toISOString()}] bedrock connection failed ${error.message}`);
            return res.send({ ok: false });
        }
    });
} else app.post("/bedrock-request", (req, res) => {
    return res.send({ ok: false });
});

app.post("/session-join", async (req, res) => {
    const networkValidation = networkControl(req.body.network);
    if (networkValidation) return res.code(400).send(networkValidation);
    req.ip = req.headers["cf-connecting-ip"] || req.ip;

    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");
    console.slientlog(`[${new Date().toISOString()}] Session join request from IP: ${req.ip} with code: ${req.body.code}`);

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
    req.ip = req.headers["cf-connecting-ip"] || req.ip;
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
        if (state.alive === false) return;
        state.alive = false;
        if (!res.raw.destroyed && !res.raw.writableEnded && data) res.raw.write(data);
        const index = session.relayWaiters.indexOf(writeandClose);
        if (index !== -1) session.relayWaiters.splice(index, 1);
        changeActiveSessionCount(req.ip, -1);
        res.raw.end();
    }
    RelayWaiters.push({ time: Date.now() + 20000, write: writeandClose, state });//20sn ttl
    changeActiveSessionCount(req.ip, 1);
    session.relayWaiters.push(writeandClose);
    res.raw.once("close", () => writeandClose(""));
});

app.post("/relay-accept", async (req, res) => {
    if (codeCheck(req.body.code)) return res.code(400).send("Invalid session code");
    if (req.body.network != null) {
        const networkValidation = networkControl(req.body.network);
        if (networkValidation) return res.code(400).send(networkValidation);
    }

    const session = sessions.get(req.body.code);
    if (!session) return res.code(404).send("Session not found");//server dicek açtım ben yeni ip bu
    if (!session.relay) return res.code(409).send("Relay has not been requested");
    session.relay.network = req.body.network;
    session.relayWaiters.slice().forEach(write => write(eventMessage("relay-accepted", session)));
    session.relayWaiters.length = 0;
    res.send({ ok: true });
});

setInterval(() => {//gc
    const now = Date.now();
    if (bedrockLatestServer > 1000000) bedrockLatestServer = 0;

    if (config.SSE_KEEPALIVE_INTERVAL > 0 && now >= nextSseKeepAlive) {
        sessions.forEach(session => session.write(": keepalive\n\n"));
        nextSseKeepAlive = now + config.SSE_KEEPALIVE_INTERVAL;
    }

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
            if (waiter.state.alive !== false) waiter.end(true);
            SessionWaiters.splice(i, 1);
        }
    }
    activeSessionsByIp.forEach((count, ip) => {
        if (count <= 0) {
            activeSessionsByIp.delete(ip);
        }
    });

    console.log(`[${new Date().toISOString()}] Waiters: ${RelayWaiters.length}, Active sessions: ${SessionWaiters.length}`);
}, 10 * 1000)

app.listen({ host: '0.0.0.0', port: process.env.PORT || 3000 }, (err, address) => {
    if (err) console.error(err);
    console.log(`Server is running at ${address}`);
});