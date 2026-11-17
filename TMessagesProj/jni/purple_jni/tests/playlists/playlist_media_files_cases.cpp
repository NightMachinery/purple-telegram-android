#include "../../playlists_files.h"
#include <QtCore/QCoreApplication>
#include <QtCore/QCryptographicHash>
#include <QtCore/QDir>
#include <QtCore/QFile>
#include <QtCore/QTemporaryDir>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef __APPLE__
#include <sys/syscall.h>
#endif
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
enum class Fault { None, Short, PartialError, Zero, ReadInterrupt, ReadError,
    FileSync, DirectorySync, Rename, Revoke, Unlink, AfterUnlinkSync };
auto fault = Fault::None;
auto calls = 0;
auto injections = 0;
auto allowed = true;
auto removed = false;
}

ssize_t MediaFaultWrite(int fd, const void *bytes, size_t size, off_t offset) {
    ++calls;
    if (fault == Fault::Short && calls == 1) { ++injections; errno = EINTR; return -1; }
    if ((fault == Fault::Short && calls == 2) || (fault == Fault::PartialError && calls == 1)) {
        ++injections;
        return ::pwrite(fd, bytes, std::min<size_t>(3, size), offset);
    }
    if (fault == Fault::PartialError && calls == 2) { ++injections; errno = EIO; return -1; }
    if (fault == Fault::Zero) { ++injections; return 0; }
    const auto result = ::pwrite(fd, bytes, size, offset);
    if (fault == Fault::Revoke) { ++injections; allowed = false; }
    return result;
}

ssize_t MediaFaultRead(int fd, void *bytes, size_t size, off_t offset) {
    ++calls;
    if (fault == Fault::ReadInterrupt && calls == 1) { ++injections; errno = EINTR; return -1; }
    if (fault == Fault::ReadError) { ++injections; errno = EIO; return -1; }
    return ::pread(fd, bytes, size, offset);
}

int MediaFaultSync(int fd) {
    struct stat s{};
    if (fstat(fd, &s)) return -1;
    if ((fault == Fault::FileSync && S_ISREG(s.st_mode))
        || ((fault == Fault::DirectorySync || (fault == Fault::AfterUnlinkSync && removed))
            && S_ISDIR(s.st_mode))) {
        ++injections; errno = EIO; return -1;
    }
    return ::fsync(fd);
}

int MediaFaultRename(int from, const char *source, int to, const char *target, unsigned flags) {
    if (fault == Fault::Rename) { ++injections; errno = EIO; return -1; }
#ifdef __APPLE__
    return renameatx_np(from, source, to, target, flags);
#else
    return int(syscall(SYS_renameat2, from, source, to, target, flags));
#endif
}

#ifndef __APPLE__
long MediaFaultSyscall(long number, int from, const char *source,
        int to, const char *target, unsigned flags) {
    if (number != SYS_renameat2) throw std::runtime_error("unexpected syscall");
    return MediaFaultRename(from, source, to, target, flags);
}
#endif

int MediaFaultUnlink(int fd, const char *name, int flags) {
    if (fault == Fault::Unlink) { ++injections; errno = EIO; return -1; }
    const auto result = ::unlinkat(fd, name, flags);
    removed |= result == 0;
    return result;
}

#include "playlist_media_subject.inc"

