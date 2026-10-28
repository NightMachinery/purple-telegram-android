#include "retained_config_bases.h"
#include "purple/purple_sync_chat_journal.h"
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
#include <set>

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
QByteArray Canonical(const QJsonObject &object) {
    const auto bytes = QJsonDocument(object).toJson(QJsonDocument::Compact);
    Need(bytes.size() <= 4194304, u"RetentionFull"_q);
    const auto value = CanonicalizeSyncJson(bytes);
    Need(bool(value));
    return value.canonical;
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
int Parent(const QString &path, QByteArray &name) {
    Need(path.startsWith(u'/') && !path.endsWith(u'/'), u"AmbiguousFiles"_q);
    const auto parts = path.toUtf8().split('/');
    Need(parts.size() >= 2, u"AmbiguousFiles"_q);
    auto fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    Need(fd >= 0, u"IoError"_q);
    for (int i = 1; i < parts.size(); ++i) {
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
void ParentCurrent(const QString &path, int fd) {
    auto name = QByteArray();
    const auto reopened = Fd(Parent(path, name));
    struct stat current{}, old{};
    Need(fstat(reopened.value, &current) == 0 && fstat(fd, &old) == 0 && Identity(current) == Identity(old)
        && Private(current, true), u"Changed"_q);
}
void Barrier(int fd, int fault, int point) {
    Need(fault != point && fsync(fd) == 0, u"DurabilityFailed"_q);
}
QJsonObject FileOperation(const QString &op, const QString &path, const QByteArray &expectedBytes,
        const QByteArray &bytes, int limit, int fault) {
    Need(limit > 0 && limit <= 4194304 && bytes.size() <= limit);
    auto name = QByteArray();
    const auto parent = Fd(Parent(path, name));
    if (op == u"directory"_q || op == u"directoryRead"_q) {
        ParentCurrent(path, parent.value);
        struct stat st{};
        if (fstatat(parent.value, name.constData(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
            Need(errno == ENOENT, u"IoError"_q);
            if (op == u"directoryRead"_q) return {{u"status"_q, u"Ready"_q}, {u"identity"_q, u"missing"_q}};
            Need(mkdirat(parent.value, name.constData(), 0700) == 0, u"IoError"_q);
        }
        const auto fd = Fd(openat(parent.value, name.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        Need(fd.value >= 0 && fstat(fd.value, &st) == 0 && Private(st, true), u"InsecurePermissions"_q);
        if (op == u"directory"_q) { Barrier(fd.value, fault, 5); Barrier(parent.value, fault, 5); }
        ParentCurrent(path, parent.value);
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
        ParentCurrent(path, parent.value);
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
        ParentCurrent(path, parent.value); Barrier(parent.value, fault, 5);
        Need(Snapshot(parent.value, name, limit) == current, u"Changed"_q);
        return current;
    }
    if (op == u"remove"_q) {
        Need(current.value(u"identity"_q) != u"missing"_q, u"Changed"_q);
        ParentCurrent(path, parent.value);
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
        ParentCurrent(path, parent.value);
        Need(Snapshot(parent.value, name, limit) == current, u"Changed"_q);
        struct stat named{};
        Need(fstatat(parent.value, temporary.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Identity(named) == Identity(created) && Private(named, false), u"Changed"_q);
        Need(fault != 3, u"DurabilityFailed"_q);
        Need(renameat(parent.value, temporary.constData(), parent.value, name.constData()) == 0, u"IoError"_q);
        renamed = true;
        Need(fault != 6, u"DurabilityFailed"_q);
        Barrier(fd.value, fault, 7); Barrier(parent.value, fault, 5);
        ParentCurrent(path, parent.value);
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

QString TransportHash(const QJsonObject &wrapper) {
    const auto j = wrapper.value(u"journal"_q).toObject();
    return SyncCarryRecordHash(Canonical(QJsonObject{
        {u"domain"_q, u"purple-sync-carry-transport-v1"_q},
        {u"operation"_q, wrapper.value(u"operation"_q)}, {u"mode"_q, j.value(u"mode"_q)},
        {u"source"_q, j.value(u"source"_q)}, {u"target"_q, j.value(u"target"_q)},
        {u"account"_q, wrapper.value(u"account"_q)}, {u"binding_sha256"_q, wrapper.value(u"binding_sha256"_q)},
        {u"login_authority"_q, wrapper.value(u"login_authority"_q)}, {u"transport"_q, wrapper.value(u"transport"_q)}}));
}
void ValidateTransport(const QJsonObject &wrapper) {
    const auto value = wrapper.value(u"transport"_q);
    if (value.isUndefined()) { Need(!wrapper.contains(u"transport_sha256"_q)); return; }
    Need(value.isObject() && wrapper.value(u"transport_sha256"_q) == TransportHash(wrapper));
    const auto t = value.toObject();
    Need(t.value(u"version"_q).isDouble() && t.value(u"version"_q).toDouble() == 1);
    Need(t.value(u"entries"_q).isObject() && t.value(u"reader_warning"_q).isBool() && t.value(u"readers"_q).isObject());
    const auto j = wrapper.value(u"journal"_q).toObject();
    Need(j.value(u"version"_q).toInt() == 2 && j.value(u"login_authority"_q).toString() == wrapper.value(u"login_authority"_q).toString().left(32));
    const auto readers = t.value(u"readers"_q).toObject();
    Need(readers.value(u"members"_q).isArray() && readers.value(u"warning"_q).isBool());
    auto members = std::set<QString>();
    for (const auto &value : readers.value(u"members"_q).toArray()) {
        Need(value.isString() && !value.toString().isEmpty() && members.insert(value.toString()).second);
    }
    for (const auto &name : {u"other_members"_q, u"other_admins"_q}) {
        const auto value = readers.value(name); Need(value.isString());
        if (!value.toString().isEmpty()) { auto ok = false; const auto n = value.toString().toULongLong(&ok); Need(ok && QString::number(n) == value.toString()); }
    }
    const auto entries = t.value(u"entries"_q).toObject(); Need(entries.size() <= 1024, u"RetentionFull"_q);
    auto used = std::set<qulonglong>();
    for (auto i = entries.begin(); i != entries.end(); ++i) {
        Need(i.value().isObject()); const auto e = i.value().toObject();
        const auto kind = e.value(u"kind"_q).toString(); const auto hash = e.value(u"hash"_q).toString();
        const auto source = e.value(u"source_id"_q); const auto state = e.value(u"state"_q).toString();
        Need((kind == u"forward"_q || kind == u"own"_q || kind == u"mark"_q) && IsSyncCarryHash(hash));
        Need(source.isDouble() && source.toDouble() == source.toInt() && (kind == u"forward"_q ? source.toInt() > 0 : source.toInt() == 0));
        Need(i.key() == SyncCarryRecordHash((kind + u':' + QString::number(source.toInt()) + u':' + hash).toUtf8()));
        Need(state == u"prepared"_q || state == u"issued"_q || state == u"unknown"_q || state == u"verified"_q);
        auto number = [&](const QString &name, bool nonzero) {
            const auto v = e.value(name); Need(v.isString()); auto ok = false; const auto n = v.toString().toULongLong(&ok);
            Need(ok && QString::number(n) == v.toString() && (!nonzero || n)); return n;
        };
        const auto random = number(u"random_id"_q, true);
        Need(used.insert(random).second);
        const auto own = j.value(u"own_request"_q).toString(), mark = j.value(u"mark_request"_q).toString();
        const auto text = QString::number(random);
        Need(kind == u"own"_q ? text == own : kind == u"mark"_q ? text == mark : text != own && text != mark);
        const auto file = number(u"file_id"_q, kind == u"own"_q); Need(kind == u"own"_q || !file);
        if (e.contains(u"ack_id"_q)) {
            const auto ack = e.value(u"ack_id"_q); Need(ack.isDouble() && ack.toDouble() == ack.toInt() && ack.toInt() > 0);
        }
    }
}

QJsonObject StorageCodec(const QString &op, const QJsonObject &input) {
    if (op == u"canonical"_q) return {{u"status"_q, u"Ready"_q}, {u"bytes"_q, Blob(Canonical(Document(Unblob(input.value(u"bytes"_q)))) )}};
    const auto account = input.value(u"account"_q).toString();
    const auto key = ParseAccountKey(account);
    Need(key && AccountKeyText(key->environment, key->userId) == account, u"AccountUnbound"_q);
    const auto binding = Unblob(input.value(u"binding"_q));
    const auto authority = input.value(u"authority"_q).toString();
    Need(IsSyncCarryHash(authority), u"AccountUnbound"_q);
    if (op == u"state"_q) {
        const auto state = State(Unblob(input.value(u"bytes"_q)));
        Need(CheckSyncAccountBinding(state, binding) == SyncAccountBindingVerdict::Bound, u"AccountUnbound"_q);
        const auto retention = ReadRetainedConfig(state);
        Need(retention.status == u"Ready"_q, retention.status);
        return {{u"status"_q, u"Ready"_q}};
    }
    auto wrapper = QJsonObject();
    const auto previous = Unblob(input.value(u"wrapper"_q));
    if (!previous.isEmpty()) wrapper = Document(previous);
    if (op == u"wrap"_q) {
        const auto journal = Unblob(input.value(u"journal"_q));
        const auto parsed = ParseSyncCarryJournal(journal);
        Need(bool(parsed) && parsed.canonical == journal);
        wrapper.insert(u"version"_q, 1);
        wrapper.insert(u"account"_q, account);
        wrapper.insert(u"binding_sha256"_q, RecordHash(binding));
        wrapper.insert(u"login_authority"_q, authority);
        wrapper.insert(u"operation"_q, parsed.journal.operationId);
        wrapper.insert(u"journal_sha256"_q, RecordHash(journal));
        wrapper.insert(u"journal"_q, Document(journal));
        Need(input.value(u"objects"_q).isObject());
        wrapper.insert(u"objects"_q, input.value(u"objects"_q));
        if (input.contains(u"send_identities"_q)) wrapper.insert(u"send_identities"_q, input.value(u"send_identities"_q));
        if (input.contains(u"transport"_q)) wrapper.insert(u"transport"_q, input.value(u"transport"_q));
        if (wrapper.contains(u"transport"_q)) wrapper.insert(u"transport_sha256"_q, TransportHash(wrapper));
    } else Need(op == u"inspect"_q);
    const auto version = wrapper.value(u"version"_q);
    Need(!(version.isDouble() && version.toDouble() > 1), u"NewerState"_q);
    Need(version.isDouble() && version.toDouble() == 1);
    Need(wrapper.value(u"account"_q) == account && wrapper.value(u"binding_sha256"_q) == RecordHash(binding)
        && wrapper.value(u"login_authority"_q) == authority, u"AccountUnbound"_q);
    Need(wrapper.value(u"journal"_q).isObject() && wrapper.value(u"objects"_q).isObject());
    const auto journal = Canonical(wrapper.value(u"journal"_q).toObject());
    const auto parsed = ParseSyncCarryJournal(journal);
    Need(parsed.status != SyncCarryJournalStatus::NewerVersion, u"NewerState"_q);
    Need(bool(parsed) && parsed.canonical == journal && parsed.journal.account == *key
        && wrapper.value(u"operation"_q) == parsed.journal.operationId
        && wrapper.value(u"journal_sha256"_q) == RecordHash(journal));
    const auto objects = wrapper.value(u"objects"_q).toObject();
    Need(objects.size() <= 1024, u"RetentionFull"_q);
    for (auto i = objects.begin(); i != objects.end(); ++i) {
        Need(IsSyncCarryHash(i.key()) && i.value().isString());
        const auto kind = i.value().toString();
        Need(kind == u"state"_q || kind == u"record"_q || kind == u"text"_q);
    }
    const auto ids = wrapper.value(u"send_identities"_q);
    if (!ids.isUndefined()) {
        Need(ids.isObject() && ids.toObject().size() <= 4096, u"RetentionFull"_q);
        const auto values = ids.toObject();
        auto used = std::set<qlonglong>();
        for (auto i = values.begin(); i != values.end(); ++i) {
            Need(!i.key().isEmpty() && i.key().size() <= 256 && i.value().isString());
            const auto text = i.value().toString(); auto ok = false; const auto id = text.toLongLong(&ok);
            Need(ok && id && QString::number(id) == text && used.insert(id).second);
        }
    }
    ValidateTransport(wrapper);
    const auto encoded = Canonical(wrapper);
    Need(encoded.size() <= 4194304, u"RetentionFull"_q);
    const auto &j = parsed.journal;
    auto required = QJsonObject();
    const auto requireObject = [&](const QString &hash, const QString &kind) {
        if (hash.isEmpty()) return;
        Need(objects.value(hash) == kind);
        Need(!required.contains(hash) || required.value(hash) == kind);
        required.insert(hash, kind);
    };
    for (const auto &hash : {j.stateHash, j.pendingStateHash, j.nextStateHash}) requireObject(hash, u"state"_q);
    requireObject(j.pendingStageHash, u"record"_q);
    if (j.plan) {
        requireObject(j.plan->stateHash, u"state"_q);
        requireObject(j.plan->reservedStateHash, u"state"_q);
        requireObject(j.plan->ownRecordHash, u"record"_q);
    }
    auto result = QJsonObject{{u"status"_q, u"Ready"_q}, {u"bytes"_q, Blob(encoded)},
        {u"journal"_q, Blob(journal)}, {u"objects"_q, objects}, {u"send_identities"_q, ids.isUndefined() ? QJsonObject() : ids},
        {u"transport"_q, wrapper.value(u"transport"_q).toObject()}, {u"requiredObjects"_q, required}, {u"stateHash"_q, j.stateHash}, {u"writeHash"_q, QString()}, {u"stageHash"_q, QString()}, {u"retireHash"_q, QString()}};
    const auto action = NextSyncCarryJournalAction(j);
    if (action == SyncCarryJournalAction::WritePendingState) result.insert(u"writeHash"_q, j.pendingStateHash);
    if (action == SyncCarryJournalAction::CommitState) result.insert(u"writeHash"_q, j.nextStateHash);
    if (action == SyncCarryJournalAction::StageOwn && j.plan) {
        result.insert(u"writeHash"_q, j.plan->reservedStateHash); result.insert(u"stageHash"_q, j.plan->ownRecordHash);
    }
    if (action == SyncCarryJournalAction::RetirePendingStage) result.insert(u"retireHash"_q, j.pendingStageHash);
    if (action == SyncCarryJournalAction::RetireOwnStage && j.plan) result.insert(u"retireHash"_q, j.plan->ownRecordHash);
    return result;
}

}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleAccountFileSnapshot_durableNative(
        JNIEnv *env, jclass, jstring op, jstring path, jbyteArray expected, jbyteArray data, jint limit, jint fault) {
    try { return Reply(env, FileOperation(Text(env, op), Text(env, path), Bytes(env, expected, 8 * 1024 * 1024), Bytes(env, data), limit, fault)); }
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleSyncChatMoveStore_codecNative(
        JNIEnv *env, jclass, jstring operation, jbyteArray input) {
    try { return Reply(env, StorageCodec(Text(env, operation), Transport(Bytes(env, input, 16 * 1024 * 1024)))); }
    catch (const Failure &e) { return Reply(env, {{u"status"_q, e.status}}); }
    catch (...) { return Reply(env, {{u"status"_q, u"InvalidState"_q}}); }
}
