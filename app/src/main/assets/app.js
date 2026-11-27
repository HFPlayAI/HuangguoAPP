'use strict';

const callbacks = new Map();
let requestNumber = 0;
window.NativeCallbacks = {
  resolve(id, value) { callbacks.get(id)?.resolve(value); callbacks.delete(id); },
  reject(id, message) { callbacks.get(id)?.reject(new Error(message)); callbacks.delete(id); }
};
// 每个 action 的等待上限。没有这个上限时，一次网络抖动会让所有 await 永久挂起：
// 页面停在旧数据、设置页永远"正在统计缓存"，只能等网络自己恢复。
// 与原生侧 FETCH_BUDGET_MS(25s) 对齐：JS 稍长一点，避免原生还在回退就被判超时。
const API_TIMEOUTS = {
  home: 35000, detail: 35000, category: 35000, newest: 35000, rank: 35000,
  search: 35000, 'feed-pool': 40000, playback: 60000, cover: 20000
};

function api(action, args = {}) {
  const limit = API_TIMEOUTS[action] || 20000;
  return new Promise((resolve, reject) => {
    const id = (window.HotRuntime?.bootId || 'legacy') + ':' + (++requestNumber);
    const timer = setTimeout(() => {
      if (!callbacks.has(id)) return;
      callbacks.delete(id);
      console.warn('[api] timeout', action, limit);
      reject(new Error(`${action} 请求超时`));
    }, limit);
    callbacks.set(id, {
      resolve: value => { clearTimeout(timer); resolve(value); },
      reject: error => { clearTimeout(timer); reject(error); }
    });
    try { AndroidHost.request(id, action, JSON.stringify(args)); }
    catch (error) { clearTimeout(timer); callbacks.delete(id); reject(error); }
  });
}

/** 推荐页标题点击：先摘掉原生播放层，再按普通详情流程打开。 */
/** 清理内容缓存后，封面 URL 缓存必须一起失效，否则会拿着已被删除的地址去请求。 */
window.hgCoversCleared = () => { coverRequests.clear(); };

// 任何未捕获异常都写进诊断日志，避免"白屏后无从查起"。
window.addEventListener('error', (event) => {
  const message = event?.message || String(event?.error || 'unknown');
  api('js-log', { msg: 'js-error ' + message + ' @' + (event?.filename || '') + ':' + (event?.lineno || 0) }).catch(() => {});
});
window.addEventListener('unhandledrejection', (event) => {
  const reason = event?.reason?.message || String(event?.reason || 'unknown');
  api('js-log', { msg: 'js-reject ' + reason }).catch(() => {});
});

window.hgFeedOpened = () => { state.feedAlive = true; };
window.hgFeedClosed = () => { state.feedAlive = false; };

window.hgOpenDetail = (id) => {
  if (!id) return;
  // 从推荐进详情，返回时要回到推荐（而不是首页），位置靠 FeedView 自己续播。
  state.feedReturn = true;
  api('feed-close').catch(() => {});
  state.route = 'home';
  goDetail(String(id)).catch(() => {});
};

window.addEventListener('error', event => {
  try {
    api('js-log', { msg: 'js-error ' + (event.message || '') + ' @' + String(event.filename || '').slice(-18) + ':' + (event.lineno || 0) }).catch(() => {});
  } catch { }
});
window.addEventListener('unhandledrejection', event => {
  try {
    const reason = event.reason && (event.reason.message || event.reason);
    api('js-log', { msg: 'js-reject ' + String(reason).slice(0, 120) }).catch(() => {});
  } catch { }
});

const scroll = document.getElementById('scroll');
const content = document.getElementById('content');
const avatarButton = document.getElementById('account-avatar');
const avatarImage = document.getElementById('account-avatar-image');
const authOverlay = document.getElementById('auth-overlay');
const compact = document.getElementById('compact-category');
const toastHost = document.getElementById('toast-host');
const updateOverlay = document.getElementById('update-overlay');
const updateDownloadButton = document.getElementById('update-download');
const updateLaterButton = document.getElementById('update-later');
const searchInput = document.getElementById('search-input');
const headerSettings = document.getElementById('header-settings');
const confirmOverlay = document.getElementById('confirm-overlay');
const categoryIds = { adult: ['ai-duanju', 'ai-manju'], ai: ['ai-huanlian', 'ai-mogai'] };
const popularTags = [
  ['dushi', '都市'], ['xiaoyuan', '校园'], ['gufeng', '古风'], ['haomen', '豪门'],
  ['chuanyue', '穿越'], ['chongsheng', '重生'], ['qihuan', '奇幻'], ['zhichang', '职场']
];
const tagGroups = [
  ['popular', '热门类型'], ['story', '故事题材'], ['setting', '场景与身份'],
  ['relationship', '人物与关系'], ['style', '外形风格'], ['plot', '情节元素'], ['other', '更多标签']
];
const state = {
  route: 'home', tab: 'home', sub: { adult: 0, ai: 0 }, home: null,
  category: null, categoryItems: [], detail: null, detailLoading: false, sourceItem: null,
  search: null, query: '', filter: 'all', banner: 0,
  listing: null, tags: null, tagsReturn: null, listingReturn: null, settingsReturn: null,
  libraryTab: 'history', libraryQuery: '', accountReturn: null, account: { loggedIn: false },
  sourceGroup: '', sourceQueue: [], previous: null, compactHeld: false
};
let routeToken = 0;
let toastTimer;
let observer;
let listingObserver;
let categoryObserver;
const screenSnapshots = {};
let predictivePreview;
let predictiveTimer;
let predictiveSourceNode;
let predictiveFrame;
let pendingPredictiveProgress = 0;
let detailCard;
const coverRequests = new Map();
let bannerChanging = false;
let bannerTimer;
let lastDockTap = { tab: '', at: 0, navigated: false };
let updateChecking = false;
let updateDownloading = false;
let updateAvailable = null;
let appVersion = '';

const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const asArray = value => Array.isArray(value) ? value : [];
const icon = name => {
  const paths = {
    play: '<path d="m8 5 11 7-11 7z"/>',
    arrow: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m15 5-7 7 7 7"/>'
  };
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
};

function toast(message, info = false) {
  clearTimeout(toastTimer);
  toastHost.innerHTML = `<div class="toast ${info ? 'info' : ''}"><i></i><span>${esc(message)}</span></div>`;
  toastHost.classList.add('visible');
  toastTimer = setTimeout(() => toastHost.classList.remove('visible'), 3000);
}

let finishConfirmation;
function closeConfirmation(accepted) {
  if (confirmOverlay.hidden) return;
  confirmOverlay.hidden = true;
  finishConfirmation?.(accepted);
  finishConfirmation = null;
}
function askConfirmation(title, message, action = '清空') {
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message;
  document.getElementById('confirm-accept').textContent = action;
  confirmOverlay.hidden = false;
  document.getElementById('confirm-cancel').focus();
  return new Promise(resolve => { finishConfirmation = resolve; });
}
document.getElementById('confirm-cancel').addEventListener('click', () => closeConfirmation(false));
document.getElementById('confirm-accept').addEventListener('click', () => closeConfirmation(true));
confirmOverlay.addEventListener('click', event => { if (event.target === confirmOverlay) closeConfirmation(false); });

function setAccount(profile) {
  state.account = profile || { loggedIn: false };
  avatarImage.hidden = !profile?.avatar;
  avatarButton.querySelector('.account-avatar-default').hidden = Boolean(profile?.avatar);
  if (profile?.avatar) avatarImage.src = profile.avatar;
  else avatarImage.removeAttribute('src');
  avatarButton.classList.toggle('is-signed-in', Boolean(profile?.loggedIn));
  avatarButton.setAttribute('aria-label', profile?.loggedIn ? `打开${profile.username}的账号管理` : '登录账号');
  if (state.route === 'library') renderLibrary();
  if (state.route === 'settings') renderSettings();
  if (state.route === 'account-management') renderAccountManagement();
}
api('account').then(setAccount).catch(() => {});
window.hgWindowInsets = height => {
  document.getElementById('app').style.setProperty('--status-bar-height', `${Math.max(0, Number(height) || 0)}px`);
};
api('window-insets').then(window.hgWindowInsets).catch(() => {});
api('screen-corners').then(corners => {
  const app = document.getElementById('app');
  for (const name of ['topLeft', 'topRight', 'bottomRight', 'bottomLeft']) {
    const radius = Number(corners?.[name]);
    if (radius > 0) app.style.setProperty(`--screen-corner-${name}`, `${radius}px`);
  }
}).catch(() => {});

let authMode = 'login';
let authPending = false;
function setAuthPending(pending, mode = authMode) {
  const submit = document.getElementById('auth-submit');
  submit.disabled = pending;
  submit.classList.toggle('is-loading', pending);
  submit.setAttribute('aria-busy', String(pending));
  submit.textContent = pending ? (mode === 'login' ? '正在登录…' : '正在注册…') : (mode === 'login' ? '登录' : '注册并登录');
  document.querySelectorAll('[data-auth-mode]').forEach(button => { button.disabled = pending; });
}
function showAuth(mode = 'login') {
  if (authPending) mode = authMode;
  authMode = mode;
  authOverlay.hidden = false;
  document.getElementById('auth-title').textContent = mode === 'login' ? '登录黄果账号' : '注册黄果账号';
  setAuthPending(authPending, mode);
  document.getElementById('auth-password').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  document.querySelectorAll('[data-auth-mode]').forEach(button => button.classList.toggle('selected', button.dataset.authMode === mode));
  document.getElementById('auth-error').hidden = true;
}
function hideAuth() {
  authOverlay.hidden = true;
  document.getElementById('auth-form').reset();
}
avatarButton.addEventListener('click', () => {
  if (state.account.loggedIn) goAccountManagement();
  else showAuth();
});
headerSettings.addEventListener('click', goSettings);
document.getElementById('auth-close').addEventListener('click', hideAuth);
document.querySelectorAll('[data-auth-mode]').forEach(button => button.addEventListener('click', () => showAuth(button.dataset.authMode)));
document.getElementById('auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (authPending) return;
  const mode = authMode;
  const username = document.getElementById('auth-username').value.trim();
  const password = document.getElementById('auth-password').value;
  const error = document.getElementById('auth-error');
  authPending = true;
  setAuthPending(true, mode);
  error.hidden = true;
  try {
    const profile = await api(mode, { username, password });
    hideAuth();
    setAccount(profile);
    if (state.route === 'library') goLibrary(state.libraryTab);
    toast(mode === 'login' ? '登录成功，片单正在同步' : '注册成功，片单正在同步');
  } catch (failure) {
    if (authOverlay.hidden) toast(failure.message || '请稍后重试', true);
    else { error.textContent = failure.message || '请稍后重试'; error.hidden = false; }
  } finally { authPending = false; setAuthPending(false, mode); }
});
window.hgAccountRefreshed = profile => {
  if (profile?.userId && state.account.userId === profile.userId) setAccount(profile);
};
window.hgAvatarUpdated = profile => { setAccount(profile); toast('头像已更新'); };
window.hgAvatarError = message => toast(message || '头像保存失败', true);
window.hgCloudSynced = () => { if (state.route === 'library') api('library').then(data => { state.library = data; renderLibrary(); }).catch(() => {}); };

function setAvailableUpdate(result) {
  updateAvailable = result?.status === 'available' ? result : null;
  headerSettings.classList.toggle('has-update', Boolean(updateAvailable));
  const versionButton = content.querySelector('[data-open-update]');
  if (versionButton) {
    versionButton.classList.toggle('has-update', Boolean(updateAvailable));
    versionButton.setAttribute('aria-label', updateAvailable ? `当前版本 ${appVersion}，发现新版 ${updateAvailable.version}，查看更新` : `当前版本 ${appVersion}，检查更新`);
  }
}

function showUpdate(result) {
  document.getElementById('update-version').textContent = `当前 ${result.currentVersion} · 新版 ${result.version}`;
  const notes = document.getElementById('update-notes');
  notes.textContent = String(result.notes || '').trim().slice(0, 1200);
  notes.hidden = !notes.textContent;
  document.getElementById('update-progress').hidden = true;
  updateDownloadButton.textContent = '下载并安装';
  updateDownloadButton.disabled = false;
  updateOverlay.hidden = false;
}

async function checkUpdate(manual) {
  if (updateDownloading) { if (manual) toast('更新包正在下载', true); return; }
  if (updateChecking) { if (manual) toast('正在检查更新', true); return; }
  updateChecking = true;
  try {
    const result = await api('check-update', { manual });
    if (result.status === 'available') {
      setAvailableUpdate(result);
      if (manual) showUpdate(result);
    } else if (result.status === 'current' || result.status === 'unpublished' || result.status === 'none') {
      setAvailableUpdate(null);
      if (manual && result.status === 'current') toast(`已是最新版本 ${result.version}`);
      if (manual && result.status === 'unpublished') toast('暂未发布更新包', true);
    }
    else if (manual && result.status === 'throttled') toast('一小时内最多手动检查 5 次，请稍后再试', true);
  } catch (error) {
    if (manual) toast(error.message || '检查更新失败，请稍后重试', true);
  } finally { updateChecking = false; }
}

