# Mini Emulator

libretro 코어를 쓰는 안드로이드용 에뮬레이터 앱입니다. 앱을 열면 GBA · Neo Geo · 아케이드 · PlayStation 중 모드를 고릅니다.
지금은 GBA(mGBA 코어)만 동작하고, 나머지 모드는 엔진을 추가하는 중입니다.

- 패키지: `com.example.minigba.gbaonly`
- 버전: `app/build.gradle`의 `versionName` / `versionCode` 참고 (릴리스마다 1씩 올림)
- minSdk 24 / targetSdk 34, `arm64-v8a` 전용

## 이 소스에 대해

원본 프로젝트 폴더가 없어서 **배포된 APK(`MiniGBA`)에서 복원한 소스**입니다.

| 구성 | 상태 |
|---|---|
| Java 코드 (`app/src/main/java`) | jadx로 디컴파일한 뒤 정리했습니다. 클래스·메서드·필드 이름은 원본과 같습니다. 디컴파일러가 망가뜨린 메서드는 바이트코드를 보고 다시 썼습니다. 스트림 처리는 try-with-resources로, 람다는 람다로 되돌렸습니다. |
| 네이티브 브리지 (`app/src/main/cpp/gbabridge.cpp`) | 원본 `libgbabridge.so`를 디스어셈블해 동작을 분석하고 C++로 새로 작성했습니다. 빌드할 때 CMake로 컴파일됩니다. |
| mGBA 코어 (`app/src/main/jniLibs/arm64-v8a/libmgba_libretro_android.so`) | [libretro/mgba](https://github.com/libretro/mgba)(MPL 2.0)를 빌드한 바이너리로, APK에 들어 있던 것을 그대로 씁니다. |
| 매니페스트, 리소스, `assets/` | APK에서 그대로 복원했습니다. |

알아둘 점:
- 지역 변수 이름(`z`, `i2` 등)과 주석은 APK에 남아 있지 않아서 원래대로 되돌릴 수 없습니다.
- `com.seleuco.mame4droid.Emulator`는 아케이드(MAME) 코어용 코드입니다. 이 GBA 전용 빌드에는 `libmame4droid-jni.so`가 없어서 아케이드 기능은 동작하지 않습니다.

### 네이티브 브리지 동작

`NativeGba.create()`가 libretro 코어를 `dlopen`해서 전용 스레드에서 돌립니다.

- 화면: `ANativeWindow`에 RGB565로 그립니다(코어가 XRGB8888/0RGB1555를 주면 변환).
- 소리: 코어의 오디오 샘플을 `AudioSink.write()`로 넘깁니다. 배속(최대 2배)일 때는 `AudioSink.configure()`로 샘플레이트를 같은 배수로 올립니다.
- 저장: `files/saves/<코어>/<이름>.sav`(게임 내 세이브), `<이름>.state`(빠른 저장). 게임을 시작할 때 둘 다 자동으로 불러오고, 종료할 때 자동으로 저장합니다.
- mGBA 옵션 고정값: `mgba_use_bios=ON`, `mgba_skip_bios=OFF`, `mgba_idle_optimization=Remove Known`, `mgba_frameskip=0`.

## 빌드

Android Studio(Android SDK, NDK 26.1, CMake 3.22.1)에서 엽니다.

```bash
./gradlew assembleDebug
```

### 릴리스 서명

이미 배포한 앱을 업데이트하려면 **같은 키로 서명해야 합니다.**
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

## 테스트

`native-test/`에 네이티브 브리지를 리눅스 PC에서 검증하는 테스트가 있습니다. 자세한 내용은 [native-test/README.md](native-test/README.md)를 참고하세요.

```bash
./native-test/run.sh
```

## 라이선스

mGBA 코어는 Mozilla Public License 2.0을 따릅니다. 자세한 내용은 `app/src/main/assets/licenses/mgba.txt`를 참고하세요.
`app/src/main/cpp/libretro.h`는 libretro API 헤더(MIT 라이선스)입니다.
이 앱에는 상용 ROM 파일이 들어 있지 않습니다.
