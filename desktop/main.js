// 羅經 · 나경 데스크톱 앱 (Windows · macOS · Linux)
// app/ 폴더의 웹앱을 창 하나에 띄운다. 인터넷 없이 동작하며, 외부 링크는 기본 브라우저로 연다.
const { app, BrowserWindow, shell, Menu, session, dialog } = require('electron');
let autoUpdater = null;
try { ({ autoUpdater } = require('electron-updater')); } catch (e) { autoUpdater = null; }
const path = require('node:path');

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null;
function appDir() {
  // 패키징 후에는 resources/app/app, 개발 중에는 ../app
  const packaged = path.join(__dirname, 'app');
  return require('node:fs').existsSync(path.join(packaged, 'index.html')) ? packaged : path.join(__dirname, '..', 'app');
}

function createWindow() {
  win = new BrowserWindow({
    width: 1200, height: 900, minWidth: 360, minHeight: 480,
    title: '鳶魚 羅經 · 연어 나경', backgroundColor: '#e8dcc3',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false },
  });
  win.loadFile(path.join(appDir(), 'index.html'), { query: { v: app.getVersion() } });   // 페이지가 버전을 알고 새 버전 안내에 쓴다
  // 새 창·외부 주소는 기본 브라우저로
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); } });
  setupUpdates();
  win.on('closed', () => { win = null; });
}

// 자동 업데이트: GitHub Release의 latest*.yml을 보고 내려받아, 다시 시작할 때 설치한다.
// 서명 없는 macOS 앱은 자동 설치가 되지 않으므로 건너뛰고(페이지가 새 버전을 안내), deb로 설치한 Linux도 건너뛴다.
let updatesReady = false;
function setupUpdates() {
  if (updatesReady || !autoUpdater || !app.isPackaged) return;
  if (process.platform === 'darwin') return;
  if (process.platform === 'linux' && !process.env.APPIMAGE) return;
  updatesReady = true;
  try {
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.logger = null;
    autoUpdater.on('update-downloaded', info => {
      if (!win) return;
      dialog.showMessageBox(win, { type: 'info', buttons: ['지금 다시 시작', '나중에'], defaultId: 0, cancelId: 1, title: '나경 새 버전',
        message: `새 버전 ${info.version}이 준비됐습니다.`, detail: '지금 다시 시작하면 바로 설치됩니다. "나중에"를 누르면 앱을 닫을 때 설치됩니다.' })
        .then(r => { if (r.response === 0) autoUpdater.quitAndInstall(); }).catch(() => {});
    });
    autoUpdater.on('error', () => {});
    setTimeout(() => autoUpdater.checkForUpdates().catch(() => {}), 8000);
    setInterval(() => autoUpdater.checkForUpdates().catch(() => {}), 6 * 60 * 60 * 1000);
  } catch (e) {}
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  // 메뉴: macOS는 기본 앱 메뉴(복사·붙여넣기·종료)를 두고, 다른 곳은 숨긴다
  if (process.platform === 'darwin') Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'viewMenu' }, { role: 'windowMenu' }]));
  else Menu.setApplicationMenu(null);
  // 위치·클립보드·화면 유지만 허용
  const allow = new Set(['geolocation', 'clipboard-sanitized-write', 'clipboard-read', 'wake-lock', 'screen-wake-lock', 'media', 'mediaKeySystem']);
  session.defaultSession.setPermissionCheckHandler((wc, perm) => allow.has(perm));
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(allow.has(perm)));
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