window.hgUpdateProgress = percent => {
  document.getElementById('update-progress').hidden = false;
  document.getElementById('update-percent').textContent = `${percent}%`;
  document.getElementById('update-fill').style.transform = `scaleX(${percent / 100})`;
};
window.hgUpdateInstallPermissionDenied = () => toast('请允许此应用安装更新包后重试', true);
window.hgUpdateInstallError = () => toast('无法打开系统安装程序，请重试', true);

updateDownloadButton.addEventListener('click', async () => {
  if (updateDownloading) return;
  updateDownloading = true;
  updateDownloadButton.disabled = true;
  updateLaterButton.disabled = true;
  updateDownloadButton.textContent = '正在下载…';
  window.hgUpdateProgress(0);
  try {
    await api('download-update');
    updateDownloadButton.textContent = '重新打开安装程序';
  } catch (error) {
    updateDownloadButton.textContent = '重试下载';
    toast(error.message || '下载失败，请重试', true);
  } finally {
    updateDownloading = false;
    updateDownloadButton.disabled = false;
    updateLaterButton.disabled = false;
  }
});
updateLaterButton.addEventListener('click', () => { if (!updateDownloading) updateOverlay.hidden = true; });

window.hgTheme = dark => { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; };
api('theme').then(window.hgTheme).catch(() => {});

function itemCard(item) {
  const id = esc(item.id);
  const title = esc(item.title || '影片');
  const info = esc(item.episodeLabel || asArray(item.tags).slice(0, 2).join(' · ') || '打开详情');
  return `<button class="film-card" type="button" data-open-id="${id}" aria-label="查看${title}">
    <span class="poster loading"><span class="poster-letter">${title.slice(0, 1)}</span><img data-cover="${esc(item.cover || '')}" alt=""><span class="poster-score" ${item.score ? '' : 'hidden'}>${esc(item.score || '')}</span></span>
    <span class="film-title">${title}</span><span class="film-info">${info}</span></button>`;
}

function section(title, items, kind = '') {
  if (!items.length) return '';
  return `<section class="film-section"><div class="section-heading"><h2>${esc(title)}</h2>${kind ? `<button class="more" data-more="${kind}" type="button">更多 ${icon('arrow')}</button>` : ''}</div><div class="film-row">${items.map(itemCard).join('')}</div></section>`;
}
function homeTagBar() {
  return `<nav class="home-tags" aria-label="按类型浏览"><button type="button" class="all-tags-link" data-all-tags="1">全部类型 ${icon('arrow')}</button>
    <div class="home-tag-scroll">${popularTags.map(([slug, name]) => `<button type="button" class="home-tag" data-tag-slug="${slug}" data-tag-name="${name}">${name}</button>`).join('')}</div></nav>`;
}

function homeSignature(data) {
  return JSON.stringify([asArray(data.featured), asArray(data.homepageRecommend), asArray(data.homepageNewest), asArray(data.homepageHot), asArray(data.recommend?.items), asArray(data.newest?.items)]
    .map(list => list.map(item => [item.id, item.title, item.episodeLabel, item.score])));
}
function listSignature(data) {
  return JSON.stringify(asArray(data.items).map(item => [item.id, item.title, item.episodeLabel, item.score]));
}

function paletteFromImage(image) {
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 48;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(image, 0, 0, 48, 48);
  const pixels = context.getImageData(0, 0, 48, 48).data;
  let red = 0, green = 0, blue = 0, weightSum = 0;
  for (let index = 0; index < pixels.length; index += 16) {
    const r = pixels[index], g = pixels[index + 1], b = pixels[index + 2];
    const brightness = (r + g + b) / 3;
    if (brightness < 28 || brightness > 225) continue;
    const weight = 0.6 + (Math.max(r, g, b) - Math.min(r, g, b)) / 255;
    red += r * weight; green += g * weight; blue += b * weight; weightSum += weight;
  }
  if (!weightSum) throw new Error('未找到可用颜色');
  const r = red / weightSum / 255, g = green / weightSum / 255, b = blue / weightSum / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), difference = max - min;
  let hue = 28;
  if (difference) {
    hue = max === r ? ((g - b) / difference) % 6 : max === g ? (b - r) / difference + 2 : (r - g) / difference + 4;
    hue = (hue * 60 + 360) % 360;
  }
  const saturation = Math.round(Math.max(17, Math.min(32, difference / Math.max(max, .01) * 75)));
  return { light: `hsl(${Math.round(hue)} ${saturation}% 62%)`, dark: `hsl(${Math.round(hue)} ${Math.min(34, saturation + 2)}% 25%)` };
}
function applyBannerPalette(image) {
  const slide = image.closest('.hero-slide') || image.closest('.hero');
  if (!slide) return;
  const key = (image.dataset.cover || '').split('?')[0];
  let cache = {};
  try { cache = JSON.parse(localStorage.getItem('banner-palettes') || '{}'); } catch { }
  let colors = cache[key];
  if (!colors) {
    try { colors = paletteFromImage(image); cache[key] = colors; localStorage.setItem('banner-palettes', JSON.stringify(cache)); }
    catch { colors = { light: '#B5A08B', dark: '#49332F' }; }
  }
  slide.style.setProperty('--hero-tint-light', colors.light);
  slide.style.setProperty('--hero-tint-dark', colors.dark);
}

function coverKey(url) {
  try { return new URL(url).pathname; } catch { return String(url).split('?')[0]; }
}
function getCover(url, inline) {
  // 首屏轮播要读像素算配色，canvas 必须同源 → 那条走 base64；其余走本地 URL 流式加载。
  const key = coverKey(url) + (inline ? '#hero' : '');
  if (coverRequests.has(key)) return coverRequests.get(key);
  const request = api('cover', { url, inline: !!inline }).catch(error => { coverRequests.delete(key); throw error; });
  coverRequests.set(key, request);
  if (coverRequests.size > 80) coverRequests.delete(coverRequests.keys().next().value);
  return request;
}
async function loadCoverInto(image) {
  const url = image.dataset.cover;
  if (image.dataset.loading === '1') return;
  if (!url) {
    image.closest('.poster')?.classList.remove('loading');
    image.closest('.poster')?.classList.add('fallback');
    return;
  }
  image.dataset.loading = '1';
  const poster = image.closest('.poster');
  try {
    const inline = image.classList.contains('hero-image');
    let data;
    try { data = await getCover(url, inline); }
    catch { await new Promise(resolve => setTimeout(resolve, 450)); data = await getCover(url, inline); }
    if (!image.isConnected || image.dataset.cover !== url) return;
    await new Promise((resolve, reject) => {
      image.onload = () => { image.onload = image.onerror = null; resolve(); };
      image.onerror = () => { image.onload = image.onerror = null; reject(new Error('图片无法解码')); };
      image.src = data;
      if (image.complete && image.naturalWidth > 0) { image.onload = image.onerror = null; resolve(); }
    });
    if (!image.isConnected || image.dataset.cover !== url) return;
    poster?.classList.remove('loading', 'fallback');
    poster?.classList.add('has-image');
    delete image.dataset.retryAfter;
    if (image.classList.contains('hero-image')) applyBannerPalette(image);
  } catch (error) {
    // 连同 hero 规格的缓存一起失效，否则内联图会一直拿到旧（可能已失效）的结果。
    coverRequests.delete(coverKey(url));
    coverRequests.delete(coverKey(url) + '#hero');
    if (image.isConnected) {
      image.removeAttribute('src');
      poster?.classList.remove('loading');
      poster?.classList.add('fallback');
      image.dataset.retryAfter = String(Date.now() + (error.message === '图片无法解码' ? 300000 : 30000));
    }
    const id = image.closest('[data-open-id]')?.dataset.openId || image.closest('.hero-slide,.category-spotlight')?.querySelector('[data-open-id]')?.dataset.openId || 'unknown';
    console.warn('封面加载失败', id, (() => { try { return new URL(url).host; } catch { return 'invalid'; } })(), error.message);
  } finally { delete image.dataset.loading; }
}

function retryFailedImages(force = false) {
  const viewport = scroll.getBoundingClientRect();
  content.querySelectorAll('img[data-cover]').forEach(image => {
    if (image.naturalWidth > 0 || image.dataset.loading === '1') return;
    if (!force && Date.now() < Number(image.dataset.retryAfter || 0)) return;
    const card = image.closest('.poster');
    if (card && !card.classList.contains('fallback')) return;
    const box = image.getBoundingClientRect();
    if (box.bottom < viewport.top - 320 || box.top > viewport.bottom + 320) return;
    const row = image.closest('.film-row');
    if (row) {
      const visible = row.getBoundingClientRect();
      if (box.right < visible.left - 250 || box.left > visible.right + 250) return;
    }
    card?.classList.remove('fallback');
    card?.classList.add('loading');
    loadCoverInto(image);
  });
}
let coverScrollTimer;
scroll.addEventListener('scroll', () => {
  clearTimeout(coverScrollTimer);
  coverScrollTimer = setTimeout(() => retryFailedImages(), 160);
}, { passive: true });
window.addEventListener('online', () => retryFailedImages(true));
function hydrateImages(root = content) {
  if (observer) observer.disconnect();
  observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const image = entry.target;
      observer.unobserve(image);
      loadCoverInto(image);
    });
  }, { root: scroll, rootMargin: '320px' });
  root.querySelectorAll('img[data-cover]').forEach((image, index) => {
    if (image.naturalWidth > 0) return;
    if (index < 14 || image.classList.contains('hero-image')) loadCoverInto(image);
    else observer.observe(image);
  });
}

