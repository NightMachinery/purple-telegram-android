#pragma once

#include "purple/purple_sync_local_state.h"
#include <QtCore/QJsonArray>
#include <QtCore/QJsonObject>

namespace PurpleAndroid {

struct RetainedConfig {
    QString status = QString::fromLatin1("Ready");
    QJsonObject object;
    QJsonArray entries;
    qsizetype total = 0;
};

[[nodiscard]] QString RecordHash(const QByteArray &bytes);
[[nodiscard]] bool MatchesConfigBase(const Purple::SyncLocalState &state, const QByteArray &record);
[[nodiscard]] RetainedConfig ReadRetainedConfig(const Purple::SyncLocalState &state);
[[nodiscard]] QString RetainConfig(Purple::SyncLocalState &state, const QByteArray &record);
[[nodiscard]] QString PrepareRetainedConfig(const Purple::SyncLocalState &before,
    Purple::SyncLocalState &next, const std::vector<QByteArray> &candidates);
[[nodiscard]] QByteArray RetainedConfigBase(const Purple::SyncLocalState &state);
[[nodiscard]] QString CompactRetainedConfig(Purple::SyncLocalState &state);

}
