# Mini Emulator

libretro 코어를 쓰는 안드로이드용 에뮬레이터 앱입니다. 앱을 열면 GBA · Neo Geo · 아케이드 · PlayStation 중 모드를 고릅니다.

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
| Neo Geo · 아케이드 엔진 | [FinalBurn Neo](https://github.com/libretro/FBNeo)와 [MAME 2003-Plus](https://github.com/libretro/mame2003-plus-libretro)를 `cores/build-cores.sh`로 직접 빌드합니다(커밋 고정, 저장소에는 바이너리를 넣지 않음). 둘 다 비상업용 라이선스입니다. |
| PlayStation 엔진 | [PCSX ReARMed](https://github.com/libretro/pcsx_rearmed)(GPLv2)를 같은 스크립트로 빌드합니다. |
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

Android Studio(Android SDK, NDK 26.1, CMake 3.22.1)에서 엽니다. 처음 한 번은 아케이드 · PlayStation 엔진을 빌드해야 합니다(수십 분 걸림).

```bash
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125 ./cores/build-cores.sh
./gradlew assembleDebug
```

GitHub Actions는 엔진을 캐시해 두고 `cores/build-cores.sh`가 바뀔 때만 다시 빌드합니다.

### 모드별 ROM 폴더

| 모드 | 폴더 (`files/` 아래) | 엔진 | BIOS |
|---|---|---|---|
| GBA | `roms` | mGBA | `system/gba_bios.bin` (선택) |
| Neo Geo | `roms_neogeo` | FinalBurn Neo 기본, 게임별로 MAME 2003-Plus 선택 가능 | `system/neogeo.zip` (필수) |
| 아케이드 | `roms_arcade` | MAME 2003-Plus 기본, 게임별로 FBNeo 선택 가능 | `system/mame/bios/*.zip` (실행 전에 ROM 폴더로 복사) |
| PlayStation | `roms_ps` | PCSX ReARMed | `system/*.bin` 512KB (선택, 없으면 HLE) |

- 두 아케이드 엔진은 ZIP **파일 이름**으로 게임을 찾습니다. 그래서 앱이 ZIP 안 파일의 CRC로 롬셋을 알아보고(`assets/romsets.gz`, `cores/gen-romsets.py`로 생성) `neobombe.zip`처럼 롬셋 이름으로 바꿔 둡니다. 목록에는 원래 이름이 그대로 보입니다. 롬셋 버전에 맞는 엔진도 자동으로 고르고, 파일이 부족하거나 지원하지 않는 게임이면 실행 전에 알려 줍니다.
- PlayStation 게임은 `.cue`+`.bin`, `.chd`, `.pbp`, `.m3u`, `.iso`를 지원합니다. 파일 여러 개나 폴더째로 가져올 수 있고, `.cue` 없는 `.bin`에는 `.cue`를, `(Disc 1)`·`(Disc 2)` 같은 여러 장 게임에는 `.m3u`를 자동으로 만듭니다.
- 게임 중 메뉴에서 아날로그 스틱(DualShock)을 켜고 끌 수 있고(게임별로 기억), `.m3u` 게임은 디스크를 바꿀 수 있습니다.
- 메모리카드는 `saves/ps/<게임 이름>.sav`에 게임별로 저장됩니다.
- BIOS 타일에서 **폴더째 등록**을 고르면 폴더 안의 파일을 종류별로 나눠 등록합니다: `gba_bios.bin` → GBA, `neogeo.zip` → Neo Geo, 512KB `.bin` → PlayStation, 알려진 기판 BIOS ZIP(`pgm.zip`, `stvbios.zip` 등) → MAME. MAME 타일에서 고르면 폴더의 ZIP을 모두 MAME BIOS로 등록합니다.

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

mGBA 코어는 Mozilla Public License 2.0, PCSX ReARMed는 GPLv2를 따릅니다. 엔진별 라이선스는 `app/src/main/assets/licenses/`를 참고하세요.
`app/src/main/cpp/libretro.h`는 libretro API 헤더(MIT 라이선스)입니다.
이 앱에는 상용 ROM 파일이 들어 있지 않습니다.
