// eat_wat front end — no build step, no dependencies.

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const MIN = 60_000;
const DAY = 86_400_000;
const SVGNS = "http://www.w3.org/2000/svg";
const SLOTS = 8;

const FLAG_TEXT = {
  no_baseline: "餐前 30 分鐘內沒有讀值",
  baseline_after: "餐前值取自餐後 10 分鐘內的讀值",
  gap: "中間有超過 30 分鐘沒有資料",
  low_coverage: "讀值涵蓋不足",
  overlap: "3 小時內又吃了別的",
  prior_meal: "餐前 3 小時內吃過別的，餐前值可能還在上一餐的曲線上",
  incomplete: "餐後 3 小時還沒過完，或之後沒有資料",
  no_data: "沒有餐後血糖資料",
};
const TREND = { 1: "↓", 2: "↘", 3: "→", 4: "↗", 5: "↑" };

const state = {
  view: "day",
  day: startOfDay(Date.now()),
  rangeDays: 14,
  analysis: [],
  slots: new Map(), // meal id -> color slot (follows the meal, not its rank)
  sort: { key: "ts", dir: -1 },
  filter: "",
  status: null,
  editing: null, // meal being edited
  detailId: null,
};

// ---------- utils ----------

function startOfDay(ts) {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}
function addDays(ts, n) {
  const d = new Date(ts);
  d.setDate(d.getDate() + n);
  return startOfDay(d.getTime());
}
const pad = (n) => String(n).padStart(2, "0");
const WEEK = ["日", "一", "二", "三", "四", "五", "六"];
function fmtTime(ts) {
  const d = new Date(ts);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fmtDate(ts, withYear = false) {
  const d = new Date(ts);
  const y = withYear ? `${d.getFullYear()}/` : "";
  return `${y}${d.getMonth() + 1}/${d.getDate()}（${WEEK[d.getDay()]}）`;
}
function fmtDateTime(ts) {
  return `${fmtDate(ts)} ${fmtTime(ts)}`;
}
function toLocalInput(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function ago(ts) {
  const m = Math.round((Date.now() - ts) / MIN);
  if (m < 1) return "剛剛";
  if (m < 60) return `${m} 分鐘前`;
  if (m < 1440) return `${Math.floor(m / 60)} 小時前`;
  return fmtDateTime(ts);
}
const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const fmtN = (v, digits = 0) => (v === null || v === undefined || Number.isNaN(v) ? "—" : Number(v).toFixed(digits));
const signed = (v) => (v === null || v === undefined ? "—" : `${v > 0 ? "+" : ""}${Math.round(v)}`);
const inProgress = (m) => Date.now() < m.ts + 180 * MIN;
function kindFor(ts) {
  const h = new Date(ts).getHours();
  if (h >= 5 && h < 10) return "早餐";
  if (h >= 10 && h < 14) return "午餐";
  if (h >= 14 && h < 17) return "點心";
  if (h >= 17 && h < 21) return "晚餐";
  return "宵夜";
}

async function api(path, opts = {}) {
  const res = await fetch(path, { credentials: "same-origin", ...opts });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

function svg(tag, attrs = {}, parent) {
  const el = document.createElementNS(SVGNS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== undefined && v !== null) el.setAttribute(k, v);
  if (parent) parent.appendChild(el);
  return el;
}

// ---------- tooltip ----------

const tip = $("#tooltip");
function showTip(html, clientX, clientY) {
  tip.innerHTML = html;
  tip.hidden = false;
  const r = tip.getBoundingClientRect();
  let x = clientX + 14;
  let y = clientY - r.height - 12;
  if (x + r.width > window.innerWidth - 8) x = clientX - r.width - 14;
  if (x < 8) x = 8;
  if (y < 8) y = clientY + 16;
  tip.style.left = `${x}px`;
  tip.style.top = `${y}px`;
}
const hideTip = () => (tip.hidden = true);

// ---------- chart engine ----------

/**
 * cfg: { x:[min,max], y:[min,max], xTicks:[{v,label}], yTicks:[n], band:[lo,hi]?, hlines:[{y,cls,label}],
 *        series:[{id,color,label,points:[{x,y}]}], gap: max x-distance to connect, markers:[{x,label,title,onClick}],
 *        tooltip:(x, hits)=>html, endLabels:boolean }
 */
function drawChart(el, cfg) {
  el.innerHTML = "";
  const W = el.clientWidth || 300;
  const H = el.clientHeight || 220;
  const m = { l: 34, r: cfg.endLabels ? 70 : 12, t: cfg.markers?.length ? 22 : 8, b: 22 };
  const iw = Math.max(10, W - m.l - m.r);
  const ih = Math.max(10, H - m.t - m.b);
  const [x0, x1] = cfg.x;
  const [y0, y1] = cfg.y;
  const sx = (v) => m.l + ((v - x0) / (x1 - x0)) * iw;
  const sy = (v) => m.t + (1 - (v - y0) / (y1 - y0)) * ih;
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": cfg.ariaLabel || "圖表" });

  if (cfg.band) {
    const [lo, hi] = cfg.band;
    svg("rect", { x: m.l, y: sy(hi), width: iw, height: sy(lo) - sy(hi), class: "band" }, root);
    for (const v of cfg.band) svg("line", { x1: m.l, x2: m.l + iw, y1: sy(v), y2: sy(v), class: "band-edge" }, root);
  }
  for (const v of cfg.yTicks) {
    if (!cfg.band?.includes(v)) svg("line", { x1: m.l, x2: m.l + iw, y1: sy(v), y2: sy(v), class: "grid" }, root);
    const t = svg("text", { x: m.l - 6, y: sy(v) + 4, "text-anchor": "end" }, root);
    t.textContent = String(v);
  }
  const maxLabel = Math.max(...cfg.xTicks.map((t) => t.label.length));
  const spacing = iw / Math.max(1, cfg.xTicks.length - 1);
  const crowded = spacing < maxLabel * 7 + 10;
  for (const tk of cfg.xTicks) {
    if (crowded && tk.minor) continue;
    const t = svg("text", { x: sx(tk.v), y: H - 6, "text-anchor": "middle" }, root);
    t.textContent = tk.label;
  }
  for (const h of cfg.hlines || []) {
    svg("line", { x1: m.l, x2: m.l + iw, y1: sy(h.y), y2: sy(h.y), class: h.cls }, root);
    if (h.label) {
      const t = svg("text", { x: m.l + iw - 2, y: sy(h.y) - 4, "text-anchor": "end", class: "label-ink" }, root);
      t.textContent = h.label;
    }
  }

  // Lines, broken at gaps.
  const clip = `clip-${Math.random().toString(36).slice(2)}`;
  const defs = svg("defs", {}, root);
  const cp = svg("clipPath", { id: clip }, defs);
  svg("rect", { x: m.l, y: m.t - 4, width: iw, height: ih + 8 }, cp);
  const lines = svg("g", { "clip-path": `url(#${clip})` }, root);
  for (const s of cfg.series) {
    let d = "";
    let prev = null;
    for (const p of s.points) {
      const cmd = !prev || p.x - prev.x > cfg.gap ? "M" : "L";
      d += `${cmd}${sx(p.x).toFixed(1)},${sy(p.y).toFixed(1)}`;
      prev = p;
    }
    // A lone point between gaps would be invisible; give it a dot.
    s.points.forEach((p, i) => {
      const a = s.points[i - 1];
      const b = s.points[i + 1];
      if ((!a || p.x - a.x > cfg.gap) && (!b || b.x - p.x > cfg.gap))
        svg("circle", { cx: sx(p.x), cy: sy(p.y), r: 2.5, fill: s.color }, lines);
    });
    if (d) svg("path", { d, class: "gline", style: `stroke:${s.color}` }, lines);
  }

  // Direct end labels (≤ 4 series).
  if (cfg.endLabels && cfg.series.length <= 4) {
    const placed = [];
    for (const s of cfg.series) {
      const last = s.points[s.points.length - 1];
      if (!last) continue;
      let y = sy(last.y) + 4;
      for (const py of placed) if (Math.abs(py - y) < 13) y = py + 13;
      placed.push(y);
      const t = svg("text", { x: sx(last.x) + 6, y, class: "label-ink" }, root);
      t.textContent = s.label.length > 8 ? `${s.label.slice(0, 8)}…` : s.label;
    }
  }

  // Meal markers.
  const pins = svg("g", {}, root);
  for (const mk of cfg.markers || []) {
    const x = sx(mk.x);
    if (x < m.l - 1 || x > m.l + iw + 1) continue;
    svg("line", { x1: x, x2: x, y1: m.t, y2: m.t + ih, class: "meal-line" }, pins);
    const hit = svg("rect", { x: x - 12, y: 0, width: 24, height: m.t + ih, class: "meal-hit", tabindex: 0, role: "button", "aria-label": mk.title }, pins);
    svg("circle", { cx: x, cy: m.t - 10, r: 9, class: "meal-pin" }, pins);
    const t = svg("text", { x, y: m.t - 6.5, "text-anchor": "middle", class: "meal-pin-text" }, pins);
    t.textContent = mk.label;
    hit.addEventListener("click", (e) => {
      e.stopPropagation();
      hideTip();
      mk.onClick?.();
    });
    hit.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        mk.onClick?.();
      }
    });
    hit.addEventListener("pointerenter", (e) => showTip(`<div>${esc(mk.tooltip || mk.title)}</div>`, e.clientX, e.clientY));
    hit.addEventListener("pointerleave", hideTip);
  }

  // Hover layer: crosshair + nearest point per series.
  const hover = svg("g", { style: "pointer-events:none", visibility: "hidden" }, root);
  const cross = svg("line", { y1: m.t, y2: m.t + ih, class: "cross" }, hover);
  const dots = cfg.series.map((s) => svg("circle", { r: 4, class: "cross-dot", fill: s.color }, hover));
  const overlay = svg("rect", { x: m.l, y: m.t, width: iw, height: ih, fill: "transparent" });
  root.insertBefore(overlay, pins);

  const move = (e) => {
    const box = root.getBoundingClientRect();
    const px = ((e.clientX - box.left) / box.width) * W;
    const xv = x0 + ((px - m.l) / iw) * (x1 - x0);
    const hits = [];
    cfg.series.forEach((s, i) => {
      let best = null;
      for (const p of s.points) if (!best || Math.abs(p.x - xv) < Math.abs(best.x - xv)) best = p;
      if (best && Math.abs(best.x - xv) <= cfg.gap / 2) {
        hits.push({ s, p: best });
        dots[i].setAttribute("cx", sx(best.x));
        dots[i].setAttribute("cy", sy(best.y));
        dots[i].setAttribute("visibility", "visible");
      } else dots[i].setAttribute("visibility", "hidden");
    });
    if (!hits.length) {
      hover.setAttribute("visibility", "hidden");
      hideTip();
      return;
    }
    const snapX = hits[0].p.x;
    cross.setAttribute("x1", sx(snapX));
    cross.setAttribute("x2", sx(snapX));
    hover.setAttribute("visibility", "visible");
    showTip(cfg.tooltip(snapX, hits), e.clientX, e.clientY);
  };
  overlay.addEventListener("pointermove", move);
  overlay.addEventListener("pointerdown", move);
  overlay.addEventListener("pointerleave", () => {
    hover.setAttribute("visibility", "hidden");
    hideTip();
  });

  el.appendChild(root);
}

