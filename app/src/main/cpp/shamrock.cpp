#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <filesystem>
#include <random>
#include <android/log.h>
#include <sys/time.h>

#include "md5.h"

// JNI symbol names follow the new package path moe.RinShiona.Shamrock.*
// The renamer script only handled .kt/.java text replacements; JNI native
// symbols (which double-encode underscores and dots) had to be updated by
// hand. If you rename the package again, update these symbols too.
extern "C"
JNIEXPORT jstring JNICALL
Java_moe_RinShiona_Shamrock_xposed_actions_PullConfig_testNativeLibrary(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF("Shamrock library OK (moe.RinShiona.Shamrock)");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_RinShiona_Shamrock_utils_MD5_genFileMd5Hex(JNIEnv *env, jobject thiz, jstring file_path) {
    auto cPathStr = env->GetStringUTFChars(file_path, nullptr);
    std::filesystem::path filePath(cPathStr);
    if (!std::filesystem::exists(filePath)) {
        jclass exClass = env->FindClass("java/io/FileNotFoundException");
        env->ThrowNew(exClass, "目标文件不存在");
        env->DeleteLocalRef(exClass);
        return nullptr;
    }
    auto file = std::ifstream(filePath.c_str(), std::ios::binary);
    MD5 md5;
    md5.update(file);
    auto md5Hex = md5.toString();

    env->ReleaseStringUTFChars(file_path, cPathStr);

    return env->NewStringUTF(md5Hex.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_RinShiona_Shamrock_utils_MD5_getMd5Hex(JNIEnv *env, jobject thiz, jbyteArray bytes) {
    auto len = env->GetArrayLength(bytes);
    auto *cBytes = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(cBytes));

    MD5 md5;
    md5.update(cBytes, len);
    auto md5Hex = md5.toString();

    delete[] cBytes;

    return env->NewStringUTF(md5Hex.c_str());
}