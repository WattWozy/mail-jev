// HTTP helpers for the local backend.

/** Sends JSON and returns the parsed reply; throws with the server's error message on failure. */
export async function api(path, body, method = 'POST') {
    const opts = { method };
    if (body !== undefined) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(body);
    }
    const res = await fetch(path, opts);
    let data = {};
    try { data = await res.json(); } catch (_) { /* non-JSON error body */ }
    if (!res.ok) throw new Error(data.error || res.statusText);
    return data;
}

/** GET that returns null instead of throwing (e.g. 409 while no account is connected). */
export async function getJson(path) {
    try {
        const res = await fetch(path);
        return res.ok ? await res.json() : null;
    } catch (_) {
        return null;
    }
}