function niceYTicks(lo, hi, candidates) {
  return candidates.filter((v) => v >= lo && v <= hi);
}

// ---------- status ----------

async function refreshStatus() {
  try {
    const s = await api("/api/status");
    const prevLast = state.status?.readings?.last;
    state.status = s;
    renderStatus();
    if (prevLast !== undefined && s.readings.last !== prevLast && state.view === "day" && state.day === startOfDay(Date.now())) loadDay();
  } catch (e) {
    $("#sync-dot").className = "sync-dot stopped";
    $("#now-meta").textContent = "連不到伺服器";
  }
}

function renderStatus() {
  const s = state.status;
  if (!s) return;
  const dot = $("#sync-dot");
  dot.className = `sync-dot ${s.poll.state === "ok" && s.poll.timeWarning ? "error" : s.poll.state}`;
  dot.title = s.poll.lastError || s.poll.timeWarning || (s.poll.lastSuccess ? `上次同步 ${ago(s.poll.lastSuccess)}` : "尚未同步");
  if (s.latest) {
    const stale = Date.now() - s.latest.ts > 20 * MIN;
    $("#now-val").textContent = `${Math.round(s.latest.mgdl)}${TREND[s.latest.trend] ? ` ${TREND[s.latest.trend]}` : ""}`;
    $("#now-val").style.color = stale ? "var(--muted)" : "";
    $("#now-meta").textContent = `mg/dL · ${ago(s.latest.ts)}`;
  } else {
    $("#now-val").textContent = "—";
    $("#now-meta").textContent = s.poll.lastError ? "同步有問題，見設定" : "尚無資料";
  }
  if (state.view === "settings") renderSettings();
}

