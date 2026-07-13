const dgram = require('dgram');
const config = require('./config.json');

const bind = socket => new Promise(resolve => socket.bind(0, '127.0.0.1', resolve));
const receive = socket => new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('UDP cevabi gelmedi.')), 3000);
  socket.once('message', (data, rinfo) => { clearTimeout(timer); resolve({ data, rinfo }); });
});

async function run() {
  const host = dgram.createSocket('udp4');
  const attacker = dgram.createSocket('udp4');
  const client = dgram.createSocket('udp4');
  const client2 = dgram.createSocket('udp4');
  let token;
  try {
    await Promise.all([bind(host), bind(client), bind(client2), new Promise(resolve => attacker.bind(0, '127.0.0.2', resolve))]);
    const response = await fetch(`http://127.0.0.1:${config.httpPort}/create-session`, {
      method: 'POST',
      headers: { authorization: `Bearer ${config.apiToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ hostip: '127.0.0.1' })
    });
    const session = await response.json();
    if (!response.ok) throw new Error(session.error);
    token = session.token;

    attacker.send('BRLY_HOST', session.port, '127.0.0.1');
    await new Promise(resolve => setTimeout(resolve, 100));
    host.send('BRLY_HOST', session.port, '127.0.0.1');
    if ((await receive(host)).data.toString() !== 'BRLY_OK') throw new Error('Host kaydolamadi.');

    client.send('PING1', session.port, '127.0.0.1');
    const first = (await receive(host)).data;
    client2.send('PING2', session.port, '127.0.0.1');
    const second = (await receive(host)).data;
    if (first.subarray(2).toString() !== 'PING1' || second.subarray(2).toString() !== 'PING2') {
      throw new Error('Oyuncu paketleri hosta gitmedi.');
    }
    if (first.readUInt16BE(0) === second.readUInt16BE(0)) throw new Error('Oyuncular ayrilmadi.');
    host.send(Buffer.concat([first.subarray(0, 2), Buffer.from('PONG1')]), session.port, '127.0.0.1');
    host.send(Buffer.concat([second.subarray(0, 2), Buffer.from('PONG2')]), session.port, '127.0.0.1');
    if ((await receive(client)).data.toString() !== 'PONG1') throw new Error('Birinci oyuncu cevabi yanlis.');
    if ((await receive(client2)).data.toString() !== 'PONG2') throw new Error('Ikinci oyuncu cevabi yanlis.');
    console.log('Relay calisiyor.');
  } finally {
    if (token) await fetch(`http://127.0.0.1:${config.httpPort}/session/${token}`, {
      method: 'DELETE', headers: { authorization: `Bearer ${config.apiToken}` }
    }).catch(() => {});
    host.close();
    attacker.close();
    client.close();
    client2.close();
  }
}

run().catch(error => { console.error(`❌ ${error.message}`); process.exitCode = 1; });
