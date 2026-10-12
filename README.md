# Mini GBA

mGBA(libretro) 코어를 쓰는 안드로이드용 GBA 에뮬레이터 앱입니다.

- 패키지: `com.example.minigba.gbaonly`
- 버전: `0.0.1-gba` (versionCode 1)
- minSdk 24 / targetSdk 34, `arm64-v8a` 전용

## 이 소스에 대해

원본 프로젝트 폴더가 없어서 **배포된 APK(`MiniGBA`)를 디컴파일해 복원한 소스**입니다.

| 구성 | 상태 |
|---|---|
| Java 코드 (`app/src/main/java`) | jadx로 디컴파일했습니다. 클래스·메서드·필드 이름은 원본과 같습니다. 지역 변수 이름(`z`, `i2` 등)과 주석은 사라졌습니다. 디컴파일러가 제대로 복원하지 못한 메서드 4개(`MainActivity.normalizeBloodRoar2Package`, `MainActivity.importMameBios`, `GameActivity`의 가로 화면 레이아웃 람다, `Emulator.bitblt`)는 바이트코드를 보고 손으로 다시 썼습니다. |
| 매니페스트, 리소스, `assets/` | APK에서 그대로 복원했습니다. |
| `libgbabridge.so` (JNI 브리지) | **C/C++ 소스가 없습니다.** APK에 들어 있던 바이너리를 `app/src/main/jniLibs/arm64-v8a/`에 그대로 넣었습니다. 연결되는 Java 쪽 선언은 `NativeGba.java`에 있습니다. |
| `libmgba_libretro_android.so` (mGBA 코어) | [libretro/mgba](https://github.com/libretro/mgba)(MPL 2.0)를 빌드한 바이너리입니다. 소스는 업스트림에 있습니다. |

알아둘 점:
- 디컴파일 특성상 `try { ... } finally { }`처럼 비어 있는 `finally` 블록이 남아 있습니다. 원래는 try-with-resources였던 부분으로, 정상 경로에서는 스트림을 닫지만 예외가 나면 닫히지 않을 수 있습니다.
- `com.seleuco.mame4droid.Emulator`는 아케이드(MAME) 코어용 코드입니다. 이 GBA 전용 빌드에는 `libmame4droid-jni.so`가 들어 있지 않아서, 아케이드 기능은 동작하지 않습니다.

## 빌드

Android Studio(또는 Android SDK가 설치된 환경)에서 엽니다.

```bash
./gradlew assembleDebug
```

### 릴리스 서명

Play 스토어 등에 이미 배포한 앱을 업데이트하려면 **같은 키로 서명해야 합니다.**
서명 키와 비밀번호는 이 저장소에 **올리지 않습니다**(`.gitignore`에 포함됨).

1. `release/minigba-release.jks`에 키스토어 파일을 둡니다.
2. 프로젝트 루트에 `keystore.properties`를 만듭니다.
   ```properties
   storeFile=release/minigba-release.jks
   storePassword=...
   keyAlias=minigba
   keyPassword=...
   ```
3. `./gradlew assembleRelease`

## 라이선스

mGBA 코어는 Mozilla Public License 2.0을 따릅니다. 자세한 내용은 `app/src/main/assets/licenses/mgba.txt`를 참고하세요.
이 앱에는 상용 ROM 파일이 들어 있지 않습니다.
