// Autopilot switch: sorts new mail in the background at a fixed interval.
import { api, getJson } from './api.js';
import { state } from './state.js';
import { $, refreshControls, show, showError } from './ui.js';
import { discardPlan, showEmpty } from './sort.js';
import { refreshActivity } from './activity.js';

function setIntervalChoice(seconds) {
    if (!seconds) return;
    const sel = $('autopilot-interval');
    if (![...sel.options].some(o => +o.value === seconds)) {
        const label = seconds % 60 ? `${seconds} s` : `${seconds / 60} min`;
        sel.add(new Option(label, String(seconds)));
    }
    sel.value = String(seconds);
}

function sync(enabled, intervalSeconds) {
    state.autopilot = !!enabled;
    setIntervalChoice(intervalSeconds);
    refreshControls();
    if (state.planId === null) showEmpty(); // its hint depends on autopilot
}

async function toggle() {
    const enabled = $('autopilot-toggle').checked;
    const intervalSeconds = +$('autopilot-interval').value;
    try {
        const res = await api('/api/autopilot', {
            enabled,
            intervalSeconds,
            limit: Math.max(1, +$('limit').value || 10),
        });
        sync(res.enabled ?? enabled, res.intervalSeconds ?? intervalSeconds);
        discardPlan(); // autopilot owns the mailbox while on; also refreshes the empty-table hint
        refreshActivity();
        show(state.autopilot ? 'Autopilot is on. See Activity below for what it does.' : 'Autopilot is off.',
            state.autopilot ? 'ok' : 'info');
    } catch (err) {
        sync(state.autopilot); // revert the switch
        showError(err);
    }
}

export async function loadAutopilot() {
    const data = await getJson('/api/autopilot');
    if (data) sync(data.enabled, data.intervalSeconds);
}

export function initAutopilot() {
    $('autopilot-toggle').onchange = toggle;
}
