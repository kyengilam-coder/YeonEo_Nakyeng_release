## 羅經 · 나경 {{VERSION}}

28층 나경(三合盤 23층 + 十二長生 四局 + 二十八宿 度數)을 컴퓨터·패드·핸드폰에서 씁니다. 인터넷 없이 동작합니다.

### 내려받을 파일

| 기기 | 파일 |
|---|---|
| Windows (설치) | `Nakyeng-{{VERSION}}-windows-setup.exe` |
| Windows (설치 없이 실행) | `Nakyeng-{{VERSION}}-windows-portable.exe` |
| macOS (Intel·Apple 칩 공용) | `Nakyeng-{{VERSION}}-mac-universal.dmg` |
| Linux | `Nakyeng-{{VERSION}}-linux-x86_64.AppImage` 또는 `…-linux-amd64.deb` |
| Android 폰·태블릿 | `Nakyeng-{{VERSION}}-android.apk` |
| iPad · iPhone | 설치 파일 없음. Safari로 https://{{OWNER}}.github.io/YeonEo_Nakyeng_release/ 를 열고 공유 → **홈 화면에 추가** |
| 아무 브라우저 | `Nakyeng-{{VERSION}}-web.zip` 을 풀고 `index.html` 열기 |

### 처음 실행할 때 나오는 경고
이 앱은 유료 개발자 인증서로 서명하지 않았습니다. 그래서 처음 한 번 경고가 나옵니다.
- **Windows**: "Windows의 PC 보호" 창에서 **추가 정보 → 실행**.
- **macOS**: 앱을 **오른쪽 클릭 → 열기**. "손상되었습니다"라고 나오면 터미널에서 `xattr -cr /Applications/Nakyeng.app` 후 다시 열기.
- **Android**: 브라우저나 파일 앱에 **출처를 알 수 없는 앱 설치**를 허용한 뒤 APK를 누르기.

### 주요 기능
- 회전·확대·이동(가로·세로 고정, 가운데로), 키보드·마우스·터치 조작
- 나침반 센서(휴대 기기), 眞北 보정, 화면 꺼짐 방지
- 붉은 선 위 모든 층을 向·坐로 판독, 大空亡·小空亡 경고
- 층 켜고 끄기, 十二長生 局 선택(水口로도 선택), 칸 찾기
- 방위 기록(메모·위치), CSV 저장

파일이 온전한지는 `SHA256SUMS.txt`로 확인할 수 있습니다.
