#include "../../playlists_files.h"
#include <QtCore/QCoreApplication>
#include <QtCore/QDir>
#include <QtCore/QFile>
#include <QtCore/QList>
#include <QtCore/QTemporaryDir>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <iostream>
#include <limits>
#include <mutex>
#include <set>
#include <stdexcept>
#include <thread>
#include <utility>
#include <vector>

namespace {
enum class Fault {
    None, ShortWrite, WriteError, WriteZero, ReadInterrupt, FileSync,
    DirectorySync, Rename, ReadbackError, ReadbackMismatch,
    CleanupError, ForeignTemporary, Revoke, ConfirmFileSync, ConfirmDirectorySync,
};
auto fault = Fault::None;
auto published = false;
auto routeAllowed = true;
auto writes = 0;
auto reads = 0;
auto injections = 0;
auto cleanupCalls = 0;
auto syncedDirectories = std::vector<QByteArray>();
auto root = QString();

QStringList Temporaries() {
    return QDir(root).entryList({".playlist-tmp-*"}, QDir::Files | QDir::Hidden);
}

void Put(const QString &path, const QByteArray &bytes) {
    auto file = QFile(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)
        || file.write(bytes) != bytes.size()) throw std::runtime_error("fixture write");
    file.close();
    if (chmod(path.toUtf8().constData(), 0600)) throw std::runtime_error("fixture mode");
}

QByteArray Get(const QString &path) {
    auto file = QFile(path);
    if (!file.open(QIODevice::ReadOnly)) throw std::runtime_error("fixture read");
    return file.readAll();
}
} // namespace

ssize_t PlaylistFaultWrite(int fd, const void *bytes, size_t length) {
    ++writes;
    if (fault == Fault::ShortWrite && writes == 1) {
        ++injections; errno = EINTR; return -1;
    }
    if (fault == Fault::ShortWrite && writes == 2) {
        ++injections; return ::write(fd, bytes, std::min<size_t>(7, length));
    }
    if ((fault == Fault::WriteError || fault == Fault::CleanupError) && writes == 2) {
        ++injections; errno = EIO; return -1;
    }
    if (fault == Fault::WriteZero) { ++injections; return 0; }
    const auto result = ::write(fd, bytes, length);
    if (fault == Fault::Revoke) { ++injections; routeAllowed = false; }
    return result;
}

ssize_t PlaylistFaultRead(int fd, void *bytes, size_t length) {
    ++reads;
    if (fault == Fault::ReadInterrupt && reads == 1) {
        ++injections; errno = EINTR; return -1;
    }
    if (published && fault == Fault::ReadbackError) {
        ++injections; errno = EIO; return -1;
    }
    const auto result = ::read(fd, bytes, length);
    if (published && fault == Fault::ReadbackMismatch && result > 0) {
        ++injections; static_cast<char*>(bytes)[0] ^= 1;
    }
    return result;
}

int PlaylistFaultSync(int fd) {
    struct stat st{};
    if (fstat(fd, &st)) return -1;
    const auto directory = S_ISDIR(st.st_mode);
    if (directory) syncedDirectories.push_back(QByteArray::number(qulonglong(st.st_dev))
        + ':' + QByteArray::number(qulonglong(st.st_ino)));
    if ((!directory && (fault == Fault::FileSync || fault == Fault::ConfirmFileSync))
        || (directory && (fault == Fault::DirectorySync || fault == Fault::ConfirmDirectorySync))) {
        ++injections; errno = EIO; return -1;
    }
    if (!directory && fault == Fault::ForeignTemporary) {
        const auto names = Temporaries();
        if (names.size() != 1) throw std::runtime_error("fixture temporary");
        const auto path = root + '/' + names.front();
        if (::rename(path.toUtf8().constData(), (root + "/preserved-owned").toUtf8().constData()))
            throw std::runtime_error("fixture rename");
        Put(path, "foreign-sentinel");
        ++injections; errno = EIO; return -1;
    }
    return ::fsync(fd);
}

int PlaylistFaultRename(int from, const char *oldName, int to, const char *newName) {
    if (fault == Fault::Rename) { ++injections; errno = EIO; return -1; }
    const auto result = ::renameat(from, oldName, to, newName);
    if (!result) published = true;
    return result;
}

int PlaylistFaultUnlink(int fd, const char *name, int flags) {
    ++cleanupCalls;
    if (fault == Fault::CleanupError) { ++injections; errno = EIO; return -1; }
    return ::unlinkat(fd, name, flags);
}

#include "playlist_files_fault_subject.inc"

