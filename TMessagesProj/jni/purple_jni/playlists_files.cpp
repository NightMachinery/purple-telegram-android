#include "playlists_files.h"
#include <QtCore/QList>
#include <QtCore/QCryptographicHash>
#include <cstdio>
#include <map>
#ifndef __APPLE__
#include <sys/syscall.h>
#endif

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <limits>
#include <mutex>
#include <set>
#include <thread>
#include <utility>
#include <vector>

namespace PurpleAndroid {
namespace {

using Failure = PlaylistFiles::Failure;
using Snapshot = PlaylistFiles::Snapshot;

void Need(bool value, const char *status) {
    if (!value) throw Failure{status};
}

class Fd final {
public:
    explicit Fd(int value = -1) : value(value) { }
    Fd(Fd &&other) noexcept : value(std::exchange(other.value, -1)) { }
    Fd &operator=(Fd &&other) noexcept {
        if (this != &other) {
            if (value >= 0) ::close(value);
            value = std::exchange(other.value, -1);
        }
        return *this;
    }
    ~Fd() { if (value >= 0) ::close(value); }
    Fd(const Fd &) = delete;
    Fd &operator=(const Fd &) = delete;
    int value;
};

QByteArray Identity(const struct stat &s) {
    return QByteArray::number(qulonglong(s.st_dev)) + ':'
        + QByteArray::number(qulonglong(s.st_ino));
}

QByteArray Revision(const struct stat &s) {
#ifdef __APPLE__
    const auto modified = s.st_mtimespec;
    const auto changed = s.st_ctimespec;
#else
    const auto modified = s.st_mtim;
    const auto changed = s.st_ctim;
#endif
    return Identity(s) + ':' + QByteArray::number(s.st_mode)
        + ':' + QByteArray::number(s.st_uid) + ':' + QByteArray::number(s.st_nlink)
        + ':' + QByteArray::number(qlonglong(s.st_size))
        + ':' + QByteArray::number(qlonglong(modified.tv_sec))
        + ':' + QByteArray::number(modified.tv_nsec)
        + ':' + QByteArray::number(qlonglong(changed.tv_sec))
        + ':' + QByteArray::number(changed.tv_nsec);
}

bool Private(const struct stat &s, bool directory) {
    return s.st_uid == geteuid()
        && (s.st_mode & 07777) == (directory ? 0700 : 0600)
        && (directory ? S_ISDIR(s.st_mode) : S_ISREG(s.st_mode) && s.st_nlink == 1);
}

struct stat Stat(int fd, bool directory) {
    struct stat s{};
    Need(fd >= 0 && fstat(fd, &s) == 0, "IoError");
    Need(Private(s, directory), "InsecurePermissions");
    return s;
}

void Sync(int fd) {
    auto result = int();
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    Need(result == 0, "DurabilityFailed");
}

QByteArray FileName(PlaylistFiles::Name name) {
    switch (name) {
    case PlaylistFiles::Name::Library: return "library.json";
    case PlaylistFiles::Name::Local: return "local.json";
    case PlaylistFiles::Name::Backup: return "library.json.bak";
    case PlaylistFiles::Name::SyncPublication: return "sync-publication.bin";
    case PlaylistFiles::Name::EditCheckpoint: return "edit-checkpoint.bin";
    }
    throw Failure{"InvalidName"};
}

void CheckFileSize(PlaylistFiles::Name name, qsizetype size) {
    Need(name != PlaylistFiles::Name::SyncPublication
        || size <= 4 * (10 * 1024 * 1024 + 4096 + 4) + 44, "PublicationLimit");
    Need(name != PlaylistFiles::Name::EditCheckpoint
        || size <= PlaylistFiles::kEditCheckpointBytes, "EditCheckpointLimit");
}

std::mutex ClaimsMutex;
std::set<QByteArray> Claims;
std::atomic<uint64_t> TemporaryCounter = 0;

} // namespace

struct PlaylistFiles::State {
    QString anchor;
    std::vector<QByteArray> parts;
    std::vector<QByteArray> directories;
    Fd root;
    Fd lock;
    QByteArray claim;
    QByteArray lockIdentity;
    Current route;
    std::thread::id thread = std::this_thread::get_id();
    bool writing = false;