// ---------- day view ----------

async function loadDay() {
  const from = state.day;
  const to = addDays(from, 1) - 1;
  const today = startOfDay(Date.now());
  $("#day-label").textContent = from === today ? `今天 · ${fmtDate(from)}` : fmtDate(from, new Date(from).getFullYear() !== new Date().getFullYear());
  $("#day-next").disabled = from >= today;
  $("#day-picker").value = toLocalInput(from).slice(0, 10);
  const [readings, meals] = await Promise.all([
    api(`/api/readings?from=${from - 30 * MIN}&to=${to + 30 * MIN}`),
    api(`/api/analysis?from=${from}&to=${to}`),
  ]);
  renderDay(readings, meals, from, to);
}

function renderDay(readings, meals, from, to) {
  const inDay = readings.filter((r) => r.ts >= from && r.ts <= to);
  const vals = inDay.map((r) => r.mgdl);
  const tiles = $("#day-tiles");
  if (vals.length) {
    const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
    const tir = (vals.filter((v) => v >= 70 && v <= 180).length / vals.length) * 100;
    tiles.innerHTML = [
      ["平均", fmtN(avg), "mg/dL"],
      ["目標範圍內", fmtN(tir), "%"],
      ["最高", fmtN(Math.max(...vals)), "mg/dL"],
      ["最低", fmtN(Math.min(...vals)), "mg/dL"],
    ]
      .map(([k, v, u]) => `<div class="tile"><div class="k">${k}</div><div class="v">${v}<span class="u">${u}</span></div></div>`)
      .join("");
  } else tiles.innerHTML = "";

  const empty = $("#day-chart-empty");
  const chartEl = $("#day-chart");
  if (!readings.length) {
    empty.hidden = false;
    empty.textContent = state.status?.poll?.lastError
      ? `這天沒有血糖資料。同步錯誤：${state.status.poll.lastError}`
      : "這天沒有血糖資料。伺服器同步後會出現；更早的日子可以到「設定」匯入 LibreView CSV。";
  } else empty.hidden = true;

  const ys = readings.map((r) => r.mgdl);
  const yLo = Math.min(50, ...ys.map((v) => v - 10));
  const yHi = Math.max(220, ...ys.map((v) => v + 20));
  const xTicks = [];
  for (let h = 0; h <= 24; h += 3) xTicks.push({ v: from + h * 3600_000, label: h === 24 ? "" : `${pad(h)}:00`, minor: h % 6 !== 0 });
  const mealsSorted = [...meals].sort((a, b) => a.ts - b.ts);
  drawChart(chartEl, {
    ariaLabel: `${fmtDate(from)} 血糖曲線`,
    x: [from, from + DAY],
    y: [yLo, yHi],
    xTicks,
    yTicks: niceYTicks(yLo, yHi, [40, 70, 100, 140, 180, 250, 300, 350, 400]),
    band: [70, 180],
    gap: 31 * MIN,
    series: [{ id: "g", color: "var(--s1)", label: "血糖", points: readings.map((r) => ({ x: r.ts, y: r.mgdl })) }],
    markers: mealsSorted.map((m, i) => ({
      x: m.ts,
      label: String(i + 1),
      title: `${fmtTime(m.ts)} ${m.title}`,
      tooltip: `${fmtTime(m.ts)} ${m.title}${m.response.delta !== null ? ` · 上升 ${signed(m.response.delta)}` : ""}`,
      onClick: () => openDetail(m.id),
    })),
    tooltip: (x, hits) => `<div class="muted">${fmtTime(x)}</div><b>${Math.round(hits[0].p.y)}</b> mg/dL`,
  });

  const list = $("#day-meals");
  if (!mealsSorted.length) {
    list.innerHTML = `<p class="empty-hint">還沒有紀錄。按右下角「＋ 記錄飲食」。</p>`;
    return;
  }
  list.innerHTML = mealsSorted
    .map((m, i) => {
      const r = m.response;
      const live = inProgress(m);
      const low = r.confidence === "low" && !live;
      const resp = live
        ? `<div class="big">${signed(r.delta)}</div><div class="sub">進行中 · 餐後 ${Math.round((Date.now() - m.ts) / MIN)} 分</div>`
        : r.delta !== null
          ? `<div class="big">${signed(r.delta)}</div><div class="sub">${r.minutesToPeak} 分到高峰 ${Math.round(r.peak)}${low ? ' <span class="warn" title="資料不完整">⚠</span>' : ""}</div>`
          : `<div class="sub">${r.flags.includes("incomplete") ? "等待資料…" : "無血糖資料"}</div>`;
      return `<button class="meal" data-id="${m.id}">
        <span class="pin">${i + 1}</span>
        <span class="meal-body">
          <div class="t">${esc(m.title)}</div>
          <div class="sub">${m.kind ? `<span class="chip">${esc(m.kind)}</span>` : ""}${fmtTime(m.ts)}${m.carbs !== null ? ` · 醣類 ${fmtN(m.carbs)}g` : ""}</div>
        </span>
        <span class="row" style="flex-wrap:nowrap">
          ${m.photo ? `<img class="thumb" src="/photos/${esc(m.photo)}" alt="" loading="lazy">` : ""}
          <span class="resp">${resp}</span>
        </span>
      </button>`;
    })
    .join("");
  $$(".meal", list).forEach((b) => b.addEventListener("click", () => openDetail(Number(b.dataset.id))));
}

