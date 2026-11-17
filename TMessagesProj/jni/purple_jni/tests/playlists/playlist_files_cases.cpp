#include "../../playlists_files.h"

#include <QtCore/QCoreApplication>
#include <QtCore/QDir>
#include <QtCore/QFile>
#include <QtCore/QProcess>
#include <QtCore/QTemporaryDir>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
using Files = PurpleAndroid::PlaylistFiles;
using Name = Files::Name;
auto checks = 0;
auto failures = 0;

void Expect(bool condition, const char *name) {
    ++checks;
    if (!condition) { ++failures; std::cerr << "FAIL: " << name << '\n'; }
}

template <typename Action>
bool Refuses(Action action, const char *status = nullptr) {
    try { action(); }
    catch (const Files::Failure &error) {
        return !status || QByteArray(error.status) == status;
    }
    return false;
}

void NeedFixture(bool value) {
    if (!value) throw std::runtime_error("Fixture setup failed");
}

void Put(const QString &path, const QByteArray &bytes, mode_t mode = 0600) {
    auto file = QFile(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)
        || file.write(bytes) != bytes.size()) throw std::runtime_error("Fixture write failed");
    file.close();
    if (chmod(path.toUtf8().constData(), mode)) throw std::runtime_error("Fixture chmod failed");
}

QByteArray Get(const QString &path) {
    auto file = QFile(path);
    if (!file.open(QIODevice::ReadOnly)) throw std::runtime_error("Fixture read failed");
    return file.readAll();
}

struct Fixture {
    QTemporaryDir temp;
    QString anchor = temp.path();
    QString parent = anchor + "/account";
    QString root = parent + "/playlists";
    bool enabled = true;
    Fixture() {
        if (!temp.isValid() || chmod(anchor.toUtf8().constData(), 0700)
            || !QDir().mkdir(parent) || chmod(parent.toUtf8().constData(), 0700))
            throw std::runtime_error("Fixture setup failed");
    }
    std::unique_ptr<Files> open() {
        return Files::Open(anchor, "account/playlists", [&] { return enabled; });
    }
};

void Basic() {
    auto fixture = Fixture();
    auto files = fixture.open();
    const auto absent = files->read(Name::Library);
    Expect(!absent.bytes && absent.revision == "missing", "absence proven explicitly");
    Expect(!QFile::exists(fixture.root + "/library.json"), "read does not materialize default");
    const auto first = files->replace(Name::Library, absent, "first", [] { return true; });
    Expect(first.bytes == QByteArray("first") && files->read(Name::Library) == first, "confirmed readback");
    Expect(Refuses([&] { files->replace(Name::Library, absent, "stale", [] { return true; }); }, "Changed"), "stale target refused");
    Expect(Get(fixture.root + "/library.json") == "first", "stale target unchanged");
    Expect(files->confirm(Name::Library, first, [] { return true; }) == first, "existing bytes explicitly confirmed");
    Expect(Refuses([&] { files->confirm(Name::Local, files->read(Name::Local), [] { return true; }); }), "absence cannot be confirmed as durable library");
    const auto backup = files->replace(Name::Backup, files->read(Name::Backup), *first.bytes, [] { return true; });
    Expect(backup.bytes == first.bytes, "backup exact prior bytes");
    const auto large = QByteArray(25 * 1024 * 1024, ' ');
    const auto second = files->replace(Name::Library, first, large, [] { return true; });
    Expect(second.bytes == large && files->read(Name::Library) == second, "large file has no sync size cap");
    Expect(Get(fixture.root + "/library.json.bak") == "first", "library write preserves backup");
    const auto local = files->replace(Name::Local, files->read(Name::Local), "state", [] { return true; });
    Expect(local.bytes == QByteArray("state") && files->read(Name::Library) == second, "local write preserves library identity");
    struct stat st{};
    Expect(lstat((fixture.root + "/library.json").toUtf8().constData(), &st) == 0
        && (st.st_mode & 07777) == 0600 && st.st_nlink == 1, "published file private and singly linked");
    Expect(Refuses([&] { fixture.open(); }, "Busy"), "same process writer excluded");
    auto wrongThread = false;
    auto worker = std::thread([&] {
        wrongThread = Refuses([&] { files->read(Name::Library); }, "WrongThread");
    });
    worker.join();
    Expect(wrongThread, "wrong worker refused");
    fixture.enabled = false;
    Expect(!files->current() && Refuses([&] { files->read(Name::Library); }, "RouteChanged"), "route revocation refuses read");
    files.reset();
    fixture.enabled = true;
    auto reopened = fixture.open();
    Expect(reopened->read(Name::Library).bytes == large, "close releases claims and preserves data");
}