    struct Media {
        Fd fd;
        uint64_t limit = 0;
        QByteArray identity;
        QByteArray revision;
        QByteArray sha256;
    };
    Fd files;
    Fd incoming;
    QByteArray filesIdentity;
    QByteArray incomingIdentity;
    std::map<QString, Media> media;

    void mediaCheck(const Current &extra) const {
        check(extra);
        Need(bool(extra), "MissingAuthority");
        struct stat named{};
        Need(files.value >= 0 && incoming.value >= 0
            && fstatat(root.value, "files", &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, true) && Identity(named) == filesIdentity
            && Identity(Stat(files.value, true)) == filesIdentity, "DirectoryChanged");
        Need(fstatat(files.value, ".incoming", &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, true) && Identity(named) == incomingIdentity
            && Identity(Stat(incoming.value, true)) == incomingIdentity, "DirectoryChanged");
        check(extra);
    }

    void mediaDirectories(const Current &extra, bool create) {
        check(extra);
        Need(bool(extra), "MissingAuthority");
        if (files.value < 0) {
            if (create) Need(mkdirat(root.value, "files", 0700) == 0 || errno == EEXIST, "IoError");
            files = Fd(openat(root.value, "files", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
            filesIdentity = Identity(Stat(files.value, true));
        }
        check(extra);
        struct stat named{};
        Need(fstatat(root.value, "files", &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, true) && Identity(named) == filesIdentity, "DirectoryChanged");
        if (incoming.value < 0) {
            if (create) Need(mkdirat(files.value, ".incoming", 0700) == 0 || errno == EEXIST, "IoError");
            incoming = Fd(openat(files.value, ".incoming", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
            incomingIdentity = Identity(Stat(incoming.value, true));
        }
        mediaCheck(extra);
        if (create) {
            Sync(incoming.value);
            mediaCheck(extra);
            Sync(files.value);
            mediaCheck(extra);
            Sync(root.value);
            mediaCheck(extra);
        }
    }

    struct stat mediaStat(int parent, const QByteArray &name, int fd,
            const QByteArray &identity, const Current &extra) const {
        mediaCheck(extra);
        const auto actual = Stat(fd, false);
        struct stat named{};
        Need(fstatat(parent, name.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, false) && Identity(actual) == identity
            && Revision(named) == Revision(actual), "Changed");
        Need(actual.st_size >= 0, "Changed");
        mediaCheck(extra);
        return actual;
    }

    Media &mediaWriter(const QString &token, const QByteArray &identity,
            const Current &extra) {
        const auto i = media.find(token);
        Need(i != media.end() && i->second.identity == identity, "Changed");
        auto &writer = i->second;
        const auto actual = mediaStat(incoming.value, token.toLatin1() + ".part",
            writer.fd.value, identity, extra);
        Need(Revision(actual) == writer.revision, "Changed");
        return writer;
    }

    MediaFile mediaHash(int parent, const QByteArray &name, int fd,
            const QByteArray &identity, uint64_t size, const Current &extra,
            QByteArray *revision = nullptr) const {
        const auto before = mediaStat(parent, name, fd, identity, extra);
        Need(uint64_t(before.st_size) == size, "SizeMismatch");
        auto hash = QCryptographicHash(QCryptographicHash::Sha256);
        auto offset = uint64_t(0);
        char buffer[65536];
        while (offset < size) {
            mediaCheck(extra);
            const auto count = pread(fd, buffer, std::min<uint64_t>(sizeof(buffer), size - offset), off_t(offset));
            if (count < 0 && errno == EINTR) continue;
            Need(count > 0, "IoError");
            hash.addData(QByteArrayView(buffer, count));
            offset += uint64_t(count);
        }
        Need(Revision(mediaStat(parent, name, fd, identity, extra)) == Revision(before), "Changed");
        if (revision) *revision = Revision(before);
        return { identity, size, hash.result().toHex() };
    }

    ~State() {
        lock = Fd();
        if (!claim.isEmpty()) {
            const auto guard = std::lock_guard(ClaimsMutex);
            Claims.erase(claim);
        }
    }

    void worker() const { Need(std::this_thread::get_id() == thread, "WrongThread"); }

    void pathCurrent() const {
        worker();
        Need(route && route(), "RouteChanged");
        auto fd = Fd(open(anchor.toUtf8().constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        Need(Identity(Stat(fd.value, true)) == directories.front(), "DirectoryChanged");
        for (auto i = size_t(0); i != parts.size(); ++i) {
            fd = Fd(openat(fd.value, parts[i].constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
            Need(Identity(Stat(fd.value, true)) == directories[i + 1], "DirectoryChanged");
        }
        if (root.value >= 0) {
            Need(Identity(Stat(root.value, true)) == directories.back(), "DirectoryChanged");
        }
        Need(route(), "RouteChanged");
    }

    void check(const Current &extra = {}) const {
        pathCurrent();
        if (lock.value >= 0) {
            struct stat named{};
            Need(fstatat(root.value, "writer.lock", &named, AT_SYMLINK_NOFOLLOW) == 0
                && Private(named, false) && named.st_size == 0
                && Identity(named) == lockIdentity
                && Identity(Stat(lock.value, false)) == lockIdentity, "LockChanged");
        }
        Need((!extra || extra()) && route(), "RouteChanged");
    }

    Snapshot snapshot(Name name) const {
        check();
        const auto filename = FileName(name);
        struct stat before{};
        if (fstatat(root.value, filename.constData(), &before, AT_SYMLINK_NOFOLLOW) != 0) {
            Need(errno == ENOENT, "IoError");
            check();
            return { directories.back(), "missing", std::nullopt };
        }
        Need(Private(before, false), "InsecurePermissions");
        Need(before.st_size >= 0 && before.st_size <= std::numeric_limits<int>::max(), "AllocationLimit");
        CheckFileSize(name, before.st_size);
        const auto fd = Fd(openat(root.value, filename.constData(), O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
        Need(Revision(Stat(fd.value, false)) == Revision(before), "Changed");
        auto bytes = QByteArray();
        char buffer[65536];
        for (;;) {
            check();
            const auto count = ::read(fd.value, buffer, sizeof(buffer));
            if (count < 0 && errno == EINTR) continue;
            Need(count >= 0, "IoError");
            if (!count) break;
            Need(bytes.size() <= std::numeric_limits<int>::max() - count, "AllocationLimit");
            CheckFileSize(name, bytes.size() + count);
            bytes.append(buffer, count);
        }
        struct stat named{};
        Need(fstatat(root.value, filename.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, false) && Revision(named) == Revision(before)
            && Revision(Stat(fd.value, false)) == Revision(before)
            && bytes.size() == before.st_size, "Changed");
        check();
        return { directories.back(), Revision(before), std::move(bytes) };
    }
};

PlaylistFiles::PlaylistFiles(std::unique_ptr<State> state) : _state(std::move(state)) { }
PlaylistFiles::~PlaylistFiles() = default;

std::unique_ptr<PlaylistFiles> PlaylistFiles::Open(
        const QString &anchor, const QString &relativeDirectory, Current current,
        bool createParents) {
    Need(anchor.startsWith('/') && anchor.size() > 1 && !anchor.endsWith('/')
        && !anchor.contains(QChar(0)), "InvalidPath");
    for (const auto &part : anchor.toUtf8().split('/').mid(1)) {
        Need(!part.isEmpty() && part != "." && part != "..", "InvalidPath");
    }
    const auto parts = relativeDirectory.toUtf8().split('/');
    Need(!parts.isEmpty(), "InvalidPath");
    for (const auto &part : parts) {
        Need(!part.isEmpty() && part != "." && part != ".." && !part.contains('\0'), "InvalidPath");
    }
    auto state = std::make_unique<State>();
    state->anchor = anchor;
    state->route = std::move(current);
    Need(state->route && state->route(), "RouteChanged");
    auto fd = Fd(open(anchor.toUtf8().constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    state->directories.push_back(Identity(Stat(fd.value, true)));
    for (auto i = qsizetype(0); i != parts.size(); ++i) {
        state->pathCurrent();
        auto next = Fd(openat(fd.value, parts[i].constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        const auto missing = next.value < 0 && errno == ENOENT;
        if (missing && (createParents || i + 1 == parts.size())) {
            state->pathCurrent();
            Need(mkdirat(fd.value, parts[i].constData(), 0700) == 0 || errno == EEXIST, "IoError");
            next = Fd(openat(fd.value, parts[i].constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        }
        Need(Private(Stat(next.value, true), true), "InsecurePermissions");
        if (createParents || i + 1 == parts.size()) {
            state->pathCurrent();
            Sync(next.value);
            state->pathCurrent();
            Sync(fd.value);
            state->pathCurrent();
        }
        state->directories.push_back(Identity(Stat(next.value, true)));
        state->parts.push_back(parts[i]);
        fd = std::move(next);
        state->pathCurrent();
    }
    state->root = std::move(fd);
    state->check();
    state->claim = state->directories.back();
    {
        const auto guard = std::lock_guard(ClaimsMutex);
        if (!Claims.insert(state->claim).second) {
            state->claim.clear();
            throw Failure{"Busy"};
        }
    }
    auto raw = openat(state->root.value, "writer.lock", O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (raw < 0 && errno == EEXIST) {
        raw = openat(state->root.value, "writer.lock", O_RDWR | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    }
    state->lock = Fd(raw);
    const auto locked = Stat(raw, false);
    Need(locked.st_size == 0, "InvalidLock");
    state->lockIdentity = Identity(locked);
    struct flock lock{};
    lock.l_type = F_WRLCK;
    lock.l_whence = SEEK_SET;
    Need(fcntl(raw, F_SETLK, &lock) == 0, "Busy");
    state->check();
    Sync(raw);
    state->check();
    Sync(state->root.value);
    state->check();
    return std::unique_ptr<PlaylistFiles>(new PlaylistFiles(std::move(state)));
}

bool PlaylistFiles::current() const {
    try { _state->check(); return true; }
    catch (const Failure &) { return false; }
}

PlaylistFiles::Snapshot PlaylistFiles::read(Name name) const {
    return _state->snapshot(name);
}

PlaylistFiles::Snapshot PlaylistFiles::confirm(
        Name name, const Snapshot &expected, const Current &current) {
    _state->worker();
    Need(!_state->writing && expected.bytes.has_value(), "InvalidConfirmation");
    _state->writing = true;
    struct Reset { bool &value; ~Reset() { value = false; } } reset{_state->writing};
    _state->check(current);
    Need(_state->snapshot(name) == expected, "Changed");
    const auto filename = FileName(name);
    const auto fd = Fd(openat(_state->root.value, filename.constData(), O_RDWR | O_NOFOLLOW | O_CLOEXEC));
    Need(Revision(Stat(fd.value, false)) == expected.revision, "Changed");
    _state->check(current);
    Sync(fd.value);
    _state->check(current);
    Sync(_state->root.value);
    _state->check(current);
    const auto actual = _state->snapshot(name);
    Need(actual == expected, "Changed");
    return actual;
}

PlaylistFiles::Snapshot PlaylistFiles::replace(Name name, const Snapshot &expected,
        const QByteArray &bytes, const Current &current) {
    _state->worker();
    CheckFileSize(name, bytes.size());
    Need(!_state->writing, "Busy");
    _state->writing = true;
    struct Reset { bool &value; ~Reset() { value = false; } } reset{_state->writing};
    _state->check(current);
    Need(_state->snapshot(name) == expected, "Changed");
    const auto target = FileName(name);
    auto temporary = QByteArray();
    auto fd = Fd();
    for (auto attempt = 0; attempt != 100; ++attempt) {
        temporary = ".playlist-tmp-" + QByteArray::number(getpid()) + '-'
            + QByteArray::number(qulonglong(++TemporaryCounter));
        fd = Fd(openat(_state->root.value, temporary.constData(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
        if (fd.value >= 0 || errno != EEXIST) break;
    }
    Need(fd.value >= 0, "IoError");
    const auto created = Stat(fd.value, false);
    auto renamed = false;
    const auto cleanup = [&] {
        struct stat named{};
        if (!renamed && fstatat(_state->root.value, temporary.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, false) && Identity(named) == Identity(created)) {
            unlinkat(_state->root.value, temporary.constData(), 0);
        }
    };
    try {
        auto offset = qsizetype(0);
        while (offset < bytes.size()) {
            _state->check(current);
            const auto count = ::write(fd.value, bytes.constData() + offset,
                std::min<qsizetype>(65536, bytes.size() - offset));
            if (count < 0 && errno == EINTR) continue;
            Need(count > 0, "IoError");
            offset += count;
        }
        _state->check(current);
        Sync(fd.value);
        _state->check(current);
        Need(_state->snapshot(name) == expected, "Changed");
        struct stat named{};
        Need(fstatat(_state->root.value, temporary.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, false) && Identity(named) == Identity(created)
            && Revision(named) == Revision(Stat(fd.value, false)), "Changed");
        _state->check(current);
        Need(_state->snapshot(name) == expected, "Changed");
        Need(renameat(_state->root.value, temporary.constData(), _state->root.value, target.constData()) == 0, "IoError");
        renamed = true;
        _state->check(current);
        Sync(_state->root.value);
        _state->check(current);
        const auto actual = _state->snapshot(name);
        Need(actual.bytes && *actual.bytes == bytes, "ReadbackFailed");
        Need(fstatat(_state->root.value, target.constData(), &named, AT_SYMLINK_NOFOLLOW) == 0
            && Private(named, false) && Identity(named) == Identity(created)
            && Revision(named) == actual.revision, "Changed");
        _state->check(current);
        return actual;
    } catch (Failure failure) {
        cleanup();
        failure.mayHaveChanged |= renamed;
        throw failure;
    } catch (...) {
        cleanup();
        throw;
    }
}

namespace {

QByteArray MediaPart(const QString &token) {
    Need(token.size() == 32 && std::all_of(token.begin(), token.end(), [](QChar ch) {
        return (ch >= u'0' && ch <= u'9') || (ch >= u'a' && ch <= u'f');
    }), "InvalidToken");
    return token.toLatin1() + ".part";
}

QByteArray MediaName(uint64_t document, const QString &extension) {
    Need(document > 0 && document <= uint64_t(std::numeric_limits<int64_t>::max())
        && !extension.isEmpty() && extension.size() <= 12
        && std::all_of(extension.begin(), extension.end(), [](QChar ch) {
            return (ch >= u'a' && ch <= u'z') || (ch >= u'0' && ch <= u'9');
        }), "InvalidName");
    return QByteArray::number(qulonglong(document)) + '.' + extension.toLatin1();
}

struct MediaWriting {
    bool &writing;
    explicit MediaWriting(bool &value) : writing(value) {
        Need(!value, "Busy");
        writing = true;
    }
    ~MediaWriting() { writing = false; }
};

int MediaRename(int from, const char *source, int to, const char *target) {
#ifdef __APPLE__
    return renameatx_np(from, source, to, target, RENAME_EXCL);
#elif defined(SYS_renameat2)
    return int(syscall(SYS_renameat2, from, source, to, target, 1U));
#else
    errno = ENOSYS;
    return -1;
#endif
}

} // namespace

PlaylistFiles::MediaFile PlaylistFiles::beginMedia(const QString &token, uint64_t size,
        const QByteArray &identity, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto name = MediaPart(token);
    Need(size > 0 && size <= uint64_t(std::numeric_limits<off_t>::max()), "InvalidSize");
    Need(!_state->media.contains(token) && _state->media.size() < 2, "Busy");
    _state->mediaDirectories(current, identity.isEmpty());
    auto changed = false;
    try {
        auto fd = Fd(openat(_state->incoming.value, name.constData(), O_RDWR | O_NONBLOCK
            | O_NOFOLLOW | O_CLOEXEC | (identity.isEmpty() ? O_CREAT | O_EXCL : 0), 0600));
        Need(fd.value >= 0, errno == EEXIST ? "Busy" : "IoError");
        changed = identity.isEmpty();
        const auto created = Stat(fd.value, false);
        Need(identity.isEmpty() || Identity(created) == identity, "Changed");
        Need(created.st_size >= 0 && uint64_t(created.st_size) <= size
            && (!identity.isEmpty() || created.st_size == 0), "SizeMismatch");
        const auto actualIdentity = Identity(created);
        _state->mediaStat(_state->incoming.value, name, fd.value, actualIdentity, current);
        Sync(fd.value);
        _state->mediaCheck(current);
        Sync(_state->incoming.value);
        const auto actual = _state->mediaStat(_state->incoming.value, name, fd.value, actualIdentity, current);
        Need(Revision(actual) == Revision(created), "Changed");
        _state->media.emplace(token, State::Media{
            std::move(fd), size, actualIdentity, Revision(actual), {} });
        return { actualIdentity, uint64_t(actual.st_size), {} };
    } catch (Failure failure) {
        failure.mayHaveChanged |= changed;
        throw failure;
    }
}

uint64_t PlaylistFiles::appendMediaOverlap(const QString &token, const QByteArray &identity,
        uint64_t offset, const QByteArray &bytes, const Current &current) {
    _state->worker();
    Need(!bytes.isEmpty() && bytes.size() <= 262144, "InvalidChunk");
    auto size = uint64_t(0);
    auto overlap = qsizetype(0);
    {
        const auto guard = MediaWriting(_state->writing);
        const auto name = MediaPart(token);
        auto &writer = _state->mediaWriter(token, identity, current);
        const auto before = _state->mediaStat(_state->incoming.value, name, writer.fd.value, identity, current);
        size = uint64_t(before.st_size);
        Need(writer.sha256.isEmpty() && offset <= size && offset <= writer.limit
            && uint64_t(bytes.size()) <= writer.limit - offset && size - offset <= uint64_t(bytes.size()), "SizeMismatch");
        overlap = qsizetype(size - offset);
        auto existing = QByteArray(overlap, Qt::Uninitialized);
        auto read = qsizetype(0);
        while (read < overlap) {
            _state->mediaCheck(current);
            const auto count = pread(writer.fd.value, existing.data() + read,
                overlap - read, off_t(offset + uint64_t(read)));
            if (count < 0 && errno == EINTR) continue;
            Need(count > 0, "IoError");
            read += count;
        }
        Need(existing == bytes.left(overlap), "SourceChanged");
        Need(Revision(_state->mediaStat(_state->incoming.value, name, writer.fd.value,
            identity, current)) == Revision(before), "Changed");
    }
    return overlap == bytes.size() ? size : appendMedia(token, identity, size, bytes.mid(overlap), current);
}

uint64_t PlaylistFiles::appendMedia(const QString &token, const QByteArray &identity,
        uint64_t offset, const QByteArray &bytes, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto name = MediaPart(token);
    Need(!bytes.isEmpty() && bytes.size() <= 262144, "InvalidChunk");
    auto &writer = _state->mediaWriter(token, identity, current);
    const auto before = _state->mediaStat(_state->incoming.value, name, writer.fd.value, identity, current);
    Need(writer.sha256.isEmpty() && offset == uint64_t(before.st_size)
        && offset <= writer.limit && uint64_t(bytes.size()) <= writer.limit - offset, "SizeMismatch");
    auto done = qsizetype(0);
    try {
        while (done < bytes.size()) {
            _state->mediaCheck(current);
            const auto count = pwrite(writer.fd.value, bytes.constData() + done,
                bytes.size() - done, off_t(offset + uint64_t(done)));
            if (count < 0 && errno == EINTR) continue;
            Need(count > 0, "IoError");
            done += count;
        }
        const auto actual = _state->mediaStat(_state->incoming.value, name, writer.fd.value, identity, current);
        Need(uint64_t(actual.st_size) == offset + uint64_t(done), "Changed");
        writer.revision = Revision(actual);
        return uint64_t(actual.st_size);
    } catch (Failure failure) {
        failure.mayHaveChanged |= done > 0;
        _state->media.erase(token);
        throw failure;
    }
}

PlaylistFiles::MediaFile PlaylistFiles::sealMedia(const QString &token,
        const QByteArray &identity, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto name = MediaPart(token);
    auto &writer = _state->mediaWriter(token, identity, current);
    const auto actual = _state->mediaHash(_state->incoming.value, name,
        writer.fd.value, identity, writer.limit, current);
    Sync(writer.fd.value);
    Need(Revision(_state->mediaStat(_state->incoming.value, name, writer.fd.value,
        identity, current)) == writer.revision, "Changed");
    writer.sha256 = actual.sha256;
    return actual;
}

PlaylistFiles::MediaFile PlaylistFiles::publishMedia(const QString &token, uint64_t document,
        const QString &extension, const MediaFile &expected, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto source = MediaPart(token);
    const auto target = MediaName(document, extension);
    auto &writer = _state->mediaWriter(token, expected.identity, current);
    Need(!writer.sha256.isEmpty() && expected.sha256 == writer.sha256
        && expected.size == writer.limit, "Unsealed");
    _state->mediaCheck(current);
    auto changed = false;
    try {
        const auto renamed = MediaRename(_state->incoming.value, source.constData(),
            _state->files.value, target.constData());
        Need(renamed == 0, errno == EEXIST ? "Busy" : "IoError");
        changed = true;
        _state->mediaStat(_state->files.value, target, writer.fd.value, expected.identity, current);
        Sync(_state->files.value);
        _state->mediaCheck(current);
        Sync(_state->incoming.value);
        const auto actual = _state->mediaHash(_state->files.value, target, writer.fd.value,
            expected.identity, expected.size, current);
        Need(actual == expected, "Changed");
        _state->media.erase(token);
        return actual;
    } catch (Failure failure) {
        failure.mayHaveChanged |= changed;
        if (changed) _state->media.erase(token);
        throw failure;
    }
}

PlaylistFiles::MediaFile PlaylistFiles::inspectMedia(uint64_t document,
        const QString &extension, const MediaFile &expected, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto target = MediaName(document, extension);
    Need(!expected.identity.isEmpty() && expected.size > 0 && expected.sha256.size() == 64, "InvalidProof");
    _state->mediaDirectories(current, false);
    const auto fd = Fd(openat(_state->files.value, target.constData(), O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
    auto revision = QByteArray();
    const auto actual = _state->mediaHash(_state->files.value, target, fd.value,
        expected.identity, expected.size, current, &revision);
    Need(actual == expected, "Changed");
    Sync(fd.value);
    _state->mediaCheck(current);
    Sync(_state->files.value);
    Need(Revision(_state->mediaStat(_state->files.value, target, fd.value,
        expected.identity, current)) == revision, "Changed");
    return actual;
}

void PlaylistFiles::confirmMediaAbsent(uint64_t document, const QString &extension, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto target = MediaName(document, extension);
    _state->mediaDirectories(current, true);
    struct stat named{};
    Need(fstatat(_state->files.value, target.constData(), &named, AT_SYMLINK_NOFOLLOW) < 0
        && errno == ENOENT, "Changed");
    Sync(_state->files.value);
    _state->mediaCheck(current);
    Need(fstatat(_state->files.value, target.constData(), &named, AT_SYMLINK_NOFOLLOW) < 0
        && errno == ENOENT, "Changed");
    _state->mediaCheck(current);
}

void PlaylistFiles::removeMedia(uint64_t document, const QString &extension,
        const MediaFile &expected, const Current &current) {
    const auto actual = inspectMedia(document, extension, expected, current);
    const auto guard = MediaWriting(_state->writing);
    const auto target = MediaName(document, extension);
    _state->mediaCheck(current);
    const auto fd = Fd(openat(_state->files.value, target.constData(), O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
    Need(_state->mediaHash(_state->files.value, target, fd.value, actual.identity,
        actual.size, current) == actual, "Changed");
    _state->mediaCheck(current);
    auto changed = false;
    try {
        Need(unlinkat(_state->files.value, target.constData(), 0) == 0, "IoError");
        changed = true;
        _state->mediaCheck(current);
        Sync(_state->files.value);
        _state->mediaCheck(current);
    } catch (Failure failure) {
        failure.mayHaveChanged |= changed;
        throw failure;
    }
}

void PlaylistFiles::confirmMediaPartAbsent(const QString &token, const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto name = MediaPart(token);
    _state->mediaDirectories(current, true);
    struct stat named{};
    Need(fstatat(_state->incoming.value, name.constData(), &named, AT_SYMLINK_NOFOLLOW) < 0
        && errno == ENOENT, "Changed");
    Sync(_state->incoming.value);
    _state->mediaCheck(current);
    Need(fstatat(_state->incoming.value, name.constData(), &named, AT_SYMLINK_NOFOLLOW) < 0
        && errno == ENOENT, "Changed");
    _state->mediaCheck(current);
    _state->media.erase(token);
}

void PlaylistFiles::discardMedia(const QString &token, const QByteArray &identity,
        const Current &current) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    const auto name = MediaPart(token);
    Need(!identity.isEmpty(), "InvalidProof");
    _state->mediaDirectories(current, false);
    const auto fd = Fd(openat(_state->incoming.value, name.constData(), O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC));
    _state->mediaStat(_state->incoming.value, name, fd.value, identity, current);
    auto changed = false;
    try {
        Need(unlinkat(_state->incoming.value, name.constData(), 0) == 0, "IoError");
        changed = true;
        _state->media.erase(token);
        _state->mediaCheck(current);
        Sync(_state->incoming.value);
        _state->mediaCheck(current);
    } catch (Failure failure) {
        failure.mayHaveChanged |= changed;
        throw failure;
    }
}

void PlaylistFiles::closeMedia(const QString &token) {
    _state->worker();
    const auto guard = MediaWriting(_state->writing);
    MediaPart(token);
    _state->media.erase(token);
}

} // namespace PurpleAndroid
