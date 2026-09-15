const games = process.argv.slice(2);
async function post(url, body) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await r.text();
  return { status: r.status, text: text.slice(0, 500) };
}
async function start(name) {
  await fetch(`http://127.0.0.1:8091/cpgame-lab/games/${name}/stop`, { method: 'POST' }).catch(() => {});
  const r = await fetch(`http://127.0.0.1:8091/cpgame-lab/games/${name}/start`, { method: 'POST' });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 800) }; }
  return { http: r.status, json };
}
async function probe(name) {
  const started = await start(name);
  const demoUrl = started.json?.demoUrl || started.json?.raw || null;
  const out = { name, startHttp: started.http, start: started.json, demoUrl };
  if (!demoUrl || typeof demoUrl !== 'string' || !demoUrl.startsWith('http')) return out;
  const page = await fetch(demoUrl);
  out.page = { status: page.status, type: page.headers.get('content-type'), bytes: Number(page.headers.get('content-length') || 0) };
  const html = await page.text();
  out.page.hasCanvasHint = /GameCanvas|canvas|cocos|createjs/i.test(html);
  out.page.title = (html.match(/<title>([^<]+)/i) || [])[1] || null;
  const origin = new URL(demoUrl).origin;
  const gid = name.startsWith('16') ? '16' : name.startsWith('45') ? '45' : '56';
  const routes = gid === '16'
    ? ['/cp/api/v1/auth/verify', '/cp/api/v1/jungle-fruit/config', '/cp/api/v1/jungle-fruit/spin']
    : gid === '45'
      ? ['/cp/api/v1/auth/verify', '/cp/api/v1/rio-carnival/config', '/cp/api/v1/rio-carnival/spin']
      : ['/cp/api/v1/auth/verify', '/cp/api/v1/crazy-piggy/config', '/cp/api/v1/crazy-piggy/spin'];
  out.api = {};
  let token = 'local-playtest';
  for (const route of routes) {
    const body = route.endsWith('/spin')
      ? `gid=${gid}&t=${token}&bl=1&bs=0.05&bet_level=1&bet_size=0.05&request_id=playtest`
      : `gid=${gid}&t=${token}`;
    const r = await fetch(origin + route, {
      method: 'POST',
      headers: {
        'content-type': 'application/x-www-form-urlencoded',
        referer: demoUrl,
      },
      body,
    });
    const text = await r.text();
    const hit = { status: r.status, text: text.slice(0, 500) };
    out.api[route] = hit;
    try {
      const parsed = JSON.parse(hit.text);
      const t = parsed?.data?.token || parsed?.data?.t || parsed?.token;
      if (t) token = t;
    } catch {}
  }
  return out;
}
const names = games.length ? games : ['16-Jungle-Fruit', '45-Rio-Carnival', '56-Crazy-Piggy'];
const results = [];
for (const name of names) results.push(await probe(name));
console.log(JSON.stringify(results, null, 2));
