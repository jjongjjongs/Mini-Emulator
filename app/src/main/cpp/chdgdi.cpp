// CHD -> GDI for Dreamcast discs ("GDI로 풀기"), CHD -> CUE + BIN for CD games (PS1, Saturn,
// Mega CD, PC Engine CD: "CUE로 풀기") and CHD -> ISO for DVDs (PS2: "ISO로 풀기"), so a CHD
// game can be patched track by track like an unpacked one.
// CD tracks are written the way chdman extracts them: a track's frames as stored (pregap
// frames included when PGTYPE starts with 'V', then INDEX 00 / INDEX 01 in the sheet; a pregap
// not stored becomes PREGAP), audio byte-swapped back to little-endian, the padding to four
// frames left out. The tracks are read with libchdr (the library Flycast reads
// CHD with) and laid out the way Flycast's own CHD reader places them: a track starts after its
// pregap, pregap frames stored in the file (PGTYPE "V…") are skipped, the padding frames at the
// end are not part of the track, and audio is stored big-endian in GD-ROM CHDs (tag CHGD).
#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <algorithm>
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
    std::string type;       // CUE type: AUDIO, MODE1/2352, …
    uint32_t pregap = 0;    // CD: frames before INDEX 01
    bool pregapInFile = false;
    uint32_t postgap = 0;
    uint32_t lba = 0;
    int ctrl = 0;           // 4 data, 0 audio
    uint32_t sectorSize = 2352;
    uint64_t firstFrame = 0;  // in the CHD
    uint32_t count = 0;
    bool swap = false;
    std::string file;
};

enum Kind { GDROM = 0, CDROM = 1, DVD = 2 };

