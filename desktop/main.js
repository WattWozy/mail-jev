const { app, BrowserWindow, dialog, safeStorage, shell } = require('electron');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');

app.setPath('userData', path.join(app.getPath('appData'), 'MailSorter'));

let backend = null;
let win = null;
let quitting = false;
let ready = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
  });
  app.whenReady().then(main).catch(fatal);
}

async function main() {
  win = createWindow();

  const jev = jevEnv();
  if (app.isPackaged && !(jev.JEV_ENDPOINT && jev.JEV_API_KEY)) {
    const dir = app.getPath('userData');
    fs.mkdirSync(dir, { recursive: true });
    await dialog.showMessageBox(win, {
      type: 'info',
      title: 'Buzon',
      message: 'Configuration missing',
      detail: `Create a config.json with "jevEndpoint" and "jevApiKey" in:\n${dir}`,
    });
    shell.openPath(dir);
    return app.quit();
  }

  const port = await freePort();
  backend = startBackend(port);
  await waitForServer(port, backend);
  await win.loadURL(`http://127.0.0.1:${port}`);
  ready = true;
}

// ---------- paths ----------
function runtimePaths() {
  if (app.isPackaged) {
    const exe = process.platform === 'win32' ? '.exe' : '';
    return {
      javaBin: path.join(process.resourcesPath, 'jre', 'bin', 'java' + exe),
      jarPath: path.join(process.resourcesPath, 'app.jar'),
      cwd: app.getPath('userData'),
    };
  }
  const root = path.resolve(__dirname, '..');
  return {
    javaBin: process.env.JAVA_BIN || 'java',
    jarPath: process.env.MAILSORTER_JAR || path.join(root, 'target', 'mailsorter.jar'),
    cwd: root, // so your Dotenv finds .env in dev
  };
}

// ---------- secret key (32 bytes, Base64) ----------
function loadOrCreateSecret() {
  const file = path.join(app.getPath('userData'), 'store.key');
  const encrypted = safeStorage.isEncryptionAvailable();
  if (fs.existsSync(file)) {
    const raw = fs.readFileSync(file);
    return encrypted ? safeStorage.decryptString(raw) : raw.toString('utf8');
  }
  const key = crypto.randomBytes(32).toString('base64');
  const data = encrypted ? safeStorage.encryptString(key) : Buffer.from(key, 'utf8');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data, { mode: 0o600 });
  return key;
}

// optional overrides from <userData>/config.json: { "jevEndpoint": "...", "jevApiKey": "..." }
function jevEnv() {
  const file = path.join(app.getPath('userData'), 'config.json');
  if (!fs.existsSync(file)) return {};
  let cfg;
  try {
    const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    cfg = JSON.parse(text);
  } catch (e) {
    throw new Error(`Invalid config.json at ${file}: ${e.message}`);
  }
  const env = {};
  const endpoint = cfg.jevEndpoint || cfg.JEV_ENDPOINT;
  const apiKey = cfg.jevApiKey || cfg.JEV_API_KEY;
  if (endpoint) env.JEV_ENDPOINT = endpoint;
  if (apiKey) env.JEV_API_KEY = apiKey;
  return env;
}

// ---------- backend ----------
function startBackend(port) {
  const { javaBin, jarPath, cwd } = runtimePaths();
  const userData = app.getPath('userData');
  const logDir = path.join(userData, 'logs');
  fs.mkdirSync(logDir, { recursive: true });
  const logFile = path.join(logDir, 'backend.log');
  const log = fs.createWriteStream(logFile, { flags: 'a' });
  const jev = jevEnv();
  log.write(`[electron] config.json keys loaded: ${Object.keys(jev).join(', ') || 'NONE'}\n`);

  const child = spawn(javaBin, ['--enable-native-access=ALL-UNNAMED', '-jar', jarPath], {
    cwd,
    windowsHide: true,
    env: {
      ...process.env,
      WEB_HOST: '127.0.0.1',
      WEB_PORT: String(port),
      MAILSORTER_PERSISTENCE: 'SQLITE',
      MAILSORTER_DB_PATH: path.join(userData, 'mailsorter.db'),
      STORE_SECRET_KEY: loadOrCreateSecret(),
      ...jev,
    },
  });

  child.stdout.on('data', (d) => log.write(d));
  child.stderr.on('data', (d) => log.write(d));
  child.on('error', (e) => fatal(new Error(`Cannot start Java: ${e.message}`)));
  child.on('exit', (code) => {
    if (quitting || !ready) return;
    dialog.showErrorBox('Buzon stopped', `The backend exited (code ${code}).\nLog: ${logFile}`);
    app.quit();
  });
  return child;
}

function waitForServer(port, child, timeoutMs = 40000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const attempt = () => {
      if (child.exitCode !== null) {
        return reject(new Error(
            `Backend exited during startup (code ${child.exitCode}).\nLog: ${path.join(app.getPath('userData'), 'logs', 'backend.log')}`
        ));
      }      if (Date.now() - started > timeoutMs) return reject(new Error('Backend start timeout'));
      // any HTTP response (even 404) means the server is listening
      const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: 1000 }, (res) => {
        res.resume();
        resolve();
      });
      req.on('timeout', () => req.destroy());
      req.on('error', () => setTimeout(attempt, 300));
    };
    attempt();
  });
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

// ---------- window ----------
function createWindow() {
  const w = new BrowserWindow({
    width: 1200,
    height: 800,
    title: 'Buzon',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  w.loadURL(
    'data:text/html;charset=utf-8,' +
      encodeURIComponent(
        '<body style="font-family:system-ui;display:grid;place-items:center;height:100vh;margin:0">Starting Mail Sorter…</body>'
      )
  );
  // external links open in the real browser, never inside the app
  w.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  w.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('http://127.0.0.1:') && !url.startsWith('data:')) {
      e.preventDefault();
      if (/^https?:/.test(url)) shell.openExternal(url);
    }
  });
  return w;
}

// ---------- lifecycle ----------
app.on('before-quit', () => {
  quitting = true;
  if (backend && backend.exitCode === null) backend.kill();
});
app.on('window-all-closed', () => app.quit());

function fatal(err) {
  quitting = true;
  dialog.showErrorBox('Mail Sorter', String(err && err.message ? err.message : err));
  if (backend && backend.exitCode === null) backend.kill();
  app.exit(1);
}
