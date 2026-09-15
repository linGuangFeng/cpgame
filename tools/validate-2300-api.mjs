import { writeFile } from 'node:fs/promises';

const base = 'http://127.0.0.1:52300';
const post = async (path, fields) => {
  const response = await fetch(base + path, {
    method: 'POST',
    headers: {'content-type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams(fields)
  });
  const body = await response.json();
  if (response.status !== 200 || body.code !== 0) throw new Error(`${path}: HTTP ${response.status}, code ${body.code}, ${body.msg}`);
  return body.data;
};

const token = 'api-final-validation';
const common = {gid: '2300', token, bet: '0.2', level: '10'};
const initial = await post('/cp/config/initialData', {...common, language: 'en'});
const user = await post('/cp/account/getUserInfo', common);
const room = await post('/cp/single_game.Game/initRoom', common);

const validateStep = data => {
  if (data.res.ps.length !== 15) throw new Error('res.ps length');
  for (const award of data.res.wa) {
    const symbol = data.res.ps[award.ws[0]];
    if (symbol < 1 || symbol > 10) throw new Error(`wa.ws[0] is not a natural paying symbol: ${symbol}`);
  }
};

const finishRound = async type => {
  const steps = [];
  let data = await post('/cp/single_game.Game/gameResult', {...common, type: String(type)});
  while (true) {
    validateStep(data);
    steps.push(data);
    if (data.f.nt === 0) break;
    if (steps.length >= 30) throw new Error('feature did not terminate');
    data = await post('/cp/single_game.Game/gameResult', {...common, type: '2'});
  }
  return steps;
};

const naturalRounds = [];
for (let i = 0; i < 10; i++) naturalRounds.push(await finishRound(1));
const directFeatureRounds = [];
const featureModeCounts = {'1': 0, '2': 0};
for (let i = 0; i < 12 && (featureModeCounts['1'] < 2 || featureModeCounts['2'] < 2); i++) {
  const round = await finishRound(3);
  directFeatureRounds.push(round);
  const mode = String(round[0].f.gt);
  if (mode in featureModeCounts) featureModeCounts[mode]++;
}
const afterFeatureResume = await finishRound(1);
const history = await post('/cp/goldgame/single_game_user_history', {...common, page_size: '100'});
const historySummary = await post('/cp/goldgame/single_game_user_gold_history', common);
const balance = await fetch(`${base}/api/balance?gid=2300&token=${token}`).then(r => r.json());
const session = await fetch(`${base}/api/session?gid=2300&token=${token}`).then(r => r.json());

const languages = {
  bn:'bn-bd', en:'en-us', es:'es-es', fr:'fr-fr', hi:'hi-in', id:'id-id',
  'in-marathi':'in-marathi', 'in-telugu':'in-telugu', ko:'ko-ko', 'pt-br':'pt-pt',
  ru:'ru-ru', th:'th-th', tr:'tr-tr', vi:'vi-vn', zh:'zh-cn', 'zh-hk':'zh-hk'
};
const languageRows = [];
for (const [input, expected] of Object.entries(languages)) {
  const data = await post('/cp/config/initialData', {...common, token: `lang-${input}`, language: input});
  languageRows.push({input, expected, actual: data.language, pass: data.language === expected});
}

const staticPaths = ['/','/versionconfig.js','/assets/main/index.93d76.js','/assets/internal/index.15125.js','/src/settings.24db9.json'];
const staticChecks = [];
for (const path of staticPaths) {
  const response = await fetch(base + path, {method: 'HEAD', redirect: 'follow'});
  staticChecks.push({path, status: response.status, pass: response.status === 200});
}

const specialNatural = naturalRounds.filter(round => round.length > 1);
const report = {
  schemaVersion: 1,
  gameId: 2300,
  rulesHash: session.data.rulesHash,
  entryConfigUserRoom: {initial: !!initial.game_info, user: user.gid === 2300, roomBoardLength: room.res.ps.length},
  naturalPaidRounds: naturalRounds.length,
  naturalSpecialRounds: specialNatural.length,
  naturalAllTerminal: naturalRounds.every(round => round.at(-1).f.nt === 0),
  naturalSpecialStepCounts: specialNatural.map(round => round.length),
  directFeatureRounds: directFeatureRounds.length,
  featureModeCounts,
  eachObservedFeatureModePlayedTwice: featureModeCounts['1'] >= 2 && featureModeCounts['2'] >= 2,
  directFeatureAllTerminal: directFeatureRounds.every(round => round.at(-1).f.nt === 0),
  afterFeatureResumeTerminal: afterFeatureResume.at(-1).f.nt === 0,
  historyRows: history.list.length,
  historySpecialRows: history.list.filter(row => row.results.length > 1).length,
  historyTotals: history.statistics,
  historySummary: historySummary.statistics,
  balance: balance.data,
  session: session.data,
  staticChecks,
  runtimeFixtureUse: session.data.runtimeFixtureUse,
  pass: specialNatural.length === 2 && naturalRounds.every(round => round.at(-1).f.nt === 0) && featureModeCounts['1'] >= 2 && featureModeCounts['2'] >= 2 && directFeatureRounds.every(round => round.at(-1).f.nt === 0) && staticChecks.every(row => row.pass) && session.data.runtimeFixtureUse === false,
  completedAt: new Date().toISOString()
};
await writeFile('reports/2300-Monster-Slayer/controller-api-validation.json', JSON.stringify(report, null, 2));
await writeFile('reports/2300-Monster-Slayer/language-validation.json', JSON.stringify({schemaVersion:1,gameId:2300,rows:languageRows,pass:languageRows.every(row=>row.pass),completedAt:new Date().toISOString()}, null, 2));
process.stdout.write(JSON.stringify(report, null, 2));
