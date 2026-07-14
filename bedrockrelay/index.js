const http = require('http');
const dgram = require('dgram');
const crypto = require('crypto');
const net = require('net');
const elenora = require('elenora');
const config = require('./config.json');

elenora.connect(console, {
  filename: 'logs/app.log',
  maxSize: 5 * 1024 * 1024,
  backupCount: 3,
  continueFromLast: false,
  interval: 10000,
  timestamp: false
});

const sessions = new Map();
const usedPorts = new Set();
const ipSessions = new Map();
let globalBytes = 0, globalWindow = Date.now();
const HOST_HELLO = Buffer.from('BRLY_HOST');
const HOST_OK = Buffer.from('BRLY_OK');
const cleanIp = ip => ip?.replace(/^::ffff:/, '');
const key = rinfo => `${cleanIp(rinfo.address)}:${rinfo.port}`;
const createUdpSocket = () => dgram.createSocket({ type: config.udpType || 'udp4', ipv6Only: false });
const log = (event, message) => console.log(`[${new Date().toISOString()}] [${event}] ${message}`);
const formatBytes = bytes => bytes < 1024 ? `${bytes} B` : bytes < 1048576
  ? `${(bytes / 1024).toFixed(1)} KiB`
  : `${(bytes / 1048576).toFixed(1)} MiB`;
const sendJson = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
};

function allowTraffic(session, bytes) {
  const now = Date.now();
  if (now - session.window >= 1000) session.window = now, session.windowBytes = 0;
  if (now - globalWindow >= 1000) globalWindow = now, globalBytes = 0;
  if (session.totalBytes + bytes > config.sessionTotalBytes) return closeSession(session.token, 'traffic-limit') && false;
  if (session.windowBytes + bytes > config.sessionBandwidthBytesPerSecond ||
      globalBytes + bytes > config.totalBandwidthBytesPerSecond) return false;
  session.windowBytes += bytes;
  session.totalBytes += bytes;
  globalBytes += bytes;
  return true;
}

function closeSession(token, reason = 'requested') {
  const session = sessions.get(token);
  if (!session) return false;
  sessions.delete(token);
  usedPorts.delete(session.port);
  const ipSessionCount = Math.max(0, (ipSessions.get(session.hostip) || 1) - 1);
  if (ipSessionCount === 0) ipSessions.delete(session.hostip);
  else ipSessions.set(session.hostip, ipSessionCount);
  session.socket.close();
  const uptime = Math.round((Date.now() - session.createdAt) / 1000);
  log(`SESSION:${session.port}`, `closed reason=${reason} uptime=${uptime}s peakPlayers=${session.peakPlayers} traffic=${formatBytes(session.totalBytes)} active=${sessions.size}`);
  return true;
}

function removePlayer(session, playerKey, reason = 'idle-timeout') {
  const player = session.players.get(playerKey);
  if (!player) return;
  session.players.delete(playerKey);
  session.playersById.delete(player.id);
  log(`PLAYER:${session.port}#${player.id}`, `left reason=${reason} players=${session.players.size}/${config.maxPlayersPerSession}`);
}