namespace {
using Files = PurpleAndroid::PlaylistFiles;
auto checks = 0;
auto failures = 0;
const auto Token = QString(32, QChar('a'));
const auto Other = QString(32, QChar('b'));
const auto Third = QString(32, QChar('c'));

void Expect(bool value, const char *name) {
    ++checks;
    if (!value) { ++failures; std::cerr << "FAIL: " << name << '\n'; }
}

template <typename F>
bool Refuses(F f, const char *status = nullptr, int changed = -1) {
    try { f(); }
    catch (const Files::Failure &e) {
        return (!status || QByteArray(e.status) == status)
            && (changed < 0 || e.mayHaveChanged == bool(changed));
    }
    return false;
}

void Require(bool value) { if (!value) throw std::runtime_error("fixture setup"); }

void Put(const QString &path, const QByteArray &data) {
    auto file = QFile(path);
    Require(file.open(QIODevice::WriteOnly | QIODevice::Truncate));
    Require(file.write(data) == data.size());
    file.close();
    Require(chmod(path.toUtf8().constData(), 0600) == 0);
}

QByteArray Get(const QString &path) {
    auto file = QFile(path);
    Require(file.open(QIODevice::ReadOnly));
    return file.readAll();
}

struct Fixture {
    QTemporaryDir temp;
    QString anchor = temp.path();
    QString root = anchor + "/account/playlists";
    std::unique_ptr<Files> files;
    Files::Current current = [] { return allowed; };
    Fixture() {
        fault = Fault::None; calls = injections = 0; allowed = true; removed = false;
        Require(temp.isValid() && chmod(anchor.toUtf8().constData(), 0700) == 0);
        files = Files::Open(anchor, "account/playlists", current, true);
    }
    QString part(QString token = Token) const { return root + "/files/.incoming/" + token + ".part"; }
    QString final() const { return root + "/files/23.mp3"; }
    Files::MediaFile filled(const QByteArray &bytes) {
        const auto made = files->beginMedia(Token, bytes.size(), {}, current);
        Require(files->appendMedia(Token, made.identity, 0, bytes, current) == uint64_t(bytes.size()));
        return files->sealMedia(Token, made.identity, current);
    }
};

void Happy() {
    auto f = Fixture();
    const auto bytes = QByteArray(262144, 'x') + QByteArray(1337, 'y');
    const auto made = f.files->beginMedia(Token, bytes.size(), {}, f.current);
    Expect(!made.identity.isEmpty() && made.size == 0 && made.sha256.isEmpty(), "exclusive durable identity before transfer");
    Expect(Get(f.part()).isEmpty(), "new writer empty");
    Expect(f.files->appendMedia(Token, made.identity, 0, bytes.left(262144), f.current) == 262144, "maximum bounded chunk");
    Expect(f.files->appendMedia(Token, made.identity, 262144, bytes.mid(262144), f.current) == uint64_t(bytes.size()), "sequential bounded tail");
    const auto sealed = f.files->sealMedia(Token, made.identity, f.current);
    Expect(sealed.size == uint64_t(bytes.size()) && sealed.sha256 == QCryptographicHash::hash(bytes, QCryptographicHash::Sha256).toHex(), "full independent digest");
    Expect(f.files->publishMedia(Token, 23, "mp3", sealed, f.current) == sealed, "exclusive durable publication");
    Expect(!QFile::exists(f.part()) && Get(f.final()) == bytes, "published bytes and no staging alias");
    Expect(f.files->inspectMedia(23, "mp3", sealed, f.current) == sealed, "exact published inspection");
    f.files->closeMedia(Token);
    Expect(Get(f.final()) == bytes, "close retains completed file");
    f.files.reset();
    f.files = Files::Open(f.anchor, "account/playlists", f.current);
    Expect(f.files->inspectMedia(23, "mp3", sealed, f.current) == sealed, "reopen recovers proven completed file");
    f.files->removeMedia(23, "mp3", sealed, f.current);
    Expect(!QFile::exists(f.final()), "explicit proof removal");
}

void Bounds() {
    auto f = Fixture();
    for (const auto &token : {QString(), QString("../escape"), QString(32, QChar('A')), QString(32, QChar('/'))}) {
        Expect(Refuses([&] { f.files->beginMedia(token, 4, {}, f.current); }, "InvalidToken"), "unsafe token refused");
    }
    Expect(Refuses([&] { f.files->beginMedia(Token, 0, {}, f.current); }, "InvalidSize"), "empty source refused");
    Expect(Refuses([&] { f.files->beginMedia(Token, UINT64_MAX, {}, f.current); }, "InvalidSize"), "offset overflow refused");
    Expect(Refuses([&] { f.files->beginMedia(Token, 4, {}, {}); }), "missing captured authority refused");
    const auto a = f.files->beginMedia(Token, 8, {}, f.current);
    const auto b = f.files->beginMedia(Other, 8, {}, f.current);
    Expect(Refuses([&] { f.files->beginMedia(Third, 8, {}, f.current); }, "Busy"), "physical two writer bound");
    Expect(Refuses([&] { f.files->beginMedia(Token, 8, {}, f.current); }, "Busy"), "duplicate writer refused");
    Expect(Refuses([&] { f.files->appendMedia(Token, b.identity, 0, "data", f.current); }, "Changed"), "foreign identity cannot write");
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 1, "data", f.current); }, "SizeMismatch"), "out of order offset refused");
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, {}, f.current); }, "InvalidChunk"), "empty chunk refused");
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, QByteArray(262145, 'x'), f.current); }, "InvalidChunk"), "unbounded chunk refused");
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "too large", f.current); }, "SizeMismatch"), "declared size cannot overflow");
    Expect(Refuses([&] { f.files->sealMedia(Token, a.identity, f.current); }, "SizeMismatch"), "partial cannot seal");
    auto fake = Files::MediaFile{a.identity, 8, QByteArray(64, 'a')};
    Expect(Refuses([&] { f.files->publishMedia(Token, 23, "mp3", fake, f.current); }, "Unsealed"), "unsealed cannot publish");
    Expect(Get(f.part()).isEmpty(), "rejected chunks leave original bytes");
    f.files->closeMedia(Token);
    Expect(Refuses([&] { f.files->beginMedia(Token, 8, {}, f.current); }, "Busy"), "empty untracked staging file never adopted");
    f.files->closeMedia(Other);
    f.files->discardMedia(Other, b.identity, f.current);
    Expect(!QFile::exists(f.part(Other)), "explicit staging discard");
}

