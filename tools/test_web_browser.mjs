/** Browser checks against real local Next/Nest/Postgres services and synthetic records. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createApp } from '../web/api/dist/src/app.js';
import { hashPassword } from '../web/api/dist/src/auth.js';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (!databaseUrl || !/^hellfire_(predevice|test)$/.test(new URL(databaseUrl).pathname.slice(1)))
  throw new Error('TEST_DATABASE_URL must name a disposable hellfire_predevice or hellfire_test database');
const require = createRequire(new URL('../server/whatsapp-web/package.json', import.meta.url));
const { default: puppeteer } = await import(require.resolve('puppeteer'));
const config = { databaseUrl, syncToken: 'synthetic-browser-sync-only-0123456789',
  sessionSecret: 'synthetic-browser-session-only-0123456789', ownerPasswordHash: hashPassword('synthetic browser password'),
  port: 43911, host: '127.0.0.1', quotaBytes: 1000000 };
const api = await createApp(config);
let frontend, browser;
const results = [];
try {
  await api.listen(config.port, config.host);
  const apiUrl = await api.getUrl();
  const phone = async (path, body) => {
    const response = await fetch(`${apiUrl}${path}`, { method: 'POST',
      headers: { authorization: `Bearer ${config.syncToken}`, 'content-type': 'application/json' }, body: JSON.stringify(body) });
    assert.equal(response.status, 200); return response.json();
  };
  const { dataset } = await phone('/api/sync/reset', {});
  await phone('/api/sync/batch', { dataset, kind: 'ledger', upserts: [{ id: 'browser-fixture', at: 1794992400000,
    data: { id: 'browser-fixture', accountId: 'cash', kind: 'EXPENSE', amount: -1500, category: 'Food',
      description: 'Synthetic lunch browser fixture', occurredAt: 1794992400000 } }] });
  await phone('/api/sync/finish', { dataset });
  frontend = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'start', '-H', '127.0.0.1', '-p', '43910'],
    { cwd: fileURLToPath(new URL('../web/frontend/', import.meta.url)), env: { ...process.env, API_URL: apiUrl }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  frontend.stdout.on('data', bytes => { output = (output + bytes).slice(-10000); });
  frontend.stderr.on('data', bytes => { output = (output + bytes).slice(-10000); });
  const base = 'http://127.0.0.1:43910';
  const deadline = Date.now() + 60000;
  while (true) {
    if (frontend.exitCode !== null) throw new Error(`Next exited: ${output}`);
    try { if ((await fetch(`${base}/login`)).ok) break; } catch { }
    if (Date.now() > deadline) throw new Error(`Next readiness timeout: ${output}`);
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  browser = await puppeteer.launch({ executablePath: process.env.TEST_CHROMIUM ?? '/usr/bin/chromium', headless: true,
    args: process.env.TEST_BROWSER_NO_SANDBOX === 'true' ? ['--no-sandbox'] : [] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(base, { waitUntil: 'networkidle0' });
  assert.equal(new URL(page.url()).pathname, '/login');
  results.push('Unauthenticated private routes redirect to login');
  const proxied = await fetch(`${base}/api/sync/state`, { headers: { authorization: `Bearer ${config.syncToken}` } });
  assert.equal(proxied.status, 200);
  assert.equal((await proxied.json()).dataset, dataset);
  assert.equal((await fetch(`${base}/api/sync/state`)).status, 401);
  results.push('Build-time sync proxy reaches the isolated API and preserves authentication');
  await page.type('#password', 'wrong fixture password');
  await page.click('button[type=submit]');
  await page.waitForSelector('[role=alert]');
  assert.match(await page.$eval('[role=alert]', e => e.textContent), /not right/);
  results.push('Wrong password is rejected visibly');
  await page.$eval('#password', e => { e.value = ''; });
  await page.type('#password', 'synthetic browser password');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('button[type=submit]')]);
  assert.equal(new URL(page.url()).pathname, '/');
  const cookie = (await page.cookies()).find(value => value.name === 'hf_session');
  assert.ok(cookie?.httpOnly && cookie.secure && cookie.sameSite === 'Strict');
  results.push('Login establishes a secure HttpOnly SameSite session');
  for (const route of ['/money', '/tasks', '/inbox', '/media', '/memory', '/news', '/search', '/library']) {
    const response = await page.goto(base + route, { waitUntil: 'networkidle0' });
    assert.equal(response.status(), 200, route);
    assert.equal(new URL(page.url()).pathname, route);
  }
  results.push('Eight authenticated routes render without browser exceptions');
  await page.goto(base + '/money', { waitUntil: 'networkidle0' });
  assert.match(await page.$eval('main', e => e.textContent), /Synthetic lunch browser fixture/);
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
  await page.goto(base + '/news', { waitUntil: 'networkidle0' });
  assert.ok(await page.$eval('main', e => e.getBoundingClientRect().width <= 390));
  results.push('Synthetic synced ledger is visible; mobile news content fits viewport');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('header form button')]);
  assert.equal(new URL(page.url()).pathname, '/login');
  await page.goto(base + '/money', { waitUntil: 'networkidle0' });
  assert.equal(new URL(page.url()).pathname, '/login');
  assert.deepEqual(errors, []);
  results.push('Logout removes access to private pages');
  await mkdir(new URL('../web/frontend/build/', import.meta.url), { recursive: true });
  await writeFile(new URL('../web/frontend/build/browser-results.json', import.meta.url), JSON.stringify({
    evidence: 'host-browser-tested', synthetic: true, browser: await browser.version(), checks: results, errors,
  }, null, 2) + '\n');
  console.log(JSON.stringify({ passed: results.length, checks: results }, null, 2));
} finally {
  if (browser) await browser.close();
  if (frontend) { frontend.kill('SIGTERM'); await new Promise(resolve => frontend.once('close', resolve)); }
  await api.close();
}
