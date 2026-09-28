// 羅經 · 나경 데스크톱 앱 (Windows · macOS · Linux)
// app/ 폴더의 웹앱을 창 하나에 띄운다. 인터넷 없이 동작하며, 외부 링크는 기본 브라우저로 연다.
const { app, BrowserWindow, shell, Menu, session } = require('electron');
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
    title: '羅經 · 나경', backgroundColor: '#e8dcc3',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false },
  });
  win.loadFile(path.join(appDir(), 'index.html'));
  // 새 창·외부 주소는 기본 브라우저로
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); } });
  win.on('closed', () => { win = null; });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  // 메뉴: macOS는 기본 앱 메뉴(복사·붙여넣기·종료)를 두고, 다른 곳은 숨긴다
  if (process.platform === 'darwin') Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'viewMenu' }, { role: 'windowMenu' }]));
  else Menu.setApplicationMenu(null);
  // 위치·클립보드·화면 유지만 허용
  const allow = new Set(['geolocation', 'clipboard-sanitized-write', 'clipboard-read', 'wake-lock', 'screen-wake-lock']);
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(allow.has(perm)));
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
