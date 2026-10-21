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
    // RENAME_NOREPLACE = 1; unlike a check followed by rename, this cannot clobber
    // a file another process creates after the destination check.
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
    // Old kernels may support hard links instead. Both operations are same-filesystem.
    // If neither primitive exists, retain the resumable part instead of unsafe rename.
    if (link(source.c_str(), target.c_str()) == 0) {
        if (unlink(source.c_str()) == 0) return;
        // The complete target is already safely published. The leftover part is harmless.
        return;
    }
    error = errno;
    std::string message = "Filesystem cannot safely publish file without replacement: ";
    message += std::strerror(error);
    env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}
