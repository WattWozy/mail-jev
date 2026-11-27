// Bottom panel: folders (the AI files mail into them by description), rules (always win) and the activity tab.
import { api, getJson } from './api.js';
import { state } from './state.js';
import { loadActivity } from './activity.js';
import { $, ACTIONS, actionLabel, badge, cell, emptyRow, iconButton, show, showError } from './ui.js';

const FIELD_LABELS = {
    FROM_DOMAIN: 'Sender domain',
    FROM_ADDRESS: 'Sender address',
    SUBJECT_CONTAINS: 'Subject contains',
};

const FOLDER_PREFIX = 'folder:';

function selectTab(name) {
    document.querySelectorAll('#setup [data-tab]').forEach(t =>
        t.setAttribute('aria-selected', String(t.dataset.tab === name)));
    document.querySelectorAll('#setup [data-pane]').forEach(p => { p.hidden = p.dataset.pane !== name; });
    if (name === 'activity') loadActivity();
}

// ── Folders ─────────────────────────────────────────

function renderRuleTargets() {
    const sel = $('rule-target');
    const picked = sel.value;
    sel.replaceChildren(
        ...ACTIONS.map(a => new Option(actionLabel(a), a)),
        ...state.folders.map(name => new Option('→ ' + name, FOLDER_PREFIX + name)));
    sel.value = [...sel.options].some(o => o.value === picked) ? picked : 'ARCHIVE';
}

function renderFolders(folders) {
    state.folders = folders.map(f => f.name);
    $('folders-count').textContent = folders.length;
    renderRuleTargets();

    if (!folders.length) {
        $('folders-tbody').replaceChildren(emptyRow(3, 'No folders yet.'));
        return;
    }
    $('folders-tbody').replaceChildren(...folders.map(f => {
        const tr = document.createElement('tr');
        cell(tr, f.name);
        cell(tr, f.description);
        const td = document.createElement('td');
        td.className = 'row-actions';
        td.append(
            iconButton('✎', 'Edit what belongs here', async () => {
                const description = prompt(`What belongs in "${f.name}"? One sentence.`, f.description);
                if (description === null || description.trim() === f.description) return;
                try {
                    await api(`/api/folders/${encodeURIComponent(f.id)}`, { description }, 'PUT');
                    await loadFolders();
                    show(`"${f.name}" updated.`, 'ok');
                } catch (err) { showError(err); }
            }),
            iconButton('✕', 'Delete folder (mail already in it stays there)', async () => {
                try {
                    await api(`/api/folders/${encodeURIComponent(f.id)}`, undefined, 'DELETE');
                    await loadFolders();
                    show(`Folder "${f.name}" removed. Mail already in it stays there.`, 'ok');
                } catch (err) { showError(err); }
            }));
        tr.appendChild(td);
        return tr;
    }));
}

export async function loadFolders() {
    const data = await getJson('/api/folders');
    renderFolders(Array.isArray(data) ? data : []);
}

async function addFolder() {
    const name = $('folder-name').value.trim();
    const description = $('folder-description').value.trim();
    if (!name || !description) {
        show('Give the folder a name and say in one sentence what belongs there.', 'err');
        return;
    }
    try {
        await api('/api/folders', { name, description });
        $('folder-name').value = '';
        $('folder-description').value = '';
        await loadFolders();
        show(`Folder "${name}" added.`, 'ok');
        $('folder-name').focus();
    } catch (err) { showError(err); }
}

// ── Rules ───────────────────────────────────────────

function renderRules(rules) {
    $('rules-count').textContent = rules.length;
    if (!rules.length) {
        $('rules-tbody').replaceChildren(emptyRow(4, 'No rules yet. Tip: open an email above and use "+ Rule".'));
        return;
    }
    $('rules-tbody').replaceChildren(...rules.map(r => {
        const tr = document.createElement('tr');
        const field = r.field || r.matchType || '';
        cell(tr, FIELD_LABELS[field] || field);
        cell(tr, r.pattern || '');
        const then = document.createElement('td');
        then.appendChild(badge(r.folder ? 'MOVE_TO_FOLDER' : (r.action || 'KEEP'), r.folder));
        tr.appendChild(then);
        const td = document.createElement('td');
        td.className = 'row-actions';
        td.appendChild(iconButton('✕', 'Delete rule', async () => {
            try {
                await api(`/api/rules/${encodeURIComponent(r.id)}`, undefined, 'DELETE');
                await loadRules();
                show('Rule removed.', 'ok');
            } catch (err) { showError(err); }
        }));
        tr.appendChild(td);
        return tr;
    }));
}

export async function loadRules() {
    const data = await getJson('/api/rules');
    renderRules(Array.isArray(data) ? data : (data?.rules || []));
}

async function addRule() {
    const pattern = $('rule-pattern').value.trim();
    if (!pattern) {
        show('Enter what the rule should match.', 'err');
        $('rule-pattern').focus();
        return;
    }
    const target = $('rule-target').value;
    const folder = target.startsWith(FOLDER_PREFIX) ? target.slice(FOLDER_PREFIX.length) : '';
    try {
        await api('/api/rules', {
            field: $('rule-field').value,
            pattern,
            action: folder ? 'MOVE_TO_FOLDER' : target,
            category: 'Rule',
            folder,
        });
        $('rule-pattern').value = '';
        await loadRules();
        show('Rule added.', 'ok');
    } catch (err) { showError(err); }
}

/** Prefills the rule form from an email the user is reading; one click on "Add rule" saves it. */
export function ruleFromSender(field, pattern, currentAction) {
    $('setup').open = true;
    selectTab('rules');
    $('rule-field').value = field;
    $('rule-pattern').value = pattern;
    if (ACTIONS.includes(currentAction)) $('rule-target').value = currentAction;
    $('setup').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    $('rule-target').focus();
    show('Pick what should happen to this sender, then press Add rule.');
}

export function loadSetup() {
    return Promise.all([loadFolders(), loadRules()]);
}

export function initSetup() {
    document.querySelectorAll('#setup [data-tab]').forEach(t => { t.onclick = () => selectTab(t.dataset.tab); });
    $('add-folder-btn').onclick = addFolder;
    $('add-rule-btn').onclick = addRule;
    // Enter in the text fields submits, so there is no need to reach for the button.
    for (const [input, fn] of [['folder-description', addFolder], ['rule-pattern', addRule]]) {
        $(input).addEventListener('keydown', e => { if (e.key === 'Enter') fn(); });
    }
    renderRuleTargets();
}