namespace {
using Files = PurpleAndroid::PlaylistFiles;
using Name = Files::Name;
auto checks = 0;
auto failures = 0;
void Expect(bool value, const char *name) {
    ++checks;
    if (!value) { ++failures; std::cerr << "FAIL: " << name << '\n'; }
}

struct Fixture {
    QTemporaryDir temp;
    std::unique_ptr<Files> files;
    Files::Snapshot old;
    QByteArray before = "old-sentinel";
    Fixture() {
        fault = Fault::None; routeAllowed = true; published = false;
        root = temp.path() + "/account/playlists";
        if (!temp.isValid() || chmod(temp.path().toUtf8().constData(), 0700)
            || !QDir().mkdir(temp.path() + "/account")
            || chmod((temp.path() + "/account").toUtf8().constData(), 0700))
            throw std::runtime_error("fixture directory");
        files = Files::Open(temp.path(), "account/playlists", [] { return routeAllowed; });
        old = files->replace(Name::Library, files->read(Name::Library), before, [] { return true; });
    }
    ~Fixture() { fault = Fault::None; routeAllowed = true; }
    void arm(Fault value) {
        fault = value; published = false; writes = 0; reads = 0; injections = 0; cleanupCalls = 0;
    }
};

void ProgressAndInterrupt() {
    auto f = Fixture();
    const auto bytes = QByteArray(196609, 'n');
    f.arm(Fault::ShortWrite);
    auto result = Files::Snapshot();
    try { result = f.files->replace(Name::Library, f.old, bytes, [] { return true; }); }
    catch (const Files::Failure &) {
        Expect(false, "short writes preserve every byte"); return;
    }
    Expect(injections == 2 && writes > 3, "short-write and EINTR exercised");
    Expect(result.bytes == bytes && Get(root + "/library.json") == bytes, "short writes preserve every byte");
    f.arm(Fault::ReadInterrupt);
    Expect(f.files->read(Name::Library).bytes == bytes && injections == 1, "read retries EINTR without losing data");
}

void PublicationFault(Fault value, const char *status, bool changed, const char *name) {
    auto f = Fixture();
    const auto bytes = QByteArray(131073, 'n');
    f.arm(value);
    auto refused = false;
    auto uncertain = false;
    try { f.files->replace(Name::Library, f.old, bytes, [] { return true; }); }
    catch (const Files::Failure &failure) {
        refused = QByteArray(failure.status) == status;
        uncertain = failure.mayHaveChanged;
    }
    Expect(refused && injections > 0, name);
    Expect(uncertain == changed, "publication failure keeps correct uncertainty");
    Expect(Get(root + "/library.json") == (changed ? bytes : f.before), "failure preserves complete old or published bytes");
    Expect(Temporaries().isEmpty(), "owned unpublished temporary cleaned");
    if (value == Fault::Revoke) Expect(writes == 1, "authority rechecked between bounded write chunks");
}

void ConfirmFault(Fault value, const char *name) {
    auto f = Fixture();
    f.arm(value);
    auto refused = false;
    try { f.files->confirm(Name::Library, f.old, [] { return true; }); }
    catch (const Files::Failure &failure) {
        refused = QByteArray(failure.status) == "DurabilityFailed" && !failure.mayHaveChanged;
    }
    Expect(refused && injections == 1, name);
    Expect(Get(root + "/library.json") == f.before, "confirmation failure never replaces library");
}

template <typename Backend>
auto OpenParents(const QString &anchor, const QString &relative, typename Backend::Current current) {
    if constexpr (requires { Backend::Open(anchor, relative, current, true); }) {
        return Backend::Open(anchor, relative, current, true);
    } else {
        return Backend::Open(anchor, relative, current);
    }
}

void FirstUseParents() {
    auto temp = QTemporaryDir();
    if (!temp.isValid() || chmod(temp.path().toUtf8().constData(), 0700))
        throw std::runtime_error("first-use fixture");
    fault = Fault::None; routeAllowed = true;
    const auto relative = QString("accounts/0-101/playlists");
    root = temp.path() + '/' + relative;
    auto refused = false;
    try { Files::Open(temp.path(), relative, [] { return true; }); }
    catch (const Files::Failure &) { refused = true; }
    Expect(refused && !QFile::exists(temp.path() + "/accounts"), "default does not create account parents");
    std::unique_ptr<Files> files;
    try { files = OpenParents<Files>(temp.path(), relative, [] { return true; }); }
    catch (const Files::Failure &) { }
    Expect(bool(files), "explicit first-use parent policy creates absent account route");
    if (!files) return;
    for (const auto &part : {"accounts", "accounts/0-101", "accounts/0-101/playlists"}) {
        struct stat st{};
        Expect(lstat((temp.path() + '/' + part).toUtf8().constData(), &st) == 0
            && S_ISDIR(st.st_mode) && (st.st_mode & 07777) == 0700, "first-use ancestors private");
    }
    Expect(!files->read(Name::Library).bytes && !QFile::exists(root + "/library.json"), "first-use absence remains read-only");
    files.reset();
    Expect(bool(Files::Open(temp.path(), relative, [] { return true; })), "existing parents permit default reopen");
}

void RepairFirstUseBarrier() {
    auto temp = QTemporaryDir();
    if (!temp.isValid() || chmod(temp.path().toUtf8().constData(), 0700)
        || !QDir().mkdir(temp.path() + "/account")
        || chmod((temp.path() + "/account").toUtf8().constData(), 0700))
        throw std::runtime_error("repair fixture");
    root = temp.path() + "/account/playlists";
    fault = Fault::DirectorySync; injections = 0;
    auto refused = false;
    try { Files::Open(temp.path(), "account/playlists", [] { return true; }); }
    catch (const Files::Failure &failure) { refused = QByteArray(failure.status) == "DurabilityFailed"; }
    Expect(refused && injections == 1 && QFile::exists(root), "failed first-use flush retains created directory");
    fault = Fault::None; syncedDirectories.clear();
    auto files = Files::Open(temp.path(), "account/playlists", [] { return true; });
    struct stat parent{};
    if (lstat((temp.path() + "/account").toUtf8().constData(), &parent)) throw std::runtime_error("repair parent");
    const auto identity = QByteArray::number(qulonglong(parent.st_dev)) + ':' + QByteArray::number(qulonglong(parent.st_ino));
    Expect(std::find(syncedDirectories.begin(), syncedDirectories.end(), identity) != syncedDirectories.end(),
        "retry repairs parent directory barrier");
    Expect(!files->read(Name::Library).bytes, "directory repair creates no library defaults");
}

void CleanupFaults() {
    {
        auto f = Fixture();
        f.arm(Fault::CleanupError);
        auto refused = false;
        try { f.files->replace(Name::Library, f.old, QByteArray(131073, 'n'), [] { return true; }); }
        catch (const Files::Failure &failure) {
            refused = QByteArray(failure.status) == "IoError" && !failure.mayHaveChanged;
        }
        const auto remaining = Temporaries();
        Expect(refused && injections == 2 && cleanupCalls == 1, "cleanup error remains a failed publication");
        Expect(remaining.size() == 1 && Get(root + "/library.json") == f.before, "failed cleanup retains unpublished temporary and old target");
        f.arm(Fault::None);
        const auto result = f.files->replace(Name::Library, f.old, "retry", [] { return true; });
        Expect(result.bytes == QByteArray("retry") && Temporaries() == remaining, "retry neither consumes nor overwrites leftover temporary");
    }
    {
        auto f = Fixture();
        f.arm(Fault::ForeignTemporary);
        auto refused = false;
        try { f.files->replace(Name::Library, f.old, "new", [] { return true; }); }
        catch (const Files::Failure &failure) {
            refused = QByteArray(failure.status) == "DurabilityFailed" && !failure.mayHaveChanged;
        }
        const auto names = Temporaries();
        Expect(refused && injections == 1, "temporary replacement triggers refusal");
        Expect(names.size() == 1 && Get(root + '/' + names.value(0)) == "foreign-sentinel", "cleanup never removes replacement inode");
        Expect(cleanupCalls == 0 && Get(root + "/library.json") == f.before, "foreign temporary and target remain untouched");
    }
}
} // namespace

