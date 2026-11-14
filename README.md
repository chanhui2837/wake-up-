# ⏰ 깨워알람

미션을 끝내야만 꺼지는 알람 (웹 + 안드로이드 앱)

- 웹: Render (`server.js` + `public/index.html`), 로그인/회원가입, 알람은 MongoDB에 저장
- 안드로이드: `android/` — GitHub Actions가 자동으로 APK를 빌드해 Releases에 올림
- APK 다운로드: https://github.com/chanhui2837/wake-up-/releases/latest/download/wakeup-alarm.apk

## Render 환경변수
- `MONGODB_URI` : MongoDB Atlas 연결 문자열
- `JWT_SECRET` : 아무 긴 랜덤 문자열
- `DB_NAME` : (선택) 기본값 `wakeup`
