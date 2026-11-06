(() => {
  const $ = selector => document.querySelector(selector);
  const normalizeMode = value => ["DAY", "NIGHT", "POOL", "FOG"].includes(value) ? value : "DAY";
  const state = {
    lang: localStorage.getItem("pvz_lang") || "zh",
    side: localStorage.getItem("pvz_side") || "PLANT",
    extra: localStorage.getItem("pvz_extra_packet") || "true",
    ban: localStorage.getItem("pvz_ban_mode") || "true",
    mode: normalizeMode(localStorage.getItem("pvz_mode")),
    theme: localStorage.getItem("pvz_theme") || "light",
    version: "all",
    names: new Map(),
    decks: [],
    activeDeck: "",
    request: 0
  };
  const palette = ["#6b963c", "#d49931", "#7584ad", "#c45e35", "#42a09a", "#a1ad4c", "#aa7250", "#4d815d", "#cf8e51", "#8888a1"];
  const CARD_IMAGE_VERSION = "pvz-release-20261106-7";
  let deckImageObserver = null;
  let cardStatsImageObserver = null;
  const text = {
    zh: { title:"对战大数据", version:"游戏版本", all:"全部版本", ban:"禁选模式", extra:"额外卡槽", off:"关闭", on:"开启", map:"地图", maps:["白天","黑夜","泳池白天","泳池黑夜"], plant:"植物", zombie:"僵尸", deckTitle:"Top 10 卡组", cardTitle:"单卡统计", recent:"最近对局", time:"时间", winner:"胜方", duration:"时长", card:"卡牌", pick:"选取", banCol:"禁用", wins:"胜场", winrate:"胜率", pickrate:"选取率", banrate:"禁用率", plays:"对局", noData:"暂无数据", waiting:"等待对局数据", records:"条卡牌记录", lineups:"组卡组", loading:"加载中…", returnHome:"返回资料站首页", allLabel:"Top 10 对局", latest:"近期", auto:"新对局数据自动更新" },
    en: { title:"Battle Data", version:"Game version", all:"All versions", ban:"Ban mode", extra:"Extra slot", off:"Off", on:"On", map:"Map", maps:["Day","Night","Pool day","Pool fog"], plant:"Plants", zombie:"Zombies", deckTitle:"Top 10 Decks", cardTitle:"Card Stats", recent:"Recent Matches", time:"Time", winner:"Winner", duration:"Duration", card:"Card", pick:"Picked", banCol:"Banned", wins:"Wins", winrate:"Win rate", pickrate:"Pick rate", banrate:"Ban rate", plays:"Plays", noData:"No data", waiting:"Waiting for match data", records:"card records", lineups:"deck lineups", loading:"Loading…", returnHome:"Back to home", allLabel:"Top 10 matches", latest:"LATEST", auto:"Live match data updates automatically" }
  };
  const t = () => text[state.lang];
  const percent = value => `${(Number(value || 0) * 100).toFixed(1)}%`;
  const displayTime = value => {
    if (!value) return "—";
    const date = new Date(value.includes("T") ? value : `${value.replace(" ", "T")}Z`);
    return Number.isNaN(date.valueOf()) ? value : date.toLocaleString(state.lang === "zh" ? "zh-CN" : "en-US", { hour12: false });
  };
  const cardName = item => {
    const name = state.names.get(Number(item.seed_type));
    return name ? (state.lang === "zh" ? name.zh || name.en : name.en || name.zh) : (state.lang === "zh" ? item.seed_zh || item.seed_en || "未知卡牌" : item.seed_en || item.seed_zh || "Unknown seed");
  };
  const cardImagePath = seedType => {
    const id = Number(seedType);
    const file = id === 91
      ? "SEED_PACKET_91_MOUND_LEVEL_0.webp"
      : `SEED_PACKET_${String(id).padStart(2, "0")}.webp`;
    return `/assets/cards/${file}?v=${CARD_IMAGE_VERSION}`;
  };

  function createImageObserver(root) {
    if (!root || typeof IntersectionObserver === "undefined") return null;
    return new IntersectionObserver((entries, observer) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const image = entry.target;
        image.src = image.dataset.src;
        delete image.dataset.src;
        observer.unobserve(image);
      }
    }, { root, rootMargin: "96px 0px" });
  }

  function observeImage(image, src, observer) {
    image.loading = "lazy";
    image.decoding = "async";
    if (observer) {
      image.dataset.src = src;
      observer.observe(image);
    } else {
      image.src = src;
    }
  }

  function labels() {
    const words = t();
    document.documentElement.lang = state.lang === "zh" ? "zh-CN" : "en";
    document.title = words.title;
    $("#topTitle").textContent = words.title;
    $("#versionLabel").textContent = words.version;
    $("#banLabel").textContent = words.ban;
    $("#extraLabel").textContent = words.extra;
    $("#mapLabel").textContent = words.map;
    $("#banSel").options[0].text = words.off;
    $("#banSel").options[1].text = words.on;
    $("#extraSel").options[0].text = words.off;
    $("#extraSel").options[1].text = words.on;
    $("#modeSel").querySelectorAll("option").forEach((option, i) => option.textContent = words.maps[i]);
    $("#deckTitle").textContent = `${state.side === "PLANT" ? words.plant : words.zombie} ${words.deckTitle}`;
    $("#cardTitle").textContent = words.cardTitle;
    $("#recentTitle").textContent = words.recent;
    const cardHeaders = [words.card, words.pick, ...(state.ban === "true" ? [words.banCol] : []), words.wins, words.winrate, words.pickrate, ...(state.ban === "true" ? [words.banrate] : [])];
    $("#cards thead tr").querySelectorAll("th").forEach((cell, index) => cell.textContent = cardHeaders[index] || "");
    $("#recentThTime").textContent = words.time;
    $("#recentThPlant").textContent = words.plant;
    $("#recentThZombie").textContent = words.zombie;
    $("#recentThWinner").textContent = words.winner;
    $("#recentThDuration").textContent = words.duration;
    $("#langBtn").textContent = state.lang === "zh" ? "中文 / EN" : "EN / 中文";
    $("#langBtn").setAttribute("aria-label", state.lang === "zh" ? "Switch language" : "切换语言");
    $("#themeBtn").setAttribute("aria-label", state.lang === "zh" ? "切换明暗主题" : "Switch color theme");
    document.querySelector(".data-footer a").textContent = words.returnHome;
    document.querySelectorAll(".section-count").forEach(el => { if (el.textContent === "LATEST" || el.textContent === "最新") el.textContent = words.latest; });
    for (const [id, side] of [["sidePlant", "PLANT"], ["sideZombie", "ZOMBIE"]]) {
      const button = $(`#${id}`);
      button.textContent = side === "PLANT" ? words.plant : words.zombie;
      button.setAttribute("aria-pressed", String(state.side === side));
      button.classList.toggle("active", state.side === side);
    }
  }

  function parameters() {
    return new URLSearchParams({ side: state.side, netplay_version: state.version, extra_packet: state.extra, ban_mode: state.ban, mode: state.mode });
  }

  async function json(url) {
    const response = await fetch(url, { cache: "no-store" });
    if (!response.ok) throw new Error(`${response.status}`);
    return response.json();
  }

  function renderDecks(decks) {
    const list = $("#deckList");
    const chart = $("#pie");
    const content = $(".deck-content");
    deckImageObserver?.disconnect();
    deckImageObserver = null;
    list.replaceChildren();
    $("#deckCount").textContent = `${decks.length} ${t().lineups}`;
    if (!decks.length) {
      content.classList.add("is-empty");
      chart.hidden = true;
      const item = document.createElement("li");
      item.className = "deck-empty";
      item.textContent = t().noData;
      list.append(item);
      return;
    }
    content.classList.remove("is-empty");
    chart.hidden = false;
    deckImageObserver = createImageObserver(list);
    const producerId = state.side === "ZOMBIE" ? 61 : 0;
    decks.forEach((deck, index) => {
      const row = document.createElement("li");
      row.className = "deck-row";
      row.dataset.deckId = deck.deck_ids;
      row.tabIndex = 0;
      const name = document.createElement("span");
      name.className = "deck-core-name";
      const rank = document.createElement("b");
      rank.className = "deck-rank";
      rank.textContent = String(index + 1).padStart(2, "0");
      const core = state.lang === "zh" ? deck.core_zh : deck.core_en;
      const ids = (deck.deck_ids || "").split("|").filter(id => id && Number(id) !== producerId);
      const names = ids.map(id => cardName({ seed_type: id }));
      const fallback = names.slice(0, 2).join(" + ");
      name.append(rank, document.createTextNode(core || fallback || t().noData));
      name.title = core || fallback || t().noData;
      const meta = document.createElement("span");
      meta.className = "deck-meta";
      meta.textContent = `${t().plays}: ${Number(deck.plays || 0).toLocaleString()} · ${t().winrate}: ${percent(deck.win_rate)}`;
      const heading = document.createElement("span");
      heading.className = "deck-heading";
      heading.append(name, meta);
      const cards = document.createElement("span");
      cards.className = "deck-cards";
      cards.hidden = ids.length === 0;
      cards.style.setProperty("--deck-card-count", Math.max(1, ids.length));
      ids.forEach((id, cardIndex) => {
        const image = document.createElement("img");
        image.className = "deck-card";
        observeImage(image, cardImagePath(id), deckImageObserver);
        image.alt = names[cardIndex];
        image.title = names[cardIndex];
        image.width = 48;
        image.height = 68;
        cards.append(image);
      });
      const marker = document.createElement("i");
      marker.className = "deck-marker";
      marker.style.background = palette[index];
      row.append(marker, heading, cards);
      row.addEventListener("mouseenter", () => highlightDeck(deck.deck_ids));
      row.addEventListener("mouseleave", () => highlightDeck(""));
      row.addEventListener("focus", () => highlightDeck(deck.deck_ids));
      row.addEventListener("blur", () => highlightDeck(""));
      list.append(row);
    });
    const total = decks.reduce((sum, deck) => sum + Number(deck.plays || 0), 0);
    chart.setAttribute("aria-label", `${t().allLabel}: ${total.toLocaleString()}`);
    drawDonut();
  }

  function highlightDeck(id) {
    state.activeDeck = id;
    $("#deckList").querySelectorAll(".deck-row").forEach(row => row.classList.toggle("active", row.dataset.deckId === id));
    drawDonut();
  }

  function renderCards(items) {
    const body = $("#cards tbody");
    cardStatsImageObserver?.disconnect();
    cardStatsImageObserver = createImageObserver(body.closest(".table-wrap"));
    body.replaceChildren();
    const banned = state.ban === "true";
    const headers = [t().card, t().pick, ...(banned ? [t().banCol] : []), t().wins, t().winrate, t().pickrate, ...(banned ? [t().banrate] : [])];
    const headerRow = $("#cards thead tr");
    headerRow.replaceChildren();
    headers.forEach(value => {
      const cell = document.createElement("th");
      cell.textContent = value;
      headerRow.append(cell);
    });
    $("#cardCount").textContent = `${items.length} ${t().records}`;
    $("#cardsEmpty").hidden = items.length > 0;
    for (const item of items) {
      const row = document.createElement("tr");
      const values = [cardName(item), Number(item.picked || 0).toLocaleString(), ...(banned ? [Number(item.banned || 0).toLocaleString()] : []), Number(item.won || 0).toLocaleString(), percent(item.win_rate), percent(item.pick_rate), ...(banned ? [percent(item.ban_rate)] : [])];
      values.forEach((value, index) => {
        const cell = document.createElement("td");
        if (index === 0) {
          cell.className = "seed-name";
          const name = document.createElement("span");
          name.textContent = value;
          const image = document.createElement("img");
          image.className = "seed-thumb";
          observeImage(image, cardImagePath(item.seed_type), cardStatsImageObserver);
          image.alt = `${value}卡图`;
          image.width = 34;
          image.height = 48;
          image.addEventListener("error", () => image.hidden = true, { once: true });
          const content = document.createElement("span");
          content.className = "seed-cell";
          content.append(image, name);
          cell.append(content);
        } else {
          cell.textContent = value;
        }
        row.append(cell);
      });
      body.append(row);
    }
  }

  function renderRecent(items) {
    const body = $("#recent tbody");
    body.replaceChildren();
    $("#recentEmpty").hidden = items.length > 0;
    for (const item of items) {
      const row = document.createElement("tr");
      const values = [displayTime(item.finished_at), item.plant_name, item.zombie_name, item.winner === "PLANT" ? t().plant : item.winner === "ZOMBIE" ? t().zombie : item.winner || "—", item.duration || "—"];
      values.forEach((value, index) => {
        const cell = document.createElement("td");
        cell.textContent = value || "—";
        if (index === 3) cell.className = item.winner === "PLANT" ? "plant-winner" : item.winner === "ZOMBIE" ? "zombie-winner" : "";
        row.append(cell);
      });
      body.append(row);
    }
  }

  async function loadVersions() {
    try {
      const payload = await json("/api/versions");
      const select = $("#versionSel");
      const values = Array.isArray(payload.items) ? payload.items.map(String) : [];
      select.replaceChildren();
      const all = document.createElement("option");
      all.value = "all";
      all.textContent = t().all;
      select.append(all);
      values.forEach(value => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = `${t().version} ${value}`;
        select.append(option);
      });
      if (state.version !== "all" && !values.includes(state.version)) state.version = "all";
      select.value = state.version;
    } catch (_) {}
  }

  async function refresh() {
    const request = ++state.request;
    await loadVersions();
    if (request !== state.request) return;
    const params = parameters();
    const query = params.toString();
    const recentQuery = new URLSearchParams({ netplay_version: state.version }).toString();
    const result = await Promise.allSettled([
      json(`/api/top-decks?${query}`),
      json(`/api/card-stats?${query}`),
      json(`/api/recent-matches?${recentQuery}`)
    ]);
    if (request !== state.request) return;
    const decks = result[0].status === "fulfilled" ? result[0].value : { items: [], total_plays: 0 };
    state.decks = decks.items || [];
    state.activeDeck = "";
    renderDecks(state.decks);
    renderCards(result[1].status === "fulfilled" ? result[1].value.items || [] : []);
    renderRecent(result[2].status === "fulfilled" ? result[2].value.items || [] : []);
  }

  function persist(key, value) {
    localStorage.setItem(key, value);
    refresh();
  }

  function applyTheme() {
    document.body.dataset.theme = state.theme;
    $("#themeBtn").textContent = state.theme === "dark" ? "☼" : "◐";
  }

  $("#versionSel").addEventListener("change", event => { state.version = event.target.value; refresh(); });
  $("#banSel").value = state.ban;
  $("#extraSel").value = state.extra;
  $("#modeSel").value = state.mode;
  if (localStorage.getItem("pvz_mode") !== state.mode) localStorage.setItem("pvz_mode", state.mode);
  $("#banSel").addEventListener("change", event => { state.ban = event.target.value; persist("pvz_ban_mode", state.ban); });
  $("#extraSel").addEventListener("change", event => { state.extra = event.target.value; persist("pvz_extra_packet", state.extra); });
  $("#modeSel").addEventListener("change", event => { state.mode = normalizeMode(event.target.value); $("#modeSel").value = state.mode; persist("pvz_mode", state.mode); });
  $("#sidePlant").addEventListener("click", () => { if (state.side !== "PLANT") { state.side = "PLANT"; localStorage.setItem("pvz_side", state.side); labels(); refresh(); } });
  $("#sideZombie").addEventListener("click", () => { if (state.side !== "ZOMBIE") { state.side = "ZOMBIE"; localStorage.setItem("pvz_side", state.side); labels(); refresh(); } });
  $("#langBtn").addEventListener("click", () => { state.lang = state.lang === "zh" ? "en" : "zh"; localStorage.setItem("pvz_lang", state.lang); labels(); refresh(); });
  $("#themeBtn").addEventListener("click", () => { state.theme = state.theme === "dark" ? "light" : "dark"; localStorage.setItem("pvz_theme", state.theme); applyTheme(); drawDonut(); });

  function drawDonut() {
    const canvas = $("#pie");
    if (canvas.hidden) return;
    const size = Math.min(270, Math.max(100, canvas.parentElement.clientWidth - 24));
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = size * ratio;
    canvas.height = size * ratio;
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;
    const ctx = canvas.getContext("2d");
    ctx.scale(ratio, ratio);
    const total = state.decks.reduce((sum, deck) => sum + Number(deck.plays || 0), 0);
    let start = -Math.PI / 2;
    state.decks.forEach((deck, index) => {
      const end = start + (total ? Number(deck.plays || 0) / total * Math.PI * 2 : 0);
      ctx.beginPath();
      ctx.arc(size / 2, size / 2, size * .46, start, end);
      ctx.arc(size / 2, size / 2, size * .28, end, start, true);
      ctx.closePath();
      ctx.globalAlpha = !state.activeDeck || state.activeDeck === deck.deck_ids ? 1 : .25;
      ctx.fillStyle = palette[index];
      ctx.fill();
      start = end;
    });
    ctx.globalAlpha = 1;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillStyle = state.theme === "dark" ? "#f3edda" : "#29382c";
    ctx.font = "700 23px 'Trebuchet MS', sans-serif";
    ctx.fillText(total.toLocaleString(), size / 2, size / 2 - 5);
    ctx.fillStyle = state.theme === "dark" ? "#bac1b1" : "#687166";
    ctx.font = "12px 'Microsoft YaHei', sans-serif";
    ctx.fillText(t().allLabel, size / 2, size / 2 + 18);
  }
  const resizeObserver = new ResizeObserver(drawDonut);
  resizeObserver.observe($(".chart-wrap"));
  window.addEventListener("resize", drawDonut, { passive: true });
  window.addEventListener("storage", event => {
    const map = { pvz_lang:"lang", pvz_side:"side", pvz_extra_packet:"extra", pvz_ban_mode:"ban", pvz_mode:"mode", pvz_theme:"theme" };
    const key = map[event.key];
    if (!key || event.newValue === null) return;
    state[key] = key === "mode" ? normalizeMode(event.newValue) : event.newValue;
    if (key === "extra") $("#extraSel").value = state.extra;
    if (key === "ban") $("#banSel").value = state.ban;
    if (key === "mode") {
      $("#modeSel").value = state.mode;
      if (state.mode !== event.newValue) localStorage.setItem("pvz_mode", state.mode);
    }
    labels();
    applyTheme();
    refresh();
  });
  applyTheme();
  labels();
  json("/api/seed-names").then(payload => {
    for (const item of payload.items || []) state.names.set(Number(item.seed_type), item);
    return refresh();
  }).catch(refresh);
  if (window.EventSource) {
    const events = new EventSource("/api/stream");
    events.addEventListener("settle", () => refresh());
  }
})();