void Resume() {
    auto f = Fixture();
    const auto a = f.files->beginMedia(Token, 8, {}, f.current);
    f.files->appendMedia(Token, a.identity, 0, "abcd", f.current);
    f.files->closeMedia(Token);
    Expect(Get(f.part()) == "abcd", "close retains partial");
    Expect(Refuses([&] { f.files->beginMedia(Token, 8, {}, f.current); }, "Busy"), "identityless reservation never adopts existing part");
    Expect(Refuses([&] { f.files->beginMedia(Token, 8, "7:7", f.current); }, "Changed"), "wrong checkpoint refuses resume");
    f.files.reset();
    f.files = Files::Open(f.anchor, "account/playlists", f.current);
    const auto resumed = f.files->beginMedia(Token, 8, a.identity, f.current);
    Expect(resumed.size == 4 && resumed.identity == a.identity, "reopen resumes actual size without truncate");
    f.files->appendMedia(Token, a.identity, 4, "efgh", f.current);
    const auto sealed = f.files->sealMedia(Token, a.identity, f.current);
    Expect(Get(f.part()) == "abcdefgh", "resume preserves prefix");
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 8, "!", f.current); }, "SizeMismatch"), "sealed writer immutable");
    for (const auto &extension : {QString(), QString("../mp3"), QString("MP3"), QString(13, QChar('x'))}) {
        Expect(Refuses([&] { f.files->publishMedia(Token, 23, extension, sealed, f.current); }, "InvalidName"), "unsafe final extension refused");
    }
    Expect(Refuses([&] { f.files->publishMedia(Token, 0, "mp3", sealed, f.current); }, "InvalidName"), "invalid document refused");
    auto wrong = sealed; wrong.sha256[0] = wrong.sha256[0] == 'a' ? 'b' : 'a';
    Expect(Refuses([&] { f.files->publishMedia(Token, 23, "mp3", wrong, f.current); }, "Unsealed"), "digest bound to seal");
    f.files->publishMedia(Token, 23, "mp3", sealed, f.current);
    Expect(Refuses([&] { f.files->inspectMedia(23, "mp3", wrong, f.current); }, "Changed"), "wrong published digest refused");
    Expect(Refuses([&] { f.files->removeMedia(23, "mp3", wrong, f.current); }, "Changed"), "wrong proof cannot remove");
    Expect(Get(f.final()) == "abcdefgh", "wrong proof leaves completed bytes");
}

void Replacement() {
    {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 4, {}, f.current);
        Require(QFile::rename(f.part(), f.part()+".old"));
        Put(f.part(), "safe");
        Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "data", f.current); }, "Changed"), "replaced file refuses descriptor write");
        Expect(Refuses([&] { f.files->discardMedia(Token, a.identity, f.current); }, "Changed"), "replacement not discarded");
        Expect(Get(f.part()) == "safe" && Get(f.part()+".old").isEmpty(), "neither replaced nor old file mutated");
    }
    {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 4, {}, f.current);
        Put(f.part(), "x");
        Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 1, "123", f.current); }, "Changed"), "same inode external revision refused");
    }
    {
        auto f = Fixture();
        const auto a = f.filled("data");
        Put(f.final(), "keep");
        Expect(Refuses([&] { f.files->publishMedia(Token, 23, "mp3", a, f.current); }, "Busy"), "publication never replaces existing file");
        Expect(Get(f.final()) == "keep" && Get(f.part()) == "data", "collision preserves both files");
    }
    for (const auto &directory : {QString("files/.incoming"), QString("files"), QString("account")}) {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 4, {}, f.current);
        const auto path = directory == "account" ? f.anchor+"/account" : f.root+'/'+directory;
        Require(QDir().rename(path, path+".old"));
        Require(QDir().mkdir(path) && chmod(path.toUtf8().constData(), 0700) == 0);
        Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "data", f.current); }), "replaced directory refuses write");
    }
    for (auto type = 0; type != 3; ++type) {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 4, {}, f.current);
        if (type == 0) Require(chmod(f.part().toUtf8().constData(), 0644) == 0);
        if (type == 1) Require(link(f.part().toUtf8().constData(), (f.part()+".link").toUtf8().constData()) == 0);
        if (type == 2) {
            Require(QFile::rename(f.part(), f.part()+".old"));
            Require(symlink((f.part()+".old").toUtf8().constData(), f.part().toUtf8().constData()) == 0);
        }
        Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "data", f.current); }), "unsafe inode or mode refuses write");
    }
}

