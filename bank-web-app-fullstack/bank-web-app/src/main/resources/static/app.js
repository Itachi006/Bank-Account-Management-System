const CURRENCY = '$';   // change to '₹' if you prefer rupees
const $ = (id) => document.getElementById(id);
let token = sessionStorage.getItem('token');

const fmt = (n) => CURRENCY + Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function toast(msg, ok = true) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast ' + (ok ? 'ok' : 'err');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.add('hidden'), 4000);
}

async function api(path, method = 'GET', body) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && token) { showAuth(); }
  if (!res.ok) throw new Error(data.error || 'Something went wrong.');
  return data;
}

function showAuth() {
  token = null; sessionStorage.removeItem('token');
  $('authView').classList.remove('hidden');
  $('dashView').classList.add('hidden');
  $('sessionInfo').classList.add('hidden');
}

async function showDashboard() {
  const me = await api('/me');
  $('authView').classList.add('hidden');
  $('dashView').classList.remove('hidden');
  $('sessionInfo').classList.remove('hidden');
  $('whoami').textContent = me.holderName + ' (#' + me.accountId + ')';
  $('accountNo').textContent = me.accountId;
  $('balance').textContent = fmt(me.balance);
  await loadHistory();
}

async function refreshBalance() {
  const me = await api('/me');
  $('balance').textContent = fmt(me.balance);
}

async function loadHistory() {
  const rows = await api('/me/transactions');
  const body = $('txnBody');
  body.innerHTML = '';
  $('emptyMsg').classList.toggle('hidden', rows.length > 0);
  for (const t of rows) {
    const tr = document.createElement('tr');
    const incoming = t.type === 'DEPOSIT' || t.type === 'TRANSFER_IN';
    const cells = [t.txnId, t.type, (incoming ? '+' : '−') + fmt(t.amount), t.description, t.timestamp];
    cells.forEach((v, i) => {
      const td = document.createElement('td');
      td.textContent = v;                       // textContent => no HTML injection
      if (i === 2) td.className = 'num ' + (incoming ? 'in' : 'out');
      tr.appendChild(td);
    });
    body.appendChild(tr);
  }
}

// ---- tabs ----
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === b));
  $('loginForm').classList.toggle('hidden', b.dataset.tab !== 'login');
  $('createForm').classList.toggle('hidden', b.dataset.tab !== 'create');
}));

// ---- generic submit helper ----
function onSubmit(id, handler) {
  $(id).addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const btn = form.querySelector('button[type=submit]');
    btn.disabled = true;
    try { await handler(Object.fromEntries(new FormData(form)), form); }
    catch (err) { toast(err.message, false); }
    finally { btn.disabled = false; }
  });
}

onSubmit('createForm', async (v, form) => {
  const res = await api('/accounts', 'POST', {
    holderName: v.holderName, pin: v.pin,
    initialDeposit: v.initialDeposit === '' ? 0 : Number(v.initialDeposit)
  });
  toast(res.message + ' — log in to continue.');
  form.reset();
  document.querySelector('[data-tab=login]').click();
  document.querySelector('#loginForm [name=accountId]').value = res.accountId;
});

onSubmit('loginForm', async (v, form) => {
  const res = await api('/login', 'POST', { accountId: Number(v.accountId), pin: v.pin });
  token = res.token; sessionStorage.setItem('token', token);
  form.reset();
  await showDashboard();
  toast('Welcome, ' + res.holderName + '!');
});

onSubmit('depositForm', async (v, form) => {
  const r = await api('/me/deposit', 'POST', { amount: Number(v.amount) });
  form.reset(); $('balance').textContent = fmt(r.balance); await loadHistory(); toast(r.message);
});
onSubmit('withdrawForm', async (v, form) => {
  const r = await api('/me/withdraw', 'POST', { amount: Number(v.amount) });
  form.reset(); $('balance').textContent = fmt(r.balance); await loadHistory(); toast(r.message);
});
onSubmit('transferForm', async (v, form) => {
  const r = await api('/me/transfer', 'POST', { toAccountId: Number(v.toAccountId), amount: Number(v.amount) });
  form.reset(); $('balance').textContent = fmt(r.balance); await loadHistory(); toast(r.message);
});

$('refreshBtn').addEventListener('click', async () => {
  try { await refreshBalance(); await loadHistory(); } catch (e) { toast(e.message, false); }
});
$('logoutBtn').addEventListener('click', async () => {
  try { await api('/logout', 'POST'); } catch (_) {}
  showAuth(); toast('Logged out.');
});

// restore session on page load
if (token) showDashboard().catch(showAuth);
