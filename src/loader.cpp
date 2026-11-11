#include <jni.h>
#include <android/log.h>
#include <stddef.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO,"LogLens",__VA_ARGS__)
extern const unsigned char dex_data[]; extern const unsigned int dex_len;
static JavaVM* g_vm=nullptr; static jclass g_ui=nullptr;
static JNIEnv* env() { JNIEnv* e=nullptr; if(!g_vm)return nullptr; if(g_vm->GetEnv((void**)&e,JNI_VERSION_1_6)!=JNI_OK)return nullptr; return e; }
static bool clear(JNIEnv* e,const char* where){if(e->ExceptionCheck()){LOG("Java exception in %s",where);e->ExceptionDescribe();e->ExceptionClear();return true;}return false;}
extern "C" __attribute__((visibility("default"))) bool PLMod_Load(JavaVM* vm,const void*) {
 g_vm=vm; JNIEnv* e=env(); if(!e)return false;
 jclass manager=e->FindClass("org/levimc/launcher/core/mods/inbuilt/overlay/InbuiltOverlayManager"); if(clear(e,"manager")||!manager) return false;
 jclass cls=e->FindClass("java/lang/Class"); jmethodID getLoader=e->GetMethodID(cls,"getClassLoader","()Ljava/lang/ClassLoader;"); jobject parent=e->CallObjectMethod(manager,getLoader);
 jclass loader=e->FindClass("dalvik/system/InMemoryDexClassLoader"); if(clear(e,"loader")||!loader)return false;
 jmethodID ctor=e->GetMethodID(loader,"<init>","(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V"); jobject buf=e->NewDirectByteBuffer((void*)dex_data,dex_len); jobject instance=e->NewObject(loader,ctor,buf,parent);
 jmethodID load=e->GetMethodID(loader,"loadClass","(Ljava/lang/String;)Ljava/lang/Class;"); jstring name=e->NewStringUTF("loglens.LogLens"); jobject ui=e->CallObjectMethod(instance,load,name); if(clear(e,"load UI")||!ui)return false;
 g_ui=(jclass)e->NewGlobalRef(ui); LOG("Loaded UI bridge"); return g_ui!=nullptr;
}
static bool call(const char* n){JNIEnv* e=env();if(!e||!g_ui)return false;jmethodID m=e->GetStaticMethodID(g_ui,n,"()V");if(clear(e,n)||!m)return false;e->CallStaticVoidMethod(g_ui,m);return !clear(e,n);}
extern "C" __attribute__((visibility("default"))) bool PLMod_Enable(){return call("start");}
extern "C" __attribute__((visibility("default"))) bool PLMod_Disable(){return call("stop");}
extern "C" __attribute__((visibility("default"))) bool PLMod_Unload(){return call("stop");}
