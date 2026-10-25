#include <jni.h>
#include "archive_files_util.h"
#include <dirent.h>
#include <cstdio>
#include <atomic>
#include <array>
#include <algorithm>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
using namespace ArchiveFiles;
thread_local std::array<jlong, 5> operationCounts{};
int Sync(int descriptor) { ++operationCounts[2]; return ::fsync(descriptor); }
void Failure(JNIEnv *env) {
    auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, "archive file operation refused");
}
bool Bytes(JNIEnv *env, jbyteArray value, std::string &result, size_t limit = 8192) {
    if (!value) return false; auto length = env->GetArrayLength(value);
    if (length < 0 || size_t(length) > limit) return false;
    result.resize(length); if (length) env->GetByteArrayRegion(value,0,length,reinterpret_cast<jbyte*>(&result[0]));
    return !env->ExceptionCheck();
}
std::string Text(JNIEnv *env, jstring value) {
    if (!value) return {};
    auto text = env->GetStringUTFChars(value,nullptr); if (!text) return {};
    auto result = std::string(text); env->ReleaseStringUTFChars(value,text); return result;
}
jobjectArray Strings(JNIEnv *env, const std::vector<std::string> &values) {
    auto cls = env->FindClass("java/lang/String"); if (!cls) return nullptr;
    auto result = env->NewObjectArray(values.size(),cls,nullptr); if (!result) return nullptr;
    for (size_t i=0;i<values.size();++i) {
        auto value = env->NewStringUTF(values[i].c_str()); if (!value) return nullptr;
        env->SetObjectArrayElement(result,i,value); env->DeleteLocalRef(value);
    }
    return result;
}
struct Directory {
    DIR *stream = nullptr;
    std::thread::id owner = std::this_thread::get_id();
    ~Directory() { if (stream) ::closedir(stream); }
};
struct Lock {
    int fd = -1;
    std::thread::id owner = std::this_thread::get_id();
    ~Lock() { if (fd >= 0) ::close(fd); }
};
std::mutex registryMutex;
std::unordered_map<jlong,std::shared_ptr<Directory>> directories;
std::unordered_map<jlong,std::shared_ptr<Lock>> locks;
std::atomic<int64_t> sequence{1};
jlong Next() { auto value = sequence.fetch_add(1); return value > 0 ? value : 0; }
}

