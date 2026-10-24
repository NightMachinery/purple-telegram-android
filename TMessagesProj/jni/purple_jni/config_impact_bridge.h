#pragma once

#include "purple/purple_config_diff.h"
#include "purple/purple_settings.h"
#include <jni.h>
#include <QtCore/QJsonArray>
#include <QtCore/QJsonObject>
#include <optional>

namespace PurpleAndroid {
inline std::optional<Purple::ConfigImpactInput> ReadImpactContext(
        JNIEnv *env, jboolean global, jint mode, jbyteArray globalFile,
        jobjectArray accounts, jobjectArray files, jintArray modes) {
    if (mode < 0 || mode > 2 || !accounts || !files || !modes) return std::nullopt;
    const auto count = env->GetArrayLength(accounts);
    if (count > 100 || env->GetArrayLength(files) != count || env->GetArrayLength(modes) != count) return std::nullopt;
    auto valid = true;
    const auto read = [&](jbyteArray value) {
        auto result = Purple::ConfigImpactText{ {}, bool(value) };
        if (!value) return result;
        const auto size = env->GetArrayLength(value);
        if (size > 4 * 1024 * 1024) { valid = false; return result; }
        result.text.resize(size);
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.text.data()));
        valid = valid && QString::fromUtf8(result.text).toUtf8() == result.text;
        return result;
    };
    auto input = Purple::ConfigImpactInput();
    input.global = read(globalFile);
    input.role = global ? Purple::ConfigImpactFileRole::Global : Purple::ConfigImpactFileRole::Layered;
    input.workMode = Purple::AccountWorkMode(mode);
    auto choices = std::vector<jint>(count);
    env->GetIntArrayRegion(modes, 0, count, choices.data());
    for (auto i = 0; i < count; ++i) {
        const auto key = static_cast<jstring>(env->GetObjectArrayElement(accounts, i));
        const auto file = static_cast<jbyteArray>(env->GetObjectArrayElement(files, i));
        if (!key || choices[i] < 0 || choices[i] > 2) valid = false;
        const auto chars = key ? env->GetStringChars(key, nullptr) : nullptr;
        const auto text = chars ? QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(key)) : QString();
        if (chars) env->ReleaseStringChars(key, chars);
        input.accounts.push_back({ text, read(file), Purple::AccountWorkMode(choices[i]) });
        if (key) env->DeleteLocalRef(key);
        if (file) env->DeleteLocalRef(file);
    }
    return valid && !env->ExceptionCheck() ? std::optional(input) : std::nullopt;
}

inline QJsonObject ImpactJson(const Purple::ConfigChangeImpact &impact,
        const QByteArray &before, const QByteArray &after) {
    auto findings = QJsonArray();
    for (const auto &entry : impact.findings) findings.push_back(QJsonObject{
        {"kind", int(entry.kind)}, {"table", entry.change.table}, {"key", entry.change.key},
        {"scope", int(entry.change.scope)}, {"label", entry.label}, {"account", entry.account},
        {"beforeUse", int(entry.beforeUse)}, {"afterUse", int(entry.afterUse)},
        {"beforeCount", entry.beforeCount}, {"afterCount", entry.afterCount}, {"dangerous", entry.dangerous}});
    const auto previous = Purple::ParseSettings(QString::fromUtf8(before), QString());
    const auto next = Purple::ParseSettings(QString::fromUtf8(after), QString());
    return {{"status", int(impact.status)}, {"account", impact.account}, {"dangerous", impact.dangerous},
        {"findings", findings}, {"turnsWorkModeOff", !previous.settings.presets.empty() && next.settings.presets.empty()}};
}
}
