const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

const site = path.resolve(__dirname, '../../main/resources/site');
const names = [
  [0, '豌豆射手', 'Peashooter'], [1, '向日葵', 'Sunflower'],
  [2, '樱桃炸弹', 'Cherry Bomb'], [3, '坚果', 'Wall-nut'],
  [4, '土豆地雷', 'Potato Mine'], [5, '寒冰射手', 'Snow Pea'],
  [62, '普通僵尸', 'Zombie'], [63, '旗帜僵尸', 'Flag Zombie'],
  [64, '路障僵尸', 'Conehead Zombie'], [65, '撑杆僵尸', 'Pole Vaulting Zombie'],
  [66, '铁桶僵尸', 'Buckethead Zombie'], [91, '召唤墓碑', 'Summoning Gravestone'],
  [9, '阳光菇', 'Sun-shroom'], [61, '墓碑', 'Gravestone']
].map(([seed_type, zh, en]) => ({ seed_type, zh, en }));
let browser;
before(async () => {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined });
});
after(async () => { await browser?.close(); });

async function dashboard(t, { delayDecks = false, mixedProducers = false, emptyMap = 'FOG', initialMode = null, deckCount = 2, cardCount = 1 } = {}) {
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  const errors = [];
  const requestedMaps = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => {
    const url = new URL(request.url());
    if (['/api/top-decks', '/api/card-stats'].includes(url.pathname)) requestedMaps.push(url.searchParams.get('mode'));
  });
  t.after(async () => { await page.close(); assert.deepEqual(errors, []); });
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  if (initialMode !== null) await page.addInitScript(mode => localStorage.setItem('pvz_mode', mode), initialMode);
  await page.addInitScript(() => {
    const original = CanvasRenderingContext2D.prototype.fillText;
    CanvasRenderingContext2D.prototype.fillText = function (text, ...args) {
      if (this.canvas.id === 'pie') {
        if (/^[\d,]+$/.test(String(text))) this.canvas.dataset.drawnTotal = String(text);
        else this.canvas.dataset.drawnLabel = text;
      }
      return original.call(this, text, ...args);
    };
    window.EventSource = class extends EventTarget {
      constructor() { super(); window.testEvents = this; }
    };
  });
  await page.route('http://dashboard.test/**', async route => {
    const url = new URL(route.request().url());
    let payload;
    if (url.pathname === '/api/seed-names') payload = { items: names };
    else if (url.pathname === '/api/versions') payload = { items: [3215, 3214] };
    else if (url.pathname === '/api/top-decks') {
      if (delayDecks) await gate;
      const zombie = url.searchParams.get('side') === 'ZOMBIE';
      const night = ['NIGHT', 'FOG'].includes(url.searchParams.get('mode'));
      const ids = zombie ? [61, 62, 63, 64, 65, 91] : mixedProducers ? [0, 1, 9, 2, 3, 5] : [0, night ? 9 : 1, 2, 3, 4, 5];
      if (url.searchParams.get('extra_packet') === 'false') ids.pop();
      const plays = url.searchParams.get('netplay_version') === '3215' ? [9, 5] : [42, 72];
      const counts = Array.from({ length: deckCount }, (_, index) => plays[index % plays.length]);
      payload = { total_plays: 120, items: url.searchParams.get('mode') === emptyMap ? [] : counts.map((count, index) => ({
        deck_ids: (index ? ids.slice().reverse() : ids).join('|'),
        core_zh: zombie ? '路障僵尸铁桶僵尸' : '寒冰射手坚果',
        core_en: zombie ? 'Conehead Zombie + Buckethead Zombie' : 'Snow Pea + Wall-nut',
        plays: count, wins: Math.round(count * .45), win_rate: Math.round(count * .45) / count
      })) };
    } else if (url.pathname === '/api/card-stats') payload = { items: Array.from({ length: cardCount }, () => ({ seed_type: 5, picked: 6, banned: 11, won: 5, win_rate: 5 / 6, pick_rate: .05, ban_rate: 11 / 120 })) };
    else if (url.pathname === '/api/recent-matches') payload = { items: [] };
    if (payload) return route.fulfill({ json: payload });
    const file = { '/data/': 'data.html', '/site/data.js': 'data.js', '/site/data.css': 'data.css', '/site/common.css': 'common.css' }[url.pathname];
    if (file) return route.fulfill({ body: await fs.readFile(path.join(site, file)), contentType: file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html' });
    if (/^\/assets\/cards\/SEED_PACKET_\d+(?:_MOUND_LEVEL_0)?\.webp$/.test(url.pathname)) {
      return route.fulfill({ body: await fs.readFile(path.join(site, '..', url.pathname.slice(1))), contentType: 'image/webp' });
    }
    return route.fulfill({ status: 204 });
  });
  await page.goto('http://dashboard.test/data/');
  return { page, release, requestedMaps };
}

async function waitForDecks(page, total) {
  await page.waitForFunction(expected => {
    const canvas = document.querySelector('#pie');
    return !canvas.hidden && canvas.dataset.drawnTotal === String(expected);
  }, total);
  assert(await page.locator('#pie').isVisible());
  // Check actual painted sectors as well as the numeric center, without a resize or theme click.
  assert(await page.locator('#pie').evaluate(canvas => {
    const { width, height } = canvas;
    return canvas.getContext('2d').getImageData(Math.floor(width / 2), Math.floor(height * .12), 1, 1).data[3] > 0;
  }));
}

test('delayed first response paints the chart without resizing', { timeout: 15000 }, async t => {
  const { page, release } = await dashboard(t, { delayDecks: true });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  release();
  await waitForDecks(page, 114);
  assert.equal(await page.locator('#pie').getAttribute('data-drawn-label'), 'Top 10 对局');
  assert.equal(await page.locator('.metric-row').count(), 0);
});

test('ranked decks show card art without seed 0', { timeout: 15000 }, async t => {
  const { page } = await dashboard(t);
  await page.waitForSelector('#deckList .deck-row');
  assert.deepEqual(await page.locator('.deck-row').first().locator('.deck-card').evaluateAll(images => images.map(image => image.alt)), ['向日葵', '樱桃炸弹', '坚果', '土豆地雷', '寒冰射手']);
  assert.equal(await page.locator('.deck-row').first().locator('.deck-cards').textContent(), '');
  await page.waitForFunction(() => [...document.querySelectorAll('.deck-row:first-child .deck-card')].every(image => image.complete && image.naturalWidth > 0));
  const before = await page.locator('#pie').evaluate(canvas => canvas.toDataURL());
  assert.equal(await page.locator('.deck-row').first().locator('.deck-card').first().getAttribute('src'), '/assets/cards/SEED_PACKET_01.webp?v=pvz-release-20261106-7');
  await page.locator('.deck-row').first().focus();
  assert.notEqual(await page.locator('#pie').evaluate(canvas => canvas.toDataURL()), before);
  await page.locator('#versionSel').focus();
  assert.equal(await page.locator('#pie').evaluate(canvas => canvas.toDataURL()), before);
  await page.click('#sideZombie');
  await page.waitForFunction(() => document.querySelector('.deck-card')?.alt === '普通僵尸');
  assert.equal(await page.locator('.deck-card[alt="墓碑"]').count(), 0);
  assert.equal(await page.locator('.deck-row').first().locator('.deck-card').last().getAttribute('src'), '/assets/cards/SEED_PACKET_91_MOUND_LEVEL_0.webp?v=pvz-release-20261106-7');
  await page.waitForFunction(() => [...document.querySelectorAll('.deck-row:first-child .deck-card')].every(image => image.complete && image.naturalWidth > 0));
});

test('deck and card stat images load as their own scroll containers approach them', { timeout: 20000 }, async t => {
  const { page } = await dashboard(t, { deckCount: 10, cardCount: 44 });
  await page.waitForFunction(() => document.querySelectorAll('.deck-row').length === 10 && document.querySelectorAll('#cards tbody tr').length === 44);
  await page.waitForFunction(() => [...document.querySelectorAll('.deck-row:first-child .deck-card')].every(image => image.complete && image.naturalWidth > 0));
  await page.waitForFunction(() => [...document.querySelectorAll('#cards tbody tr:first-child .seed-thumb')].every(image => image.complete && image.naturalWidth > 0));
  assert(await page.locator('.deck-row:last-child .deck-card').evaluateAll(images => images.every(image => !image.hasAttribute('src'))));
  assert.equal(await page.locator('#cards tbody tr:last-child .seed-thumb').getAttribute('src'), null);

  await page.locator('.deck-list').evaluate(async list => {
    const step = Math.max(80, Math.floor(list.clientHeight / 4));
    for (let top = step; top <= list.scrollHeight; top += step) {
      list.scrollTop = top;
      await new Promise(requestAnimationFrame);
    }
    list.scrollTop = list.scrollHeight;
  });
  await page.waitForFunction(() => [...document.querySelectorAll('.deck-card')].every(image => image.complete && image.naturalWidth > 0));
  await page.locator('#cards').evaluate(async table => {
    const scroller = table.closest('.table-wrap');
    const step = Math.max(80, Math.floor(scroller.clientHeight / 4));
    for (let top = step; top <= scroller.scrollHeight; top += step) {
      scroller.scrollTop = top;
      await new Promise(requestAnimationFrame);
    }
    scroller.scrollTop = scroller.scrollHeight;
  });
  await page.waitForFunction(() => {
    return [...document.querySelectorAll('#cards tbody .seed-thumb')].every(image => image.complete && image.naturalWidth > 0);
  });
});

test('filter changes, empty recovery, language and live updates redraw complete decks', { timeout: 30000 }, async t => {
  const { page } = await dashboard(t);
  await waitForDecks(page, 114);
  await page.selectOption('#versionSel', '3215');
  await waitForDecks(page, 14);
  await page.selectOption('#modeSel', 'FOG');
  await page.waitForSelector('.deck-empty');
  assert.equal(await page.locator('#pie').isVisible(), false);
  await page.selectOption('#modeSel', 'DAY');
  await waitForDecks(page, 14);
  await page.selectOption('#extraSel', 'false');
  await page.waitForFunction(() => document.querySelectorAll('.deck-row:first-child .deck-card').length === 4);
  await page.click('#sideZombie');
  await page.waitForFunction(() => document.querySelector('.deck-card')?.alt === '普通僵尸');
  await page.click('#langBtn');
  await page.waitForFunction(() => document.querySelector('.deck-card')?.alt === 'Zombie');
  assert.equal(await page.locator('#pie').getAttribute('data-drawn-label'), 'Top 10 matches');
  const lightColor = await page.locator('.deck-core-name').first().evaluate(el => getComputedStyle(el).color);
  await page.click('#themeBtn');
  assert.notEqual(await page.locator('.deck-core-name').first().evaluate(el => getComputedStyle(el).color), lightColor);
  await waitForDecks(page, 14);
  await page.selectOption('#banSel', 'false');
  await page.waitForFunction(() => document.querySelectorAll('#cards thead th').length === 5);
  assert.equal(await page.locator('#cards tbody td').count(), 5);
  await page.evaluate(() => {
    document.querySelector('#pie').dataset.drawnTotal = '';
    window.testEvents.dispatchEvent(new Event('settle'));
  });
  await waitForDecks(page, 14);
});

test('card art skips seed 0 on every map and preserves statistics', { timeout: 30000 }, async t => {
  const { page } = await dashboard(t, { mixedProducers: true, emptyMap: null });
  await waitForDecks(page, 114);
  const originalStats = await page.locator('.deck-meta').allTextContents();
  for (const map of ['NIGHT', 'POOL', 'FOG', 'DAY']) {
    await page.selectOption('#modeSel', map);
    const expected = ['向日葵', '阳光菇', '樱桃炸弹', '坚果', '寒冰射手'];
    await page.waitForFunction(expected => JSON.stringify([...document.querySelectorAll('.deck-row:first-child .deck-card')].map(image => image.alt)) === JSON.stringify(expected), expected);
    assert.deepEqual(await page.locator('.deck-meta').allTextContents(), originalStats);
    await waitForDecks(page, 114);
  }
});

test('retired roof selection falls back to day on startup and cross-tab updates', { timeout: 15000 }, async t => {
  const { page, requestedMaps } = await dashboard(t, { initialMode: 'ROOF' });
  await waitForDecks(page, 114);
  assert.deepEqual(await page.locator('#modeSel option').evaluateAll(options => options.map(option => option.value)), ['DAY', 'NIGHT', 'POOL', 'FOG']);
  assert.equal(await page.locator('#modeSel').inputValue(), 'DAY');
  assert.equal(await page.evaluate(() => localStorage.getItem('pvz_mode')), 'DAY');
  assert(requestedMaps.length > 0 && requestedMaps.every(mode => mode === 'DAY'));
  await page.selectOption('#modeSel', 'NIGHT');
  await page.waitForFunction(() => document.querySelector('.deck-row:first-child .deck-cards').children.length === 5);
  const dayResponse = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/top-decks' && url.searchParams.get('mode') === 'DAY';
  });
  await page.evaluate(() => {
    localStorage.setItem('pvz_mode', 'ROOF');
    window.dispatchEvent(new StorageEvent('storage', { key: 'pvz_mode', newValue: 'ROOF' }));
  });
  await dayResponse;
  assert.equal(await page.locator('#modeSel').inputValue(), 'DAY');
  assert.equal(await page.evaluate(() => localStorage.getItem('pvz_mode')), 'DAY');
  await page.click('#langBtn');
  assert.deepEqual(await page.locator('#modeSel option').allTextContents(), ['Day', 'Night', 'Pool day', 'Pool fog']);
  assert(!requestedMaps.includes('ROOF'));
});

