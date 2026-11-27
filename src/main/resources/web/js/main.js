// Entry point: wires the modules together and listens for live server events.
import { initAccounts, loadAccounts } from './accounts.js';
import { initAutopilot, loadAutopilot } from './autopilot.js';
import { initSetup, loadSetup } from './setup.js';
import { initActivity, refreshActivity } from './activity.js';
import { discardPlan, initSort, showEmpty } from './sort.js';
import { loadStats } from './stats.js';
import { initTheme } from './theme.js';
import { refreshControls, show } from './ui.js';

async function onAccountChanged() {
    // stats, activity, folders and rules are all per account; a plan belongs to the old one
    discardPlan();
    refreshActivity();
    await Promise.all([loadSetup(), loadStats()]);
}

async function onAutopilotRun(summary) {
    await loadStats();
    if (summary) show(`Autopilot: ${summary}`);
    refreshActivity(); // only if the Activity tab is open
}

function connectEvents() {
    try {
        const es = new EventSource('/api/events');
        es.addEventListener('stats', () => loadStats());
        es.addEventListener('autopilot', ev => onAutopilotRun(ev.data));
        es.onmessage = ev => {
            try {
                const msg = JSON.parse(ev.data);
                if (msg.type === 'stats' || msg.type === 'autopilot') onAutopilotRun(msg.summary);
            } catch (_) { /* plain text message ignored */ }
        };
    } catch (_) { /* live updates are optional */ }
}

initTheme();
initSort();
initSetup();
initActivity();
initAutopilot();
initAccounts(onAccountChanged);
refreshControls();
showEmpty();

await Promise.all([loadAutopilot(), loadAccounts()]);
connectEvents();
