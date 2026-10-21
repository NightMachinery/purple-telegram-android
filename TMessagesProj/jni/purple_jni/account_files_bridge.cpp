#include <jni.h>

#include "purple/purple_account_files.h"
#include "purple/purple_settings.h"
#include "purple/purple_state.h"

#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <QtCore/QDateTime>

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