void Authority() {
    auto f = Fixture();
    const auto a = f.files->beginMedia(Token, 4, {}, f.current);
    auto wrongThread = false;
    auto thread = std::thread([&] { wrongThread = Refuses([&] { f.files->closeMedia(Token); }, "WrongThread"); });
    thread.join();
    Expect(wrongThread, "creator thread enforced");
    allowed = false;
    Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "data", f.current); }, "RouteChanged"), "revoked captured account refuses write");
    Expect(Get(f.part()).isEmpty(), "revoked write has no bytes");
    allowed = true;
    auto nested = false;
    const auto reentrant = [&] {
        nested |= Refuses([&] { f.files->closeMedia(Token); }, "Busy");
        return true;
    };
    f.files->appendMedia(Token, a.identity, 0, "data", reentrant);
    Expect(nested, "authority callback cannot reenter writer");
    const auto sealed = f.files->sealMedia(Token, a.identity, f.current);
    f.files->publishMedia(Token, 23, "mp3", sealed, f.current);
    Expect(Refuses([&] { f.files->removeMedia(23, "mp3", sealed, [] { return false; }); }, "RouteChanged"), "referenced file cannot remove when caller authority refuses");
    Expect(Get(f.final()) == "data", "reference refusal retains completed file");
}

void PartialAbsence() {
    {
        auto f = Fixture();
        f.files->confirmMediaPartAbsent(Token, f.current);
        Expect(!QFile::exists(f.part()), "absent reservation confirmation never creates a staging file");
        const auto made = f.files->beginMedia(Token, 4, {}, f.current);
        Expect(Refuses([&] { f.files->confirmMediaPartAbsent(Token, f.current); }, "Changed"),
            "existing identityless part cannot be confirmed absent");
        f.files->appendMedia(Token, made.identity, 0, "data", f.current);
        removed = false; fault = Fault::AfterUnlinkSync; injections = 0;
        Expect(Refuses([&] { f.files->discardMedia(Token, made.identity, f.current); }, "DurabilityFailed", 1),
            "partial unlink barrier failure reports changed files");
        Expect(injections > 0 && !QFile::exists(f.part()), "actual post-unlink partial barrier injected");
        Expect(Refuses([&] { f.files->confirmMediaPartAbsent(Token, f.current); }, "DurabilityFailed"),
            "missing part cannot bypass failing durability barrier");
        fault = Fault::None;
        f.files->confirmMediaPartAbsent(Token, f.current);
        Expect(!QFile::exists(f.part()), "healthy partial absence retry never recreates or unlinks");
        Expect(Refuses([&] { f.files->confirmMediaPartAbsent(Token, [] { return false; }); }, "RouteChanged"),
            "revoked authority cannot confirm partial absence");
    }
}