static jobjectArray Mark(JNIEnv *env,jbyteArray input,int kind) {
    ++operationCounts[3];
    std::string path; if (!Bytes(env,input,path) || path.find('\0') != std::string::npos) { Failure(env); return nullptr; }
    auto slash = path.find_last_of('/'); if (slash == std::string::npos) { Failure(env); return nullptr; }
    auto name = path.substr(slash+1); Fd parent(OpenDirectory(path.substr(0,slash)));
    if (parent.value < 0 || !Leaf(name)) { Failure(env); return nullptr; }
    struct stat value{}; auto result = ::fstatat(parent.value,name.c_str(),&value,AT_SYMLINK_NOFOLLOW);
    if (result != 0 && errno == ENOENT) { if (!parent.close()) Failure(env); return nullptr; }
    if (result != 0 || (kind == 1 ? (!S_ISDIR(value.st_mode) || value.st_uid != getuid()) : kind == 0 ? !Regular(value) :
        !((S_ISDIR(value.st_mode) && value.st_uid == getuid()) || Regular(value))) || !parent.close()) {
        Failure(env); return nullptr;
    }
    return Strings(env,{Identity(value),Stamp(value),std::to_string(value.st_size)});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_markNative(JNIEnv *env,jclass,jbyteArray input,jboolean directory) {
    return Mark(env,input,directory ? 1 : 0);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_markAnyNative(JNIEnv *env,jclass,jbyteArray input) {
    return Mark(env,input,2);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_mkdirNative(JNIEnv *env,jclass,jbyteArray input,jstring identity,jstring child,jboolean exclusive) {
    std::string path; auto name = Text(env,child);
    if (!Bytes(env,input,path) || !Leaf(name)) { Failure(env); return nullptr; }
    Fd parent(OpenDirectory(path,Text(env,identity))); struct stat value{};
    if (parent.value < 0 || (::mkdirat(parent.value,name.c_str(),0700) != 0 && (exclusive || errno != EEXIST))
        || ::fstatat(parent.value,name.c_str(),&value,AT_SYMLINK_NOFOLLOW) != 0 || !S_ISDIR(value.st_mode) || value.st_uid != getuid()) {
        Failure(env); return nullptr;
    }
    Fd opened(::openat(parent.value,name.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
    if (opened.value < 0 || ::fchmod(opened.value,0700) != 0 || Sync(opened.value) != 0 || Sync(parent.value) != 0
        || !opened.close() || !parent.close()) { Failure(env); return nullptr; }
    return Strings(env,{Identity(value),Stamp(value),std::to_string(value.st_size)});
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_readNative(JNIEnv *env,jclass,jbyteArray input,jstring identity,jstring child,jstring expected,jint limit) {
    ++operationCounts[4];
    std::string path; auto name = Text(env,child);
    if (!Bytes(env,input,path) || !Leaf(name) || limit < 0 || limit > 4*1024*1024) { Failure(env); return nullptr; }
    Fd parent(OpenDirectory(path,Text(env,identity))); if (parent.value < 0) { Failure(env); return nullptr; }
    Fd file(::openat(parent.value,name.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC));
    if (file.value < 0 && errno == ENOENT) { if (!parent.close()) Failure(env); return nullptr; }
    struct stat before{},after{};
    if (file.value < 0 || ::fstat(file.value,&before) != 0 || !Regular(before) || before.st_size > limit || Stamp(before)!=Text(env,expected)) { Failure(env); return nullptr; }
    std::vector<char> bytes(before.st_size); size_t offset=0;
    while (offset<bytes.size()) {
        auto count=::read(file.value,bytes.data()+offset,bytes.size()-offset);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) { Failure(env); return nullptr; } offset+=count;
    }
    char extra; auto count=::read(file.value,&extra,1);
    if (count!=0 || ::fstat(file.value,&after)!=0 || Stamp(before)!=Stamp(after) || !file.close() || !parent.close()) { Failure(env); return nullptr; }
    auto result=env->NewByteArray(bytes.size());
    if (result && !bytes.empty()) env->SetByteArrayRegion(result,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_publishNative(JNIEnv *env,jclass,jbyteArray input,jstring identity,
        jstring child,jstring expected,jstring temporary,jbyteArray data) {
    std::string path,bytes; auto name=Text(env,child),temp=Text(env,temporary),stamp=Text(env,expected);
    ++operationCounts[0]; if (name.compare(0,2,"n-")==0) ++operationCounts[1];
    if (!Bytes(env,input,path) || !Bytes(env,data,bytes,4*1024*1024) || !Leaf(name) || !Leaf(temp)
        || temp.compare(0,5,".new-")!=0 || name==temp) return false;
    Fd parent(OpenDirectory(path,Text(env,identity))); if (parent.value<0) return false;
    Fd file(::openat(parent.value,temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));
    if (file.value<0) return false;
    struct stat written{};
    if (::fstat(file.value,&written)!=0 || !Regular(written)) return false;
    auto cleanup=[&] { struct stat value{}; if (::fstatat(parent.value,temp.c_str(),&value,AT_SYMLINK_NOFOLLOW)==0
        && Identity(value)==Identity(written)) ::unlinkat(parent.value,temp.c_str(),0); };
    size_t offset=0;
    while (offset<bytes.size()) {
        auto count=::write(file.value,bytes.data()+offset,bytes.size()-offset);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) { cleanup(); return false; } offset+=count;
    }
    if (Sync(file.value)!=0 || !file.close()) { cleanup(); return false; }
    struct stat target{}; auto exists=::fstatat(parent.value,name.c_str(),&target,AT_SYMLINK_NOFOLLOW)==0;
    if ((exists && (!Regular(target) || Stamp(target)!=stamp)) || (!exists && (errno!=ENOENT || !stamp.empty()))) { cleanup(); return false; }
    if (stamp.empty() ? !RenameNew(parent.value,temp,name)
        : ::renameat(parent.value,temp.c_str(),parent.value,name.c_str())!=0) { cleanup(); return false; }
    return Sync(parent.value)==0 && parent.close();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_removeNative(JNIEnv *env,jclass,jbyteArray input,jstring identity,
        jstring child,jstring expected,jboolean directory) {
    std::string path; auto name=Text(env,child),stamp=Text(env,expected);
    if (!Bytes(env,input,path) || !Leaf(name)) return false;
    Fd parent(OpenDirectory(path,Text(env,identity))); if (parent.value<0) return false;
    struct stat value{};
    if (::fstatat(parent.value,name.c_str(),&value,AT_SYMLINK_NOFOLLOW)!=0)
        return errno==ENOENT && Sync(parent.value)==0 && parent.close();
    if (Stamp(value)!=stamp || (directory ? (!S_ISDIR(value.st_mode)||value.st_uid!=getuid()) : !Regular(value))) return false;
    return ::unlinkat(parent.value,name.c_str(),directory?AT_REMOVEDIR:0)==0 && Sync(parent.value)==0 && parent.close();
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_listOpenNative(JNIEnv *env,jclass,jbyteArray input,jstring identity) {
    std::string path; if (!Bytes(env,input,path)) { Failure(env); return 0; }
    Fd fd(OpenDirectory(path,Text(env,identity))); if (fd.value<0) { Failure(env); return 0; }
    auto stream=::fdopendir(fd.value); if (!stream) { Failure(env); return 0; } fd.value=-1;
    auto value=std::make_shared<Directory>(); value->stream=stream; auto id=Next();
    if (!id) { Failure(env); return 0; }
    std::lock_guard<std::mutex> lock(registryMutex); directories.emplace(id,value); return id;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_listNextNative(JNIEnv *env,jclass,jlong id) {
    std::shared_ptr<Directory> directory;
    { std::lock_guard<std::mutex> lock(registryMutex); auto found=directories.find(id);
      if (found!=directories.end()) directory=found->second; }
    if (!directory || directory->owner!=std::this_thread::get_id()) { Failure(env); return nullptr; }
    std::vector<std::string> names;
    while (names.size()<128) {
        errno=0; auto entry=::readdir(directory->stream);
        if (!entry) { if (errno) { Failure(env); return nullptr; } break; }
        auto name=std::string(entry->d_name); if (name=="." || name=="..") continue;
        if (!Leaf(name)) { Failure(env); return nullptr; } names.push_back(name);
    }
    return Strings(env,names);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_listCloseNative(JNIEnv *,jclass,jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex); auto found=directories.find(id);
    if (found==directories.end() || found->second->owner!=std::this_thread::get_id()) return false;
    auto stream=found->second->stream; found->second->stream=nullptr; directories.erase(found); return ::closedir(stream)==0;
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_lockNative(JNIEnv *env,jclass,jbyteArray input,jstring identity) {
    std::string path; if (!Bytes(env,input,path)) return 0;
    Fd parent(OpenDirectory(path,Text(env,identity))); if (parent.value<0) return 0;
    Fd file(::openat(parent.value,"lock",O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600)); struct stat value{}; struct flock lock{};
    lock.l_type=F_WRLCK; lock.l_whence=SEEK_SET;
    if (file.value<0 || ::fstat(file.value,&value)!=0 || !Regular(value) || ::fcntl(file.value,F_SETLK,&lock)!=0 || !parent.close()) return 0;
    auto id=Next(); if (!id) return 0;
    auto owned=std::make_shared<Lock>(); owned->fd=file.value; file.value=-1;
    std::lock_guard<std::mutex> guard(registryMutex); locks.emplace(id,owned); return id;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_unlockNative(JNIEnv *,jclass,jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex); auto found=locks.find(id);
    if (found==locks.end() || found->second->owner!=std::this_thread::get_id()) return false;
    auto fd=found->second->fd; found->second->fd=-1; locks.erase(found); return ::close(fd)==0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFiles_operationsNative(JNIEnv *env,jclass,jboolean reset) {
    auto result = env->NewLongArray(operationCounts.size());
    if (!result) return nullptr;
    env->SetLongArrayRegion(result,0,operationCounts.size(),operationCounts.data());
    if (reset && !env->ExceptionCheck()) operationCounts.fill(0);
    return result;
}
