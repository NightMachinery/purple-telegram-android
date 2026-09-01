/*
Purple Telegram for Android - JNI bridge to purple-core.

The core (TMessagesProj/jni/purple, a submodule of
https://github.com/NightMachinery/purple-core) is plain C++20 over Qt Core and
knows nothing about Android. This file is the only place that knows about both,
which is why it lives outside the submodule: the submodule stays pristine and
shared with the desktop fork.

Licensed under the GNU General Public License, version 2 or (at your option)
any later version.
*/
#include <jni.h>

#include "purple/purple_settings.h"

#include <QtCore/QChar>
#include <QtCore/QString>

namespace {

// JSON string escaping, by hand. Pulling in a JSON library for four fields
// would cost more than it saves, and the parser on the Java side is
// org.json, which wants exactly this.
void AppendJsonString(QString &out, const QString &value) {
	out += QChar('"');
	for (const QChar ch : value) {
		const auto unicode = ch.unicode();
		switch (unicode) {
		case '"': out += QStringLiteral("\\\""); continue;
		case '\\': out += QStringLiteral("\\\\"); continue;
		case '\b': out += QStringLiteral("\\b"); continue;
		case '\f': out += QStringLiteral("\\f"); continue;
		case '\n': out += QStringLiteral("\\n"); continue;
		case '\r': out += QStringLiteral("\\r"); continue;
		case '\t': out += QStringLiteral("\\t"); continue;
		}
		if (unicode < 0x20) {
			static const char kHex[] = "0123456789abcdef";
			out += QStringLiteral("\\u00");
			out += QChar(kHex[(unicode >> 4) & 0xF]);
			out += QChar(kHex[unicode & 0xF]);
		} else {
			out += ch;
		}
	}
	out += QChar('"');
}

[[nodiscard]] QString ResultJson(const Purple::ParseResult &result) {
	auto json = QString();
	json.reserve(256);
	json += QStringLiteral("{\"ok\":");
	json += result.ok() ? QStringLiteral("true") : QStringLiteral("false");
	json += QStringLiteral(",\"version\":");
	json += QString::number(result.settings.version);
	json += QStringLiteral(",\"error\":");
	AppendJsonString(json, result.error);
	json += QStringLiteral(",\"warnings\":[");
	auto first = true;
	for (const auto &warning : result.warnings) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		AppendJsonString(json, warning);
	}
	json += QStringLiteral("]}");
	return json;
}

[[nodiscard]] jstring ToJava(JNIEnv *env, const QString &text) {
	return env->NewString(
		reinterpret_cast<const jchar*>(text.utf16()),
		jsize(text.size()));
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_parseSettings(
		JNIEnv *env,
		jclass,
		jbyteArray utf8) {
	auto text = QString();
	if (utf8) {
		const auto length = env->GetArrayLength(utf8);
		if (length > 0) {
			auto *bytes = env->GetByteArrayElements(utf8, nullptr);
			if (!bytes) {
				return ToJava(env, QStringLiteral(
					"{\"ok\":false,\"version\":0,"
					"\"error\":\"out of memory reading settings\","
					"\"warnings\":[]}"));
			}
			text = QString::fromUtf8(
				reinterpret_cast<const char*>(bytes),
				int(length));
			env->ReleaseByteArrayElements(utf8, bytes, JNI_ABORT);
		}
	}
	const auto result = Purple::ParseSettings(
		text,
		QStringLiteral("settings.toml"));
	return ToJava(env, ResultJson(result));
}
