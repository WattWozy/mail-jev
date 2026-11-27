// Activity tab: what was done to which email and when, filtered by a time range.
// Loaded only while the tab is open, never on its own.
import { getJson } from './api.js';
import { state } from './state.js';
import { $, badge, cell, emptyRow, sourceTag } from './ui.js';

const COLS = 5;
const LIMIT = 500; // server maximum

/** Start of the local day, (days - 1) days ago; null for "ever". */
function sinceFor(days) {
    if (!days) return null;
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - (days - 1));
    return d.toISOString();
}

function formatWhen(timestamp) {
    const d = new Date(timestamp);
    const today = new Date().toDateString() === d.toDateString();
    const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return today ? time : `${d.toLocaleDateString([], { day: '2-digit', month: 'short' })} ${time}`;
}

function isVisible() {
    return $('setup').open && !document.querySelector('#setup [data-pane="activity"]').hidden;
}

export async function loadActivity() {
    const tbody = $('activity-tbody');
    $('activity-count').textContent = '';
    if (!state.hasAccount) {
        tbody.replaceChildren(emptyRow(COLS, 'Connect an account to see its activity.'));
        return;
    }
    const since = sinceFor(+$('activity-range').value);
    const query = `limit=${LIMIT}` + (since ? `&since=${encodeURIComponent(since)}` : '');
    const data = await getJson(`/api/activity?${query}`);
    const list = Array.isArray(data) ? data : [];

    if (!list.length) {
        tbody.replaceChildren(emptyRow(COLS, 'Nothing was sorted in this period.'));
        return;
    }
    $('activity-count').textContent = list.length >= LIMIT ? `latest ${LIMIT}` : `${list.length} emails`;
    tbody.replaceChildren(...list.map(e => {
        const tr = document.createElement('tr');
        const when = cell(tr, formatWhen(e.timestamp));
        when.className = 'when-cell';
        when.title = new Date(e.timestamp).toLocaleString();
        const action = document.createElement('td');
        action.appendChild(badge(e.action || 'KEEP', e.folder));
        tr.appendChild(action);
        cell(tr, e.from ?? '');
        cell(tr, e.subject ?? '');
        const why = document.createElement('td');
        const tag = sourceTag(e.source, e.overridden);
        if (tag) why.appendChild(tag);
        why.appendChild(document.createTextNode(e.reason ?? ''));
        why.title = why.textContent;
        tr.appendChild(why);
        return tr;
    }));
}

/** Reloads only if the user is looking at the tab (after an apply, an autopilot run, an account switch). */
export function refreshActivity() {
    if (isVisible()) loadActivity();
}

export function initActivity() {
    $('activity-range').onchange = loadActivity;
    $('setup').addEventListener('toggle', refreshActivity);
}
