#include <jni.h>
#include <cstdlib>
#include <android/log.h>

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