function activeCampaigns() {
  const now = Date.now();
  return asArray(window.HotRuntime?.theme?.campaigns).filter(item => item?.id
    && (!item.startsAt || now >= Date.parse(item.startsAt))
    && (!item.endsAt || now < Date.parse(item.endsAt))
    && /^theme\/[a-zA-Z0-9_./-]+$/.test(item.artwork || '') && !item.artwork.split('/').includes('..'));
}
function campaignArtwork(item) {
  return new URL(item.artwork, 'https://appassets.androidplatform.net/assets/').href;
}
function homeSlides() {
  const featured = asArray(state.home?.featured);
  const videos = featured.length ? featured : asArray(state.home?.recommend?.items).slice(0, 5);
  return [...activeCampaigns().map(item => ({ ...item, kind: 'campaign', cover: '' })), ...videos];
}
function campaignSlide(item, index, count) {
  return '<div class="hero-slide hero-campaign" data-index="' + index + '">'
    + '<img class="hero-image campaign-image" src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<button class="hero-cover-button" type="button" data-open-campaign="' + esc(item.id) + '" aria-label="查看国庆庆祝页"></button>'
    + '<div class="hero-copy"><h2>' + esc(item.title) + '</h2><p>' + esc(item.description || '').replaceAll('\n', '<br>') + '</p>'
    + '<button class="hero-action" type="button" data-open-campaign="' + esc(item.id) + '">' + esc(item.button || '查看庆祝页') + '</button></div>'
    + '<span class="campaign-date">' + esc(item.dateLabel || '') + '</span>'
    + '<div class="hero-index"><b>' + String(index + 1).padStart(2, '0') + '</b> / ' + String(count).padStart(2, '0') + '</div></div>';
}
function goCampaign(item) {
  state.campaignReturn = captureScreen();
  ++routeToken;
  setRoute('campaign');
  state.campaign = item;
  content.innerHTML = '<article class="campaign-page"><button class="campaign-back" type="button" data-back>' + icon('back') + ' 返回首页</button>'
    + '<section class="campaign-poster"><img src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<div><h1>' + esc(item.title) + '</h1><p>' + esc(item.description || '').replaceAll('\n', '<br>') + '</p><span>' + esc(item.dateLabel || '') + '</span></div></section>'
    + '<section class="campaign-message"><h2>共庆好时光</h2><p>' + esc(item.message || '') + '</p><button type="button" data-back>返回首页</button></section></article>';
  scroll.scrollTop = 0;
}
function stopBannerAuto() {
  clearTimeout(bannerTimer);
  bannerTimer = null;
}
function scheduleBannerAuto() {
  stopBannerAuto();
  if (state.route !== 'home' || document.hidden || homeSlides().length < 2 || !content.querySelector('.hero')) return;
  bannerTimer = setTimeout(() => {
    bannerTimer = null;
    if (state.route !== 'home' || document.hidden) return;
    const count = homeSlides().length;
    if (count > 1) changeBanner((state.banner + 1) % count, 1, true);
  }, 3000);
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopBannerAuto();
  else scheduleBannerAuto();
});
function heroSlide(item, index, count) {
  if (item.kind === 'campaign') return campaignSlide(item, index, count);
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><button class="hero-cover-button" type="button" data-open-id="${esc(item.id || '')}" aria-label="查看${esc(item.title || '作品')}详情"></button><div class="hero-shade"></div>
    <div class="hero-copy"><span class="hero-label">本周焦点 <i></i> 独家精选</span><h2>${esc(item.title || '为你推荐')}</h2><p>${esc(item.description || item.episodeLabel || '')}</p><button class="hero-action" data-open-id="${esc(item.id || '')}" type="button">${icon('play')} 立即观看</button></div>
    <div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
async function changeBanner(targetIndex, direction, automatic = false) {
  const slides = homeSlides();
  const hero = content.querySelector('.hero');
  if (!hero || !slides.length || bannerChanging || targetIndex === state.banner) return;
  stopBannerAuto();
  bannerChanging = true;
  let incoming = null;
  try {
    const item = slides[targetIndex];
    const data = item.kind === 'campaign' ? campaignArtwork(item) : await getCover(item.cover);
    const template = document.createElement('template');
    template.innerHTML = heroSlide(item, targetIndex, slides.length);
    incoming = template.content.firstElementChild;
    const image = incoming.querySelector('img');
    image.src = data;
    if (!hero.isConnected || state.route !== 'home' || document.hidden) return;
    incoming.style.transform = `translateX(${direction > 0 ? 100 : -100}%)`;
    hero.appendChild(incoming);
    await image.decode();
    if (!hero.isConnected || state.route !== 'home' || document.hidden) { incoming.remove(); return; }
    if (item.kind !== 'campaign') applyBannerPalette(image);
    const current = hero.querySelector('.hero-slide:not(:last-child)');
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    incoming.classList.add('animating');
    current.classList.add('animating');
    incoming.style.transform = 'translateX(0)';
    current.style.transform = `translateX(${direction > 0 ? -100 : 100}%)`;
    await new Promise(resolve => setTimeout(resolve, 330));
    current.remove();
    incoming.classList.remove('animating');
    incoming.style.transform = '';
    state.banner = targetIndex;
    content.querySelectorAll('.hero-dots [data-slide]').forEach(dot => dot.classList.toggle('active', Number(dot.dataset.slide) === targetIndex));
  } catch (error) {
    incoming?.remove();
    hero.querySelectorAll('.hero-slide').forEach(slide => { slide.classList.remove('animating'); slide.style.transform = ''; });
    if (!automatic) toast('封面加载失败，请重试', true);
  }
  finally { bannerChanging = false; scheduleBannerAuto(); }
}
function renderHome() {
  const data = state.home;
  if (!data) { renderSkeleton(); return; }
  const slides = homeSlides();
  const index = Math.min(state.banner, Math.max(0, slides.length - 1));
  const selected = slides[index] || {};
  content.innerHTML = `<div class="home-page"><div class="hero" data-hero="1">${heroSlide(selected, index, slides.length)}</div>
      <div class="hero-dots">${slides.map((_, i) => `<i class="${i === index ? 'active' : ''}" data-slide="${i}"></i>`).join('')}</div>
      ${homeTagBar()}
      ${section('精选推荐', asArray(data.homepageRecommend).length ? asArray(data.homepageRecommend) : asArray(data.recommend?.items).slice(0, 12), 'recommend')}
      ${section('最近上新', asArray(data.homepageNewest).length ? asArray(data.homepageNewest) : asArray(data.newest?.items).slice(0, 12), 'newest')}
      ${section('热播短剧', asArray(data.homepageHot), 'rank')}</div>`;
  hydrateImages();
  slides.forEach(item => { if (item.cover) getCover(item.cover).catch(() => {}); });
  scheduleBannerAuto();
}

function renderTags() {
  const tags = asArray(state.tags?.items);
  const groups = tagGroups.map(([id, title]) => {
    const members = tags.filter(tag => tag.group === id);
    if (!members.length) return '';
    return `<section class="tags-group"><h2>${title}</h2><div class="tags-grid">${members.map(tag => {
      const name = String(tag.name || tag.slug);
      const size = name.length > 8 ? 'wide' : name.length > 4 ? 'medium' : '';
      return `<button type="button" class="tag-choice ${size}" data-tag-slug="${esc(tag.slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`;
    }).join('')}</div></section>`;
  }).join('');
  content.innerHTML = `<div class="tags-page"><div class="tags-heading"><button type="button" data-back="1" aria-label="返回首页">${icon('back')}</button><h1>全部类型</h1><span>${tags.length} 个标签</span></div>
    <p class="tags-intro">点选标签，发现相关作品</p>${groups}</div>`;
}

function refreshMissingImages(items) {
  const byId = new Map(items.filter(item => item?.id && item?.cover).map(item => [String(item.id), item.cover]));
  content.querySelectorAll('[data-open-id]').forEach(button => {
    const image = button.querySelector('img[data-cover]') || button.closest('.hero-slide,.category-spotlight')?.querySelector('img[data-cover]');
    const cover = byId.get(button.dataset.openId);
    if (!image || !cover || image.naturalWidth > 0) return;
    image.dataset.cover = cover;
    delete image.dataset.loading;
    loadCoverInto(image);
  });
}

function categoryId() { return categoryIds[state.tab][state.sub[state.tab]]; }
function compactMarkup() {
  const adult = state.tab === 'adult';
  const tabs = adult ? ['成人短剧', '成人漫剧'] : ['AI换脸', 'AI魔改'];
  return `<strong>${adult ? '成人剧场' : 'AI专区'}</strong><div class="compact-tabs" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" aria-selected="${index === state.sub[state.tab]}" data-sub="${index}" class="${index === state.sub[state.tab] ? 'selected' : ''}">${name}</button>`).join('')}</div>`;
}
function updateCompact() {
  const eligible = state.route === 'category';
  if (!eligible || scroll.scrollTop <= 0) state.compactHeld = false;
  else if (scroll.scrollTop > 92) state.compactHeld = true;
  const visible = eligible && state.compactHeld;
  compact.classList.toggle('visible', visible);
  compact.setAttribute('aria-hidden', String(!visible));
  compact.inert = !visible;
  content.querySelector('.category-page')?.classList.toggle('is-collapsed', visible);
}
scroll.addEventListener('scroll', updateCompact, { passive: true });

function renderCategory(keepCompact = false) {
  categoryObserver?.disconnect();
  const adult = state.tab === 'adult';
  const tabs = adult ? ['成人短剧', '成人漫剧'] : ['AI换脸', 'AI魔改'];
  const items = asArray(state.category?.items);
  const lead = items[0] || null;
  content.innerHTML = `<div class="category-page"><div class="category-head"><h1>${adult ? '成人剧场' : 'AI专区'}</h1><p>${adult ? '短剧与漫剧，走进不同的人生。' : '换脸与魔改，探索影像新可能。'}</p></div>
    <div class="segmented" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" data-sub="${index}" aria-selected="${index === state.sub[state.tab]}" class="${index === state.sub[state.tab] ? 'selected' : ''}">${name}</button>`).join('')}</div>
    ${lead ? `<div class="category-spotlight"><img data-cover="${esc(lead.cover)}" alt=""><div class="category-spotlight-copy"><h2>${esc(lead.title)}</h2><button type="button" data-open-id="${esc(lead.id)}">查看作品 ${icon('arrow')}</button></div></div>` : ''}
    <section class="category-section"><div class="section-heading"><h2>${tabs[state.sub[state.tab]]}作品</h2><span class="category-count">已显示 ${items.length} 部</span></div>
    <div class="search-grid category-grid">${items.map(itemCard).join('')}</div><div class="category-footer">${categoryFooter()}</div></section></div>`;
  compact.innerHTML = compactMarkup();
  scroll.scrollTop = keepCompact ? 150 : 0;
  state.compactHeld = keepCompact;
  updateCompact();
  hydrateImages();
  observeCategoryEnd();
}

function categoryFooter() {
  const category = state.category;
  if (category?.error) return '<button type="button" data-retry-category="1">加载失败，点击重试</button>';
  if (category?.loading) return '<span role="status">正在加载更多…</span>';
  if (category?.refreshing) return '<span role="status">正在更新列表…</span>';
  if (category?.nextPage) return '<span class="category-sentinel" aria-hidden="true"></span>';
  return '<span>已经到底了</span>';
}

function observeCategoryEnd() {
  categoryObserver?.disconnect();
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const sentinel = content.querySelector('.category-sentinel');
  if (!sentinel) return;
  if (!categoryObserver) {
    categoryObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreCategory();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  categoryObserver.observe(sentinel);
}

function updateCategoryFooter() {
  const footer = content.querySelector('.category-footer');
  if (!footer) return;
  footer.innerHTML = categoryFooter();
  observeCategoryEnd();
}

function renderLibrary() {
  const data = state.library || { progress: [], bookmarks: [] };
  const query = state.libraryQuery.trim().toLocaleLowerCase();
  const matches = item => String(item.title || '').toLocaleLowerCase().includes(query);
  const progress = asArray(data.progress).filter(matches);
  const bookmarks = asArray(data.bookmarks).filter(matches);
  const historyActive = state.libraryTab === 'history';
  const historyCards = progress.map(item => {
    const completed = Boolean(item.completed);
    const episode = Number(item.episode) || 1;
    const percentage = completed ? 100 : Math.max(0, Math.min(100, item.duration ? item.position / item.duration * 100 : 0));
    return `<button type="button" class="history-card" data-history-id="${esc(item.id)}" data-history-episode="${episode}" data-history-completed="${completed}" aria-label="${esc(item.title)}，第 ${episode} 集，${completed ? '已看完，打开详情' : '继续观看'}">
      <span class="history-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span><span class="history-copy"><span class="history-status">${completed ? '已看完' : '继续观看'} · 第 ${episode} 集</span>
      <strong>${esc(item.title || '影片')}</strong><span class="progress"><i style="width:${percentage}%"></i></span><span class="history-hint">${completed ? '打开详情选择剧集' : `已看 ${Math.floor(item.position / 60000)} 分钟`}</span></span></button>`;
  }).join('');
  content.innerHTML = `<div class="library-page"><div class="my-heading"><h1>我的</h1></div>
    ${state.account.loggedIn ? '' : `<button type="button" class="my-account" data-account-action="1">点击头像登录，同步收藏和观看历史 ${icon('arrow')}</button>`}
    ${query ? `<div class="library-results"><p class="library-count">在观看历史中找到 ${progress.length} 部，在我的收藏中找到 ${bookmarks.length} 部</p>
      <h2>观看历史</h2>${progress.length ? `<div class="history-list">${historyCards}</div>` : '<p class="library-no-match">没有匹配的观看记录</p>'}
      <h2>我的收藏</h2>${bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<p class="library-no-match">没有匹配的收藏</p>'}</div>` : `
    <div class="segmented library-tabs" role="tablist" aria-label="我的片单分类"><button type="button" role="tab" data-library-tab="history" aria-selected="${historyActive}" class="${historyActive ? 'selected' : ''}">观看历史</button>
    <button type="button" role="tab" data-library-tab="bookmarks" aria-selected="${!historyActive}" class="${!historyActive ? 'selected' : ''}">我的收藏</button></div>
    <p class="library-count">${historyActive ? `最近观看的 ${progress.length} 部作品` : `已收藏 ${bookmarks.length} 部作品`}</p>
    ${historyActive ? (progress.length ? `<div class="history-list">${historyCards}</div>` : '<div class="empty-state">还没有观看记录，播放作品后会出现在这里</div>') :
      (bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<div class="empty-state">还没有收藏作品</div>')}`}</div>`;
  hydrateImages();
}

function renderSettings() {
  try { renderSettingsBody(); }
  catch (error) {
    api('js-log', { msg: 'settings-render-fail ' + (error && error.message ? error.message : error) }).catch(() => {});
    content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回</button>`
      + `<h1>设置</h1><section><h2>设置页加载失败</h2><p>${esc(String(error && error.message || error))}</p>`
      + `<p>可返回后重试；若持续出现，请在「站点地址」里反馈诊断日志。</p></section></div>`;
  }
}

