# 羅經 · 나경 — 설치 파일

28층 나경 앱의 배포 저장소입니다. 설치 파일은 **[Releases](../../releases/latest)** 에서 내려받습니다.

## 기기별 설치

| 기기 | 방법 |
|---|---|
| **Windows** | `…-windows-setup.exe`를 실행해 설치합니다. 설치 없이 쓰려면 `…-windows-portable.exe`를 바로 실행합니다. "Windows의 PC 보호"가 나오면 **추가 정보 → 실행**. |
| **macOS** | `…-mac-universal.dmg`를 열어 Nakyeng을 응용 프로그램 폴더로 끌어 놓습니다. 처음에는 **오른쪽 클릭 → 열기**. "손상되었습니다"가 나오면 터미널에서 `xattr -cr /Applications/Nakyeng.app`. |
| **Linux** | AppImage: `chmod +x Nakyeng-*.AppImage` 후 실행. Debian·Ubuntu: `sudo apt install ./Nakyeng-*-linux-amd64.deb`. |
| **Android** | `…-android.apk`를 폰에서 내려받아 누릅니다. "출처를 알 수 없는 앱 설치"를 허용하라는 안내가 나오면 허용합니다. Android 7.0 이상. |
| **iPad · iPhone** | 애플은 App Store 밖 설치 파일을 허용하지 않습니다. Safari로 **https://kyengilam-coder.github.io/YeonEo_Nakyeng_release/** 를 열고 공유 단추 → **홈 화면에 추가**. 한 번 연 뒤에는 인터넷 없이도 열립니다. |
| **그 밖의 브라우저** | `…-web.zip`을 풀어 `index.html`을 엽니다. |

모든 판은 인터넷 없이 동작합니다. 인터넷이 있으면 Noto Serif KR 글꼴을 받아 쓰고, 없으면 기기 글꼴로 표시합니다.

## 기기별 차이
- **나침반**: 방위 센서가 있는 폰·패드에서만 동작합니다. 컴퓨터에서는 자동으로 꺼지고 수동 회전으로 씁니다.
- **위치 저장**: 폰·패드와 브라우저에서 동작합니다. 데스크톱 앱에서는 위치를 읽지 못할 수 있으며, 이때는 방위만 저장됩니다.
- **CSV 저장**: Android는 `Download/Nakyeng` 폴더, 데스크톱은 저장 창에서 고른 곳에 저장됩니다.

## 저장소 구성
- `app/` — 웹앱 본체(모든 판이 이 파일을 씁니다). 원본은 개발 저장소에 있으며 릴리스 때 복사해 옵니다.
- `desktop/` — Windows·macOS·Linux 앱(Electron). `npm ci && npm run dist:linux` 처럼 빌드합니다.
- `android/` — Android 앱(WebView). `./gradlew assembleRelease`. 빌드 전에 `app/`이 `assets/www`로 복사됩니다.
- `.github/workflows/release.yml` — `v`로 시작하는 태그를 올리면 모든 판을 만들어 Release에 붙이고, 웹판을 GitHub Pages에 올립니다.

## 새 버전 내기
1. 개발 저장소의 웹앱 파일(`index.html`, `layers.json`, `sw.js`, `manifest.webmanifest`, `icon.svg`, `icon-512.png`)을 `app/`에 복사합니다.
2. `VERSION` 파일을 새 버전으로 고칩니다.
3. 커밋 후 `git tag v0.6.0 && git push origin v0.6.0`.

## Android 서명 키 (권장)
비밀값이 없으면 빌드 때마다 임시 키로 서명되어, 새 버전을 설치할 때 이전 앱을 지워야 할 수 있습니다. 한 번 만들어 두면 이후 업데이트가 그대로 설치됩니다.

```
keytool -genkeypair -v -keystore nakyeng.jks -alias nakyeng -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 nakyeng.jks   # 출력 전체를 복사
```
저장소 **Settings → Secrets and variables → Actions**에 다음 네 개를 넣습니다: `ANDROID_KEYSTORE_B64`(위 출력), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`(`nakyeng`), `ANDROID_KEY_PASSWORD`. 키 파일과 비밀번호는 잃어버리지 않게 따로 보관하십시오.