int main(int argc, char **argv) {
    auto application = QCoreApplication(argc, argv);
    try {
        if (application.arguments().contains("--repair-only")) {
            RepairFirstUseBarrier();
            if (failures) return 1;
            std::cout << "PLAYLIST_FILES_FIRST_USE_REPAIR_CHECKS=" << checks << '\n';
            return 0;
        }
        FirstUseParents(); RepairFirstUseBarrier();
        ProgressAndInterrupt();
        PublicationFault(Fault::WriteError, "IoError", false, "partial write then EIO refuses publication");
        PublicationFault(Fault::WriteZero, "IoError", false, "zero write refuses publication");
        PublicationFault(Fault::FileSync, "DurabilityFailed", false, "file fsync failure refuses publication");
        PublicationFault(Fault::DirectorySync, "DurabilityFailed", true, "parent fsync failure remains uncertain");
        PublicationFault(Fault::Rename, "IoError", false, "rename failure refuses publication");
        PublicationFault(Fault::ReadbackError, "IoError", true, "readback I/O failure remains uncertain");
        PublicationFault(Fault::ReadbackMismatch, "ReadbackFailed", true, "readback bytes mismatch refuses success");
        PublicationFault(Fault::Revoke, "RouteChanged", false, "authority revocation refuses publication");
        ConfirmFault(Fault::ConfirmFileSync, "unchanged library file barrier required");
        ConfirmFault(Fault::ConfirmDirectorySync, "unchanged library parent barrier required");
        CleanupFaults();
    } catch (const Files::Failure &failure) {
        ++failures; std::cerr << "Unexpected refusal: " << failure.status << '\n';
    } catch (const std::exception &e) {
        ++failures; std::cerr << "Unexpected fixture exception: " << e.what() << '\n';
    }
    if (failures) return 1;
    std::cout << "PLAYLIST_FILES_FAULT_CHECKS=" << checks << '\n';
    return 0;
}