function renderSettingsBody() {
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} ${state.settingsReturn?.route === 'library' ? '返回我的' : '返回'}</button><h1>设置</h1>
    <section><button type="button" class="settings-link" data-account-management="1"><span><strong>账号管理</strong><small>${state.account.loggedIn ? esc(state.account.username) : '登录或注册账号'}</small></span>${icon('arrow')}</button></section>
    <section class="settings-library"><h2>片单管理</h2><p>${state.account.loggedIn ? '清空后会同步到当前账号的其他设备。' : '清空当前设备保存的片单。'}</p>
      <button type="button" data-clear="history" ${asArray(state.library?.progress).length ? '' : 'disabled'}>清空观看历史</button>
      <button type="button" data-clear="bookmarks" ${asArray(state.library?.bookmarks).length ? '' : 'disabled'}>清空我的收藏</button></section>
    <section class="settings-site"><h2>站点地址</h2><p>启动时自动从官方发布页获取最新地址并测速，使用最快的；地址失效后重进应用即可自动更换。也可手动粘贴地址，手动地址优先。</p>
      <form id="site-origin-form" class="site-form">
        <input id="site-origin-input" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="例如：eehwl.gkudvxhjh.cc" aria-label="替代站点地址">
        <p class="site-current site-log" data-site-auto hidden></p>
        <p class="site-current site-log" data-site-current hidden></p>
        <p class="site-current site-log" data-site-test hidden></p>
        <p class="site-current site-log" data-site-diag hidden></p>
        <div class="site-form-actions"><button type="submit">保存并测试</button><button type="button" data-discover-site>检测最新地址</button><button type="button" data-reset-site hidden>恢复默认</button></div>
      </form></section>
    <section class="update-settings"><div><h2>应用更新</h2><button type="button" class="app-version ${updateAvailable ? 'has-update' : ''}" data-open-update aria-label="${updateAvailable ? `当前版本 ${esc(appVersion)}，发现新版 ${esc(updateAvailable.version)}，查看更新` : `当前版本 ${esc(appVersion)}，检查更新`}"><span class="app-version-text">版本 ${appVersion ? `v${esc(appVersion)}` : '读取中…'}</span></button><p>启动后自动检查，也可以在这里手动检查。</p></div><button type="button" data-check-update>检查更新</button></section>
    <section class="settings-storage"><h2>本地存储</h2>
      <p class="cache-stats" data-cache-stats>正在统计缓存…</p>
      <button type="button" data-clear="cache">清理内容缓存</button></section></div>`;
  refreshCacheStats();
  api('site-origin').then(info => {
    const input = content.querySelector('#site-origin-input');
    const status = content.querySelector('[data-site-current]');
    const test = content.querySelector('[data-site-test]');
    const reset = content.querySelector('[data-reset-site]');
    if (!input || !status) return;
    // Do not clobber the attributed "当前使用站点" line that auto-site renders;
    // only fall back to the plain current-site text when that line is still empty.
    if (!String(status.textContent || '').startsWith('当前使用站点')) {
      status.textContent = `当前使用站点：${info.current}${info.resolved && info.resolved !== info.current ? `（自动解析 → ${info.resolved}）` : ''}`;
      status.hidden = false;
    }
    if (info.custom) {
      input.value = info.custom;
      if (reset) reset.hidden = false;
      if (test) {
        const raw = String(info.status || '');
        const sep = raw.indexOf(':');
        const st = sep === -1 ? raw : raw.slice(0, sep);
        const detail = sep === -1 ? '' : raw.slice(sep + 1);
        test.textContent = st === 'ok' ? '上次测试：站点可达'
          : st === 'webview' ? (detail.indexOf('未检测到跳转') === 0
            ? `上次测试：浏览器通道已连通，但前门未跳转（${detail.slice('未检测到跳转，'.length) || '无详情'}）`
            : `上次测试：原生通道被站点过滤，已通过内置浏览器通道验证可达（解析到 ${detail}）`)
          : st === 'unreachable' ? `上次测试：两条通道均未连通${detail ? `（${detail}）` : ''}，内容可能无法加载`
          : '';
        test.hidden = !st;
      }
    }
    const diag = content.querySelector('[data-site-diag]');
    if (diag && info.diag && String(info.diag).trim()) {
      diag.hidden = false;
      // Line 5: keep the raw log readable — newline separated, no "｜" squeeze and
      // no 6-line chop (that truncation is what hid most of the diagnostics).
      const rows = String(info.diag).trim().split('\n').slice(0, 12);
      diag.textContent = '诊断日志：\n' + rows.join('\n');
    }
  }).catch(() => {});
  const refreshAuto = info => {
    const auto = content.querySelector('[data-site-auto]');
    const currentLine = content.querySelector('[data-site-current]');
    const discover = content.querySelector('[data-discover-site]');
    if (auto) {
      // Fixed layout: line 1 publish sites + total, line 2 per-site counts,
      // line 3 the site being probed right now. Never collapse these into a
      // single overwritten progress string — the old code only kept the last one.
      const lines = [];
      const connected = Number(info.sourcesConnected || 0);
      const total = Number(info.totalSites || 0);
      const stats = asArray(info.sourceStats);
      if (info.checking || total || connected) {
        lines.push(`发布站：当前已成功抓取 ${connected} 个发布站点；有效站点总数：${total} 个。`);
      }
      if (stats.length) {
        lines.push('发布站统计：' + stats.map(item => `[${item.label} ${item.count} 个]`).join(' '));
      }
      if (info.probeUrl) {
        const source = info.probeSource ? `[${info.probeSource}]` : '';
        lines.push(`当前测速站点：${info.probeUrl} 所属发布站 ${source} ${info.probeIndex || 0}/${info.probeTotal || 0}`);
      } else if (info.checking) {
        const stageLabels = { verify: '快速验证当前地址', quick: '快速接管（GitHub 优先）', sweep: '全量扫描', silent: '后台静默优选' };
        lines.push(`当前测速站点：${stageLabels[info.stage] || '正在连接官方发布页…'}`);
      } else if (info.progress && /^[✗⚠⏱]/.test(info.progress)) {
        lines.push(`当前测速站点：上次检测未完成 — ${info.progress}`);
      }
      auto.textContent = lines.join('\n');
      auto.hidden = lines.length === 0;
    }
    if (currentLine && info.origin) {
      // Line 4: the site actually in use, with the publish site that delivered it.
      const source = info.usingSource ? ` 所属发布站 [${info.usingSource}]` : '';
      currentLine.textContent = `当前使用站点：${info.origin}${source}`;
      currentLine.hidden = false;
    }
    if (discover && !info.checking) discover.disabled = false;
  };
  window.hgAutoSite = refreshAuto;
  api('auto-site').then(refreshAuto).catch(() => {});
}

function renderAccountManagement() {
  const account = state.account;
  content.innerHTML = `<div class="settings-page account-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>账号管理</h1>
    ${account.loggedIn ? `<section class="account-identity"><img src="${esc(account.avatar || 'user.svg')}" alt=""><div><strong>${esc(account.username)}</strong><span>已登录黄果账号</span></div></section>
      <section class="settings-account"><h2>个人资料</h2><button type="button" data-choose-avatar="1">从相册更换头像</button></section>
      <section class="settings-account"><h2>修改密码</h2><p>先验证旧密码，再设置至少 8 位的新密码。</p>
        <form id="password-form" class="password-form">
          <label for="old-password">旧密码</label><input id="old-password" type="password" autocomplete="current-password" required>
          <label for="new-password">新密码</label><input id="new-password" type="password" autocomplete="new-password" minlength="8" required>
          <label for="confirm-password">再次输入新密码</label><input id="confirm-password" type="password" autocomplete="new-password" minlength="8" required>
          <p id="password-error" class="auth-error" role="alert" hidden></p><button type="submit">确认修改</button>
        </form></section>
      <section class="settings-account"><h2>同步与登录</h2><button type="button" data-sync-now="1">立即同步片单</button><button type="button" data-logout="1" aria-live="polite">退出登录</button></section>` : `<section class="settings-account"><p>登录后可同步收藏和观看历史，并管理头像与密码。</p><button type="button" data-account-action="1">登录或注册</button></section>`}</div>`;
}

function renderSearch() {
  const filters = [['all', '全部'], ['ai-duanju', '成人短剧'], ['ai-manju', '成人漫剧'], ['ai-huanlian', 'AI换脸'], ['ai-mogai', 'AI魔改']];
  const all = asArray(state.search?.items);
  const items = state.filter === 'all' ? all : all.filter(item => {
    if (item.category) return item.category === state.filter;
    const title = String(item.title || '');
    if (state.filter === 'ai-huanlian') return /AI\s*换脸/.test(title);
    if (state.filter === 'ai-mogai') return /AI\s*魔改/.test(title);
    if (state.filter === 'ai-manju') return /漫剧/.test(title);
    return !/AI\s*(换脸|魔改)|漫剧/.test(title);
  });
  content.innerHTML = `<div class="search-page"><h1>搜索结果</h1><p>${esc(state.query)} · ${items.length} 部作品</p><div class="filter-row">${filters.map(([id, label]) => `<button type="button" data-filter="${id}" class="${state.filter === id ? 'active' : ''}">${label}</button>`).join('')}</div>
    ${items.length ? `<div class="search-grid">${items.map(itemCard).join('')}</div>` : '<div class="empty-state">没有找到匹配的影片</div>'}</div>`;
  hydrateImages();
}

function listingFooter() {
  const listing = state.listing;
  if (listing.error) return '<button type="button" data-retry-listing="1">加载失败，点击重试</button>';
  if (listing.loading) return '<span role="status">正在加载更多…</span>';
  if (listing.refreshing) return '<span role="status">正在更新列表…</span>';
  if (listing.nextPage || listing.kind === 'score') {
    if (listingHasMore(listing)) return listing.kind === 'tag-list' ? '<span class="listing-sentinel" aria-hidden="true"></span>' : '<span class="listing-sentinel">继续下滑加载更多</span>';
  }
  return '<span>已经到底了</span>';
}

function observeListingEnd() {
  listingObserver?.disconnect();
  if (state.route !== 'listing' || !listingHasMore(state.listing || {}) || state.listing.loading ||
      state.listing.refreshing || state.listing.error) return;
  const sentinel = content.querySelector('.listing-sentinel');
  if (!sentinel) return;
  if (!listingObserver) {
    listingObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreListing();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  listingObserver.observe(sentinel);
}

function updateListingFooter() {
  const footer = content.querySelector('.listing-footer');
  if (!footer) return;
  footer.innerHTML = listingFooter();
  observeListingEnd();
}

const rankTabs = [['hot', '热播榜'], ['recommend', '推荐榜'], ['potential', '飙升榜']];

function listingTitle(listing) {
  if (listing.kind === 'topic') return listing.title || '专题';
  if (listing.kind === 'rank') return '热播短剧';
  if (listing.kind === 'score') return '按评分排序';
  if (listing.kind === 'tag-list') return `#${String(listing.label || listing.slug).replace(/^#+/, '')}`;
  return listing.kind === 'recommend' ? '精选推荐' : '最近上新';
}

function listingCount(listing) {
  const total = Number(listing.total || 0);
  const shown = listing.kind === 'score' ? Math.min(listing.shown || 60, listing.items.length) : listing.items.length;
  return total > shown ? `共 ${total} 部 · 已加载 ${shown} 部` : `共 ${total || shown} 部`;
}

function listingHasMore(listing) {
  if (listing.kind === 'score') return (listing.shown || 60) < listing.items.length;
  return !!listing.nextPage;
}

function scoreValue(item) {
  const value = parseFloat(String(item?.score ?? '').replace(/[^0-9.]/g, ''));
  return Number.isFinite(value) ? value : 0;
}

function renderListing() {
  const listing = state.listing;
  // Score mode starts empty by design while it crawls every page; showing the
  // home skeleton there made the whole screen flash back to "loading home".
  if (!listing?.items.length && listing?.refreshing && listing.kind !== 'score') { renderSkeleton(); return; }
  const title = listingTitle(listing);
  const backLabel = listing.kind === 'topic' ? '返回专题'
    : listing.kind === 'tag-list' && state.listingReturn?.route === 'tags' ? '返回全部类型' : '返回首页';
  const tabs = listing.kind === 'rank' ? `<div class="rank-tabs">${rankTabs.map(([id, label]) =>
    `<button type="button" data-rank-tab="${id}" class="${(listing.rank || 'hot') === id ? 'active' : ''}">${label}</button>`).join('')}</div>` : '';
  const sortButton = listing.kind === 'newest' ? '<button type="button" class="score-sort" data-score-sort="1">按评分排序</button>'
    : listing.kind === 'score' ? '<button type="button" class="score-sort" data-sort-newest="1">按上新时间</button>' : '';
  const visible = listing.kind === 'score' ? listing.items.slice(0, listing.shown || 60) : listing.items;
  content.innerHTML = `<div class="listing-page"><button class="listing-back" type="button" data-back="1">${icon('back')} ${backLabel}</button>
    <h1>${esc(title)}</h1>${tabs}<p class="listing-count"><span>${esc(listing.progress || listingCount(listing))}</span>${sortButton}</p>
    ${visible.length ? `<div class="search-grid listing-grid">${visible.map(itemCard).join('')}</div>` : (listing.refreshing ? '<div class="empty-state">正在统计全站作品，请稍候…</div>' : '<div class="empty-state">这个标签下暂无作品</div>')}
    <div class="listing-footer">${listingFooter()}</div></div>`;
  hydrateImages();
  observeListingEnd();
}