// ---------- meal detail ----------

async function openDetail(id) {
  state.detailId = id;
  const m = await api(`/api/meals/${id}/analysis`);
  const dlg = $("#detail-dialog");
  $("#d-when").textContent = `${m.kind ? `${m.kind} · ` : ""}${fmtDateTime(m.ts)}${m.carbs !== null ? ` · 醣類 ${fmtN(m.carbs)}g` : ""}`;
  $("#d-title").textContent = m.title;
  const img = $("#d-photo");
  img.hidden = !m.photo;
  if (m.photo) img.src = `/photos/${m.photo}`;
  $("#d-note").textContent = m.note || "";
  const r = m.response;
  $("#d-tiles").innerHTML = [
    ["餐前", fmtN(r.baseline), "mg/dL"],
    ["高峰", fmtN(r.peak), "mg/dL"],
    ["上升", signed(r.delta), "mg/dL"],
    ["到高峰", fmtN(r.minutesToPeak), "分"],
    ["2 小時後", signed(r.delta2h), "mg/dL"],
    ["iAUC", fmtN(r.iauc), "mg/dL·h"],
  ]
    .map(([k, v, u]) => `<div class="tile"><div class="k">${k}</div><div class="v">${v}<span class="u">${u}</span></div></div>`)
    .join("");
  $("#d-flags").textContent = r.flags.length ? `⚠ ${r.flags.map((f) => FLAG_TEXT[f] || f).join("；")}` : "";
  if (!dlg.open) dlg.showModal();
  dlg.dataset.meal = JSON.stringify(m);

  const pts = m.curve.map((c) => ({ x: c.min, y: c.mgdl }));
  const ys = pts.map((p) => p.y);
  const yLo = Math.min(60, ...ys.map((v) => v - 10));
  const yHi = Math.max(200, ...ys.map((v) => v + 20));
  drawChart($("#d-chart"), {
    ariaLabel: "餐後血糖",
    x: [-30, 180],
    y: [yLo, yHi],
    xTicks: [-30, 0, 30, 60, 90, 120, 150, 180].map((v) => ({ v, label: v === 0 ? "用餐" : `${v > 0 ? "+" : ""}${v}`, minor: v % 60 !== 0 })),
    yTicks: niceYTicks(yLo, yHi, [40, 70, 100, 140, 180, 250, 300, 350]),
    band: [70, 180],
    hlines: r.baseline !== null ? [{ y: r.baseline, cls: "baseline", label: `餐前 ${Math.round(r.baseline)}` }] : [],
    gap: 31,
    series: [{ id: "g", color: "var(--s1)", label: "血糖", points: pts }],
    markers: [{ x: 0, label: "", title: m.title }],
    tooltip: (x, hits) => `<div class="muted">${x >= 0 ? "餐後" : "餐前"} ${Math.abs(x)} 分</div><b>${Math.round(hits[0].p.y)}</b> mg/dL`,
  });
}

