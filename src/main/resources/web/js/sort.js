// Main table: the preview plan to apply or discard (empty until the user presses Preview).
// Also hosts the inline email preview that opens under a clicked row.
import { api } from './api.js';
import { state } from './state.js';
import {
    $, ACTIONS, actionLabel, badge, busy, cell, emptyRow, pretty, refreshControls,
    setBadge, show, showError, sourceTag,
} from './ui.js';
import { loadStats } from './stats.js';
import { ruleFromSender } from './setup.js';
import { refreshActivity } from './activity.js';

const COLS = 5;

// Only entries the plan routed to a folder can cycle back to MOVE_TO_FOLDER.
const actionsFor = folder => folder ? ['MOVE_TO_FOLDER', ...ACTIONS] : ACTIONS;

function setTitle(title, hint = '') {
    $('table-title').textContent = title;
    $('table-hint').textContent = hint;
}

function renderSummary() {
    const counts = {};
    document.querySelectorAll('#rows .badge').forEach(b => {
        const label = b.textContent;
        counts[label] = (counts[label] || 0) + 1;
    });
    $('summary').textContent = Object.entries(counts).map(([a, n]) => `${a} ${n}`).join(' · ');
}

function whyCell(tr, reason, source) {
    const td = document.createElement('td');
    const tag = sourceTag(source, false);
    if (tag) td.appendChild(tag);
    td.appendChild(document.createTextNode(reason ?? ''));
    td.title = td.textContent;
    tr.appendChild(td);
}

/** Empty table with a pointer to the next step. */
export function showEmpty() {
    closePreview();
    setTitle('Preview');
    const n = Math.max(1, +$('limit').value || 10);
    const text = !state.hasAccount ? 'Connect an account to get started.'
        : state.autopilot ? 'Autopilot is sorting new mail. See Activity below for what it did.'
        : `Press Preview to see what would happen to your next ${n} emails. Nothing changes until you apply.`;
    $('rows').replaceChildren(emptyRow(COLS, text));
}

// ── Plan ────────────────────────────────────────────

function renderPlan(plan) {
    state.planId = plan.planId;
    state.overrides = {};
    closePreview();
    setTitle('Preview', 'Click an action to change it · click a row to read the email');

    $('rows').replaceChildren(...plan.entries.map(e => {
        const tr = document.createElement('tr');
        const key = e.emailId ?? e.id ?? e.uid ?? e.messageId;
        const b = badge(e.action, e.folder, true);

        if (key != null) {
            b.title = 'Click to change';
            b.onclick = ev => {
                ev.stopPropagation();
                const cycle = actionsFor(e.folder);
                const next = cycle[(cycle.indexOf(b.dataset.action) + 1) % cycle.length];
                setBadge(b, next, e.folder);
                state.overrides[key] = next;
                if (preview.emailId === key) preview.action = next;
                renderSummary();
            };
        } else {
            b.classList.add('locked');
            b.title = 'This entry has no id, so its action cannot be changed';
            b.onclick = ev => ev.stopPropagation();
        }

        const td = document.createElement('td');
        td.appendChild(b);
        tr.appendChild(td);
        cell(tr, e.from);
        cell(tr, e.subject);
        cell(tr, pretty(e.category));
        whyCell(tr, e.reason, e.source || (e.matchedRule || e.fromRule ? 'RULE' : ''));

        tr.style.cursor = 'pointer';
        tr.onclick = () => tr.classList.contains('selected')
            ? closePreview()
            : openPreview(key, e.from, e.subject, b.dataset.action, tr);
        return tr;
    }));

    $('apply').textContent = `Apply to ${plan.entries.length}`;
    renderSummary();
    refreshControls();
}

/** Drops the current plan (nothing is changed in the mailbox). */
export function discardPlan() {
    state.planId = null;
    state.overrides = {};
    refreshControls();
    showEmpty();
}

async function previewPlan() {
    if (state.autopilot) return;
    show('');
    try {
        const plan = await busy('Looking at your mail…', () =>
            api('/api/classify', { limit: Math.max(1, +$('limit').value || 10) }));
        if (!plan.entries.length) {
            show('Nothing to sort: your inbox is clean.', 'ok');
            return;
        }
        renderPlan(plan);
    } catch (err) {
        showError(err);
    }
}