function createSession(res, hostip) {
  hostip = cleanIp(hostip);
  if (net.isIP(hostip) === 0) return sendJson(res, 400, { error: 'Gecerli hostip gerekli.' });
  if ((ipSessions.get(hostip) || 0) >= config.maxSessionsPerIp) {
    return sendJson(res, 429, { error: 'Bu IP session limitine ulasti.' });
  }

  let port;
  for (let i = config.udpPortStart; i <= config.udpPortEnd; i++) {
    if (!usedPorts.has(i)) { port = i; break; }
  }
  if (!port) return sendJson(res, 503, { error: 'Bos relay portu yok.' });

  const token = crypto.randomBytes(24).toString('hex');
  const socket = createUdpSocket();
  const now = Date.now();
  const session = {
    token, socket, port, hostip, host: null, players: new Map(), playersById: new Map(), nextPlayerId: 1,
    createdAt: now, window: now, windowBytes: 0, totalBytes: 0, peakPlayers: 0,
    hostExpiresAt: now + config.hostTimeoutSeconds * 1000,
    expiresAt: now + config.sessionTimeoutSeconds * 1000,
    maxExpiresAt: now + config.sessionMaxLifetimeSeconds * 1000
  };
  usedPorts.add(port);
  ipSessions.set(hostip, (ipSessions.get(hostip) || 0) + 1);

  socket.on('message', (data, rinfo) => {
    const senderKey = key(rinfo);
    if (!session.host) {
      if (cleanIp(rinfo.address) !== session.hostip || !data.equals(HOST_HELLO)) return;
      session.host = rinfo;
      session.hostExpiresAt = Date.now() + config.hostTimeoutSeconds * 1000;
      session.expiresAt = Date.now() + config.sessionTimeoutSeconds * 1000;
      socket.send(HOST_OK, rinfo.port, rinfo.address);
      log(`HOST:${port}`, `registered endpoint=${key(rinfo)}`);
      return;
    }

    if (data.equals(HOST_HELLO) && cleanIp(rinfo.address) === session.hostip) {
      const previousHost = key(session.host);
      session.host = rinfo;
      session.hostExpiresAt = Date.now() + config.hostTimeoutSeconds * 1000;
      session.expiresAt = Date.now() + config.sessionTimeoutSeconds * 1000;
      socket.send(HOST_OK, rinfo.port, rinfo.address);
      if (previousHost !== senderKey) log(`HOST:${port}`, `endpoint changed ${previousHost} -> ${senderKey}`);
      return;
    }

    if (senderKey === key(session.host)) {
      if (data.length < 3) return;
      const player = session.playersById.get(data.readUInt16BE(0));
      if (!player || !allowTraffic(session, data.length * 2 - 2)) return;
      player.lastSeen = Date.now();
      session.hostExpiresAt = Date.now() + config.hostTimeoutSeconds * 1000;
      session.expiresAt = Date.now() + config.sessionTimeoutSeconds * 1000;
      socket.send(data.subarray(2), player.port, player.address);
      return;
    }

    let player = session.players.get(senderKey);
    if (!player && session.players.size >= config.maxPlayersPerSession) return;
    if (!player) {
      let id = session.nextPlayerId;
      while (session.playersById.has(id)) id = id === 65535 ? 1 : id + 1;
      session.nextPlayerId = id === 65535 ? 1 : id + 1;
      player = { id, address: rinfo.address, port: rinfo.port, lastSeen: Date.now() };
      session.players.set(senderKey, player);
      session.playersById.set(id, player);
      session.peakPlayers = Math.max(session.peakPlayers, session.players.size);
      log(`PLAYER:${port}#${id}`, `joined endpoint=${senderKey} players=${session.players.size}/${config.maxPlayersPerSession}`);
    }
    const frame = Buffer.allocUnsafe(data.length + 2);
    frame.writeUInt16BE(player.id, 0);
    data.copy(frame, 2);
    if (!allowTraffic(session, data.length + frame.length)) return;
    socket.send(frame, session.host.port, session.host.address);
    player.lastSeen = Date.now();
    session.expiresAt = Date.now() + config.sessionTimeoutSeconds * 1000;
  });
  socket.once('error', error => {
    log(`ERROR:${port}`, `UDP ${error.code || 'error'}: ${error.message}`);
    if (!closeSession(token, 'udp-error')) {
      usedPorts.delete(port);
      ipSessions.set(hostip, Math.max(0, (ipSessions.get(hostip) || 1) - 1));
    }
    if (!res.headersSent) sendJson(res, 503, { error: 'UDP portu acilamadi.' });
  });
  socket.bind(port, config.udpHost, () => {
    sessions.set(token, session);
    log(`SESSION:${port}`, `created host=${hostip} active=${sessions.size}`);
    sendJson(res, 201, { token, ip: config.publicHost, port });
  });
}

const server = http.createServer((req, res) => {
  if (req.headers.authorization !== `Bearer ${config.apiToken}`) {
    return sendJson(res, 401, { error: 'Gecersiz API token.' });
  }
  if (req.method === 'POST' && req.url === '/create-session') {
    let body = '';
    req.on('data', chunk => { if ((body += chunk).length > 1000) req.destroy(); });
    req.on('end', () => {
      try { createSession(res, JSON.parse(body).hostip); }
      catch { sendJson(res, 400, { error: 'Gecersiz JSON.' }); }
    });
    return;
  }
  if (req.method === 'DELETE' && req.url.startsWith('/session/')) {
    const ok = closeSession(req.url.slice(9), 'api-delete');
    return sendJson(res, ok ? 200 : 404, { ok });
  }
  if (req.method === 'GET' && req.url === '/status') {
    const players = [...sessions.values()].reduce((sum, session) => sum + session.players.size, 0);
    return sendJson(res, 200, { sessions: sessions.size, players, ports: [...usedPorts], globalBytes });
  }
  sendJson(res, 404, { error: 'Endpoint bulunamadi.' });
});

setInterval(() => {
  const now = Date.now();
  for (const [token, session] of sessions) {
    for (const [playerKey, player] of session.players) {
      if (now - player.lastSeen >= config.playerTimeoutSeconds * 1000) removePlayer(session, playerKey);
    }
    if (now >= session.maxExpiresAt) closeSession(token, 'max-lifetime');
    else if (now >= session.hostExpiresAt) closeSession(token, 'host-timeout');
    else if (now >= session.expiresAt) closeSession(token, 'session-timeout');
  }
}, 10_000).unref();

server.listen(config.httpPort, config.httpHost, () => {
  log('READY', `HTTP ${config.httpHost}:${config.httpPort} | UDP ${config.udpHost}:${config.udpPortStart}-${config.udpPortEnd} | public=${config.publicHost}`);
});