void Faults() {
    for (const auto mode : {Fault::Short, Fault::PartialError, Fault::Zero, Fault::Revoke}) {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 8, {}, f.current);
        fault = mode; calls = injections = 0;
        if (mode == Fault::Short) {
            Expect(f.files->appendMedia(Token, a.identity, 0, "abcdefgh", f.current) == 8, "short and interrupted writes finish exact bytes");
            Expect(Get(f.part()) == "abcdefgh", "short write content exact");
        } else {
            Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "abcdefgh", f.current); }, nullptr, mode != Fault::Zero), "failed write reports possible mutation");
            fault = Fault::None; allowed = true;
            Expect(Refuses([&] { f.files->appendMedia(Token, a.identity, 0, "abcdefgh", f.current); }, "Changed"), "failed descriptor retired");
            const auto resumed = f.files->beginMedia(Token, 8, a.identity, f.current);
            Expect(resumed.size == uint64_t(Get(f.part()).size()), "failed write recovery uses actual bytes");
        }
        Expect(injections > 0, "write fault injected");
    }
    for (const auto mode : {Fault::ReadInterrupt, Fault::ReadError, Fault::FileSync}) {
        auto f = Fixture();
        const auto a = f.files->beginMedia(Token, 4, {}, f.current);
        f.files->appendMedia(Token, a.identity, 0, "data", f.current);
        fault = mode; calls = injections = 0;
        if (mode == Fault::ReadInterrupt) {
            Expect(f.files->sealMedia(Token, a.identity, f.current).size == 4, "interrupted independent hash recovers");
        } else {
            Expect(Refuses([&] { f.files->sealMedia(Token, a.identity, f.current); }), "failed read or file barrier cannot seal");
            const auto fake = Files::MediaFile{a.identity, 4, QCryptographicHash::hash("data", QCryptographicHash::Sha256).toHex()};
            Expect(Refuses([&] { f.files->publishMedia(Token, 23, "mp3", fake, f.current); }, "Unsealed"), "failed barrier cannot publish");
        }
        Expect(injections > 0, "hash or file sync fault injected");
    }
    for (const auto mode : {Fault::Rename, Fault::DirectorySync}) {
        auto f = Fixture();
        const auto sealed = f.filled("data");
        fault = mode; injections = 0;
        Expect(Refuses([&] { f.files->publishMedia(Token, 23, "mp3", sealed, f.current); }, nullptr, mode == Fault::DirectorySync), "publication failure reports exact mutation boundary");
        Expect(injections > 0, "publish fault injected");
        fault = Fault::None;
        if (mode == Fault::DirectorySync) {
            Expect(!QFile::exists(f.part()) && Get(f.final()) == "data", "post rename failure never restores file");
            f.files.reset(); f.files = Files::Open(f.anchor, "account/playlists", f.current);
            Expect(f.files->inspectMedia(23, "mp3", sealed, f.current) == sealed, "durability retry verifies exact published file");
            fault = Fault::DirectorySync;
            Expect(Refuses([&] { f.files->removeMedia(23, "mp3", sealed, f.current); }), "pre removal durability failure refuses delete");
            Expect(Get(f.final()) == "data", "failed pre removal barrier retains file");
        } else {
            Expect(Get(f.part()) == "data" && !QFile::exists(f.final()), "rename failure preserves staging only");
        }
    }
    for (const auto mode : {Fault::Unlink, Fault::AfterUnlinkSync}) {
        auto f = Fixture();
        const auto sealed = f.filled("data");
        f.files->publishMedia(Token, 23, "mp3", sealed, f.current);
        removed = false; fault = mode; injections = 0;
        Expect(Refuses([&] { f.files->removeMedia(23, "mp3", sealed, f.current); }, nullptr,
            mode == Fault::AfterUnlinkSync), "removal failure reports exact mutation boundary");
        Expect(injections > 0, "removal fault injected");
        if (mode == Fault::Unlink) Expect(Get(f.final()) == "data", "failed unlink keeps original");
        else {
            Expect(!QFile::exists(f.final()), "post unlink barrier failure never recreates file");
            Expect(Refuses([&] { f.files->confirmMediaAbsent(23, "mp3", f.current); }, "DurabilityFailed"),
                "absent file does not bypass a still failing directory barrier");
            fault = Fault::None;
            f.files->confirmMediaAbsent(23, "mp3", f.current);
            Expect(!QFile::exists(f.final()), "healthy retry confirms durable absence without recreating or unlinking");
        }
    }
    {
        auto f = Fixture();
        fault = Fault::FileSync;
        Expect(Refuses([&] { f.files->beginMedia(Token, 4, {}, f.current); }, "DurabilityFailed", 1), "create barrier failure reports retained part");
        Expect(QFile::exists(f.part()) && Get(f.part()).isEmpty(), "failed creation preserves unproven file");
        fault = Fault::None;
        Expect(Refuses([&] { f.files->beginMedia(Token, 4, {}, f.current); }, "Busy"), "failed identity checkpoint cannot adopt file");
    }
    fault = Fault::None;
}
}

int main(int argc, char *argv[]) {
    const auto app = QCoreApplication(argc, argv);
    try { Happy(); Bounds(); Resume(); Replacement(); Authority(); Faults(); PartialAbsence(); }
    catch (const Files::Failure &e) { std::cerr << "unexpected: " << e.status << '\n'; return 2; }
    catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 2; }
    std::cout << "PLAYLIST_MEDIA_FILES_CHECKS=" << checks << '\n'
        << checks << " checks, " << failures << " failures\n";
    return failures ? 1 : 0;
}
