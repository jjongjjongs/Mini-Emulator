# Native bridge host test

`app/src/main/cpp/gbabridge.cpp`를 안드로이드 기기 없이 리눅스 PC에서 검증하는 테스트입니다.

- Android NDK API(`ANativeWindow_*`, `__android_log_*`)는 `stubs.cpp`의 대체 구현을 씁니다.
- mGBA libretro 코어는 [libretro/mgba](https://github.com/libretro/mgba) 소스를 받아 리눅스용으로 빌드합니다.
- `rom/rom.s`로 만든 작은 테스트 ROM을 실행하고, JVM에서 JNI로 `NativeGba`의 모든 메서드를 호출해 봅니다.
  - 화면 출력(RGB565 변환), 오디오 콜백, 버튼 입력, SRAM/스테이트 저장·불러오기, 배속, 일시정지,
    Surface 교체, 오류 메시지 등을 확인합니다.

```bash
./native-test/run.sh
# AddressSanitizer/UBSan과 함께 (g++의 libasan을 미리 로드해야 할 수 있음)
SANITIZE="-fsanitize=address,undefined" ./native-test/run.sh
```

필요한 도구: clang/clang++, ld.lld, llvm-objcopy, cmake, make, git, JDK
