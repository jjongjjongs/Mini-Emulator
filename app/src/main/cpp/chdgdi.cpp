// CHD -> GDI for Dreamcast discs ("GDI로 풀기"), so a CHD game can be patched track by track
// or with a .dcp like a GDI one. The tracks are read with libchdr (the library Flycast reads
// CHD with) and laid out the way Flycast's own CHD reader places them: a track starts after its
// pregap, pregap frames stored in the file (PGTYPE "V…") are skipped, the padding frames at the
// end are not part of the track, and audio is stored big-endian in GD-ROM CHDs (tag CHGD).
#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libchdr/chd.h>
}

namespace {

constexpr uint32_t kFrame = 2352 + 96;
constexpr uint32_t kPadding = 4;

struct Track {
    int number = 0;
    uint32_t lba = 0;
    int ctrl = 0;           // 4 data, 0 audio
    uint32_t sectorSize = 2352;
    uint64_t firstFrame = 0;  // in the CHD
    uint32_t count = 0;
    bool swap = false;
    std::string file;
};

struct Disc {
    chd_file *chd = nullptr;
    uint32_t hunkBytes = 0;
    uint32_t framesPerHunk = 0;
    std::vector<Track> tracks;

    ~Disc() {
        if (chd != nullptr) {
            chd_close(chd);
        }
    }
};

uint32_t sectorSizeOf(const char *type, std::string &error) {
    if (strcmp(type, "AUDIO") == 0 || strcmp(type, "MODE1_RAW") == 0 || strcmp(type, "MODE1/2352") == 0
            || strcmp(type, "MODE2_RAW") == 0 || strcmp(type, "MODE2/2352") == 0) {
        return 2352;
    }
    if (strcmp(type, "MODE1") == 0 || strcmp(type, "MODE1/2048") == 0) {
        return 2048;
    }
    error = std::string("지원하지 않는 트랙 형식이에요 (") + type + ").";
    return 0;
}

// Reads the track list. Only GD-ROM CHDs (the Dreamcast's own discs) are taken.
std::string open(const char *path, Disc &disc) {
    chd_error err = chd_open(path, CHD_OPEN_READ, nullptr, &disc.chd);
    if (err != CHDERR_NONE) {
        disc.chd = nullptr;
        return std::string("CHD 파일을 열 수 없어요 (") + chd_error_string(err) + ").";
    }
    const chd_header *header = chd_get_header(disc.chd);
    disc.hunkBytes = header->hunkbytes;
    if (disc.hunkBytes == 0 || disc.hunkBytes % kFrame != 0) {
        return "CD 이미지 CHD가 아니에요.";
    }
    disc.framesPerHunk = disc.hunkBytes / kFrame;
    uint32_t total = 150;
    uint64_t offset = 0;
    bool gdrom = false;
    for (;;) {
        char meta[512];
        uint32_t length = 0;
        uint32_t tag = 0;
        uint8_t flags = 0;
        char type[32] = "", subtype[32] = "NONE", pgtype[32] = "NONE", pgsub[32] = "NONE";
        int number = -1, frames = 0, pad = 0, pregap = 0, postgap = 0;
        uint32_t index = (uint32_t) disc.tracks.size();
        bool swap = true;
        memset(meta, 0, sizeof(meta));
        err = chd_get_metadata(disc.chd, GDROM_OLD_METADATA_TAG, index, meta, sizeof(meta) - 1, &length, &tag, &flags);
        if (err == CHDERR_NONE) {
            swap = false;  // the first GD-ROM CHDs kept audio little-endian
        } else {
            err = chd_get_metadata(disc.chd, GDROM_TRACK_METADATA_TAG, index, meta, sizeof(meta) - 1, &length, &tag, &flags);
        }
        if (err != CHDERR_NONE) {
            if (index == 0 && (chd_get_metadata(disc.chd, CDROM_TRACK_METADATA2_TAG, 0, meta, sizeof(meta) - 1, &length, &tag, &flags) == CHDERR_NONE
                    || chd_get_metadata(disc.chd, CDROM_TRACK_METADATA_TAG, 0, meta, sizeof(meta) - 1, &length, &tag, &flags) == CHDERR_NONE)) {
                return "드림캐스트 GD-ROM CHD가 아니에요 (일반 CD 이미지예요).";
            }
            break;
        }
        gdrom = true;
        if (sscanf(meta, GDROM_TRACK_METADATA_FORMAT, &number, type, subtype, &frames, &pad, &pregap, pgtype, pgsub, &postgap) < 4) {
            return "CHD의 트랙 정보를 읽지 못했어요.";
        }
        if (number != (int) index + 1 || frames <= 0) {
            return "CHD의 트랙 정보가 이상해요.";
        }
        std::string error;
        Track track;
        track.number = number;
        track.sectorSize = sectorSizeOf(type, error);
        if (track.sectorSize == 0) {
            return error;
        }
        track.ctrl = strcmp(type, "AUDIO") == 0 ? 0 : 4;
        uint32_t inFile = pregap > 0 && pgtype[0] == 'V' ? (uint32_t) pregap : 0;
        if ((int) inFile > frames || pad < 0 || (int) inFile + pad > frames) {
            return "CHD의 트랙 정보가 이상해요.";
        }
        uint32_t start = total + (uint32_t) pregap;
        total = start + (uint32_t) frames - inFile;
        track.lba = start - 150;
        track.firstFrame = offset + inFile;
        track.count = (uint32_t) frames - inFile - (uint32_t) pad;
        track.swap = track.ctrl == 0 && swap;
        disc.tracks.push_back(track);
        offset += (uint64_t) ((frames + kPadding - 1) / kPadding) * kPadding;
    }
    if (!gdrom || disc.tracks.size() < 3) {
        return "드림캐스트 GD-ROM CHD가 아니에요.";
    }
    return "";
}

uint64_t outputBytes(const Disc &disc) {
    uint64_t bytes = 0;
    for (const Track &track : disc.tracks) {
        bytes += (uint64_t) track.count * track.sectorSize;
    }
    return bytes;
}

std::string jstr(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string text(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return text;
}

}  // namespace

// {bytes the GDI takes, tracks}, or null with the reason through error[0].
extern "C" JNIEXPORT jlongArray JNICALL Java_com_example_minigba_ChdGdi_info(JNIEnv *env, jclass, jstring chdPath, jobjectArray error) {
    Disc disc;
    std::string why = open(jstr(env, chdPath).c_str(), disc);
    if (!why.empty()) {
        env->SetObjectArrayElement(error, 0, env->NewStringUTF(why.c_str()));
        return nullptr;
    }
    jlong values[2] = {(jlong) outputBytes(disc), (jlong) disc.tracks.size()};
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// Writes "<base> (Track NN).bin/.raw/.iso" and "<base>.gdi" into dir. Null when done, else the
// reason (files written so far are removed). progress.onProgress(done, total) false cancels.
extern "C" JNIEXPORT jstring JNICALL Java_com_example_minigba_ChdGdi_convert(JNIEnv *env, jclass, jstring chdPath, jstring dirPath, jstring baseName, jobject progress) {
    Disc disc;
    std::string why = open(jstr(env, chdPath).c_str(), disc);
    if (!why.empty()) {
        return env->NewStringUTF(why.c_str());
    }
    std::string dir = jstr(env, dirPath);
    std::string base = jstr(env, baseName);
    jmethodID onProgress = nullptr;
    if (progress != nullptr) {
        jclass type = env->GetObjectClass(progress);
        onProgress = env->GetMethodID(type, "onProgress", "(JJ)Z");
        env->DeleteLocalRef(type);
    }
    uint64_t total = outputBytes(disc);
    uint64_t done = 0;
    std::vector<std::string> written;
    std::vector<uint8_t> hunk(disc.hunkBytes);
    std::vector<uint8_t> out;
    out.reserve(1 << 20);
    uint64_t loaded = UINT64_MAX;
    bool failed = false;
    for (Track &track : disc.tracks) {
        char name[64];
        snprintf(name, sizeof(name), " (Track %02d).%s", track.number, track.ctrl == 0 ? "raw" : track.sectorSize == 2048 ? "iso" : "bin");
        track.file = base + name;
        std::string path = dir + "/" + track.file;
        FILE *file = fopen(path.c_str(), "wb");
        if (file == nullptr) {
            why = std::string("트랙 파일을 만들 수 없어요 (") + strerror(errno) + ").";
            failed = true;
            break;
        }
        written.push_back(path);
        for (uint32_t k = 0; k < track.count && !failed; k++) {
            uint64_t frame = track.firstFrame + k;
            uint64_t index = frame / disc.framesPerHunk;
            if (index != loaded) {
                chd_error err = chd_read(disc.chd, (uint32_t) index, hunk.data());
                if (err != CHDERR_NONE) {
                    why = std::string("CHD를 읽는 중에 문제가 생겼어요 (") + chd_error_string(err) + ").";
                    failed = true;
                    break;
                }
                loaded = index;
            }
            const uint8_t *sector = hunk.data() + (frame % disc.framesPerHunk) * kFrame;
            size_t at = out.size();
            out.insert(out.end(), sector, sector + track.sectorSize);
            if (track.swap) {
                for (size_t b = at; b + 1 < out.size(); b += 2) {
                    uint8_t t = out[b];
                    out[b] = out[b + 1];
                    out[b + 1] = t;
                }
            }
            if (out.size() >= (1 << 20) || k + 1 == track.count) {
                if (fwrite(out.data(), 1, out.size(), file) != out.size()) {
                    why = "저장 공간이 부족하거나 파일을 쓸 수 없어요.";
                    failed = true;
                    break;
                }
                done += out.size();
                out.clear();
                if (onProgress != nullptr && !env->CallBooleanMethod(progress, onProgress, (jlong) done, (jlong) total)) {
                    why = "취소했어요.";
                    failed = true;
                    break;
                }
            }
        }
        out.clear();
        if (fclose(file) != 0 && !failed) {
            why = "저장 공간이 부족하거나 파일을 쓸 수 없어요.";
            failed = true;
        }
        if (failed) {
            break;
        }
    }
    if (!failed) {
        std::string gdi = std::to_string(disc.tracks.size()) + "\r\n";
        for (const Track &track : disc.tracks) {
            gdi += std::to_string(track.number) + " " + std::to_string(track.lba) + " " + std::to_string(track.ctrl) + " " + std::to_string(track.sectorSize)
                    + " \"" + track.file + "\" 0\r\n";
        }
        std::string path = dir + "/" + base + ".gdi";
        FILE *file = fopen(path.c_str(), "wb");
        if (file == nullptr || fwrite(gdi.data(), 1, gdi.size(), file) != gdi.size()) {
            why = "GDI 파일을 쓸 수 없어요.";
            failed = true;
        }
        if (file != nullptr) {
            fclose(file);
            written.push_back(path);
        }
    }
    if (failed) {
        for (const std::string &path : written) {
            remove(path.c_str());
        }
        return env->NewStringUTF(why.c_str());
    }
    return nullptr;
}
