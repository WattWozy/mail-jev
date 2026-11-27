// Light/dark toggle. The initial theme is set inline in index.html before first paint.
import { $ } from './ui.js';

function currentTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
}

function applyTheme(theme) {
    const normalized = theme === 'light' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', normalized);
    document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', normalized);

    const isDark = normalized === 'dark';
    $('theme-icon').textContent = isDark ? '☀' : '☾';
    $('theme-label').textContent = isDark ? 'Light' : 'Dark';
    $('theme-toggle').setAttribute('aria-label', isDark ? 'Switch to light theme' : 'Switch to dark theme');

    try { localStorage.setItem('mailsorter.theme', normalized); } catch (_) { /* storage optional */ }
}

export function initTheme() {
    $('theme-toggle').onclick = () => applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
    applyTheme(currentTheme());
}
