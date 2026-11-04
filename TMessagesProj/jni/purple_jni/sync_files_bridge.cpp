#include "retained_config_bases.h"
#include "purple/purple_sync_json.h"
#include "purple/purple_types.h"
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonArray>
#include <jni.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <limits>
#include <stdexcept>

namespace {
using namespace Purple;
using namespace PurpleAndroid;

struct Failure { QString status; };
void Need(bool value, const QString &status = u"InvalidState"_q) { if (!value) throw Failure{status}; }
struct Fd {
    int value = -1;
    explicit Fd(int fd = -1) : value(fd) { }
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd &) = delete;
    Fd &operator=(const Fd &) = delete;
};
QString Text(JNIEnv *env, jstring value) {
    Need(value != nullptr);
    const auto size = env->GetStringLength(value);
    const auto chars = env->GetStringChars(value, nullptr);
    Need(chars != nullptr);
    const auto text = QString::fromUtf16(reinterpret_cast<const char16_t *>(chars), size);
    env->ReleaseStringChars(value, chars);
    Need(!text.contains(QChar(0)));
    return text;
}
QByteArray Bytes(JNIEnv *env, jbyteArray value, int limit = 4 * 1024 * 1024) {
    if (!value) return {};
    const auto size = env->GetArrayLength(value);
    Need(size <= limit, u"RetentionFull"_q);
    auto bytes = QByteArray(size, Qt::Uninitialized);
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    Need(!env->ExceptionCheck());
    return bytes;
}
jbyteArray Reply(JNIEnv *env, QJsonObject object) {
    const auto bytes = QJsonDocument(object).toJson(QJsonDocument::Compact);
    const auto value = env->NewByteArray(bytes.size());
    if (value) env->SetByteArrayRegion(value, 0, bytes.size(), reinterpret_cast<const jbyte *>(bytes.constData()));
    return value;
}
QString Blob(const QByteArray &bytes) { return QString::fromLatin1(bytes.toBase64()); }
QByteArray Unblob(const QJsonValue &value) {
    Need(value.isString());
    const auto text = value.toString().toLatin1();
    const auto decoded = QByteArray::fromBase64Encoding(text, QByteArray::AbortOnBase64DecodingErrors);
    Need(bool(decoded) && decoded.decoded.toBase64() == text);
    return decoded.decoded;
}
QJsonObject Document(const QByteArray &bytes) {
    const auto canonical = CanonicalizeSyncJson(bytes);
    Need(bool(canonical));
    const auto doc = QJsonDocument::fromJson(canonical.canonical);
    Need(doc.isObject());
    return doc.object();
}
QJsonObject Transport(const QByteArray &bytes) {
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(bytes, &error);
    Need(error.error == QJsonParseError::NoError && document.isObject());
    return document.object();
}
SyncLocalState State(const QByteArray &bytes) {
    const auto value = ParseSyncLocalState(bytes);
    Need(bool(value), value.status == SyncLocalStatus::NewerVersion ? u"NewerState"_q : u"InvalidState"_q);
    const auto encoded = SerializeSyncLocalState(value.state);
    Need(encoded && encoded.canonical == bytes);
    return value.state;
}
QString Identity(const struct stat &s) {
    return QString::number(qulonglong(s.st_dev)) + u':' + QString::number(qulonglong(s.st_ino));
}
QString Revision(const struct stat &s) {
#ifdef __APPLE__
    const auto modified = s.st_mtimespec, changed = s.st_ctimespec;
#else
    const auto modified = s.st_mtim, changed = s.st_ctim;
#endif
    return Identity(s) + u':' + QString::number(s.st_mode) + u':' + QString::number(s.st_uid)
        + u':' + QString::number(s.st_nlink) + u':' + QString::number(s.st_size)
        + u':' + QString::number(qlonglong(modified.tv_sec)) + u':' + QString::number(modified.tv_nsec)
        + u':' + QString::number(qlonglong(changed.tv_sec)) + u':' + QString::number(changed.tv_nsec);
}
bool Private(const struct stat &s, bool directory) {
    return (directory ? S_ISDIR(s.st_mode) : S_ISREG(s.st_mode) && s.st_nlink == 1)
        && s.st_uid == geteuid() && (s.st_mode & 07777) == (directory ? 0700 : 0600);
}
// WHY: the walk starts at the app's files directory, never at "/": Android refuses an app any read of the root
// directory (SELinux, rootfs), so opening "/" failed every durable operation with IoError (native check A3). The
// anchor comes from Java (PurpleAccountFileSnapshot.anchor) and every path must lie strictly below it.
int Parent(const QString &anchor, const QString &path, QByteArray &name) {
    Need(anchor.size() > 1 && anchor.startsWith(u'/') && !anchor.endsWith(u'/') && !path.endsWith(u'/')
        && path.startsWith(anchor + u'/'), u"PathOutsideRoot"_q);
    const auto parts = path.mid(anchor.size() + 1).toUtf8().split('/');
    for (const auto &part : path.toUtf8().split('/').mid(1)) Need(!part.isEmpty() && part != "." && part != "..", u"PathOutsideRoot"_q);
    auto fd = open(anchor.toUtf8().constData(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    Need(fd >= 0, u"IoError"_q);
    for (int i = 0; i < parts.size(); ++i) {
        const auto &part = parts[i];
        if (part.isEmpty() || part == "." || part == "..") { close(fd); throw Failure{u"AmbiguousFiles"_q}; }
        if (i + 1 == parts.size()) { name = part; return fd; }
        const auto next = openat(fd, part.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(fd);
        if (next < 0) throw Failure{u"AmbiguousFiles"_q};
        fd = next;
    }
    close(fd);
    throw Failure{u"AmbiguousFiles"_q};
}
QJsonObject Snapshot(int parent, const QByteArray &name, int limit) {
    struct stat directory{};
    Need(fstat(parent, &directory) == 0 && Private(directory, true), u"InsecurePermissions"_q);
    auto object = QJsonObject{{u"status"_q, u"Ready"_q}, {u"parent"_q, Identity(directory)}};
    struct stat before{};
    if (fstatat(parent, name.constData(), &before, AT_SYMLINK_NOFOLLOW) != 0) {
        Need(errno == ENOENT, u"IoError"_q);
        object.insert(u"identity"_q, u"missing"_q);
        object.insert(u"revision"_q, u"missing"_q);
        object.insert(u"bytes"_q, QString());
        return object;
    }
    Need(Private(before, false), u"InsecurePermissions"_q);
    Need(before.st_size >= 0 && before.st_size <= limit, u"RetentionFull"_q);
    const auto fd = Fd(openat(parent, name.constData(), O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
    struct stat opened{};
    Need(fd.value >= 0 && fstat(fd.value, &opened) == 0 && Revision(before) == Revision(opened), u"Changed"_q);
    auto bytes = QByteArray();
    char buffer[8192];
    for (;;) {
        const auto size = read(fd.value, buffer, sizeof(buffer));
        if (size < 0 && errno == EINTR) continue;
        Need(size >= 0, u"IoError"_q);
        if (!size) break;
        Need(bytes.size() <= limit - size, u"RetentionFull"_q);
        bytes.append(buffer, size);
    }
    struct stat after{}, named{};
    Need(fstat(fd.value, &after) == 0 && fstatat(parent, name.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
        && Revision(before) == Revision(after) && Revision(after) == Revision(named), u"Changed"_q);
    object.insert(u"identity"_q, Identity(after));
    object.insert(u"revision"_q, Revision(after));
    object.insert(u"bytes"_q, Blob(bytes));
    return object;
}
void ParentCurrent(const QString &anchor, const QString &path, int fd) {
    auto name = QByteArray();
    const auto reopened = Fd(Parent(anchor, path, name));
    struct stat current{}, old{};
    Need(fstat(reopened.value, &current) == 0 && fstat(fd, &old) == 0 && Identity(current) == Identity(old)
        && Private(current, true), u"Changed"_q);
}
void Barrier(int fd, int fault, int point) {
    Need(fault != point && fsync(fd) == 0, u"DurabilityFailed"_q);
}
QJsonObject FileOperation(const QString &op, const QString &anchor, const QString &path, const QByteArray &expectedBytes,
        const QByteArray &bytes, int limit, int fault) {
    Need(limit > 0 && limit <= 4194304 && bytes.size() <= limit);
    auto name = QByteArray();
    const auto parent = Fd(Parent(anchor, path, name));
    if (op == u"directory"_q || op == u"directoryRead"_q) {
        ParentCurrent(anchor, path, parent.value);
        struct stat st{};
        if (fstatat(parent.value, name.constData(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
            Need(errno == ENOENT, u"IoError"_q);
            if (op == u"directoryRead"_q) return {{u"status"_q, u"Ready"_q}, {u"identity"_q, u"missing"_q}};
            Need(mkdirat(parent.value, name.constData(), 0700) == 0, u"IoError"_q);
        }
        const auto fd = Fd(openat(parent.value, name.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        Need(fd.value >= 0 && fstat(fd.value, &st) == 0 && Private(st, true), u"InsecurePermissions"_q);
        if (op == u"directory"_q) { Barrier(fd.value, fault, 5); Barrier(parent.value, fault, 5); }
        ParentCurrent(anchor, path, parent.value);
        struct stat named{};
        Need(fstatat(parent.value, name.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Identity(named) == Identity(st) && Private(named, true), u"Changed"_q);
        return {{u"status"_q, u"Ready"_q}, {u"identity"_q, Identity(st)}};
    }
    if (op == u"lock"_q) {
        struct stat directory{}, file{};
        Need(fstat(parent.value, &directory) == 0 && Private(directory, true), u"InsecurePermissions"_q);
        Need(fstatat(parent.value, name.constData(), &file, AT_SYMLINK_NOFOLLOW) == 0
            && Private(file, false) && file.st_size == 0, u"InsecurePermissions"_q);
        ParentCurrent(anchor, path, parent.value);
        return {{u"status"_q, u"Ready"_q}, {u"parent"_q, Identity(directory)},
            {u"identity"_q, Identity(file)}, {u"revision"_q, Revision(file)}, {u"bytes"_q, QString()}};
    }
    const auto current = Snapshot(parent.value, name, limit);
    if (op == u"read"_q) return current;
    const auto expected = Transport(expectedBytes);
    Need(current == expected, u"Changed"_q);
    if (op == u"barrier"_q) {
        if (current.value(u"identity"_q) != u"missing"_q) {
            const auto fd = Fd(openat(parent.value, name.constData(), O_RDWR | O_NOFOLLOW | O_CLOEXEC));
            struct stat st{};
            Need(fd.value >= 0 && fstat(fd.value, &st) == 0 && Revision(st) == current.value(u"revision"_q).toString(), u"Changed"_q);
            Barrier(fd.value, fault, 4);
        }
        ParentCurrent(anchor, path, parent.value); Barrier(parent.value, fault, 5);
        Need(Snapshot(parent.value, name, limit) == current, u"Changed"_q);
        return current;
    }
    if (op == u"remove"_q) {
        Need(current.value(u"identity"_q) != u"missing"_q, u"Changed"_q);
        ParentCurrent(anchor, path, parent.value);
        Need(Snapshot(parent.value, name, limit) == current && unlinkat(parent.value, name.constData(), 0) == 0, u"Changed"_q);
        Barrier(parent.value, fault, 5);
        return Snapshot(parent.value, name, limit);
    }
    Need(op == u"write"_q);
    Need(fault != 1, u"DurabilityFailed"_q);
    auto temporary = QByteArray();
    int raw = -1;
    for (int i = 0; i < 100 && raw < 0; ++i) {
        temporary = ".carry-tmp-" + QByteArray::number(getpid()) + "-" + QByteArray::number(i);
        raw = openat(parent.value, temporary.constData(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (raw < 0 && errno != EEXIST) break;
    }
    Need(raw >= 0, u"IoError"_q);
    const auto fd = Fd(raw);
    struct stat created{};
    Need(fstat(fd.value, &created) == 0 && Private(created, false), u"InsecurePermissions"_q);
    bool renamed = false;
    try {
        qsizetype offset = 0;
        while (offset < bytes.size()) {
            const auto count = fault == 2 ? qsizetype(1) : bytes.size() - offset;
            const auto size = write(fd.value, bytes.constData() + offset, count);
            if (size < 0 && errno == EINTR) continue;
            Need(size > 0, u"IoError"_q); offset += size;
            Need(fault != 2, u"DurabilityFailed"_q);
        }
        Barrier(fd.value, fault, 4);
        ParentCurrent(anchor, path, parent.value);
        Need(Snapshot(parent.value, name, limit) == current, u"Changed"_q);
        struct stat named{};
        Need(fstatat(parent.value, temporary.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Identity(named) == Identity(created) && Private(named, false), u"Changed"_q);
        Need(fault != 3, u"DurabilityFailed"_q);
        Need(renameat(parent.value, temporary.constData(), parent.value, name.constData()) == 0, u"IoError"_q);
        renamed = true;
        Need(fault != 6, u"DurabilityFailed"_q);
        Barrier(fd.value, fault, 7); Barrier(parent.value, fault, 5);
        ParentCurrent(anchor, path, parent.value);
        const auto result = Snapshot(parent.value, name, limit);
        Need(Unblob(result.value(u"bytes"_q)) == bytes, u"Changed"_q);
        return result;
    } catch (...) {
        struct stat named{};
        if (!renamed && fstatat(parent.value, temporary.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Identity(named) == Identity(created)) unlinkat(parent.value, temporary.constData(), 0);
        throw;
    }
}

}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleAccountFileSnapshot_durableNative(
        JNIEnv *env, jclass, jstring op, jstring anchor, jstring path, jbyteArray expected, jbyteArray data, jint limit, jint fault) {
    try { return Reply(env, FileOperation(Text(env, op), Text(env, anchor), Text(env, path), Bytes(env, expected, 8 * 1024 * 1024), Bytes(env, data), limit, fault)); }
    catch (const Failure &e) { return Reply(env, {{u"status"_q, e.status}}); }
    catch (...) { return Reply(env, {{u"status"_q, u"IoError"_q}}); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleSyncRetainedBases_retentionNative(
        JNIEnv *env, jclass, jstring operation, jbyteArray before, jbyteArray proposed, jobjectArray candidates) {
    try {
        const auto op = Text(env, operation);
        const auto initial = State(Bytes(env, before));
        auto next = proposed ? State(Bytes(env, proposed)) : initial;
        auto status = ReadRetainedConfig(initial).status;
        if (status != u"Ready"_q) return Reply(env, {{u"status"_q, status}});
        auto records = std::vector<QByteArray>();
        if (candidates) {
            Need(env->GetArrayLength(candidates) <= 1024);
            for (int i = 0; i < env->GetArrayLength(candidates); ++i) {
                const auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(candidates, i));
                records.push_back(Bytes(env, value)); env->DeleteLocalRef(value);
            }
        }
        if (op == u"retain"_q) for (const auto &record : records) { status = RetainConfig(next, record); if (status != u"Ready"_q) break; }
        else if (op == u"prepare"_q) status = PrepareRetainedConfig(initial, next, records);
        else if (op == u"compact"_q) status = CompactRetainedConfig(next);
        else if (op == u"find"_q) {
            for (const auto &record : records) if (MatchesConfigBase(initial, record)) { status = RetainConfig(next, record); break; }
            if (!initial.configData.base.isEmpty() && RetainedConfigBase(next).isEmpty()) status = u"MissingLocalVersion"_q;
        } else Need(op == u"inspect"_q || op == u"base"_q);
        if (op == u"base"_q && !initial.configData.base.isEmpty() && RetainedConfigBase(initial).isEmpty()) status = u"MissingLocalVersion"_q;
        auto result = QJsonObject{{u"status"_q, status}};
        if (status == u"Ready"_q) {
            const auto encoded = SerializeSyncLocalState(next);
            Need(bool(encoded));
            result.insert(u"state"_q, Blob(encoded.canonical));
            result.insert(u"record"_q, Blob(RetainedConfigBase(next)));
            result.insert(u"entries"_q, ReadRetainedConfig(initial).entries);
        }
        return Reply(env, result);
    } catch (const Failure &e) { return Reply(env, {{u"status"_q, e.status}}); }
    catch (...) { return Reply(env, {{u"status"_q, u"InvalidRetention"_q}}); }
}

