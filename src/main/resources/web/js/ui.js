// Small DOM helpers shared by all modules: notices, spinner, badges, control state.
import { state } from './state.js';

export const $ = id => document.getElementById(id);

export const ACTIONS = ['KEEP', 'FLAG_FOR_REVIEW', 'ARCHIVE', 'MOVE_TO_TRASH'];

const ACTION_LABELS = {
    KEEP: 'Keep',
    FLAG_FOR_REVIEW: 'Review',
    ARCHIVE: 'Archive',
    MOVE_TO_TRASH: 'Trash',
};

export function actionLabel(action, folder) {
    if (action === 'MOVE_TO_FOLDER') return '→ ' + (folder || 'folder');
    return ACTION_LABELS[action] || action;
}

export function setBadge(badge, action, folder) {
    badge.dataset.action = action;
    badge.textContent = actionLabel(action, folder);
    badge.classList.remove(...ACTIONS, 'MOVE_TO_FOLDER');
    badge.classList.add(action);
}

export function badge(action, folder, clickable = false) {
    const b = document.createElement('span');
    b.className = clickable ? 'badge' : 'badge static-badge';
    setBadge(b, action, folder);
    return b;
}

/** "NEWSLETTER_PROMO" → "Newsletter promo" */
export function pretty(text) {
    if (!text) return '';
    const s = String(text).replace(/_/g, ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
}

const SOURCE_LABELS = { RULE: 'Rule', MODEL: 'AI', FALLBACK: 'Unsure' };

/** Small tag saying who decided: Rule, AI or You. */
export function sourceTag(source, overridden) {
    const label = overridden ? 'You' : SOURCE_LABELS[(source || '').toUpperCase()];
    if (!label) return null;
    const tag = document.createElement('span');
    tag.className = 'src-tag' + (label === 'Rule' || label === 'You' ? ' rule' : '');
    tag.textContent = label;
    return tag;
}

export function cell(row, text) {
    const td = document.createElement('td');
    td.textContent = text ?? '';
    td.title = text ?? '';
    row.appendChild(td);
    return td;
}

export function emptyRow(colspan, text) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = colspan;
    td.className = 'empty-note';
    td.textContent = text;
    tr.appendChild(td);
    return tr;
}

export function iconButton(text, title, onclick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn-sm';
    b.textContent = text;
    b.title = title;
    b.onclick = onclick;
    return b;
}

// ── Notices ─────────────────────────────────────────

let fadeTimer = null;

/** Shows a notice. level: 'ok' | 'info' | 'warn' | 'err'. Empty text hides it. */
export function show(text, level = 'info') {
    const el = $('msg');
    clearTimeout(fadeTimer);

    if (!text) {
        if (!el.classList.contains('visible')) return;
        el.classList.remove('visible');
        el.classList.add('fading-out');
        fadeTimer = setTimeout(() => { el.className = ''; el.textContent = ''; }, 150);
        return;
    }
    el.textContent = text;
    el.className = `notice-${level} visible`;
}

export const showError = err => show(err?.message || String(err), 'err');

// ── Busy state & controls ───────────────────────────

/** Runs fn with the spinner on and the controls locked. */
export async function busy(text, fn) {
    state.busy = true;
    $('spinner-text').textContent = text;
    $('spinner').classList.add('visible');
    refreshControls();
    try {
        return await fn();
    } finally {
        state.busy = false;
        $('spinner').classList.remove('visible');
        refreshControls();
    }
}

/** Single place that decides which buttons are visible and enabled. */
export function refreshControls() {
    const { hasAccount, busy, autopilot, planId } = state;
    const planShown = planId !== null;

    $('idle-actions').hidden = planShown;
    $('plan-actions').hidden = !planShown;

    $('preview-btn').disabled = !hasAccount || busy || autopilot;
    $('apply').disabled = busy || autopilot;
    $('discard').disabled = busy;

    $('autopilot-toggle').checked = autopilot;
    $('autopilot-toggle').disabled = busy || (!hasAccount && !autopilot);
    $('autopilot-interval').disabled = busy || autopilot;

    $('connect').disabled = busy;
    $('account').disabled = busy;
}