async function applyPlan() {
    if (state.planId === null || state.autopilot) return;
    show('');
    try {
        const result = await busy('Applying…', () =>
            api('/api/apply', { planId: state.planId, overrides: state.overrides }));
        const applied = Object.entries(result.applied || {})
            .map(([a, n]) => `${actionLabel(a)} ${n}`).join(', ') || 'nothing';
        const failures = (result.failures || []).length;
        discardPlan();
        refreshActivity();
        await loadStats();
        if (failures) show(`Done (${applied}). ${failures} could not be moved; they will be retried next time.`, 'warn');
        else show(`Done: ${applied}.`, 'ok');
    } catch (err) {
        showError(err);
    }
}

// ── Inline email preview ────────────────────────────

const preview = { emailId: null, from: '', action: 'ARCHIVE', row: null };

export function closePreview() {
    preview.emailId = null;
    preview.from = '';
    document.querySelectorAll('#rows tr.selected').forEach(r => r.classList.remove('selected'));

    // Rescue the preview node BEFORE removing the row that contains it,
    // otherwise it is destroyed and every later $('preview') is null.
    const p = $('preview');
    p.classList.remove('visible');
    $('preview-holder').appendChild(p);
    preview.row?.remove();
    preview.row = null;

    $('promote-rule-btn').hidden = true;
    $('preview-from').textContent = '';
    $('preview-subject').textContent = '';
    $('preview-date').textContent = '';
    $('preview-body').textContent = '';
    $('preview-body').classList.add('empty');
    $('preview-loading').hidden = true;
}

const domainOf = from => (from || '').match(/@([a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/)?.[1].toLowerCase() || '';

function showPromote() {
    const domain = domainOf(preview.from);
    $('promote-rule-btn').textContent = domain ? `+ Rule for @${domain}` : '+ Rule for sender';
    $('promote-rule-btn').hidden = !(domain || preview.from.trim());
}

async function openPreview(emailId, from, subject, action, row) {
    closePreview();
    preview.emailId = emailId ?? null;
    preview.from = from || '';
    preview.action = action || 'ARCHIVE';
    row.classList.add('selected');
    showPromote();

    preview.row = document.createElement('tr');
    preview.row.className = 'detail-row';
    const td = document.createElement('td');
    td.colSpan = COLS;
    preview.row.appendChild(td);
    row.after(preview.row);
    td.appendChild($('preview'));
    $('preview').classList.add('visible');

    $('preview-from').textContent = from || '(unknown sender)';
    $('preview-subject').textContent = subject || '(no subject)';
    $('preview-body').classList.remove('empty');

    if (emailId == null) {
        $('preview-body').textContent = 'This email cannot be opened.';
        return;
    }
    $('preview-loading').hidden = false;
    try {
        const data = await api('/api/email', { emailId });
        if (preview.emailId !== emailId) return; // another row was opened meanwhile
        const text = data.textBody ?? data.body ?? data.plainText;
        $('preview-body').textContent = text || '(no plain-text body available)';
        $('preview-body').classList.toggle('empty', !text);
        if (data.date) $('preview-date').textContent = data.date;
        if (data.subject) $('preview-subject').textContent = data.subject;
        if (data.from) {
            $('preview-from').textContent = data.from;
            preview.from = data.from;
            showPromote();
        }
    } catch (err) {
        if (preview.emailId === emailId) $('preview-body').textContent = 'Could not load email: ' + err.message;
    } finally {
        if (preview.emailId === emailId) $('preview-loading').hidden = true;
    }
}

export function initSort() {
    $('preview-btn').onclick = previewPlan;
    $('apply').onclick = applyPlan;
    $('discard').onclick = discardPlan;
    $('preview-close').onclick = closePreview;
    $('limit').oninput = () => { if (state.planId === null) showEmpty(); };
    $('promote-rule-btn').onclick = () => {
        const domain = domainOf(preview.from);
        ruleFromSender(domain ? 'FROM_DOMAIN' : 'FROM_ADDRESS', domain || preview.from.trim(), preview.action);
    };
}
