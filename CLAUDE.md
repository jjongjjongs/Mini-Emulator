# Mini Emulator 작업 규칙

## 릴리스
- 릴리스할 때마다 `app/build.gradle`의 버전을 하나씩 올린다.
  - `versionCode`를 1 올리고, `versionName`의 마지막 숫자도 1 올린다 (예: 4 / `0.0.4` → 5 / `0.0.5`).
  - versionCode가 올라가야 설치된 앱 위에 업데이트로 설치된다.
- 릴리스는 GitHub Actions의 **Build APK** 워크플로를 수동 실행하면서 `release_tag`에 `v<versionName>`(예: `v0.0.4`)을 넣어 만든다. 릴리스 APK 이름은 `Mini-Emulator-<versionName>.apk`.
- 릴리스에는 원본 키로 서명된 APK만 올린다. 서명 키와 비밀번호는 저장소에 커밋하지 않는다(GitHub Secrets 사용).

## 도움말 (개요 문서)
- 기능 설명서는 `docs/overview/overview.tpl.html`(그림은 `docs/overview/img/`)이다. 릴리스에서 기능이 바뀌면 이 문서에도 그 내용을 넣는다.
- 빌드 때 `docs/overview/render.py`가 버전 · 날짜를 채워 `app/src/main/assets/help/`로 만들고, 앱의 설정 → 도움말이 그것을 보여 준다 (그래서 앱 버전마다 그 버전의 문서가 들어간다). 이 폴더는 커밋하지 않는다.

## 앱
- 앱 이름은 Mini Emulator. 내부 패키지 ID `com.example.minigba.gbaonly`는 기존 설치본 업데이트와 세이브 유지를 위해 바꾸지 않는다.