$("#d-close").addEventListener("click", () => $("#detail-dialog").close());
$("#d-edit").addEventListener("click", () => {
  const m = JSON.parse($("#detail-dialog").dataset.meal);
  $("#detail-dialog").close();
  openForm(m);
});
$("#d-delete").addEventListener("click", async () => {
  const m = JSON.parse($("#detail-dialog").dataset.meal);
  if (!confirm(`刪除「${m.title}」？`)) return;
  await api(`/api/meals/${m.id}`, { method: "DELETE" });
  state.slots.delete(m.id);
  $("#detail-dialog").close();
  reloadCurrent();
});

// ---------- meal form ----------

function openForm(meal = null) {
  state.editing = meal;
  const f = $("#meal-form");
  f.reset();
  $("#meal-error").textContent = "";
  $("#meal-form-title").textContent = meal ? "編輯紀錄" : "記錄飲食";
  let ts = meal?.ts;
  if (!ts) {
    const now = Date.now();
    ts = state.day === startOfDay(now) ? now : state.day + (now - startOfDay(now));
  }
  f.ts.value = toLocalInput(ts);
  const kind = meal ? meal.kind : kindFor(ts);
  $$('input[name="kind"]', f).forEach((r) => (r.checked = r.value === kind));
  f.elements.title.value = meal?.title ?? "";
  f.carbs.value = meal?.carbs ?? "";
  f.note.value = meal?.note ?? "";
  const cur = $("#photo-cur");
  cur.hidden = !meal?.photo;
  if (meal?.photo) $("img", cur).src = `/photos/${meal.photo}`;
  $("#meal-dialog").showModal();
  if (!meal) setTimeout(() => f.elements.title.focus(), 50);
}

