#include <jni.h>

#include "purple/purple_account_files.h"
#include "purple/purple_settings.h"
#include "purple/purple_state.h"
#include "purple/purple_splice.h"
#include "purple/purple_config_diff.h"

#define TOML_EXCEPTIONS 0
#include <toml.hpp>

#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <QtCore/QDateTime>
#include <QtCore/QDir>
#include <QtCore/QFile>

#include <cstdlib>

#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <map>
#include <mutex>

namespace {

QString Text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const auto result = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

QString Bytes(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    const auto size = env->GetArrayLength(value);
    auto bytes = QByteArray(size, Qt::Uninitialized);
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return QString::fromUtf8(bytes);
}

jstring Json(JNIEnv *env, const QJsonObject &object) {
    const auto text = QString::fromUtf8(QJsonDocument(object).toJson(QJsonDocument::Compact));
    return env->NewString(reinterpret_cast<const jchar*>(text.utf16()), text.size());
}

QString Key(const std::optional<Purple::AccountKey> &key) {
    return key ? Purple::AccountKeyText(key->environment, key->userId).value_or(QString()) : QString();
}

QString AccountEditorTemplate() {
    return u"# Purple Telegram settings for one account.\n"
            "# This file holds only what differs for this account. Anything it leaves out\n"
            "# comes from the account that supplies app-wide settings on each device.\n"
            "# Work Mode, Local Premium and the other app-wide settings written here take\n"
            "# effect only on devices where this account supplies the app-wide settings.\n"
            "# See config.md, \"Settings per account\".\nversion = 2\n"_q;
}

struct Layer {
    Purple::Settings settings;
    QJsonObject description;
};

std::mutex mutex;
std::map<jlong, Layer> layers;
jlong nextLayer = 0;
jlong layerEpoch = 0;

}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountFiles_keyNative(
        JNIEnv *env, jclass, jint environment, jlong userId) {
    const auto key = Purple::AccountKeyText(environment, userId);
    return key ? env->NewString(reinterpret_cast<const jchar*>(key->utf16()), key->size()) : nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountFiles_resolveNative(
        JNIEnv *env, jclass, jstring keyText, jstring ownerText) {
    const auto key = Purple::ParseAccountKey(Text(env, keyText));
    const auto owner = Purple::ParseAccountKey(Text(env, ownerText));
    if (!key || !owner) return Json(env, {{"ok", false}});
    const auto files = Purple::ResolveAccountFiles(*key, owner, Purple::AccountFileLayout::Android);
    if (!files) return Json(env, {{"ok", false}});
    return Json(env, {{"ok", true}, {"directory", files->directory}, {"settings", files->settings},
        {"good", files->good}, {"backup", files->backup}, {"importBackup", files->importBackup},
        {"syncDirectory", files->syncDirectory}, {"syncStore", files->syncStore},
        {"history", files->history}, {"fingerprint", files->fingerprintFile}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountOwner_planNative(
        JNIEnv *env, jclass, jbyteArray local, jbyteArray owner, jboolean accountsPresent,
        jobjectArray keys, jbooleanArray directories, jbooleanArray bindings, jstring choice) {
    auto input = Purple::AccountOwnerStartupInput();
    input.localChoice = { local != nullptr, Bytes(env, local) };
    input.ownerRecord = { owner != nullptr, Bytes(env, owner) };
    input.accountsDirectoryPresent = accountsPresent;
    const auto count = env->GetArrayLength(keys);
    if (env->GetArrayLength(directories) != count || env->GetArrayLength(bindings) != count) {
        return Json(env, {{"status", int(Purple::AccountOwnerStartupStatus::InvalidInput)}});
    }
    for (auto i = 0; i != count; ++i) {
        const auto text = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        const auto key = Purple::ParseAccountKey(Text(env, text));
        env->DeleteLocalRef(text);
        if (!key) return Json(env, {{"status", int(Purple::AccountOwnerStartupStatus::InvalidInput)}});
        auto directory = jboolean();
        auto binding = jboolean();
        env->GetBooleanArrayRegion(directories, i, 1, &directory);
        env->GetBooleanArrayRegion(bindings, i, 1, &binding);
        input.signedInAccounts.push_back({*key, directory == JNI_TRUE, binding == JNI_TRUE});
    }
    if (choice) input.chosenAccount = Purple::ParseAccountKey(Text(env, choice));
    if (choice && !input.chosenAccount) return Json(env, {{"status", int(Purple::AccountOwnerStartupStatus::InvalidInput)}});
    const auto plan = Purple::PlanAccountOwnerStartup(input);
    auto eligible = QJsonArray();
    for (const auto &key : plan.eligibleChoices) eligible.push_back(Key(key));
    auto writes = QJsonArray();
    for (const auto &write : plan.writes) writes.push_back(QJsonObject{{"path", write.relativePath}, {"text", write.text}});
    return Json(env, {{"status", int(plan.status)}, {"owner", Key(plan.startupOwner)},
        {"pendingOwner", Key(plan.pendingOwner)}, {"global", Key(plan.globalAccount)},
        {"eligible", eligible}, {"writes", writes}, {"recovered", plan.restoredHandEditedOwnerNotice},
        {"automatic", plan.permissions.settingsSync && plan.permissions.messageImports
            && plan.permissions.automaticSend && plan.permissions.launchOffers}, {"error", plan.error}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountOwner_choiceNative(
        JNIEnv *env, jclass, jbyteArray local, jstring ownerText, jstring globalText) {
    const auto owner = Purple::ParseAccountKey(Text(env, ownerText));
    if (!owner) return Json(env, {{"ok", false}});
    auto parsed = Purple::ParseLocalChoice(Bytes(env, local), u"local.toml"_q);
    if (!parsed.ok()) return Json(env, {{"ok", false}, {"error", parsed.error}});
    parsed.choice.rootFileAccount = owner;
    if (globalText) {
        const auto global = Purple::ParseAccountKey(Text(env, globalText));
        if (!global) return Json(env, {{"ok", false}});
        parsed.choice.globalAccount = global;
    }
    auto warnings = QJsonArray();
    for (const auto &warning : parsed.warnings) warnings.push_back(warning);
    const auto global = Purple::GlobalAccountFor(parsed.choice, *owner);
    return Json(env, {{"ok", true}, {"global", Key(global)}, {"warnings", warnings},
        {"text", Purple::SerializeLocalChoice(parsed.choice).value_or(QString())}});
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_epochNative(JNIEnv *, jclass) {
    const auto lock = std::lock_guard(mutex);
    return layerEpoch;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_createNative(
        JNIEnv *env, jclass, jlong epoch, jbyteArray global, jstring globalPath, jbyteArray globalGood,
        jbyteArray account, jstring accountPath, jbyteArray accountGood) {
    auto options = Purple::SettingsLayerOptions();
    options.globalPresent = global != nullptr;
    options.accountPresent = account != nullptr;
    const auto gp = Text(env, globalPath);
    const auto ap = Text(env, accountPath);
    if (globalGood) options.globalGood = Purple::SettingsLayerText{Bytes(env, globalGood), gp + u".good"_q};
    if (accountGood) options.accountGood = Purple::SettingsLayerText{Bytes(env, accountGood), ap + u".good"_q};
    const auto result = Purple::LayerSettings(Bytes(env, global), gp, Bytes(env, account), ap, options);
    const auto lock = std::lock_guard(mutex);
    if (epoch != layerEpoch) return 0;
    const auto handle = ++nextLayer;
    const auto &seen = result.effective.settings.lastSeen;
    const auto description = QJsonObject{{"handle", QString::number(handle)}, {"ok", result.effective.ok()},
        {"globalGood", result.global.usedGood}, {"accountGood", result.account.usedGood},
        {"globalError", result.global.current.parsed.error}, {"accountError", result.account.current.parsed.error},
        {"globalGoodError", result.global.good ? result.global.good->parsed.error : QString()},
        {"accountGoodError", result.account.good ? result.account.good->parsed.error : QString()},
        {"reasons", seen.reasons}, {"trade", seen.trade}, {"hold", seen.tradeHoldSeconds}};
    layers.emplace(handle, Layer{result.effective.settings, description});
    return handle;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_describeNative(
        JNIEnv *env, jclass, jlong handle) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    return i == layers.end() ? nullptr : Json(env, i->second.description);
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_releaseNative(
        JNIEnv *, jclass, jlong handle) {
    const auto lock = std::lock_guard(mutex);
    layers.erase(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_layerNative(
        JNIEnv *env, jclass type, jbyteArray global, jstring globalPath, jbyteArray globalGood,
        jbyteArray account, jstring accountPath, jbyteArray accountGood) {
    const auto epoch = Java_org_telegram_messenger_purple_PurpleAccountSettings_epochNative(env, type);
    const auto handle = Java_org_telegram_messenger_purple_PurpleAccountSettings_createNative(
        env, type, epoch, global, globalPath, globalGood, account, accountPath, accountGood);
    const auto result = Java_org_telegram_messenger_purple_PurpleAccountSettings_describeNative(env, type, handle);
    if (!result) Java_org_telegram_messenger_purple_PurpleAccountSettings_releaseNative(env, type, handle);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_clearNative(JNIEnv *, jclass) {
    const auto lock = std::lock_guard(mutex);
    ++layerEpoch;
    layers.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_previewNative(
        JNIEnv *, jclass, jlong handle, jlong peer, jint kind) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    return i != layers.end() && peer > 0 && kind >= 0 && kind <= int(Purple::ChatKind::Bot)
        && Purple::PreviewAlways(i->second.settings, Purple::PeerIdValue(peer), Purple::ChatKind(kind));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSettings_noteNative(
        JNIEnv *env, jclass, jlong handle, jbyteArray stateBytes, jlong peer, jint reason, jint shape) {
    const auto state = Purple::ParseState(Bytes(env, stateBytes), u"state.toml"_q);
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    if (i == layers.end() || reason < 0 || reason > int(Purple::LastSeenReason::HiddenByThem)
        || shape < 0 || shape > int(Purple::LastSeenShape::LongAgo)) return nullptr;
    const auto note = Purple::LastSeenNoteNow(i->second.settings, state,
        Purple::PeerIdValue(peer), Purple::LastSeenReason(reason), Purple::LastSeenShape(shape),
        QDateTime::currentSecsSinceEpoch());
    const jlong values[] = {jlong(note.line), note.tappable ? 1 : 0,
        note.wasOnlineUnix, note.readAtUnix, note.cooldownLeftSeconds};
    const auto result = env->NewLongArray(5);
    if (result) env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountOwner_globalTransitionNative(
        JNIEnv *env, jclass, jbyteArray settings, jstring path, jbyteArray good, jbyteArray stateBytes) {
    const auto name = Text(env, path);
    auto selected = Purple::ParseSettings(Bytes(env, settings), name);
    auto usingGood = false;
    if ((!settings || !selected.ok()) && good) {
        const auto fallback = Purple::ParseSettings(Bytes(env, good), name + u".good"_q);
        if (fallback.ok()) {
            selected = fallback;
            usingGood = true;
        }
    }
    const auto usable = selected.ok() && (settings || usingGood);
    auto state = Purple::ParseState(Bytes(env, stateBytes), u"state.toml"_q);
    const auto normal = state.activePreset.isEmpty()
        || !state.activePreset.compare(Purple::NormalPreset(), Qt::CaseInsensitive);
    const auto reset = !normal && (!usable || !selected.settings.preset(state.activePreset));
    if (reset) {
        state.activePreset = Purple::NormalPreset();
        state.activeSource = Purple::PresetSource::Manual;
        state.peekActive = false;
        state.peekDeadlineUnix = 0;
    }
    return Json(env, {{"ok", usable || (!settings && !good)}, {"normal", reset},
        {"preset", state.activePreset}, {"missing", settings == nullptr}, {"empty", settings && !env->GetArrayLength(settings)},
        {"good", usingGood}, {"state", Purple::SerializeState(state)}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountRowWrite_planNative(
        JNIEnv *env, jclass, jbyteArray own, jstring path, jbyteArray good,
        jint purpose, jint action) {
    if (purpose < 0 || purpose > 2 || action < -2 || action > 1
        || (purpose == 2 && action == -1)) return Json(env, {{"ok", false}});
    const auto table = purpose == 2 ? u"sync"_q : u"last_seen"_q;
    const auto key = purpose == 0 ? u"reasons_p"_q
        : purpose == 1 ? u"trade_p"_q : u"send_after_save_p"_q;
    const auto scope = Purple::SettingsScope(table, key);
    if (scope != (purpose == 2 ? Purple::SettingScope::FileLocal : Purple::SettingScope::PerAccount)) {
        return Json(env, {{"ok", false}});
    }
    const auto name = Text(env, path);
    auto text = Bytes(env, own);
    auto parsed = Purple::ParseSettings(text, name);
    auto usingGood = false;
    if (action == -2 && own && !parsed.ok() && good) {
        text = Bytes(env, good);
        parsed = Purple::ParseSettings(text, name + u".good"_q);
        usingGood = true;
    }
    if (!parsed.ok() || parsed.settings.version > 2) return Json(env, {{"ok", false}});
    const auto removed = Purple::RemoveTableKey(text, name, table, key);
    if (!removed.ok()) return Json(env, {{"ok", false}});
    const auto value = purpose == 0 ? parsed.settings.lastSeen.reasons
        : purpose == 1 ? parsed.settings.lastSeen.trade : parsed.settings.sync.sendAfterSave;
    if (action == -2) return Json(env, {{"ok", true}, {"value", own ? value : false},
        {"pinned", own && removed.changed}, {"usingGood", usingGood}});
    if (!own) {
        if (action == -1) return Json(env, {{"ok", true}, {"changed", false}});
        if (purpose == 2) return Json(env, {{"ok", false}});
        text = AccountEditorTemplate();
    }
    const auto result = action == -1 ? removed
        : Purple::SetTableBool(text, name, table, key, action != 0);
    return Json(env, {{"ok", result.ok()}, {"changed", result.changed}, {"text", result.text},
        {"created", own == nullptr}, {"version", own ? parsed.settings.version : 2}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleGlobalChoiceModel_planNative(
        JNIEnv *env, jclass, jbyteArray local, jbyteArray ownerRecord, jstring ownerText, jstring currentText, jstring selectedText) {
    const auto owner = Purple::ParseAccountKey(Text(env, ownerText));
    const auto current = Purple::ParseAccountKey(Text(env, currentText));
    const auto selected = Purple::ParseAccountKey(Text(env, selectedText));
    if (!owner || !current || !selected || !local) return Json(env, {{"ok", false}});
    const auto record = Purple::ParseOwnerRecord(Bytes(env, ownerRecord), u"accounts/root.toml"_q);
    if (!record.ok() || record.account != owner) return Json(env, {{"ok", false}});
    auto raw = QByteArray(env->GetArrayLength(local), Qt::Uninitialized);
    env->GetByteArrayRegion(local, 0, raw.size(), reinterpret_cast<jbyte*>(raw.data()));
    const auto text = QString::fromUtf8(raw);
    if (text.toUtf8() != raw) return Json(env, {{"ok", false}});
    const auto previous = Purple::ParseLocalChoice(text, u"local.toml"_q);
    if (!previous.ok() || previous.choice.rootFileAccount != owner
        || Purple::GlobalAccountFor(previous.choice, *owner) != current) return Json(env, {{"ok", false}});
    const auto oldPaths = Purple::ResolveAccountFiles(*current, owner, Purple::AccountFileLayout::Android);
    const auto newPaths = Purple::ResolveAccountFiles(*selected, owner, Purple::AccountFileLayout::Android);
    if (!oldPaths || !newPaths) return Json(env, {{"ok", false}});
    const auto parsed = toml::parse(std::string_view(raw.constData(), raw.size()), std::string_view("local.toml"));
    if (!parsed) return Json(env, {{"ok", false}});
    const auto key = Purple::AccountKeyText(selected->environment, selected->userId).value();
    const auto value = u"\"%1\""_q.arg(key);
    const auto comment = u"# That account's file on this device: "_q + newPaths->settings;
    auto next = text;
    if (const auto node = parsed.table().get("global_account")) {
        const auto begin = node->source().begin;
        const auto end = node->source().end;
        if (node->is_table() || node->is_array() || begin.line != end.line) return Json(env, {{"ok", false}});
        auto lines = text.split('\n');
        if (begin.line < 1 || begin.line > lines.size()) return Json(env, {{"ok", false}});
        const auto points = lines[begin.line - 1].toUcs4();
        const auto from = int(begin.column) - 1;
        const auto till = int(end.column) - 1;
        if (from < 0 || till < from || till > points.size()) return Json(env, {{"ok", false}});
        lines[begin.line - 1] = QString::fromUcs4(points.constData(), from) + value
            + QString::fromUcs4(points.constData() + till, points.size() - till);
        if (begin.line < lines.size() && lines[begin.line].startsWith(u"# That account's file on this device: "_q)) {
            lines[begin.line] = comment;
        }
        next = lines.join('\n');
    } else {
        next = u"global_account = %1\n%2\n"_q.arg(value, comment) + text;
    }
    const auto verified = Purple::ParseLocalChoice(next, u"local.toml"_q);
    if (!verified.ok() || verified.choice.rootFileAccount != owner || verified.choice.globalAccount != selected
        || verified.choice.accountWorkMode != previous.choice.accountWorkMode) return Json(env, {{"ok", false}});
    return Json(env, {{"ok", true}, {"text", next}, {"currentPath", oldPaths->settings}, {"selectedPath", newPaths->settings}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleGlobalChoiceModel_describeNative(
        JNIEnv *env, jclass, jbyteArray settings, jstring path, jbyteArray good, jbyteArray stateBytes) {
    const auto name = Text(env, path);
    auto parsed = Purple::ParseSettings(Bytes(env, settings), name);
    const auto primary = settings && parsed.ok() && parsed.settings.version <= Purple::kHighestSettingsVersion;
    auto usingGood = false;
    if (!primary && good) {
        const auto fallback = Purple::ParseSettings(Bytes(env, good), name + u".good"_q);
        if (fallback.ok() && fallback.settings.version <= Purple::kHighestSettingsVersion) {
            parsed = fallback;
            usingGood = true;
        }
    }
    const auto usable = primary || usingGood;
    const auto defaults = !settings && !good;
    if (!usable && !defaults) return Json(env, {{"ok", false}, {"needsGood", !primary}});
    const auto selectedBytes = usingGood ? good : settings;
    const auto summary = Purple::SummarizeConfigChange(QByteArray("version=2\n"), Bytes(env, selectedBytes).toUtf8());
    auto noAppWide = true;
    for (const auto &entry : summary.entries) {
        for (const auto &key : entry.keys) if (key.scope == Purple::SettingScope::AppWide) noAppWide = false;
    }
    auto state = Purple::ParseState(Bytes(env, stateBytes), u"state.toml"_q);
    const auto runningPreset = state.activePreset;
    const auto wasNormal = state.activePreset.isEmpty()
        || !state.activePreset.compare(Purple::NormalPreset(), Qt::CaseInsensitive);
    const auto normal = !wasNormal && !parsed.settings.preset(state.activePreset);
    if (normal) {
        state.activePreset = Purple::NormalPreset();
        state.activeSource = Purple::PresetSource::Manual;
        state.peekActive = false;
        state.peekDeadlineUnix = 0;
    }
    const auto &value = parsed.settings;
    return Json(env, {{"ok", true}, {"needsGood", !primary}, {"good", usingGood}, {"defaults", defaults},
        {"noAppWide", noAppWide}, {"lists", int(value.lists.size())}, {"presets", int(value.presets.size())},
        {"schedule", value.schedule.enabled && (!value.schedule.rules.empty() || !value.schedule.rulesets.empty())},
        {"premium", value.premium.enabled}, {"normal", normal}, {"preset", state.activePreset},
        {"state", Purple::SerializeState(state)}, {"runningPreset", runningPreset}, {"missing", !settings}});
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleGlobalChoiceModel_stateMatchesNative(
        JNIEnv *env, jclass, jbyteArray bytes, jstring preset) {
    const auto state = Purple::ParseState(Bytes(env, bytes), u"state.toml"_q);
    return !state.activePreset.compare(Text(env, preset), Qt::CaseInsensitive);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleGlobalChoiceFiles_writeNative(
        JNIEnv *env, jclass, jstring path, jstring ownedIdentity, jbyteArray input) {
    if (!path || !ownedIdentity || !input) return false;
    const auto length = env->GetArrayLength(input);
    if (length < 0 || length > 4 * 1024 * 1024) return false;
    auto bytes = QByteArray(length, Qt::Uninitialized);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return false;
    const auto name = Text(env, path).toUtf8();
    const auto expected = Text(env, ownedIdentity);
    const auto descriptor = ::open(name.constData(), O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return false;
    struct stat actual;
    if (::fstat(descriptor, &actual) != 0 || !S_ISREG(actual.st_mode) || actual.st_nlink != 1
        || QString::number(qulonglong(actual.st_dev)) + u":"_q + QString::number(qulonglong(actual.st_ino)) != expected) {
        ::close(descriptor);
        return false;
    }
    if (::fchmod(descriptor, 0600) != 0 || ::ftruncate(descriptor, 0) != 0) {
        ::close(descriptor);
        return false;
    }
    auto offset = qsizetype(0);
    while (offset < bytes.size()) {
        const auto written = ::write(descriptor, bytes.constData() + offset, bytes.size() - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) {
            ::close(descriptor);
            return false;
        }
        offset += written;
    }
    const auto synced = (::fsync(descriptor) == 0);
    const auto closed = (::close(descriptor) == 0);
    return synced && closed;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAppWideRemovalModel_planNative(
        JNIEnv *env, jclass, jbyteArray original, jstring path) {
    if (!original || env->GetArrayLength(original) > 4 * 1024 * 1024) return Json(env, {{"ok", false}});
    const auto size = env->GetArrayLength(original);
    auto raw = QByteArray(size, Qt::Uninitialized);
    env->GetByteArrayRegion(original, 0, size, reinterpret_cast<jbyte*>(raw.data()));
    const auto text = QString::fromUtf8(raw);
    if (text.toUtf8() != raw) return Json(env, {{"ok", false}});
    const auto source = Text(env, path);
    const auto parsed = Purple::ParseSettings(text, source);
    if (!parsed.ok() || parsed.settings.version > Purple::kHighestSettingsVersion) return Json(env, {{"ok", false}});
    const auto splice = Purple::RemoveAppWideSettings(text, source);
    if (!splice.ok() || splice.text.toUtf8().size() > 4 * 1024 * 1024) return Json(env, {{"ok", false}});
    const auto summary = Purple::SummarizeConfigChange(raw, splice.text.toUtf8());
    if (!summary.parsed) return Json(env, {{"ok", false}});
    auto count = 0;
    auto groups = QJsonArray();
    for (const auto &entry : summary.entries) {
        auto removed = 0;
        for (const auto &key : entry.keys) {
            if (key.scope == Purple::SettingScope::AppWide && key.kind == Purple::ConfigChangeKind::Removed) ++removed;
        }
        if (removed) groups.push_back(QJsonObject{{"label", entry.label}, {"count", removed}});
        count += removed;
    }
    return Json(env, {{"ok", true}, {"count", count}, {"groups", groups}, {"text", splice.text}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAppWideRemovalModel_createNative(
        JNIEnv *env, jclass, jstring parent) {
    auto pattern = QFile::encodeName(QDir(Text(env, parent)).filePath(u"appwide-XXXXXX"_q));
    const auto descriptor = ::mkstemp(pattern.data());
    if (descriptor < 0) return Json(env, {{"ok", false}});
    struct stat status {};
    const auto measured = ::fstat(descriptor, &status) == 0 && S_ISREG(status.st_mode) && status.st_nlink == 1;
    ::close(descriptor);
    if (!measured) return Json(env, {{"ok", false}});
    const auto identity = QString::number(qulonglong(status.st_dev)) + u":"_q
        + QString::number(qulonglong(status.st_ino));
    return Json(env, {{"ok", true}, {"path", QFile::decodeName(pattern)},
        {"identity", identity}, {"mode", int(status.st_mode)}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountEditorModel_templateNative(
        JNIEnv *env, jclass) {
    const auto text = AccountEditorTemplate();
    return env->NewString(reinterpret_cast<const jchar*>(text.utf16()), text.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleAccountEditorModel_previewNative(
        JNIEnv *env, jclass, jbyteArray opened, jbyteArray draft, jstring path, jboolean global, jint workMode) {
    if (workMode < 0 || workMode > 2) return Json(env, {{"ok", false}});
    if (!draft || env->GetArrayLength(draft) > 4 * 1024 * 1024) return Json(env, {{"ok", false}});
    const auto size = env->GetArrayLength(draft);
    auto raw = QByteArray(size, Qt::Uninitialized);
    env->GetByteArrayRegion(draft, 0, size, reinterpret_cast<jbyte*>(raw.data()));
    const auto text = QString::fromUtf8(raw);
    if (text.toUtf8() != raw) return Json(env, {{"ok", false}});
    const auto name = Text(env, path);
    const auto parsed = Purple::ParseSettings(text, name);
    if (!parsed.ok() || parsed.settings.version > Purple::kHighestSettingsVersion
        || (!opened && parsed.settings.version != 2)) return Json(env, {{"ok", false}});
    auto beforeInput = QByteArray();
    if (opened) {
        const auto beforeSize = env->GetArrayLength(opened);
        if (beforeSize > 4 * 1024 * 1024) return Json(env, {{"ok", false}});
        auto beforeRaw = QByteArray(beforeSize, Qt::Uninitialized);
        env->GetByteArrayRegion(opened, 0, beforeSize, reinterpret_cast<jbyte*>(beforeRaw.data()));
        beforeInput = beforeRaw;
        const auto beforeText = QString::fromUtf8(beforeRaw);
        if (beforeText.toUtf8() != beforeRaw) return Json(env, {{"ok", false}});
        const auto before = Purple::ParseSettings(beforeText, name);
        if (before.ok() && before.settings.version > Purple::kHighestSettingsVersion) return Json(env, {{"ok", false}});
    }
    auto warnings = QJsonArray();
    for (const auto &warning : parsed.warnings) warnings.push_back(warning);
    auto inactive = QJsonArray();
    auto followMembers = false;
    if (!global) {
        const auto summary = Purple::SummarizeConfigChange(raw, QByteArray("version=2\n"));
        if (!summary.parsed) return Json(env, {{"ok", false}});
        for (const auto &entry : summary.entries) {
            auto keys = QJsonArray();
            for (const auto &key : entry.keys) {
                const auto followed = workMode == int(Purple::AccountWorkMode::Follow)
                    && key.scope == Purple::SettingScope::PerAccount && key.key == u"members"_q
                    && key.table.startsWith(u"lists."_q);
                followMembers |= followed;
                if (key.scope == Purple::SettingScope::AppWide || followed) keys.push_back(key.table + u"."_q + key.key);
            }
            if (!keys.empty()) inactive.push_back(QJsonObject{{"label", entry.label}, {"keys", keys}});
        }
    }
    if (!global && opened && workMode == int(Purple::AccountWorkMode::Follow)) {
        const auto changes = Purple::SummarizeConfigChange(beforeInput, raw);
        for (const auto &entry : changes.entries) for (const auto &key : entry.keys) {
            if (key.scope != Purple::SettingScope::PerAccount || key.key != u"members"_q
                || !key.table.startsWith(u"lists."_q)) continue;
            followMembers = true;
            const auto address = key.table + u"."_q + key.key;
            auto listed = false;
            for (const auto &group : inactive) if (group.toObject()["keys"].toArray().contains(address)) listed = true;
            if (!listed) inactive.push_back(QJsonObject{{"label", entry.label}, {"keys", QJsonArray{address}}});
        }
    }
    return Json(env, {{"ok", true}, {"version", parsed.settings.version}, {"warnings", warnings}, {"inactive", inactive}, {"followMembers", followMembers}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleEditorDirectories_createNative(
        JNIEnv *env, jclass, jstring parentText, jstring nameText, jstring expectedText) {
    const auto parent = Text(env, parentText);
    const auto name = Text(env, nameText);
    const auto expected = Text(env, expectedText);
    if (parent.isEmpty() || parent.contains(QChar(0)) || name.isEmpty()
        || name == u"."_q || name == u".."_q || name.contains(u'/')
        || name.contains(u'\\') || name.contains(QChar(0))) {
        return Json(env, {{"created", false}, {"owned", false}});
    }
    const auto parentName = QFile::encodeName(parent);
    const auto childName = QFile::encodeName(name);
    const auto parentFd = ::open(parentName.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parentFd < 0) return Json(env, {{"created", false}, {"owned", false}});
    const auto identity = [](const struct stat &value) {
        return QString::number(qulonglong(value.st_dev)) + u":"_q + QString::number(qulonglong(value.st_ino));
    };
    struct stat parentBefore {}, previous {};
    const auto validParent = ::fstat(parentFd, &parentBefore) == 0
        && S_ISDIR(parentBefore.st_mode) && identity(parentBefore) == expected;
    const auto absent = validParent && ::fstatat(parentFd, childName.constData(), &previous, AT_SYMLINK_NOFOLLOW) < 0
        && errno == ENOENT;
    if (!absent || ::mkdirat(parentFd, childName.constData(), 0700) != 0) {
        ::close(parentFd);
        return Json(env, {{"created", false}, {"owned", false}});
    }
    const auto childFd = ::openat(parentFd, childName.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (childFd < 0) {
        ::close(parentFd);
        return Json(env, {{"created", true}, {"owned", false}});
    }
    struct stat child {}, entry {}, parentAfter {}, visibleParent {};
    const auto measured = ::fstat(childFd, &child) == 0;
    const auto visibleFd = ::open(parentName.constData(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    const auto owned = measured && S_ISDIR(child.st_mode) && child.st_uid == ::getuid()
        && (child.st_mode & 0777) == 0700
        && ::fstatat(parentFd, childName.constData(), &entry, AT_SYMLINK_NOFOLLOW) == 0
        && S_ISDIR(entry.st_mode) && identity(entry) == identity(child) && entry.st_mode == child.st_mode
        && ::fstat(parentFd, &parentAfter) == 0 && identity(parentAfter) == expected
        && parentAfter.st_mode == parentBefore.st_mode && visibleFd >= 0
        && ::fstat(visibleFd, &visibleParent) == 0 && identity(visibleParent) == expected
        && visibleParent.st_mode == parentBefore.st_mode;
    if (visibleFd >= 0) ::close(visibleFd);
    ::close(childFd);
    ::close(parentFd);
    return Json(env, {{"created", true}, {"owned", owned}, {"parentIdentity", expected},
        {"identity", measured ? identity(child) : QString()}, {"mode", measured ? int(child.st_mode) : 0}});
}

extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleRoutedWriteModel_modeNative(
        JNIEnv *env, jclass, jbyteArray local, jstring keyText, jstring globalText) {
    const auto key = Purple::ParseAccountKey(Text(env, keyText));
    const auto global = Purple::ParseAccountKey(Text(env, globalText));
    const auto parsed = Purple::ParseLocalChoice(Bytes(env, local), u"local.toml"_q);
    if (!local || !key || !global || !parsed.ok()) return -1;
    return int(Purple::AccountWorkModeFor(parsed.choice, *key, *global));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleRoutedWriteModel_listNative(
        JNIEnv *env, jclass type, jbyteArray local, jbyteArray ownBytes, jstring ownPath,
        jbyteArray globalBytes, jstring globalPath, jstring keyText, jstring globalText,
        jstring listText, jlong peer, jboolean add, jstring titleText) {
    const auto mode = Java_org_telegram_messenger_purple_PurpleRoutedWriteModel_modeNative(env, type, local, keyText, globalText);
    if (mode < 0 || !globalBytes || peer <= 0) return Json(env, {{"ok", false}});
    const auto own = Bytes(env, ownBytes);
    const auto global = Bytes(env, globalBytes);
    const auto gp = Text(env, globalPath);
    const auto ap = Text(env, ownPath);
    const auto appWide = Text(env, keyText) == Text(env, globalText)
        || mode == int(Purple::AccountWorkMode::Follow);
    auto options = Purple::SettingsLayerOptions();
    options.accountPresent = ownBytes != nullptr;
    options.followListMembers = appWide;
    const auto layered = Purple::LayerSettings(global, gp, own, ap, options);
    if (!layered.global.current.parsed.ok()
        || layered.global.current.parsed.settings.version > Purple::kHighestSettingsVersion
        || (!appWide && ownBytes && (!layered.account.current.parsed.ok()
            || layered.account.current.parsed.settings.version > Purple::kHighestSettingsVersion))
        || !layered.effective.ok()) return Json(env, {{"ok", false}});
    const auto raw = own.toUtf8();
    const auto tree = toml::parse(std::string_view(raw.constData(), raw.size()));
    auto lists = QJsonArray();
    for (const auto &entry : layered.effective.settings.lists) {
        auto members = QJsonArray();
        auto member = false;
        for (const auto id : entry.members) {
            members.push_back(QString::number(id));
            if (id == Purple::PeerIdValue(peer)) member = true;
        }
        const auto table = tree ? tree.table()["lists"][entry.name.toStdString()].as_table() : nullptr;
        const auto ownMembers = table && table->contains("members");
        const auto deleteOwn = !appWide && ownMembers && table->size() == 1
            && layered.account.current.parsed.settings.version == 2;
        lists.push_back(QJsonObject{{"name", entry.name}, {"title", entry.title}, {"member", member},
            {"members", members}, {"ownMembers", ownMembers && !appWide}, {"deleteOwn", deleteOwn}});
    }
    if (!listText) return Json(env, {{"ok", true}, {"appWide", appWide}, {"lists", lists}});
    const auto name = Text(env, listText);
    const auto list = layered.effective.settings.list(name);
    if (!list) return Json(env, {{"ok", false}});
    auto ids = list->members;
    const auto id = Purple::PeerIdValue(peer);
    const auto found = std::find(ids.begin(), ids.end(), id);
    if (add && found == ids.end()) ids.push_back(id);
    else if (!add) ids.erase(std::remove(ids.begin(), ids.end(), id), ids.end());
    auto inherited = true;
    if (!appWide && ownBytes) {
        const auto raw = own.toUtf8();
        const auto tree = toml::parse(std::string_view(raw.constData(), raw.size()));
        const auto table = tree ? tree.table()["lists"][name.toStdString()].as_table() : nullptr;
        inherited = !table || !table->contains("members");
    }
    const auto comments = Purple::ListMemberComments((appWide || inherited) ? global : own,
        (appWide || inherited) ? gp : ap, name);
    const auto title = Text(env, titleText);
    const auto result = Purple::SetListMembers(appWide ? global : ownBytes ? own : AccountEditorTemplate(),
        appWide ? gp : ap, name, ids, [&](Purple::PeerIdValue member) {
            if (member == id) return title;
            const auto i = comments.find(member);
            return i == comments.end() ? QString() : i->second;
        });
    return Json(env, {{"ok", result.ok()}, {"appWide", appWide}, {"changed", result.changed},
        {"text", result.text}, {"error", result.error}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleRoutedWriteModel_modePlanNative(
        JNIEnv *env, jclass, jbyteArray local, jstring keyText, jstring globalText, jint mode) {
    const auto key = Purple::ParseAccountKey(Text(env, keyText));
    const auto global = Purple::ParseAccountKey(Text(env, globalText));
    const auto original = Bytes(env, local);
    const auto parsed = Purple::ParseLocalChoice(original, u"local.toml"_q);
    if (!local || !key || !global || !parsed.ok() || mode < 0 || mode > 2
        || (*key == *global && mode == 1)) return Json(env, {{"ok", false}});
    const auto result = Purple::SetAccountWorkMode(original, u"local.toml"_q, *key, Purple::AccountWorkMode(mode));
    return Json(env, {{"ok", result.ok()}, {"changed", result.changed}, {"text", result.text}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleRoutedWriteModel_deleteOwnNative(
        JNIEnv *env, jclass, jbyteArray ownBytes, jstring ownPath, jbyteArray globalBytes,
        jstring globalPath, jstring listText) {
    const auto own = Bytes(env, ownBytes);
    const auto path = Text(env, ownPath);
    const auto name = Text(env, listText);
    const auto parsed = Purple::ParseSettings(own, path);
    const auto global = Purple::ParseSettings(Bytes(env, globalBytes), Text(env, globalPath));
    const auto raw = own.toUtf8();
    const auto tree = toml::parse(std::string_view(raw.constData(), raw.size()));
    const auto table = tree ? tree.table()["lists"][name.toStdString()].as_table() : nullptr;
    if (!ownBytes || !globalBytes || !parsed.ok() || parsed.settings.version != 2
        || !global.ok() || !global.settings.list(name) || !table || table->size() != 1
        || !table->contains("members") || path == Text(env, globalPath)) return Json(env, {{"ok", false}});
    auto quoted = QString();
    for (const auto ch : name) {
        if (ch == '"' || ch == '\\') { quoted += '\\'; quoted += ch; }
        else if (ch.unicode() < 0x20 || ch.unicode() == 0x7f) quoted += u"\\u%1"_q.arg(int(ch.unicode()), 4, 16, QChar('0'));
        else quoted += ch;
    }
    const auto result = Purple::RemoveTableKey(own, path, u"lists.\""_q + quoted + u"\""_q, u"members"_q);
    return Json(env, {{"ok", result.ok()}, {"changed", result.changed}, {"text", result.text}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleListDiagnosticsModel_factsNative(
        JNIEnv *env, jclass, jbyteArray beforeBytes, jbyteArray ownBytes, jbyteArray globalBytes) {
    const auto own = Bytes(env, ownBytes), global = Bytes(env, globalBytes);
    const auto parsed = Purple::ParseSettings(own, u"account settings"_q);
    const auto definitions = Purple::ParseSettings(global, u"app-wide settings"_q);
    if (!ownBytes || !globalBytes || !parsed.ok() || !definitions.ok()) return Json(env, {{"ok", false}});
    auto inactive = 0;
    if (beforeBytes) {
        const auto summary = Purple::SummarizeConfigChange(Bytes(env, beforeBytes).toUtf8(), own.toUtf8());
        if (summary.parsed) for (const auto &entry : summary.entries) for (const auto &key : entry.keys) {
            if (key.scope == Purple::SettingScope::AppWide) ++inactive;
        }
    }
    const auto raw = own.toUtf8();
    const auto tree = toml::parse(std::string_view(raw.constData(), raw.size()));
    const auto oldRaw = Bytes(env, beforeBytes).toUtf8();
    const auto previous = toml::parse(std::string_view(oldRaw.constData(), oldRaw.size()));
    const auto tables = tree ? tree.table()["lists"].as_table() : nullptr;
    auto orphaned = QJsonArray(), emptied = QJsonArray();
    if (tables) for (const auto &[key, value] : *tables) {
        const auto table = value.as_table();
        if (!table) continue;
        const auto name = QString::fromUtf8(key.str().data(), int(key.str().size()));
        const auto members = (*table)["members"].as_array();
        if (members && !definitions.settings.list(name)) orphaned.push_back(QJsonObject{{"name", name}, {"count", int(members->size())}});
        const auto old = previous ? previous.table()["lists"][key.str()].as_table() : nullptr;
        const auto oldMembers = old ? (*old)["members"].as_array() : nullptr;
        const auto defines = parsed.settings.version != 2 || table->contains("title") || table->contains("kinds");
        if (beforeBytes && defines && (!members || members->empty()) && oldMembers && !oldMembers->empty()) {
            emptied.push_back(name);
        }
    }
    return Json(env, {{"ok", true}, {"inactiveCount", inactive}, {"orphaned", orphaned}, {"emptied", emptied}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleConfigImpact_analyzeNative(
        JNIEnv *env, jclass, jbyteArray current, jbyteArray proposed, jboolean global,
        jint mode, jbyteArray globalFile, jobjectArray accounts, jobjectArray files, jintArray modes) {
    const auto refused = [&] { return Json(env, {{"status", int(Purple::ConfigImpactStatus::InvalidContext)}}); };
    if (mode < 0 || mode > 2 || !accounts || !files || !modes) return refused();
    const auto count = env->GetArrayLength(accounts);
    if (count > 100 || env->GetArrayLength(files) != count || env->GetArrayLength(modes) != count) return refused();
    auto valid = true;
    const auto read = [&](jbyteArray value) {
        auto result = Purple::ConfigImpactText{ {}, bool(value) };
        if (!value) return result;
        const auto size = env->GetArrayLength(value);
        if (size > 4 * 1024 * 1024) { valid = false; return result; }
        result.text.resize(size);
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.text.data()));
        valid &= QString::fromUtf8(result.text).toUtf8() == result.text;
        return result;
    };
    auto input = Purple::ConfigImpactInput();
    input.current = read(current); input.proposed = read(proposed); input.global = read(globalFile);
    input.role = global ? Purple::ConfigImpactFileRole::Global : Purple::ConfigImpactFileRole::Layered;
    input.workMode = Purple::AccountWorkMode(mode);
    auto choices = std::vector<jint>(count);
    env->GetIntArrayRegion(modes, 0, count, choices.data());
    for (auto i = 0; i < count; ++i) {
        const auto key = static_cast<jstring>(env->GetObjectArrayElement(accounts, i));
        const auto file = static_cast<jbyteArray>(env->GetObjectArrayElement(files, i));
        if (choices[i] < 0 || choices[i] > 2) valid = false;
        input.accounts.push_back({ Text(env, key), read(file), Purple::AccountWorkMode(choices[i]) });
        if (key) env->DeleteLocalRef(key);
        if (file) env->DeleteLocalRef(file);
    }
    if (!valid || env->ExceptionCheck()) return refused();
    const auto result = Purple::AnalyzeConfigChange(input);
    auto findings = QJsonArray();
    for (const auto &entry : result.findings) {
        findings.push_back(QJsonObject{{"kind", int(entry.kind)}, {"table", entry.change.table},
            {"key", entry.change.key}, {"scope", int(entry.change.scope)}, {"label", entry.label},
            {"account", entry.account}, {"beforeUse", int(entry.beforeUse)}, {"afterUse", int(entry.afterUse)},
            {"beforeCount", entry.beforeCount}, {"afterCount", entry.afterCount}, {"dangerous", entry.dangerous}});
    }
    const auto before = Purple::ParseSettings(QString::fromUtf8(input.current.text), u"current"_q);
    const auto after = Purple::ParseSettings(QString::fromUtf8(input.proposed.text), u"proposed"_q);
    return Json(env, {{"status", int(result.status)}, {"account", result.account},
        {"dangerous", result.dangerous}, {"findings", findings},
        {"turnsWorkModeOff", !before.settings.presets.empty() && after.settings.presets.empty()}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleLegacySendState_noteNative(
        JNIEnv *env, jclass, jbyteArray state, jbyteArray bytes, jboolean imported) {
    if (!bytes) return nullptr;
    const auto stateSize = state ? env->GetArrayLength(state) : 0;
    if (stateSize > 8 * 1024 * 1024) return nullptr;
    auto raw = QByteArray(stateSize, Qt::Uninitialized);
    if (state) env->GetByteArrayRegion(state, 0, stateSize, reinterpret_cast<jbyte*>(raw.data()));
    const auto text = QString::fromUtf8(raw);
    if (text.toUtf8() != raw) return nullptr;
    auto parsed = toml::parse(std::string_view(raw.constData(), size_t(raw.size())));
    if (!parsed) return nullptr;
    for (const auto marker : { "last_imported_fingerprint", "last_sent_fingerprint" }) {
        const auto node = parsed.table().get(marker);
        if (node && !node->is_string()) return nullptr;
    }
    const auto key = imported ? "last_imported_fingerprint" : "last_sent_fingerprint";
    const auto size = env->GetArrayLength(bytes);
    if (size > 4 * 1024 * 1024) return nullptr;
    auto content = QByteArray(size, Qt::Uninitialized);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(content.data()));
    const auto quoted = u"\""_q + Purple::SettingsFingerprint(content) + u"\""_q;
    auto next = text;
    if (const auto node = parsed.table().get(key)) {
        if (!node->is_string()) return nullptr;
        const auto at = [&](const auto &position) {
            const auto lines = text.split(u'\n');
            auto offset = 0;
            for (auto line = 1; line < int(position.line); ++line) offset += lines[line - 1].size() + 1;
            const auto prefix = lines[int(position.line) - 1].toUcs4().mid(0, int(position.column) - 1);
            return offset + QString::fromUcs4(prefix.constData(), prefix.size()).size();
        };
        const auto begin = at(node->source().begin), end = at(node->source().end);
        next.replace(begin, end - begin, quoted);
    } else {
        next.prepend(QString::fromLatin1(key) + u" = "_q + quoted + u"\n"_q);
    }
    const auto check = next.toUtf8();
    auto verified = toml::parse(std::string_view(check.constData(), size_t(check.size())));
    if (!verified || verified.table()[key].value<std::string>() != std::optional<std::string>(Purple::SettingsFingerprint(content).toStdString())) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(next.utf16()), next.size());
}
