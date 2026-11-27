// Account switcher in the header and the "Add account" dialog.
import { api, getJson } from './api.js';
import { state } from './state.js';
import { $, busy, refreshControls, show, showError } from './ui.js';

const NEW = '__new__';

const PROVIDERS = {
    gmx:     { host: 'imap.gmx.com',          port: 993, ssl: true },
    gmail:   { host: 'imap.gmail.com',        port: 993, ssl: true },
    outlook: { host: 'outlook.office365.com', port: 993, ssl: true },
    yahoo:   { host: 'imap.mail.yahoo.com',   port: 993, ssl: true },
    icloud:  { host: 'imap.mail.me.com',      port: 993, ssl: true },
};

let onChanged = () => {};
let previous = '';

function openDialog() {
    $('connect-error').textContent = '';
    $('connect-dialog').showModal();
    $('user').focus();
}

function closeDialog() {
    $('connect-dialog').close();
    $('account').value = previous; // undo the "+ Add account" pick
}

function onProviderChange() {
    const p = PROVIDERS[$('provider').value];
    if (p) {
        $('host').value = p.host;
        $('port').value = p.port;
        $('ssl').checked = p.ssl;
    }
    const custom = !$('provider').value;
    document.querySelectorAll('#connect-dialog .custom-only').forEach(el => { el.hidden = !custom; });
}

async function connectNew(e) {
    e.preventDefault();
    const provider = $('provider').value;
    const body = { provider, username: $('user').value.trim(), secret: $('pass').value };
    if (!provider) {
        body.host = $('host').value;
        body.port = +$('port').value;
        body.ssl = String($('ssl').checked);
    }
    $('connect-error').textContent = '';
    try {
        await busy('Connecting…', () => api('/api/connect', body));
        $('pass').value = '';
        $('connect-dialog').close();
        await loadAccounts();
        show('Connected.', 'ok');
    } catch (err) {
        $('connect-error').textContent = err.message;
    }
}

async function switchAccount() {
    const id = $('account').value;
    if (id === NEW) { openDialog(); return; }
    if (!id) return;
    try {
        await busy('Connecting…', () => api('/api/accounts/connect', { id }));
        await loadAccounts();
        show('Connected.', 'ok');
    } catch (err) {
        $('account').value = previous;
        showError(err);
    }
}

export async function loadAccounts() {
    const accounts = await getJson('/api/accounts');
    if (!accounts) { show('Could not load accounts.', 'err'); return; }

    const active = accounts.find(a => a.status === 'ACTIVE');
    const sel = $('account');
    sel.replaceChildren();
    if (!active) {
        const placeholder = new Option(accounts.length ? 'Choose account…' : 'No account', '', true, true);
        placeholder.disabled = true;
        sel.add(placeholder);
    }
    for (const a of accounts) {
        sel.add(new Option(`${a.username} (${a.service})`, a.id, false, a === active));
    }
    sel.add(new Option('+ Add account…', NEW));
    previous = sel.value;

    document.querySelectorAll('.active-account').forEach(el => { el.textContent = active ? active.username : 'no account'; });
    state.hasAccount = !!active;
    refreshControls();
    await onChanged();

    if (!accounts.length) openDialog();
}

/** changed: called after the active account was (re)loaded, to refresh everything that is per account. */
export function initAccounts(changed) {
    onChanged = changed;
    $('account').onchange = switchAccount;
    $('provider').onchange = onProviderChange;
    $('connect-form').onsubmit = connectNew;
    $('connect-cancel').onclick = closeDialog;
    $('connect-dialog').addEventListener('cancel', () => { $('account').value = previous; });
    onProviderChange();
}