$("#fab").addEventListener("click", () => openForm());
$("#meal-cancel").addEventListener("click", () => $("#meal-dialog").close());
$("#meal-form").ts.addEventListener("change", (e) => {
  if (state.editing) return;
  const t = new Date(e.target.value).getTime();
  if (Number.isFinite(t)) $$('input[name="kind"]').forEach((r) => (r.checked = r.value === kindFor(t)));
});

$("#meal-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target;
  const ts = new Date(f.ts.value).getTime();
  if (!Number.isFinite(ts)) {
    $("#meal-error").textContent = "時間不正確";
    return;
  }
  const fd = new FormData();
  fd.set("ts", String(ts));
  fd.set("title", f.elements.title.value.trim());
  fd.set("kind", f.querySelector('input[name="kind"]:checked')?.value ?? "");
  fd.set("carbs", f.carbs.value);
  fd.set("note", f.note.value);
  if (f.photo.files[0]) fd.set("photo", f.photo.files[0]);
  if (f.removePhoto.checked) fd.set("removePhoto", "1");
  const btn = $("#meal-save");
  btn.disabled = true;
  try {
    const path = state.editing ? `/api/meals/${state.editing.id}` : "/api/meals";
    await api(path, { method: state.editing ? "PUT" : "POST", body: fd });
    $("#meal-dialog").close();
    state.day = startOfDay(ts);
    loadSuggestions();
    reloadCurrent();
  } catch (err) {
    $("#meal-error").textContent = err.message;
  } finally {
    btn.disabled = false;
  }
});

async function loadSuggestions() {
  try {
    const meals = await api(`/api/meals?from=${Date.now() - 90 * DAY}&to=${Date.now() + DAY}`);
    const titles = [...new Set(meals.reverse().map((m) => m.title))].slice(0, 50);
    $("#title-suggest").innerHTML = titles.map((t) => `<option value="${esc(t)}">`).join("");
  } catch {}
}

// ---------- analysis view ----------

async function loadAnalysis() {
  const to = Date.now();
  const from = startOfDay(to) - (state.rangeDays - 1) * DAY;
  state.analysis = await api(`/api/analysis?from=${from}&to=${to}&curve=1`);
  const ids = new Set(state.analysis.map((m) => m.id));
  for (const id of [...state.slots.keys()]) if (!ids.has(id)) state.slots.delete(id);
  if (!state.slots.size) {
    // Start with the 3 biggest clean responses so the chart is not empty.
    const top = state.analysis
      .filter((m) => m.response.delta !== null && m.response.baseline !== null)
      .sort((a, b) => b.response.delta - a.response.delta)
      .slice(0, 3);
    top.forEach((m, i) => state.slots.set(m.id, i));
  }
  renderAnalysis();
}

function sortValue(m, key) {
  if (key === "ts" || key === "title" || key === "carbs") return m[key];
  return m.response[key];
}

function renderAnalysis() {
  const q = state.filter.trim().toLowerCase();
  const rows = state.analysis.filter((m) => !q || `${m.title} ${m.note} ${m.kind}`.toLowerCase().includes(q));
  const { key, dir } = state.sort;
  rows.sort((a, b) => {
    const va = sortValue(a, key);
    const vb = sortValue(b, key);
    if (va === null || va === undefined) return 1;
    if (vb === null || vb === undefined) return -1;
    return (typeof va === "string" ? va.localeCompare(vb, "zh-Hant") : va - vb) * dir;
  });
  $$("#an-table th[data-sort]").forEach((th) => {
    th.classList.toggle("sorted", th.dataset.sort === key);
    th.classList.toggle("asc", th.dataset.sort === key && dir === 1);
  });
  $("#an-empty").hidden = rows.length > 0;
  $("#an-table tbody").innerHTML = rows
    .map((m) => {
      const r = m.response;
      const slot = state.slots.get(m.id);
      const sel = slot !== undefined;
      const low = inProgress(m)
        ? ` <span class="chip">進行中</span>`
        : r.confidence === "low" ? ` <span class="warn" title="${esc(r.flags.map((f) => FLAG_TEXT[f] || f).join("；"))}">⚠</span>` : "";
      return `<tr class="${sel ? "sel" : ""}" data-id="${m.id}">
        <td><input type="checkbox" ${sel ? "checked" : ""} aria-label="比較 ${esc(m.title)}">${sel ? `<span class="swatch" style="background:var(--s${slot + 1})"></span>` : ""}</td>
        <td>${fmtDateTime(m.ts)}</td>
        <td class="title"><a href="#" data-open="${m.id}">${esc(m.title)}</a>${low}</td>
        <td class="num">${fmtN(m.carbs)}</td>
        <td class="num">${fmtN(r.baseline)}</td>
        <td class="num">${fmtN(r.peak)}</td>
        <td class="num"><b>${signed(r.delta)}</b></td>
        <td class="num">${r.minutesToPeak !== null ? `${r.minutesToPeak} 分` : "—"}</td>
        <td class="num">${signed(r.delta2h)}</td>
        <td class="num">${fmtN(r.iauc)}</td>
      </tr>`;
    })
    .join("");
  renderOverlay();
}