function renderDetail() {
  const data = state.detail;
  if (!data) { renderSkeleton(); return; }
  const loading = state.detailLoading;
  window.AndroidHost?.setDetailMode?.(true);
  const episodes = asArray(data.episodes);
  content.innerHTML = `<div class="detail-page"><button class="detail-back" type="button" data-back="1">${icon('back')} 返回</button>
    <div class="detail-art"><img data-cover="${esc(data.cover)}" alt=""></div><div class="detail-copy"><h1>${esc(data.title)}</h1><p>${loading ? '正在获取内容…' : `${esc(data.score)} ${esc(data.episodeLabel)}`}</p><p class="detail-description">${esc(data.description || '')}</p>
    <div class="detail-buttons"><button class="detail-play" type="button" data-play="1" ${loading ? 'disabled' : ''}>${loading ? '正在加载…' : '立即播放'}</button><button class="bookmark-button" type="button" data-bookmark="1" aria-label="${state.bookmarked ? '取消收藏' : '收藏'}" ${loading ? 'disabled' : ''}>${state.bookmarked ? '★' : '☆'}</button></div>
    ${loading ? '<div class="detail-loading" role="status">正在获取选集…</div>' : episodes.length ? `<h2>选集</h2><div class="episode-grid">${episodes.map((item, index) => `<button type="button" data-episode="${item.number}" class="${index === 0 ? 'active' : ''}">${item.number}</button>`).join('')}</div>` : ''}</div></div>`;
  hydrateImages();
}

function renderSkeleton() {
  content.innerHTML = '<div class="skeleton-home" role="status" aria-label="正在加载内容"><div class="skeleton skeleton-hero"></div><div class="skeleton skeleton-title"></div><div class="skeleton-row"><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div></div><div class="skeleton skeleton-line"></div></div>';
}
function renderError(error, retry) {
  if (state.route === 'detail') window.AndroidHost?.setDetailMode?.(false);
  if (/站点连接失败|站点请求失败|failed to connect|unable to resolve host|unknownhost|connectexception|sockettimeoutexception|timed? out|network is unreachable|connection reset|connection refused|网络|连接失败|无法连接/i.test(error?.message || '')) {
    content.innerHTML = `<div class="error-state error-state--network" role="alert">
      <div class="error-main">
        <svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="m12 24 6-5 6 5 6-5 6 5"/></svg>
        <h1>网络走丢了</h1>
      </div>
      <div class="error-footer"><p>请尝试使用代理网络，或联系开发者。${error?.message ? `<span style="display:block;margin-top:6px;font-size:11px;opacity:.7">原因：${esc(error.message)}</span>` : ''}<span style="display:block;margin-top:6px;font-size:11px;opacity:.55">已自动触发地址重检，进度见「设置 → 站点地址」；也可立即点「检测最新地址」强制刷新</span></p><button type="button" data-retry="${esc(retry)}">重新尝试</button></div>
    </div>`;
    return;
  }
  content.innerHTML = `<div class="error-state">${esc(error.message || '加载失败，请稍后重试')}<button type="button" data-retry="${esc(retry)}">重试</button></div>`;
}

function rememberScreen() {
  if (state.route === 'detail') return;
  const previewContent = content.cloneNode(true);
  previewContent.removeAttribute('id');
  screenSnapshots[state.route] = {
    content: previewContent, scrollTop: scroll.scrollTop,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function captureScreen() {
  rememberScreen();
  return {
    route: state.route, tab: state.tab, pageNode: content.firstElementChild,
    scrollTop: scroll.scrollTop, searchValue: searchInput.value,
    listing: state.route === 'listing' ? state.listing : null,
    listingReturn: state.listingReturn, tagsReturn: state.tagsReturn,
    compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function restoreScreen(snapshot) {
  if (!snapshot?.pageNode) { goHome(); return; }
  ++routeToken;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  setRoute(snapshot.route);
  state.tab = snapshot.tab;
  state.compactHeld = snapshot.compactHeld;
  if (snapshot.listing) state.listing = snapshot.listing;
  state.listingReturn = snapshot.listingReturn;
  state.tagsReturn = snapshot.tagsReturn;
  content.replaceChildren(snapshot.pageNode);
  if (snapshot.route === 'library') renderLibrary();
  if (snapshot.route === 'settings') renderSettings();
  searchInput.value = snapshot.searchValue || '';
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = snapshot.scrollTop || 0;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  hydrateImages();
  if (snapshot.route === 'home') scheduleBannerAuto();
  if (snapshot.route === 'category') { compact.innerHTML = compactMarkup(); updateCompact(); observeCategoryEnd(); }
  if (snapshot.route === 'listing') observeListingEnd();
}

function setRoute(route) {
  if (route !== 'home') stopBannerAuto();
  // 推荐是原生层，切到任何其他路由都要先把它摘掉，否则会盖住页面。
  if (state.route === 'feed' && route !== 'feed') {
    state.feedAlive = false;
    api('feed-close').catch(() => {});
  }
  if (state.route === 'detail' && route !== 'detail') { detailCard?.dispose(); detailCard = null; }
  const libraryArea = ['library', 'settings', 'account-management'].includes(route);
  if (state.route === 'library' && route !== 'library') searchInput.value = '';
  state.route = route;
  window.hgScheduleResourceApply?.();
  document.getElementById('app').dataset.route = route;
  window.hgThemeRefresh?.();
  document.getElementById('app').classList.toggle('route-detail', route === 'detail');
  window.AndroidHost?.setDetailMode?.(route === 'detail');
  headerSettings.hidden = route !== 'library';
  searchInput.placeholder = libraryArea ? '搜索观看历史和收藏' : '搜索剧名或 #标签';
  searchInput.setAttribute('aria-label', libraryArea ? '搜索观看历史和收藏' : '搜索剧名或标签');
  if (route === 'library') searchInput.value = state.libraryQuery;
  window.AndroidHost?.setBackEnabled(route !== 'home');
}

function backDestination() {
  if (state.route === 'campaign') return state.campaignReturn?.route || 'home';
  if (state.route === 'detail') return state.previous?.route || 'home';
  if (state.route === 'account-management') return state.accountReturn?.route || 'settings';
  if (state.route === 'settings') return state.settingsReturn?.route || 'library';
  if (state.route === 'tags') return state.tagsReturn?.route || 'home';
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') return state.listingReturn?.route || 'home';
  if (state.route === 'listing' && state.listing?.kind === 'topic') return 'topics';
  if (state.route === 'topics') return 'home';
  return state.route === 'home' ? null : 'home';
}

function clearPredictivePreview() {
  clearTimeout(predictiveTimer);
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  if (predictiveSourceNode && predictivePreview?.contains(predictiveSourceNode)) predictiveSourceNode.remove();
  predictiveSourceNode = null;
  predictivePreview?.remove();
  predictivePreview = null;
  document.getElementById('app').classList.remove('predictive-active');
  scroll.style.removeProperty('transition');
  scroll.style.removeProperty('transform');
}

window.hgPredictiveBackStart = fromRight => {
  clearPredictivePreview();
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && updateOverlay.hidden) {
    detailCard.fromRight = fromRight;
    return detailCard.begin();
  }
  const destination = backDestination();
  const fromDetail = state.route === 'detail' && state.previous?.route === destination && state.previous.pageNode;
  const snapshot = fromDetail ? state.previous : screenSnapshots[destination];
  if (!snapshot) return false;
  const preview = document.createElement('div');
  preview.className = 'back-preview';
  preview.setAttribute('aria-hidden', 'true');
  if (state.route === 'detail') {
    const header = document.querySelector('#app > .app-top').cloneNode(true);
    const settings = header.querySelector('.header-settings');
    settings.hidden = destination !== 'library';
    if (destination === 'library') {
      const input = header.querySelector('.search-input');
      input.placeholder = '搜索观看历史和收藏';
      input.value = state.libraryQuery;
    }
    header.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    preview.appendChild(header);
  }
  if (fromDetail) {
    const holder = document.createElement('div');
    holder.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    predictiveSourceNode = snapshot.pageNode;
    holder.appendChild(predictiveSourceNode);
    preview.appendChild(holder);
  } else {
    predictiveSourceNode = snapshot.content;
    predictiveSourceNode.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    preview.appendChild(predictiveSourceNode);
  }
  document.getElementById('app').appendChild(preview);
  preview.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  document.getElementById('app').classList.add('predictive-active');
  scroll.style.transition = 'none';
  predictivePreview = preview;
  predictivePreview.fromRight = fromRight;
  return true;
};

window.hgPredictiveBackProgress = progress => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') {
    detailCard.predictive(Math.max(0, Math.min(1, progress)), detailCard.fromRight);
    return;
  }
  if (!predictivePreview) return;
  pendingPredictiveProgress = Math.max(0, Math.min(1, progress));
  if (predictiveFrame) return;
  predictiveFrame = requestAnimationFrame(() => {
    predictiveFrame = null;
    if (!predictivePreview) return;
    const direction = predictivePreview.fromRight ? -1 : 1;
    scroll.style.transform = `translate3d(${direction * pendingPredictiveProgress * 100}%,0,0)`;
  });
};

window.hgPredictiveBackCancel = () => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') { detailCard.cancel(); return; }
  if (!predictivePreview) return;
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  scroll.style.transition = 'transform 180ms ease-out';
  scroll.style.transform = 'translate3d(0,0,0)';
  predictiveTimer = setTimeout(clearPredictivePreview, 180);
};

window.hgPredictiveBackCommit = () => {
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && updateOverlay.hidden) {
    detailCard.close(); return true;
  }
  if (!predictivePreview) return window.hgBack();
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  const direction = predictivePreview.fromRight ? -1 : 1;
  scroll.style.transition = 'transform 150ms ease-out';
  scroll.style.transform = `translate3d(${direction * 100}%,0,0)`;
  predictiveTimer = setTimeout(() => {
    const oldPage = content.firstElementChild;
    if (!window.hgBack()) { clearPredictivePreview(); return; }
    if (content.firstElementChild !== oldPage) { clearPredictivePreview(); return; }
    const changed = new MutationObserver(() => {
      if (content.firstElementChild === oldPage) return;
      changed.disconnect();
      clearPredictivePreview();
    });
    changed.observe(content, { childList: true });
    predictiveTimer = setTimeout(() => { changed.disconnect(); clearPredictivePreview(); }, 1500);
  }, 150);
  return true;
};

function setNav(tab) {
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === tab));
  compact.classList.remove('visible');
  compact.inert = true;
  state.compactHeld = false;
  scroll.scrollTop = 0;
}

async function goHome() {
  const token = ++routeToken;
  stopBannerAuto();
  setRoute('home'); state.tab = 'home'; setNav('home');
  // 先切页再取数：桥接请求变慢时页面也要立刻响应，不能停在上一页的数据上。
  renderSkeleton();
  const refreshing = api('home');
  const cached = await api('cached', { key: 'home' }).catch(() => null);
  if (token !== routeToken) return;
  state.home = cached?.data || null;
  if (state.home) renderHome(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = state.home && homeSignature(state.home) !== homeSignature(fresh);
    const firstLoad = !state.home;
    state.home = fresh;
    if (firstLoad || changed) renderHome();
    else refreshMissingImages([...asArray(fresh.featured), ...asArray(fresh.recommend?.items), ...asArray(fresh.newest?.items)]);
    if (changed) toast('更新成功');
  } catch (error) { if (!state.home && token === routeToken) renderError(error, 'home'); }
}

async function goTags() {
  const token = ++routeToken;
  if (state.route !== 'tags') state.tagsReturn = captureScreen();
  setRoute('tags'); state.tab = 'home'; setNav('home');
  const refreshing = api('tags');
  const cached = await api('cached', { key: 'tags' }).catch(() => null);
  if (token !== routeToken) return;
  state.tags = cached?.data || state.tags;
  if (state.tags) renderTags(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = JSON.stringify(state.tags?.items) !== JSON.stringify(fresh.items);
    const previousScroll = scroll.scrollTop;
    state.tags = fresh;
    if (changed) { renderTags(); scroll.scrollTop = previousScroll; }
  } catch (error) { if (token === routeToken && !state.tags) renderError(error, 'tags'); }
}

