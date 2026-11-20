#pragma once

#include <QtCore/QByteArray>
#include <QtCore/QString>
#include <functional>
#include <cstdint>
#include <memory>
#include <optional>

namespace PurpleAndroid {

class PlaylistFiles final {
public:
    using Current = std::function<bool()>;
    enum class Name { Library, Local, Backup, SyncPublication, EditCheckpoint };
    static constexpr auto kEditCheckpointBytes = 212;
    struct Snapshot {
        QByteArray parent;
        QByteArray revision;
        std::optional<QByteArray> bytes;
        bool operator==(const Snapshot &other) const = default;
    };
    struct Failure {
        const char *status;
        bool mayHaveChanged = false;
    };

    static std::unique_ptr<PlaylistFiles> Open(
        const QString &anchor,
        const QString &relativeDirectory,
        Current current,
        bool createParents = false);
    ~PlaylistFiles();
    PlaylistFiles(const PlaylistFiles &) = delete;
    PlaylistFiles &operator=(const PlaylistFiles &) = delete;

    bool current() const;
    Snapshot read(Name name) const;
    Snapshot confirm(Name name, const Snapshot &expected, const Current &current);
    Snapshot replace(Name name, const Snapshot &expected,
        const QByteArray &bytes, const Current &current);

    struct MediaFile {
        QByteArray identity;
        uint64_t size = 0;
        QByteArray sha256;
        bool operator==(const MediaFile &other) const = default;
    };
    MediaFile beginMedia(const QString &token, uint64_t size,
        const QByteArray &identity, const Current &current);
    uint64_t appendMediaOverlap(const QString &token, const QByteArray &identity,
        uint64_t offset, const QByteArray &bytes, const Current &current);
    uint64_t appendMedia(const QString &token, const QByteArray &identity,
        uint64_t offset, const QByteArray &bytes, const Current &current);
    MediaFile sealMedia(const QString &token, const QByteArray &identity,
        const Current &current);
    MediaFile publishMedia(const QString &token, uint64_t document,
        const QString &extension, const MediaFile &expected, const Current &current);
    MediaFile inspectMedia(uint64_t document, const QString &extension,
        const MediaFile &expected, const Current &current);
    void confirmMediaAbsent(uint64_t document, const QString &extension, const Current &current);
    void removeMedia(uint64_t document, const QString &extension,
        const MediaFile &expected, const Current &current);
    void confirmMediaPartAbsent(const QString &token, const Current &current);
    void discardMedia(const QString &token, const QByteArray &identity,
        const Current &current);
    void closeMedia(const QString &token);

private:
    struct State;
    explicit PlaylistFiles(std::unique_ptr<State> state);
    std::unique_ptr<State> _state;
};

} // namespace PurpleAndroid