function renderOverlay() {
  const sel = state.analysis
    .filter((m) => state.slots.has(m.id))
    .sort((a, b) => state.slots.get(a.id) - state.slots.get(b.id));
  const el = $("#overlay-chart");
  const legend = $("#overlay-legend");
  $("#overlay-empty").hidden = sel.length > 0;
  el.style.display = sel.length ? "" : "none";
  if (!sel.length) {
    legend.innerHTML = "";
    return;
  }
  const series = sel.map((m) => ({
    id: m.id,
    color: `var(--s${state.slots.get(m.id) + 1})`,
    label: m.title,
    meal: m,
    points: (m.curve || []).filter((c) => c.delta !== null).map((c) => ({ x: c.min, y: c.delta })),
  }));
  legend.innerHTML = series
    .map(
      (s) =>
        `<span><i style="background:${s.color}"></i><b>${esc(s.label)}</b><span class="muted">${fmtDate(s.meal.ts)}${s.points.length ? "" : " · 無餐前值"}</span></span>`,
    )
    .join("");
  const ys = series.flatMap((s) => s.points.map((p) => p.y));
  const yLo = Math.min(-20, ...ys) - 5;
  const yHi = Math.max(60, ...ys) + 10;
  const step = yHi - yLo > 160 ? 50 : 20;
  const yTicks = [];
  for (let v = Math.ceil(yLo / step) * step; v <= yHi; v += step) yTicks.push(v);
  drawChart(el, {
    ariaLabel: "餐後血糖變化對照",
    x: [-30, 180],
    y: [yLo, yHi],
    xTicks: [-30, 0, 30, 60, 90, 120, 150, 180].map((v) => ({ v, label: v === 0 ? "用餐" : `${v > 0 ? "+" : ""}${v} 分`, minor: v % 60 !== 0 })),
    yTicks,
    hlines: [{ y: 0, cls: "zero" }],
    gap: 31,
    series,
    endLabels: series.length <= 4,
    tooltip: (x, hits) =>
      `<div class="muted">${x >= 0 ? "餐後" : "餐前"} ${Math.abs(x)} 分</div>` +
      hits
        .sort((a, b) => b.p.y - a.p.y)
        .map((h) => `<div class="row-t"><i style="background:${h.s.color}"></i>${esc(h.s.label.slice(0, 14))} <b>${signed(h.p.y)}</b></div>`)
        .join(""),
  });
}

$("#an-table tbody").addEventListener("click", (e) => {
  const open = e.target.closest("[data-open]");
  if (open) {
    e.preventDefault();
    openDetail(Number(open.dataset.open));
    return;
  }
  const tr = e.target.closest("tr[data-id]");
  if (!tr) return;
  const id = Number(tr.dataset.id);
  if (state.slots.has(id)) state.slots.delete(id);
  else {
    const used = new Set(state.slots.values());
    let free = -1;
    for (let i = 0; i < SLOTS; i++)
      if (!used.has(i)) {
        free = i;
        break;
      }
    if (free < 0) {
      alert("最多同時比較 8 筆，請先取消勾選一些。");
      renderAnalysis();
      return;
    }
    state.slots.set(id, free);
  }
  renderAnalysis();
});
$$("#an-table th[data-sort]").forEach((th) =>
  th.addEventListener("click", () => {
    const k = th.dataset.sort;
    state.sort = state.sort.key === k ? { key: k, dir: -state.sort.dir } : { key: k, dir: k === "title" ? 1 : -1 };
    renderAnalysis();
  }),
);
$$("#range-seg button").forEach((b) =>
  b.addEventListener("click", () => {
    $$("#range-seg button").forEach((x) => x.classList.toggle("on", x === b));
    state.rangeDays = Number(b.dataset.days);
    loadAnalysis();
  }),
);
$("#an-filter").addEventListener("input", (e) => {
  state.filter = e.target.value;
  renderAnalysis();
});

