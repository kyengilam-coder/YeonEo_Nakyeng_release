// app/ 의 웹앱 파일을 각 패키지 폴더로 복사한다.
//   node scripts/copy-app.mjs desktop/app
//   node scripts/copy-app.mjs android/app/src/main/assets/www
// 저장소 루트 기준 경로를 받는다. 대상 폴더는 비우고 다시 채운다.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'app');
const dest = process.argv[2];
if (!dest) { console.error('usage: node scripts/copy-app.mjs <dest>'); process.exit(2); }
const DST = path.join(ROOT, dest);
if (!DST.startsWith(ROOT + path.sep)) { console.error('dest must be inside the repo'); process.exit(2); }

fs.rmSync(DST, { recursive: true, force: true });
fs.mkdirSync(DST, { recursive: true });
let n = 0;
function walk(src, dst) {
  for (const f of fs.readdirSync(src)) {
    const a = path.join(src, f), z = path.join(dst, f);
    if (fs.statSync(a).isDirectory()) { fs.mkdirSync(z, { recursive: true }); walk(a, z); }
    else { fs.copyFileSync(a, z); n++; }
  }
}
walk(SRC, DST);
if (!fs.existsSync(path.join(DST, 'index.html'))) { console.error('index.html missing in app/'); process.exit(1); }
// 버전 도장: 환경 변수 NAKYENG_VERSION(릴리스 태그) → 없으면 VERSION 파일. 페이지의 APP_VERSION 기본값을 바꿔 넣는다
const ver = (process.env.NAKYENG_VERSION || fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf-8')).trim().replace(/^v/, '');
const ip = path.join(DST, 'index.html');
const html = fs.readFileSync(ip, 'utf-8').replace(/(const APP_VERSION=new URLSearchParams\(location\.search\)\.get\('v'\)\|\|')[^']*(')/, `$1${ver}$2`);
if (!html.includes(`||'${ver}'`)) { console.error('APP_VERSION 자리를 찾지 못했습니다'); process.exit(1); }
fs.writeFileSync(ip, html);
console.log('version stamped:', ver);
console.log(`copied ${n} files: app/ -> ${dest}`);
