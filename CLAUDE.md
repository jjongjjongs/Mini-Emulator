# Mini GBA 작업 규칙

## 릴리스
- 릴리스할 때마다 `app/build.gradle`의 버전을 하나씩 올린다.
  - `versionCode`를 1 올리고, `versionName`의 마지막 숫자도 1 올린다 (예: 3 / `0.0.3-gba` → 4 / `0.0.4-gba`).
  - versionCode가 올라가야 설치된 앱 위에 업데이트로 설치된다.
- 릴리스는 GitHub Actions의 **Build APK** 워크플로를 수동 실행하면서 `release_tag`에 `v<versionName의 숫자 부분>`(예: `v0.0.3`)을 넣어 만든다.
- 릴리스에는 원본 키로 서명된 APK만 올린다. 서명 키와 비밀번호는 저장소에 커밋하지 않는다(GitHub Secrets 사용).
