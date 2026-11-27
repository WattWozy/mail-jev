// Header counters.
import { getJson } from './api.js';
import { $ } from './ui.js';

export async function loadStats() {
    const s = await getJson('/api/stats');
    if (!s) return;
    const total = s.totalSorted ?? s.total ?? 0;
    const ruleHits = s.ruleHits ?? s.rules ?? 0;
    const aiHits = s.jevHits ?? s.jev ?? 0;
    const manual = s.manualOverrides ?? s.manual ?? 0;
    const pct = total > 0 ? Math.round((ruleHits / total) * 100) : 0;

    $('stat-total').textContent = total.toLocaleString();
    $('stat-rule-pct').textContent = `${pct}%`;
    $('stat-rule-pct').parentElement.title = `${ruleHits.toLocaleString()} sorted by your rules`;
    $('stat-ai').textContent = aiHits.toLocaleString();
    $('stat-manual').textContent = manual.toLocaleString();
}