test('deck images and inline statistics fit desktop and mobile in both languages', { timeout: 30000 }, async t => {
  const { page } = await dashboard(t);
  await waitForDecks(page, 114);
  assert(await page.locator('#cards').evaluate(table => {
    const picked = table.rows[0].cells[1];
    return picked.getBoundingClientRect().left - table.getBoundingClientRect().left < table.clientWidth / 3
      && getComputedStyle(picked).textAlign === 'left';
  }));
  for (const width of [1366, 990, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    for (let language = 0; language < 2; language++) {
      await page.click('#langBtn');
      await page.waitForFunction(lang => document.documentElement.lang === lang && document.querySelector('.deck-card')?.alt === (lang === 'en' ? 'Sunflower' : '向日葵'), language === 0 ? 'en' : 'zh-CN');
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `page overflow at ${width}`);
      assert(await page.locator('.deck-cards').evaluateAll(elements => elements.every(el => el.scrollWidth <= el.clientWidth)), `deck images clipped at ${width}`);
      assert(await page.locator('.deck-heading').evaluateAll(elements => elements.every(el => {
        const name = el.querySelector('.deck-core-name').getBoundingClientRect();
        const meta = el.querySelector('.deck-meta').getBoundingClientRect();
        return Math.abs(name.top - meta.top) < 4 && meta.left >= name.right && meta.right <= el.getBoundingClientRect().right + 1;
      })), `deck statistics not inline at ${width}`);
      assert(await page.locator('.deck-core-name').evaluateAll(elements => elements.every(el => el.title.length > 0)));
    }
  }
});