// ---------- settings ----------

function renderSettings() {
  const s = state.status;
  if (!s) return;
  const p = s.poll;
  const stateText = { idle: "尚未同步", ok: "正常", error: "暫時失敗，會自動重試", stopped: "已停止（需要處理）", disabled: "停用" }[p.state] || p.state;
  const rows = [
    ["模式", s.mock ? "MOCK 假資料" : s.configured ? "LibreLinkUp" : "未設定帳號"],
    ["狀態", stateText],
    ["上次成功", p.lastSuccess ? `${fmtDateTime(p.lastSuccess)}（${ago(p.lastSuccess)}）` : "—"],
    ["下次同步", p.nextPoll ? fmtTime(p.nextPoll) : "—"],
    ["區域", p.region || "（全球）"],
    ["追蹤對象", p.patientName || "—"],
  ];
  if (p.connections.length > 1) rows.push(["所有連線", p.connections.map((c) => `${c.name}（${c.patientId}）`).join("、")]);
  if (p.lastError) rows.push(["錯誤", p.lastError]);
  if (p.timeWarning) rows.push(["時間警告", p.timeWarning]);
  const warnKeys = ["錯誤", "時間警告"];
  $("#sync-info").innerHTML = rows
    .map(([k, v]) => `<dt>${k}</dt><dd${warnKeys.includes(k) ? ' class="warn"' : ""}>${esc(v)}</dd>`)
    .join("");
  const r = s.readings;
  $("#data-info").innerHTML = [
    ["血糖讀值", `${r.count.toLocaleString()} 筆`],
    ["最早", r.first ? fmtDateTime(r.first) : "—"],
    ["最新", r.last ? fmtDateTime(r.last) : "—"],
  ]
    .map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`)
    .join("");
}

$("#sync-now").addEventListener("click", async (e) => {
  e.target.disabled = true;
  try {
    await api("/api/sync", { method: "POST" });
  } catch {}
  await refreshStatus();
  e.target.disabled = false;
});

$("#csv-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const file = $("#csv-file").files[0];
  if (!file) return;
  const out = $("#csv-result");
  out.textContent = "匯入中…";
  const fd = new FormData();
  fd.set("file", file);
  try {
    const tz = -new Date().getTimezoneOffset();
    const r = await api(`/api/import/csv?tz=${tz}`, { method: "POST", body: fd });
    out.textContent = `完成：新增 ${r.inserted} 筆，略過 ${r.skipped} 筆（已有資料或重複）。範圍 ${fmtDateTime(r.first)} – ${fmtDateTime(r.last)}，單位 ${r.unit}，日期格式 ${r.dateOrder}。`;
    refreshStatus();
  } catch (err) {
    out.textContent = `匯入失敗：${err.message}`;
  }
});

// ---------- navigation ----------

function setView(v) {
  state.view = v;
  $$(".view").forEach((s) => s.classList.toggle("active", s.id === `view-${v}`));
  $$(".tabbar button").forEach((b) => b.classList.toggle("on", b.dataset.view === v));
  $("#fab").style.display = v === "settings" ? "none" : "";
  hideTip();
  reloadCurrent();
}

function reloadCurrent() {
  const run = state.view === "day" ? loadDay() : state.view === "analysis" ? loadAnalysis() : Promise.resolve(renderSettings());
  run.catch((e) => console.error(e));
  if (state.detailId && $("#detail-dialog").open) openDetail(state.detailId).catch(() => {});
}

$$(".tabbar button").forEach((b) => b.addEventListener("click", () => setView(b.dataset.view)));
$("#day-prev").addEventListener("click", () => {
  state.day = addDays(state.day, -1);
  loadDay();
});
$("#day-next").addEventListener("click", () => {
  state.day = addDays(state.day, 1);
  loadDay();
});
$("#day-label").addEventListener("click", () => {
  const p = $("#day-picker");
  if (p.showPicker) p.showPicker();
  else {
    state.day = startOfDay(Date.now());
    loadDay();
  }
});
$("#day-picker").addEventListener("change", (e) => {
  const [y, m, d] = e.target.value.split("-").map(Number);
  if (!y) return;
  state.day = new Date(y, m - 1, d).getTime();
  loadDay();
});

let resizeT;
window.addEventListener("resize", () => {
  clearTimeout(resizeT);
  resizeT = setTimeout(() => {
    if (state.view === "day") loadDay();
    if (state.view === "analysis") renderOverlay();
  }, 150);
});

// ---------- boot ----------

refreshStatus().then(() => loadDay());
loadSuggestions();
setInterval(refreshStatus, 60_000);