async function goTag(slug, label) {
  const token = ++routeToken;
  if (state.route !== 'listing' || state.listing?.kind !== 'tag-list' || state.listing?.slug !== slug) state.listingReturn = captureScreen();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  searchInput.value = `#${String(label).replace(/^#+/, '')}`;
  const refreshing = api('tag-list', { slug, page: 1 });
  const cached = await api('cached', { key: `tag:${slug}:1` }).catch(() => null);
  if (token !== routeToken) return;
  const firstLoad = !cached?.data;
  state.listing = cached?.data ? { ...cached.data, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false } :
    { items: [], nextPage: null, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false };
  renderListing();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = listSignature(state.listing) !== listSignature(fresh) || state.listing.nextPage !== fresh.nextPage;
    const previousScroll = scroll.scrollTop;
    state.listing = { ...fresh, kind: 'tag-list', slug, label, refreshing: false, loading: false, error: false };
    if (firstLoad || changed) { renderListing(); scroll.scrollTop = previousScroll; }
    else updateListingFooter();
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.listing.items.length) renderError(error, 'tag-list');
    else { state.listing.refreshing = false; updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goTagQuery(query) {
  const name = query.replace(/^[#＃]\s*/, '').trim();
  if (!name) { goTags(); return; }
  let tags = asArray(state.tags?.items);
  if (!tags.length) {
    const cached = await api('cached', { key: 'tags' }).catch(() => null);
    tags = asArray(cached?.data?.items);
  }
  if (!tags.length) {
    try { const fresh = await api('tags'); state.tags = fresh; tags = asArray(fresh.items); }
    catch { toast('标签暂时无法加载，请重试', true); return; }
  }
  const normalize = value => String(value || '').replace(/^#+/, '').trim().toLocaleLowerCase();
  let tag = tags.find(item => normalize(item.name) === normalize(name));
  if (!tag) {
    try { const fresh = await api('tags'); state.tags = fresh; tag = asArray(fresh.items).find(item => normalize(item.name) === normalize(name)); }
    catch { /* 已有清单仍可用于搜索 */ }
  }
  if (tag) goTag(tag.slug, tag.name);
  else toast('没有找到这个标签', true);
}

async function openListing(kind) {
  if (kind === 'rank') return openRank('hot');
  if (kind !== 'recommend' && kind !== 'newest') return;
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home';
  setNav('home');
  const initial = state.home?.[kind];
  state.listing = {
    kind, items: [...asArray(initial?.items)], nextPage: initial?.nextPage || null,
    total: initial?.total || 0,
    refreshing: true, loading: false, error: false
  };
  renderListing();
  try {
    const fresh = await api(kind, { page: 1 });
    if (token !== routeToken) return;
    state.listing.items = asArray(fresh.items);
    state.listing.nextPage = fresh.nextPage;
    state.listing.total = fresh.total || 0;
    state.listing.refreshing = false;
    const previousScroll = scroll.scrollTop;
    renderListing();
    scroll.scrollTop = previousScroll;
  } catch (error) {
    if (token !== routeToken) return;
    state.listing.refreshing = false;
    if (!state.listing.items.length) renderError(error, 'listing');
    else { updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

function topicCard(topic) {
  const count = Number(topic.count || 0);
  return `<button class="topic-card" type="button" data-topic-slug="${esc(topic.slug)}" data-topic-title="${esc(topic.title)}">
    <span class="poster topic-cover loading"><img data-cover="${esc(topic.cover || '')}" alt="">${count ? `<span class="topic-badge">${count} 部</span>` : ''}</span>
    <span class="topic-title">${esc(topic.title)}</span>
    <span class="topic-desc">${esc(topic.description || '')}</span></button>`;
}

function renderTopics() {
  const items = asArray(state.topics?.items);
  content.innerHTML = `<div class="topics-page"><div class="topics-heading"><h1>专题</h1><span>${items.length} 个专题</span></div>
    ${items.length ? `<div class="topics-grid">${items.map(topicCard).join('')}</div>` : '<div class="empty-state">暂无专题</div>'}</div>`;
  hydrateImages();
}

async function goTopics() {
  const token = ++routeToken;
  listingObserver?.disconnect();
  setRoute('topics'); state.tab = 'topics'; setNav('topics');
  if (state.topics) renderTopics(); else renderSkeleton();
  try {
    const data = await api('topics');
    if (token !== routeToken) return;
    state.topics = data;
    renderTopics();
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.topics) renderError(error, 'topics');
  }
}

const SCORE_PAGE_SIZE = 60;
const SCORE_MAX_PAGES = 120;

/**
 * 全站按评分排序。站点没有服务端排序（?sort=/?order= 都被忽略），所以这里
 * 逐页抓「最近上新」把全站作品并成一份内存列表再本地排序。每页一次桥调用，
 * 页面保持可滚动、进度可见；抓完只渲染前 60 张，其余下滑增量追加。
 */
async function openScoreBoard() {
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  state.listing = {
    kind: 'score', items: [], nextPage: null, total: 0, shown: SCORE_PAGE_SIZE,
    progress: '正在统计全站作品…', refreshing: true, loading: false, error: false
  };
  renderListing();
  loadScoreBoard(token);
}

async function loadScoreBoard(token) {
  const listing = state.listing;
  const seen = new Map();
  try {
    let page = 1, pages = 1, total = 0;
    while (token === routeToken && state.listing === listing && page >= 1 && page <= SCORE_MAX_PAGES) {
      const data = await api('newest', { page });
      if (token !== routeToken || state.listing !== listing) return;
      total = Number(data.total || 0) || total;
      const items = asArray(data.items);
      items.forEach(item => { if (item?.id) seen.set(String(item.id), item); });
      if (total && items.length) pages = Math.ceil(total / items.length);
      listing.progress = `正在统计全站 ${seen.size}${total ? '/' + total : ''} 部 · 第 ${page}/${Math.max(pages, page)} 页`;
      const text = content.querySelector('.listing-count > span');
      if (text) text.textContent = listing.progress; else renderListing();
      if (!data.nextPage) break;
      page = Number(data.nextPage);
    }
    if (token !== routeToken || state.listing !== listing) return;
    listing.items = [...seen.values()].sort((a, b) => scoreValue(b) - scoreValue(a));
    listing.total = listing.items.length;
    listing.shown = SCORE_PAGE_SIZE;
    listing.progress = '';
    listing.refreshing = false;
    renderListing();
  } catch (error) {
    if (token !== routeToken || state.listing !== listing) return;
    listing.refreshing = false;
    listing.progress = '';
    if (!listing.items.length) { renderError(error, 'listing'); return; }
    renderListing();
    toast('评分排序加载中断，已显示已获取的部分', true);
  }
}

function appendScoreMore() {
  const listing = state.listing;
  if (!listing || listing.kind !== 'score') return;
  const from = listing.shown || SCORE_PAGE_SIZE;
  const next = listing.items.slice(from, from + SCORE_PAGE_SIZE);
  if (!next.length) return;
  listing.shown = from + next.length;
  const grid = content.querySelector('.listing-grid');
  if (grid) {
    grid.insertAdjacentHTML('beforeend', next.map(itemCard).join(''));
    const images = [...grid.querySelectorAll('img[data-cover]')].slice(-next.length);
    images.forEach((image, index) => index < 8 ? loadCoverInto(image) : observer.observe(image));
  }
  const count = content.querySelector('.listing-count > span');
  if (count) count.textContent = listingCount(listing);
  updateListingFooter();
  observeListingEnd();
}

async function openTopic(slug, title) {  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'topics'; setNav('topics');
  state.listing = { kind: 'topic', slug, title, items: [], nextPage: null, total: 0, refreshing: true, loading: false, error: false };
  renderListing();
  try {
    const fresh = await api('topic', { slug, page: 1 });
    if (token !== routeToken || state.listing?.kind !== 'topic') return;
    state.listing.items = asArray(fresh.items);
    state.listing.nextPage = fresh.nextPage || null;
    state.listing.total = fresh.total || 0;
    state.listing.title = fresh.title || title;
    state.listing.refreshing = false;
    renderListing();
  } catch (error) {
    if (token !== routeToken) return;
    state.listing.refreshing = false;
    renderError(error, 'listing');
  }
}

async function loadRank(kind, token) {
  try {
    const fresh = await api('rank', { rank: kind });
    if (token !== routeToken || state.listing?.kind !== 'rank' || state.listing.rank !== kind) return;
    state.listing.items = asArray(fresh.items);
    state.listing.total = fresh.total || 0;
    state.listing.nextPage = null;
    state.listing.refreshing = false;
    state.listing.error = false;
    renderListing();
  } catch (error) {
    if (token !== routeToken || state.listing?.kind !== 'rank') return;
    state.listing.refreshing = false;
    state.listing.error = true;
    if (!state.listing.items.length) renderError(error, 'listing');
    else toast('榜单加载失败，请稍后重试', true);
  }
}

async function openRank(kind = 'hot') {
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  state.listing = { kind: 'rank', rank: kind, items: [], nextPage: null, total: 0, refreshing: true, loading: false, error: false };
  renderListing();
  loadRank(kind, token);
}

async function loadMoreListing() {
  const listing = state.listing;
  if (state.route !== 'listing' || !listingHasMore(listing || {}) || listing.refreshing || listing.loading || listing.error) return;
  if (listing.kind === 'score') { appendScoreMore(); return; }
  const token = routeToken;
  const pageNumber = listing.nextPage;
  listing.loading = true;
  updateListingFooter();
  try {
    const payload = { page: pageNumber };
    if (listing.kind === 'topic') payload.slug = listing.slug;
    const page = await api(listing.kind, payload);
    if (token !== routeToken || state.listing !== listing || state.route !== 'listing') return;
    const known = new Set(listing.items.map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const id = String(item.id);
      if (known.has(id)) return false;
      known.add(id);
      return true;
    });
    listing.items.push(...added);
    listing.nextPage = page.items?.length && page.nextPage > pageNumber ? page.nextPage : null;
    const grid = content.querySelector('.listing-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      const images = [...grid.querySelectorAll('img[data-cover]')].slice(-added.length);
      images.forEach((image, index) => index < 8 ? loadCoverInto(image) : observer.observe(image));
    }
    const count = content.querySelector('.listing-page .listing-count');
    if (count) count.textContent = listingCount(listing);
  } catch (error) {
    if (token === routeToken && state.listing === listing && state.route === 'listing') listing.error = true;
  } finally {
    listing.loading = false;
    if (state.listing === listing && state.route === 'listing') updateListingFooter();
  }
}

async function goCategory(tab, sub = state.sub[tab], keepCompact = false) {
  const token = ++routeToken;
  categoryObserver?.disconnect();
  if (state.route === 'home') rememberScreen();
  setRoute('category'); state.tab = tab; state.sub[tab] = sub; state.category = null;
  if (!keepCompact) setNav(tab);
  renderSkeleton();
  const id = categoryId();
  const refreshing = api('category', { id, page: 1 });
  const cached = await api('cached', { key: `category:${id}:1` }).catch(() => null);
  if (token !== routeToken) return;
  state.category = cached?.data ? { ...cached.data, refreshing: true, loading: false, error: false } : null;
  if (state.category) renderCategory(keepCompact); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = state.category && listSignature(state.category) !== listSignature(fresh);
    const firstLoad = !state.category;
    const previousScroll = scroll.scrollTop;
    state.category = { ...fresh, refreshing: false, loading: false, error: false };
    state.categoryItems = asArray(fresh.items);
    if (firstLoad || changed) {
      renderCategory(keepCompact || state.compactHeld);
      if (!firstLoad) scroll.scrollTop = previousScroll;
    } else {
      refreshMissingImages(asArray(fresh.items));
      updateCategoryFooter();
    }
    if (changed) toast('更新成功');
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.category) renderError(error, 'category');
    else { state.category.refreshing = false; updateCategoryFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

function formatBytes(bytes) {
  const value = Number(bytes) || 0;
  if (value >= 1024 * 1024 * 1024) return `${(value / 1073741824).toFixed(2)} GB`;
  if (value >= 1024 * 1024) return `${(value / 1048576).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(0)} KB`;
  return `${value} B`;
}

/** 设置页「本地存储」：视频分段 / 封面 / 目录 三项实际占用。 */
async function refreshCacheStats() {
  const node = content.querySelector('[data-cache-stats]');
  if (!node) return;
  try {
    const stats = await api('cache-stats');
    const video = Number(stats?.video || 0);
    const covers = Number(stats?.covers || 0);
    const meta = Number(stats?.meta || 0);
    node.innerHTML = `视频缓存 <b>${formatBytes(video)}</b> · 封面 <b>${formatBytes(covers)}</b> · 目录 <b>${formatBytes(meta)}</b>`
      + `<span class="cache-total">合计 ${formatBytes(video + covers + meta)}</span>`;
  } catch (error) {
    node.textContent = '缓存统计失败（请求超时），点这里重试';
    node.style.cursor = 'pointer';
    node.onclick = () => { node.onclick = null; node.textContent = '正在统计缓存…'; refreshCacheStats(); };
  }
}

async function goLibrary(tab = 'history') {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.libraryTab = tab;
  setRoute('library'); state.tab = 'library'; setNav('library'); renderSkeleton();
  try { state.library = await api('library'); if (token === routeToken) renderLibrary(); }
  catch (error) { if (token === routeToken) renderError(error, 'library'); }
}

function goSettings() {
  // 推进 token：让还在飞行中的页面请求失效，否则它们超时后会把设置页覆盖掉。
  ++routeToken;
  state.settingsReturn = captureScreen();
  setRoute('settings'); state.tab = 'settings'; setNav('settings');
  try {
    renderSettings();
  } catch (error) {
    // 渲染异常绝不留白屏：退回一个可操作的最小设置页，并把真实错误写进诊断日志。
    api('js-log', { msg: 'settings-fail ' + (error?.message || error) }).catch(() => {});
    content.innerHTML = '<div class="empty-state"><p>设置页加载失败：' + esc(error?.message || '未知错误') + '</p>'
      + '<button type="button" class="primary" data-retry-settings>重试</button></div>';
  }
}

function goAccountManagement() {
  if (state.route === 'account-management') return;
  if (state.route !== 'settings') goSettings();
  state.accountReturn = captureScreen();
  setRoute('account-management');
  renderAccountManagement();
}

async function goSearch(query) {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.previous = { route: state.route, tab: state.tab };
  setRoute('search'); state.query = query.trim(); state.filter = 'all'; state.search = null;
  const refreshing = api('search', { query: state.query });
  const cached = await api('cached', { key: `search:${state.query}` }).catch(() => null);
  if (token !== routeToken) return;
  state.search = cached?.data || null;
  if (state.search) renderSearch(); else renderSkeleton();
  try { const fresh = await refreshing; if (token === routeToken) { state.search = fresh; renderSearch(); } }
  catch (error) { if (!state.search && token === routeToken) renderError(error, 'search'); }
}

function sourceItem(id) {
  const candidates = [state.feedItems, state.listing?.items, state.home?.featured, state.home?.homepageRecommend, state.home?.homepageNewest, state.home?.recommend?.items, state.home?.newest?.items, state.category?.items, state.search?.items, state.library?.bookmarks, state.library?.progress].flatMap(asArray);
  return candidates.find(item => item.id === id) || { id, title: `剧集 ${id}` };
}
async function goDetail(id, trigger) {
  if (state.route === 'detail' && state.sourceItem?.id === id) {
    // Retry in place; preserve the original source card and return destination.
    state.detailLoading = true; renderDetail();
    const token = ++routeToken;
    try { const fresh = await api('detail', { id }); if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); } }
    catch (error) { if (token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); } }
    return;
  }
  const token = ++routeToken;
  const entrySource = bannerChanging && trigger?.closest('.hero') ? null : HGCardTransition.capture(trigger);
  rememberScreen();
  state.previous = {
    route: state.route, tab: state.tab, sub: state.sub[state.tab], query: state.query,
    filter: state.filter, scrollTop: scroll.scrollTop, compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft),
    pageNode: content.firstElementChild
  };
  if (state.route === 'listing' && state.listing) state.listing.refreshing = false;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  state.sourceItem = sourceItem(id);
  state.sourceGroup = state.route === 'category' ? categoryId() : state.sourceItem.category || '';
  const aiGroup = state.sourceGroup === 'ai-huanlian' || state.sourceGroup === 'ai-mogai';
  const candidates = state.route === 'category' ? asArray(state.category?.items) : state.route === 'search' ? asArray(state.search?.items) : state.route === 'listing' ? asArray(state.listing?.items) :
    [...asArray(state.home?.recommend?.items), ...asArray(state.home?.newest?.items)];
  state.sourceQueue = aiGroup ? candidates.filter(item => item.category === state.sourceGroup) : [];
  setRoute('detail'); state.detail = { ...state.sourceItem }; state.detailLoading = true; state.bookmarked = false;
  scroll.scrollTop = 0;
  updateCompact();
  renderDetail();
  if (entrySource) {
    detailCard = new HGCardTransition({
      app: document.getElementById('app'), scroll, content, source: entrySource, snapshot: state.previous,
      isDetail: () => state.route === 'detail',
      onClose: () => { returnFromDetail(); trigger?.focus({ preventScroll: true }); }
    });
    detailCard.open();
  }
  const refreshing = api('detail', { id });
  const cached = await api('cached', { key: `detail:${id}` }).catch(() => null);
  if (token !== routeToken) return;
  let hasDetailData = Boolean(cached?.data);
  if (hasDetailData) { state.detail = cached.data; state.detailLoading = false; renderDetail(); }
  api('bookmark-state', { id }).then(saved => { if (token === routeToken) { state.bookmarked = saved; if (state.detail) renderDetail(); } }).catch(() => {});
  try { const fresh = await refreshing; if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); } }
  catch (error) { if (!hasDetailData && token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); } }
}