void UnsafeFiles() {
    for (auto kind = 0; kind != 4; ++kind) {
        auto fixture = Fixture();
        auto files = fixture.open();
        const auto target = fixture.root + "/library.json";
        const auto outside = fixture.anchor + "/outside";
        Put(outside, "outside");
        if (kind == 0) NeedFixture(symlink(outside.toUtf8().constData(), target.toUtf8().constData()) == 0);
        if (kind == 1) NeedFixture(link(outside.toUtf8().constData(), target.toUtf8().constData()) == 0);
        if (kind == 2) NeedFixture(mkfifo(target.toUtf8().constData(), 0600) == 0);
        if (kind == 3) Put(target, "not private", 0644);
        Expect(Refuses([&] { files->read(Name::Library); }, "InsecurePermissions"), "unsafe input refused before reading");
        Expect(Get(outside) == "outside", "unsafe input leaves outside file unchanged");
    }
}

void Replacement() {
    auto fixture = Fixture();
    auto files = fixture.open();
    const auto old = files->read(Name::Library);
    NeedFixture(QDir().rename(fixture.parent, fixture.anchor + "/old-account"));
    NeedFixture(QDir().mkdir(fixture.parent));
    NeedFixture(chmod(fixture.parent.toUtf8().constData(), 0700) == 0);
    Expect(!files->current(), "ancestor replacement revokes route");
    Expect(Refuses([&] { files->replace(Name::Library, old, "denied", [] { return true; }); }), "replacement refuses publication");
    Expect(!QFile::exists(fixture.anchor + "/old-account/playlists/library.json"), "unlinked old root not written");
    Expect(!QFile::exists(fixture.parent + "/playlists"), "foreign replacement not initialized");
}

void Revocation() {
    auto sawBefore = false;
    auto sawAfter = false;
    for (auto stop = 1; stop != 70; ++stop) {
        auto fixture = Fixture();
        auto files = fixture.open();
        const auto old = files->replace(Name::Library, files->read(Name::Library), "old", [] { return true; });
        auto calls = 0;
        auto refused = false;
        auto uncertain = false;
        try {
            files->replace(Name::Library, old, QByteArray(131073, 'n'), [&] { return ++calls != stop; });
        } catch (const Files::Failure &failure) {
            refused = true;
            uncertain = failure.mayHaveChanged;
        }
        const auto data = Get(fixture.root + "/library.json");
        Expect(data == "old" || data == QByteArray(131073, 'n'), "revocation never publishes partial bytes");
        if (refused) {
            Expect(uncertain == (data != "old"), "post-publication revocation remains uncertain");
            sawAfter |= uncertain;
            sawBefore |= !uncertain;
        }
        Expect(QDir(fixture.root).entryList({".playlist-tmp-*"}, QDir::Files | QDir::Hidden).isEmpty(), "owned temporary cleaned on refusal");
    }
    Expect(sawBefore && sawAfter, "revocation covers both sides of rename");
}

void CrossProcess(const QString &executable) {
    auto fixture = Fixture();
    auto files = fixture.open();
    auto child = QProcess();
    child.start(executable, {"--try-lock", fixture.root + "/writer.lock"});
    NeedFixture(child.waitForStarted(5000) && child.waitForFinished(5000));
    Expect(child.exitStatus() == QProcess::NormalExit && child.exitCode() == 10, "independent process writer refused");
    files.reset();
    child.start(executable, {"--try-lock", fixture.root + "/writer.lock"});
    NeedFixture(child.waitForStarted(5000) && child.waitForFinished(5000));
    Expect(child.exitStatus() == QProcess::NormalExit && child.exitCode() == 0, "independent process lock available after close");
}

} // namespace

int main(int argc, char **argv) {
    auto application = QCoreApplication(argc, argv);
    const auto args = application.arguments();
    if (args.size() == 3 && args[1] == "--try-lock") {
        const auto fd = open(args[2].toUtf8().constData(), O_RDWR | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) return 11;
        struct flock lock{};
        lock.l_type = F_WRLCK;
        lock.l_whence = SEEK_SET;
        const auto result = fcntl(fd, F_SETLK, &lock);
        const auto failed = errno == EACCES || errno == EAGAIN;
        close(fd);
        return result == 0 ? 0 : failed ? 10 : 12;
    }
    try {
        Basic(); UnsafeFiles(); Replacement(); Revocation(); CrossProcess(args[0]);
    } catch (const Files::Failure &failure) {
        ++failures; std::cerr << "Unexpected backend refusal: " << failure.status << '\n';
    } catch (const std::exception &) {
        ++failures; std::cerr << "Unexpected fixture exception\n";
    }
    if (failures) { std::cerr << "PLAYLIST_FILES_FAILURES=" << failures << '\n'; return 1; }
    std::cout << "PLAYLIST_FILES_CHECKS=" << checks << '\n';
    return 0;
}
