#include <jni.h>
#include <cstdlib>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>

#define LOG_TAG "JNI_MEMORY"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
//我不会c++，代码由ChatGPT生成
extern "C"
JNIEXPORT jobject JNICALL
Java_top_weixiansen574_hybridfilexfer_NativeMemory_allocateLargeBuffer(JNIEnv *env, jclass clazz,
                                                                       jint size) {
    void *buffer = malloc(size);
    if (!buffer) {
        LOGE("malloc failed! Requested size: %d", size);
        return nullptr;
    }
    return env->NewDirectByteBuffer(buffer, size);
}

extern "C"
JNIEXPORT void JNICALL
Java_top_weixiansen574_hybridfilexfer_NativeMemory_freeBuffer(JNIEnv *env, jclass clazz,
                                                              jobject byteBuffer) {
    void *buffer = env->GetDirectBufferAddress(byteBuffer);
    if (buffer) {
        free(buffer);
        //LOGE("Buffer freed successfully");
    }
}

static std::string pathBytes(JNIEnv *env, jbyteArray value) {
    jsize length = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(length), '\0');
    if (length) {
        env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(&result[0]));
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_top_weixiansen574_hybridfilexfer_NativeMemory_moveNoReplaceBytes(
        JNIEnv *env, jclass, jbyteArray sourceBytes, jbyteArray targetBytes) {
    std::string source = pathBytes(env, sourceBytes);
    if (env->ExceptionCheck()) return;
    std::string target = pathBytes(env, targetBytes);
    if (env->ExceptionCheck()) return;
    int error;
#ifdef __NR_renameat2
    // RENAME_NOREPLACE = 1：由内核原子保证不覆盖已存在的目标文件，避免先检查后重命名存在竞态
    if (syscall(__NR_renameat2, AT_FDCWD, source.c_str(), AT_FDCWD, target.c_str(), 1U) == 0)
        return;
    error = errno;
    if (error != ENOSYS && error != EINVAL && error != EOPNOTSUPP) {
        std::string message = "Cannot publish file without replacing destination: ";
        message += std::strerror(error);
        env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
        return;
    }
#endif
    // 若旧内核不支持 renameat2，则退回使用同文件系统的 link 硬链接；若仍不支持则报错并保留 .part 暂存文件
    if (link(source.c_str(), target.c_str()) == 0) {
        if (unlink(source.c_str()) == 0) return;
        // 目标文件已通过硬链接成功发布，残留的源 .part 文件由上层统一处理
        return;
    }
    error = errno;
    std::string message = "Filesystem cannot safely publish file without replacement: ";
    message += std::strerror(error);
    env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}