function returnFromDetail() {
  if (state.feedReturn) {
    state.feedReturn = false;
    state.previous = null;
    goFeed();
    return;
  }
  const previous = state.previous || { route: 'home', tab: 'home' };
  if (!previous.pageNode) { goHome(); return; }
  ++routeToken;
  setRoute(previous.route);
  state.tab = previous.tab;
  if (previous.route === 'category') state.sub[previous.tab] = previous.sub;
  state.query = previous.query || '';
  state.filter = previous.filter || 'all';
  state.compactHeld = previous.compactHeld;
  content.replaceChildren(previous.pageNode);
  state.previous = null;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = previous.horizontalScroll?.[index] || 0;
  });
  document.querySelectorAll('#dock [data-nav]').forEach(button =>
    button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = previous.scrollTop || 0;
  if (previous.route === 'category') compact.innerHTML = compactMarkup();
  updateCompact();
  hydrateImages();
  if (previous.route === 'category') observeCategoryEnd();
  if (previous.route === 'listing') observeListingEnd();
  if (previous.route === 'home') scheduleBannerAuto();
}

window.hgBack = () => {
  if (!authOverlay.hidden) { hideAuth(); return true; }
  if (!confirmOverlay.hidden) { closeConfirmation(false); return true; }
  if (!updateOverlay.hidden) { if (!updateDownloading) updateOverlay.hidden = true; return true; }
  if (state.route === 'campaign') { restoreScreen(state.campaignReturn); return true; }
  if (state.route === 'account-management') { restoreScreen(state.accountReturn); return true; }
  if (state.route === 'settings') { restoreScreen(state.settingsReturn); return true; }
  if (state.route === 'detail' && state.feedReturn) {
    state.feedReturn = false;
    state.previous = null;
    api('js-log', { msg: 'back-to-feed' }).catch(() => {});
    goFeed();
    return true;
  }
  if (state.route === 'detail') { if (detailCard) detailCard.close(); else returnFromDetail(); return true; }
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') { restoreScreen(state.listingReturn); return true; }
  if (state.route === 'listing' && state.listing?.kind === 'topic') { goTopics(); return true; }
  if (state.route === 'tags') { restoreScreen(state.tagsReturn); return true; }
  if (state.route === 'listing') { goHome(); return true; }
  if (state.route === 'search') { searchInput.value = ''; goHome(); return true; }
  if (state.route !== 'home') { goHome(); return true; }
  return false;
};
window.hgResume = () => {
  window.hgRefreshPendingResources?.();
  window.hgThemeRefresh?.();
  retryFailedImages(true);
  if (state.route === 'library') goLibrary(state.libraryTab);
  if (state.route === 'detail' && state.detail) api('bookmark-state', { id: state.detail.id }).then(value => { state.bookmarked = value; renderDetail(); });
};

async function openPlayer(episode = 1) {
  const detail = state.detail;
  if (!detail) return;
  const item = { ...state.sourceItem, ...detail };
  const queue = state.sourceQueue.length ? [...state.sourceQueue] : [item];
  if (!queue.some(candidate => candidate.id === item.id)) queue.unshift(item);
  const index = queue.findIndex(candidate => candidate.id === item.id);
  await api('open-player', { item, detail, episodes: asArray(detail.episodes), queue, index, group: state.sourceGroup, episode });
}

async function loadMoreCategory() {
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const token = routeToken;
  const next = category.nextPage;
  const id = categoryId();
  category.loading = true;
  updateCategoryFooter();
  try {
    const page = await api('category', { id, page: next });
    if (token !== routeToken || state.category !== category || state.route !== 'category' || categoryId() !== id) return;
    const existing = new Set(asArray(category.items).map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const itemId = String(item.id);
      if (existing.has(itemId)) return false;
      existing.add(itemId);
      return true;
    });
    category.items.push(...added);
    category.nextPage = page.items?.length && page.nextPage > next ? page.nextPage : null;
    const grid = content.querySelector('.category-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      [...grid.querySelectorAll('img[data-cover]')].slice(-added.length)
        .forEach((image, index) => index < 8 ? loadCoverInto(image) : observer.observe(image));
    }
    const count = content.querySelector('.category-count');
    if (count) count.textContent = `已显示 ${category.items.length} 部`;
  } catch (error) {
    if (token === routeToken && state.category === category && state.route === 'category') category.error = true;
  } finally {
    category.loading = false;
    if (state.category === category && state.route === 'category') updateCategoryFooter();
  }
}

