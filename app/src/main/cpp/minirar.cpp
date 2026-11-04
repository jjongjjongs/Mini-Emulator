// RAR archives (RAR4 and RAR5) for the app: what is in one, and taking chosen files out of
// it. Built on libarchive's RAR readers (BSD); no RAR is ever written.
#include <jni.h>

#include <clocale>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

#include "archive.h"
#include "archive_entry.h"

namespace {

std::string toString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

archive* openRar(const std::vector<std::string>& paths) {
    // RAR keeps names in UTF-16 (RAR4) or UTF-8 (RAR5); libarchive turns them into the
    // locale's encoding, which must be UTF-8 for Korean and Japanese names to come out.
    static bool localeSet = false;
    if (!localeSet) {
        localeSet = true;
        if (setlocale(LC_CTYPE, "C.UTF-8") == nullptr) setlocale(LC_CTYPE, "en_US.UTF-8");
    }
    archive* a = archive_read_new();
    archive_read_support_format_rar(a);
    archive_read_support_format_rar5(a);
    // The parts of a split archive (part1, part2 … in order) are read as one.
    std::vector<const char*> names;
    for (const auto& p : paths) names.push_back(p.c_str());
    names.push_back(nullptr);
    archive_read_open_filenames(a, names.data(), 1 << 16);  // an error shows on the first read
    return a;
}

std::vector<std::string> toPaths(JNIEnv* env, jobjectArray jpaths) {
    std::vector<std::string> out;
    jsize n = env->GetArrayLength(jpaths);
    for (jsize i = 0; i < n; i++) {
        auto s = (jstring) env->GetObjectArrayElement(jpaths, i);
        out.push_back(toString(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

void setError(JNIEnv* env, jobjectArray err, const std::string& message) {
    if (err != nullptr && env->GetArrayLength(err) > 0) {
        jstring s = env->NewStringUTF(message.c_str());
        env->SetObjectArrayElement(err, 0, s);
        env->DeleteLocalRef(s);
    }
}

std::string entryName(archive_entry* e) {
    const char* utf8 = archive_entry_pathname_utf8(e);
    if (utf8 == nullptr) utf8 = archive_entry_pathname(e);
    return utf8 ? utf8 : "";
}

}  // namespace

// "size\tencrypted\tname" for every file; null with err[0] set when it can't be read.
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_example_minigba_RarReader_list(JNIEnv* env, jclass, jobjectArray jpaths, jobjectArray err) {
    archive* a = openRar(toPaths(env, jpaths));
    std::vector<std::string> lines;
    archive_entry* e = nullptr;
    int r;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK || r == ARCHIVE_WARN) {
        if (archive_entry_filetype(e) == AE_IFREG) {
            long long size = archive_entry_size_is_set(e) ? (long long) archive_entry_size(e) : -1;
            lines.push_back(std::to_string(size) + "\t" + (archive_entry_is_encrypted(e) ? "1" : "0") + "\t" + entryName(e));
        }
        archive_read_data_skip(a);
    }
    if (r != ARCHIVE_EOF) {
        const char* why = archive_error_string(a);
        setError(env, err, why ? why : "RAR를 읽지 못했어요.");
        archive_read_free(a);
        return nullptr;
    }
    archive_read_free(a);
    jobjectArray out = env->NewObjectArray((jsize) lines.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < lines.size(); i++) {
        jstring s = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(out, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

// Takes the named files out to the given paths (in one pass, so solid archives work); null
// when done, else why not. progress.onProgress(done bytes) now and then.
extern "C" JNIEXPORT jstring JNICALL Java_com_example_minigba_RarReader_extract(JNIEnv* env, jclass, jobjectArray jpaths, jobjectArray names,
                                                                            jobjectArray outs, jobject progress) {
    std::vector<std::string> paths = toPaths(env, jpaths);
    std::map<std::string, std::string> wanted;
    jsize count = env->GetArrayLength(names);
    for (jsize i = 0; i < count; i++) {
        auto n = (jstring) env->GetObjectArrayElement(names, i);
        auto o = (jstring) env->GetObjectArrayElement(outs, i);
        wanted[toString(env, n)] = toString(env, o);
        env->DeleteLocalRef(n);
        env->DeleteLocalRef(o);
    }
    jmethodID onProgress = nullptr;
    if (progress != nullptr) {
        jclass cls = env->GetObjectClass(progress);
        onProgress = env->GetMethodID(cls, "onProgress", "(J)V");
        env->DeleteLocalRef(cls);
    }
    archive* a = openRar(paths);
    archive_entry* e = nullptr;
    int r;
    long long done = 0;
    long long reported = 0;
    std::string error;
    size_t left = wanted.size();
    std::vector<char> buffer(1 << 18);
    while (left > 0 && ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK || r == ARCHIVE_WARN)) {
        auto it = wanted.find(entryName(e));
        if (it == wanted.end()) {
            archive_read_data_skip(a);
            continue;
        }
        if (archive_entry_is_encrypted(e)) {
            error = "암호 걸린 RAR라 풀 수 없어요.";
            break;
        }
        FILE* out = fopen(it->second.c_str(), "wb");
        if (out == nullptr) {
            error = "파일을 만들지 못했어요: " + it->second;
            break;
        }
        la_ssize_t n;
        long long written = 0;
        const long long expected = archive_entry_size_is_set(e) ? (long long) archive_entry_size(e) : -1;
        while ((n = archive_read_data(a, buffer.data(), buffer.size())) > 0) {
            written += n;
            if (fwrite(buffer.data(), 1, (size_t) n, out) != (size_t) n) {
                n = -1;
                break;
            }
            done += n;
            if (onProgress != nullptr && done - reported >= (8 << 20)) {
                reported = done;
                env->CallVoidMethod(progress, onProgress, (jlong) done);
            }
        }
        if (fclose(out) != 0 || n < 0) {
            const char* why = n < 0 ? archive_error_string(a) : nullptr;
            error = why ? why : "풀지 못했어요 (저장 공간을 확인해 주세요).";
            remove(it->second.c_str());
            break;
        }
        // A split archive with a part missing can end a file early without an error.
        if (expected >= 0 && written != expected) {
            error = "조각이 손상됐거나 빠졌어요 (" + entryName(e) + "이(가) 덜 풀렸어요).";
            remove(it->second.c_str());
            break;
        }
        wanted.erase(it);
        left--;
    }
    if (error.empty() && left > 0) {
        const char* why = archive_error_string(a);
        error = why ? why : "RAR 안에서 파일을 찾지 못했어요.";
    }
    archive_read_free(a);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