struct Disc {
    chd_file *chd = nullptr;
    int kind = GDROM;
    uint64_t dvdBytes = 0;
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

// A CD track type from CHD metadata: its sector size and the CUE name (0 when not taken).
uint32_t cdType(const char *type, std::string &cue) {
    struct Kind { const char *chd; uint32_t size; const char *cue; };
    static const Kind kinds[] = {
            {"AUDIO", 2352, "AUDIO"}, {"MODE1", 2048, "MODE1/2048"}, {"MODE1/2048", 2048, "MODE1/2048"}, {"MODE1_RAW", 2352, "MODE1/2352"},
            {"MODE1/2352", 2352, "MODE1/2352"}, {"MODE2", 2336, "MODE2/2336"}, {"MODE2/2336", 2336, "MODE2/2336"},
            {"MODE2_FORM_MIX", 2336, "MODE2/2336"}, {"MODE2_RAW", 2352, "MODE2/2352"}, {"MODE2/2352", 2352, "MODE2/2352"}, {"CDI/2352", 2352, "CDI/2352"},
    };
    for (const Kind &kind : kinds) {
        if (strcmp(type, kind.chd) == 0) {
            cue = kind.cue;
            return kind.size;
        }
    }
    return 0;
}

std::string openCd(Disc &disc) {
    uint64_t offset = 0;
    for (;;) {
        char meta[512];
        uint32_t length = 0;
        uint32_t tag = 0;
        uint8_t flags = 0;
        char type[32] = "", subtype[32] = "NONE", pgtype[32] = "NONE", pgsub[32] = "NONE";
        int number = -1, frames = 0, pregap = 0, postgap = 0;
        uint32_t index = (uint32_t) disc.tracks.size();
        memset(meta, 0, sizeof(meta));
        if (chd_get_metadata(disc.chd, CDROM_TRACK_METADATA2_TAG, index, meta, sizeof(meta) - 1, &length, &tag, &flags) == CHDERR_NONE) {
            if (sscanf(meta, CDROM_TRACK_METADATA2_FORMAT, &number, type, subtype, &frames, &pregap, pgtype, pgsub, &postgap) < 4) {
                return "CHD의 트랙 정보를 읽지 못했어요.";
            }
        } else if (chd_get_metadata(disc.chd, CDROM_TRACK_METADATA_TAG, index, meta, sizeof(meta) - 1, &length, &tag, &flags) == CHDERR_NONE) {
            if (sscanf(meta, CDROM_TRACK_METADATA_FORMAT, &number, type, subtype, &frames) < 4) {
                return "CHD의 트랙 정보를 읽지 못했어요.";
            }
        } else {
            break;
        }
        if (number != (int) index + 1 || frames <= 0 || pregap < 0 || postgap < 0) {
            return "CHD의 트랙 정보가 이상해요.";
        }
        Track track;
        track.number = number;
        track.sectorSize = cdType(type, track.type);
        if (track.sectorSize == 0) {
            return std::string("지원하지 않는 트랙 형식이에요 (") + type + ").";
        }
        track.ctrl = strcmp(type, "AUDIO") == 0 ? 0 : 4;
        track.pregap = (uint32_t) pregap;
        track.pregapInFile = pregap > 0 && pgtype[0] == 'V';
        if (track.pregapInFile && pregap > frames) {
            return "CHD의 트랙 정보가 이상해요.";
        }
        track.postgap = (uint32_t) postgap;
        track.firstFrame = offset;
        track.count = (uint32_t) frames;
        track.swap = track.ctrl == 0;  // CD audio is stored big-endian
        disc.tracks.push_back(track);
        offset += (uint64_t) ((frames + kPadding - 1) / kPadding) * kPadding;
    }
    if (disc.tracks.empty()) {
        return "CHD의 트랙 정보가 없어요.";
    }
    disc.kind = CDROM;
    return "";
}

// Reads the track list: a GD-ROM (Dreamcast), a CD, or a DVD.
std::string open(const char *path, Disc &disc) {
    chd_error err = chd_open(path, CHD_OPEN_READ, nullptr, &disc.chd);
    if (err != CHDERR_NONE) {
        disc.chd = nullptr;
        return std::string("CHD 파일을 열 수 없어요 (") + chd_error_string(err) + ").";
    }
    const chd_header *header = chd_get_header(disc.chd);
    disc.hunkBytes = header->hunkbytes;
    {
        char meta[256];
        uint32_t length = 0;
        uint32_t tag = 0;
        uint8_t flags = 0;
        if (chd_get_metadata(disc.chd, DVD_METADATA_TAG, 0, meta, sizeof(meta), &length, &tag, &flags) == CHDERR_NONE) {
            if (disc.hunkBytes == 0 || disc.hunkBytes % 2048 != 0) {
                return "DVD CHD의 형식이 이상해요.";
            }
            disc.kind = DVD;
            disc.dvdBytes = header->logicalbytes;
            return "";
        }
    }
    if (disc.hunkBytes == 0 || disc.hunkBytes % kFrame != 0) {
        return "디스크 이미지 CHD가 아니에요 (하드디스크 · 레이저디스크 CHD는 풀 수 없어요).";
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
            if (index == 0) {
                return openCd(disc);
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
    if (disc.kind == DVD) {
        return disc.dvdBytes;
    }
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

std::string msf(uint32_t frames) {
    char text[16];
    snprintf(text, sizeof(text), "%02u:%02u:%02u", frames / 4500, frames / 75 % 60, frames % 75);
    return text;
}

// A DVD: the hunks' bytes up to the logical size, into one .iso.
std::string writeDvd(JNIEnv *env, Disc &disc, const std::string &path, jobject progress, jmethodID onProgress) {
    FILE *file = fopen(path.c_str(), "wb");
    if (file == nullptr) {
        return std::string("파일을 만들 수 없어요 (") + strerror(errno) + ").";
    }
    std::vector<uint8_t> hunk(disc.hunkBytes);
    uint64_t done = 0;
    std::string why;
    for (uint32_t index = 0; done < disc.dvdBytes; index++) {
        chd_error err = chd_read(disc.chd, index, hunk.data());
        if (err != CHDERR_NONE) {
            why = std::string("CHD를 읽는 중에 문제가 생겼어요 (") + chd_error_string(err) + ").";
            break;
        }
        size_t n = (size_t) std::min<uint64_t>(disc.hunkBytes, disc.dvdBytes - done);
        if (fwrite(hunk.data(), 1, n, file) != n) {
            why = "저장 공간이 부족하거나 파일을 쓸 수 없어요.";
            break;
        }
        done += n;
        if ((index & 63) == 0 && onProgress != nullptr && !env->CallBooleanMethod(progress, onProgress, (jlong) done, (jlong) disc.dvdBytes)) {
            why = "취소했어요.";
            break;
        }
    }
    if (fclose(file) != 0 && why.empty()) {
        why = "저장 공간이 부족하거나 파일을 쓸 수 없어요.";
    }
    if (!why.empty()) {
        remove(path.c_str());
    }
    return why;
}

// {bytes the unpacked game takes, tracks, kind (0 GD-ROM, 1 CD, 2 DVD)}, or null with the
// reason through error[0].
extern "C" JNIEXPORT jlongArray JNICALL Java_com_example_minigba_ChdGdi_info(JNIEnv *env, jclass, jstring chdPath, jobjectArray error) {
    Disc disc;
    std::string why = open(jstr(env, chdPath).c_str(), disc);
    if (!why.empty()) {
        env->SetObjectArrayElement(error, 0, env->NewStringUTF(why.c_str()));
        return nullptr;
    }
    jlong values[3] = {(jlong) outputBytes(disc), (jlong) (disc.kind == DVD ? 1 : disc.tracks.size()), (jlong) disc.kind};
    jlongArray result = env->NewLongArray(3);
    env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

// Writes into dir: a GD-ROM as "<base> (Track NN).bin/.raw" + "<base>.gdi", a CD as
// "<base> (Track NN).bin" (or "<base>.bin" for one track) + "<base>.cue", a DVD as
// "<base>.iso". Null when done, else the reason (files written so far are removed).
// progress.onProgress(done, total) false cancels.
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
    if (disc.kind == DVD) {
        why = writeDvd(env, disc, dir + "/" + base + ".iso", progress, onProgress);
        return why.empty() ? nullptr : env->NewStringUTF(why.c_str());
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
        if (disc.kind == CDROM) {
            snprintf(name, sizeof(name), " (Track %02d).bin", track.number);
            track.file = disc.tracks.size() == 1 ? base + ".bin" : base + name;
        } else {
            snprintf(name, sizeof(name), " (Track %02d).%s", track.number, track.ctrl == 0 ? "raw" : track.sectorSize == 2048 ? "iso" : "bin");
            track.file = base + name;
        }
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
    if (!failed && disc.kind == CDROM) {
        std::string cue;
        for (const Track &track : disc.tracks) {
            char number[8];
            snprintf(number, sizeof(number), "%02d", track.number);
            cue += "FILE \"" + track.file + "\" BINARY\r\n";
            cue += std::string("  TRACK ") + number + " " + track.type + "\r\n";
            if (track.pregapInFile) {
                cue += "    INDEX 00 00:00:00\r\n";
                cue += "    INDEX 01 " + msf(track.pregap) + "\r\n";
            } else {
                if (track.pregap > 0) {
                    cue += "    PREGAP " + msf(track.pregap) + "\r\n";
                }
                cue += "    INDEX 01 00:00:00\r\n";
            }
            if (track.postgap > 0) {
                cue += "    POSTGAP " + msf(track.postgap) + "\r\n";
            }
        }
        std::string path = dir + "/" + base + ".cue";
        FILE *file = fopen(path.c_str(), "wb");
        if (file == nullptr || fwrite(cue.data(), 1, cue.size(), file) != cue.size()) {
            why = "CUE 파일을 쓸 수 없어요.";
            failed = true;
        }
        if (file != nullptr) {
            fclose(file);
            written.push_back(path);
        }
    } else if (!failed) {
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