document.getElementById('search-form').addEventListener('submit', event => {
  event.preventDefault();
  const query = searchInput.value.trim();
  searchInput.blur();
  if (['library', 'settings', 'account-management'].includes(state.route)) {
    state.libraryQuery = query;
    if (state.route === 'library') renderLibrary();
    else goLibrary(state.libraryTab);
    scroll.scrollTop = 0;
    return;
  }
  if (/^[#＃]/.test(query)) goTagQuery(query);
  else if (query) goSearch(query);
});
searchInput.addEventListener('input', () => {
  if (state.route !== 'library') return;
  state.libraryQuery = searchInput.value;
  renderLibrary();
  scroll.scrollTop = 0;
});

document.addEventListener('submit', async event => {
  if (event.target.id === 'site-origin-form') {
    event.preventDefault();
    const input = event.target.querySelector('#site-origin-input');
    const button = event.target.querySelector('[type="submit"]');
    button.disabled = true;
    try {
      await api('set-site-origin', { origin: input.value.trim() });
      toast('站点已保存，正在重新加载…');
    } catch (failure) {
      toast(failure.message || '保存失败，请稍后重试', true);
      button.disabled = false;
    }
    return;
  }
  if (event.target.id !== 'password-form') return;
  event.preventDefault();
  const form = event.target;
  const oldPassword = form.querySelector('#old-password').value;
  const newPassword = form.querySelector('#new-password').value;
  const repeated = form.querySelector('#confirm-password').value;
  const error = form.querySelector('#password-error');
  const button = form.querySelector('[type="submit"]');
  error.hidden = true;
  if (newPassword !== repeated) { error.textContent = '两次输入的新密码不一致'; error.hidden = false; return; }
  if (newPassword === oldPassword) { error.textContent = '新密码不能与旧密码相同'; error.hidden = false; return; }
  button.disabled = true;
  try { setAccount(await api('change-password', { oldPassword, newPassword })); toast('密码已修改'); }
  catch (failure) { error.textContent = failure.message || '修改失败，请稍后重试'; error.hidden = false; button.disabled = false; }
});

function navigateDock(tab) {
  if (state.route === 'detail') state.previous = null;
  if (tab === 'feed') { goFeed(); return; }
  if (tab === 'home') goHome();
  else if (tab === 'library') goLibrary();
  else if (tab === 'settings') goSettings();
  else if (tab === 'topics') goTopics();
  else goCategory(tab);
}

let feedOpening = false;

/** 推荐流：内嵌在 WebView 之上、dock 之上，本身就是一个 tab（无返回按钮）。
 *  池子取热播榜 + 最新一页，播放/连播/切换由 FeedView 负责，进度也由它持久化。 */
async function goFeed() {
  api('js-log', { msg: 'goFeed route=' + state.route + ' alive=' + !!state.feedAlive + ' cached=' + asArray(state.feedItems).length }).catch(() => {});
  if (feedOpening) return;
  // 只有"路由是 feed 且原生层真的在"才跳过；否则重建，避免层被移除后永久黑屏。
  if (state.route === 'feed' && state.feedAlive) return;
  feedOpening = true;
  try {
    let items = asArray(state.feedItems);
    if (!items.length) {
      let pool = null;
      try { pool = await api('feed-pool'); }
      catch (error) {
        const cached = await api('cached', { key: 'feed-pool' }).catch(() => null);
        pool = cached?.data || null;
        if (!pool) throw error;
      }
      items = asArray(pool?.items);
    }
    if (!items.length) { toast('推荐池暂时不可用，请稍后重试', true); return; }
    setRoute('feed');
    state.feedAlive = false;
    state.feedItems = items;
    state.tab = 'feed';
    setNav('feed');
    content.innerHTML = '';
    const dock = document.getElementById('dock');
    const inset = dock ? Math.max(0, window.innerHeight - dock.getBoundingClientRect().top + 6) : 82;
    api('feed-open', { items, index: -1, bottomInset: inset });
    // 兜底：1.5 秒内原生层没确认就退回首页，绝不停在黑屏上。
    setTimeout(() => {
      if (state.route === 'feed' && !state.feedAlive) {
        api('js-log', { msg: 'feed-open-timeout' }).catch(() => {});
        goHome();
      }
    }, 1500);
  } catch (error) {
    api('js-log', { msg: 'goFeed-fail: ' + (error?.message || error) }).catch(() => {});
    toast(error?.message || '打开推荐失败', true);
    goHome();
  } finally {
    feedOpening = false;
  }
}

function tapDock(button) {
  const tab = button.dataset.nav;
  const now = performance.now();
  if (lastDockTap.tab === tab && now - lastDockTap.at < 320) {
    const alreadyNavigated = lastDockTap.navigated;
    lastDockTap = { tab: '', at: 0, navigated: false };
    const icon = button.querySelector('.nav-icon');
    icon?.classList.remove('dock-bounce');
    void icon?.offsetWidth;
    icon?.classList.add('dock-bounce');
    icon?.addEventListener('animationend', () => icon.classList.remove('dock-bounce'), { once: true });
    scroll.scrollTop = 0;
    if (!alreadyNavigated) navigateDock(tab);
    return;
  }
  const route = tab === 'home' ? 'home' : tab === 'library' ? 'library' : tab === 'settings' ? 'settings' : tab === 'topics' ? 'topics' : 'category';
  const navigated = (tab === 'settings' || tab === 'topics') ? state.route !== route : state.route !== route || state.tab !== tab;
  lastDockTap = { tab, at: now, navigated };
  if (navigated) navigateDock(tab);
}

document.addEventListener('click', async event => {
  const campaignButton = event.target.closest('[data-open-campaign]');
  if (campaignButton) {
    if (campaignButton.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    const item = activeCampaigns().find(item => item.id === campaignButton.dataset.openCampaign);
    if (item) goCampaign(item);
    return;
  }
  if (event.target.closest('[data-open-update]')) { if (updateAvailable) showUpdate(updateAvailable); else checkUpdate(true); return; }
  if (event.target.closest('[data-check-update]')) { checkUpdate(true); return; }
  if (event.target.closest('[data-discover-site]')) {
    const button = event.target.closest('[data-discover-site]');
    button.disabled = true;
    try { await api('discover-sites'); }
    catch (error) { toast(error.message || '检测失败，请稍后重试', true); button.disabled = false; }
    return;
  }
  if (event.target.closest('[data-reset-site]')) {
    const button = event.target.closest('[data-reset-site]');
    button.disabled = true;
    api('set-site-origin', { origin: '' })
      .then(() => toast('已恢复默认站点，正在重新加载…'))
      .catch(error => { toast(error.message, true); button.disabled = false; });
    return;
  }
  if (event.target.closest('[data-account-management]')) { goAccountManagement(); return; }
  if (event.target.closest('[data-choose-avatar]')) { api('choose-avatar').catch(error => toast(error.message, true)); return; }
  if (event.target.closest('[data-account-action]')) {
    if (state.account.loggedIn) goAccountManagement();
    else showAuth();
    return;
  }
  if (event.target.closest('[data-sync-now]')) {
    try { await api('sync'); state.library = await api('library'); toast('片单已同步'); }
    catch (error) { toast(`同步失败：${error.message}`, true); }
    return;
  }
  if (event.target.closest('[data-logout]')) {
    const button = event.target.closest('[data-logout]');
    if (button.disabled) return;
    button.disabled = true;
    button.classList.add('is-loading');
    button.setAttribute('aria-busy', 'true');
    button.textContent = '正在退出…';
    try {
      setAccount(await api('logout'));
      state.library = await api('library');
      setRoute('settings'); renderSettings();
      toast('已退出登录');
    }
    catch (error) { toast(error.message, true); }
    finally {
      if (button.isConnected) {
        button.disabled = false;
        button.classList.remove('is-loading');
        button.removeAttribute('aria-busy');
        button.textContent = '退出登录';
      }
    }
    return;
  }
  const nav = event.target.closest('[data-nav]');
  if (nav) { tapDock(nav); return; }
  const libraryTab = event.target.closest('[data-library-tab]');
  if (libraryTab) { state.libraryTab = libraryTab.dataset.libraryTab; renderLibrary(); scroll.scrollTop = 0; return; }
  const historyCard = event.target.closest('[data-history-id]');
  if (historyCard) {
    const id = historyCard.dataset.historyId;
    if (historyCard.dataset.historyCompleted === 'true') goDetail(id, historyCard);
    else goDetail(id).then(() => openPlayer(Number(historyCard.dataset.historyEpisode || 1)));
    return;
  }
  if (event.target.closest('[data-all-tags]')) { goTags(); return; }
  const tagChoice = event.target.closest('[data-tag-slug]');
  if (tagChoice) { goTag(tagChoice.dataset.tagSlug, tagChoice.dataset.tagName); return; }
  const sub = event.target.closest('[data-sub]');
  if (sub) { goCategory(state.tab, Number(sub.dataset.sub), !!sub.closest('#compact-category')); return; }
  const card = event.target.closest('[data-open-id]');
  if (card?.dataset.openId) {
    if (card.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    goDetail(card.dataset.openId, card); return;
  }
  const filter = event.target.closest('[data-filter]');
  if (filter) { state.filter = filter.dataset.filter; renderSearch(); return; }
  const slide = event.target.closest('[data-slide]');
  if (slide) { const target = Number(slide.dataset.slide); changeBanner(target, target > state.banner ? 1 : -1); return; }
  if (event.target.closest('[data-back]')) { window.hgBack(); return; }
  const episode = event.target.closest('[data-episode]');
  if (episode) { openPlayer(Number(episode.dataset.episode)); return; }
  if (event.target.closest('[data-play]')) { openPlayer(1); return; }
  if (event.target.closest('[data-bookmark]')) {
    if (!state.detail) return;
    try { state.bookmarked = await api('bookmark', state.detail); renderDetail(); toast(state.bookmarked ? '已加入我的片单' : '已取消收藏'); }
    catch (error) { toast(error.message, true); }
    return;
  }
  if (event.target.closest('[data-retry-category]')) { state.category.error = false; loadMoreCategory(); return; }
  if (event.target.closest('[data-score-sort]')) { openScoreBoard(); return; }
  if (event.target.closest('[data-sort-newest]')) { openListing('newest'); return; }
  const topicButton = event.target.closest('[data-topic-slug]');
  if (topicButton) {
    openTopic(topicButton.dataset.topicSlug, topicButton.dataset.topicTitle || '');
    return;
  }
  const rankTab = event.target.closest('[data-rank-tab]');
  if (rankTab) {
    const kind = rankTab.dataset.rankTab;
    if (state.listing?.kind === 'rank' && state.listing.rank !== kind) {
      state.listing.rank = kind;
      state.listing.items = [];
      state.listing.total = 0;
      state.listing.refreshing = true;
      state.listing.error = false;
      renderListing();
      loadRank(kind, routeToken);
    }
    return;
  }
  const more = event.target.closest('[data-more]');
  if (more) { openListing(more.dataset.more); return; }
  if (event.target.closest('[data-retry-listing]')) { state.listing.error = false; loadMoreListing(); return; }
  const retry = event.target.closest('[data-retry]');
  if (retry) {
    if (retry.dataset.retry === 'home') goHome();
    else if (retry.dataset.retry === 'category') goCategory(state.tab);
    else if (retry.dataset.retry === 'library') goLibrary();
    else if (retry.dataset.retry === 'search') goSearch(state.query);
    else if (retry.dataset.retry === 'listing') {
      const listing = state.listing || {};
      if (listing.kind === 'topic') openTopic(listing.slug, listing.title);
      else if (listing.kind === 'rank') openRank(listing.rank || 'hot');
      else if (listing.kind === 'score') openScoreBoard();
      else openListing(listing.kind);
    }
    else if (retry.dataset.retry === 'topics') goTopics();
    else if (retry.dataset.retry === 'tags') goTags();
    else if (retry.dataset.retry === 'tag-list') goTag(state.listing.slug, state.listing.label);
    else if (retry.dataset.retry === 'detail') goDetail(state.sourceItem.id);
    return;
  }
  if (event.target.closest('[data-retry-settings]')) { goSettings(); return; }
  const clear = event.target.closest('[data-clear]');
  if (clear) {
    if (clear.dataset.clear === 'cache') {
      if (!await askConfirmation('清理内容缓存', '将删除已缓存的视频、目录和封面。收藏与观看进度会保留。', '清理')) return;
      await api('clear-content-cache');
      refreshCacheStats();
      toast('内容缓存已清空');
    } else if (clear.dataset.clear === 'history' || clear.dataset.clear === 'bookmarks') {
      const history = clear.dataset.clear === 'history';
      if (!await askConfirmation(history ? '清空观看历史' : '清空我的收藏', history ? '所有观看记录会被删除，我的收藏会保留。' : '所有收藏作品会被移除，观看历史会保留。')) return;
      await api(history ? 'clear-history' : 'clear-bookmarks');
      state.library = await api('library');
      renderSettings();
      toast(history ? '观看历史已清空' : '收藏已清空');
    }
  }
});

let heroTouch;
let heroSuppressClickUntil = 0;
content.addEventListener('touchstart', event => {
  if (!event.target.closest('.hero') || event.touches.length !== 1) return;
  stopBannerAuto();
  const touch = event.touches[0];
  heroTouch = { x: touch.clientX, y: touch.clientY, moved: false };
}, { passive: true });
content.addEventListener('touchmove', event => {
  if (!heroTouch || !event.touches.length) return;
  const touch = event.touches[0];
  heroTouch.moved ||= Math.abs(touch.clientX - heroTouch.x) > 10 || Math.abs(touch.clientY - heroTouch.y) > 10;
}, { passive: true });
content.addEventListener('touchend', event => {
  if (!heroTouch) return;
  const touch = event.changedTouches[0];
  const move = touch.clientX - heroTouch.x;
  const vertical = touch.clientY - heroTouch.y;
  if (heroTouch.moved || Math.abs(move) > 10 || Math.abs(vertical) > 10) heroSuppressClickUntil = performance.now() + 400;
  heroTouch = null;
  scheduleBannerAuto();
  if (Math.abs(move) < 55 || Math.abs(move) <= Math.abs(vertical) || !state.home) return;
  const count = homeSlides().length;
  if (!count) return;
  const direction = move < 0 ? 1 : -1;
  changeBanner((state.banner + direction + count) % count, direction);
}, { passive: true });
content.addEventListener('touchcancel', () => {
  if (!heroTouch) return;
  heroTouch = null; heroSuppressClickUntil = performance.now() + 400;
  scheduleBannerAuto();
}, { passive: true });

api('app-version').then(version => {
  appVersion = String(version || '');
  const label = content.querySelector('.app-version-text');
  if (label) label.textContent = `版本 v${appVersion}`;
  setAvailableUpdate(updateAvailable);
}).catch(() => {});
api('cached-update').then(result => { if (result.status === 'available') setAvailableUpdate(result); }).catch(() => {});
function startHome() { goHome(); setTimeout(() => checkUpdate(false), 3000); }
document.getElementById('age-confirm').addEventListener('click', () => { localStorage.setItem('adult-confirmed', '1'); api('confirm-age').catch(() => {}); document.getElementById('age-gate').hidden = true; startHome(); });
document.getElementById('age-exit').addEventListener('click', () => api('quit'));
api('age-status').then(confirmed => {
  if (confirmed || localStorage.getItem('adult-confirmed') === '1') {
    document.getElementById('age-gate').hidden = true;
    startHome();
  } else document.getElementById('age-gate').hidden = false;
}).catch(() => { if (localStorage.getItem('adult-confirmed') === '1') startHome(); else document.getElementById('age-gate').hidden = false; });

// Resource hot updates are fully manual now: no background check, no idle auto-apply.
// Stubs kept so any hot-runtime UI that calls them stays harmless.
window.hgCanApplyHotUpdate = () => false;
window.hgScheduleResourceApply = () => {};
window.hgRefreshPendingResources = () => {};
window.hgHotUpdateStatus = () => {};
window.hgBootReady?.();
