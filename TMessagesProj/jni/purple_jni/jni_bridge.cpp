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

#include "purple/purple_engine.h"
#include "purple/purple_settings.h"
#include "purple/purple_state.h"

#include <QtCore/QChar>
#include <QtCore/QString>

#include <mutex>

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

void AppendJsonBool(QString &out, bool value) {
	out += value ? QStringLiteral("true") : QStringLiteral("false");
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

// A null or empty array is a file that is not there, which is not an error -
// both settings.toml and state.toml are absent on a fresh install. False means
// the VM could not hand the bytes over, and it has thrown by the time it says
// so.
[[nodiscard]] bool ReadUtf8(JNIEnv *env, jbyteArray array, QString &out) {
	out = QString();
	if (!array) {
		return true;
	}
	const auto length = env->GetArrayLength(array);
	if (length <= 0) {
		return true;
	}
	auto *bytes = env->GetByteArrayElements(array, nullptr);
	if (!bytes) {
		return false;
	}
	out = QString::fromUtf8(reinterpret_cast<const char*>(bytes), int(length));
	env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
	return true;
}

[[nodiscard]] QString FromJava(JNIEnv *env, jstring text) {
	if (!text) {
		return QString();
	}
	const auto length = env->GetStringLength(text);
	const auto *chars = env->GetStringChars(text, nullptr);
	if (!chars) {
		return QString();
	}
	auto result = QString::fromUtf16(
		reinterpret_cast<const char16_t*>(chars),
		int(length));
	env->ReleaseStringChars(text, chars);
	return result;
}

// The live half of the gate: the settings and state last loaded, and the
// resolution in force. One per process, because there is one configuration per
// process and the chat list asks about it from whatever thread it is on.
//
// The desktop keeps the same three next to each other in Purple::Gate; here
// Java owns the files and the reload policy, and this is only the part that has
// to survive between calls.
struct Gate {
	std::mutex mutex;
	Purple::Settings settings;
	Purple::State state;
	Purple::Resolved resolved;
	bool loaded = false;
};

[[nodiscard]] Gate &TheGate() {
	static auto result = Gate();
	return result;
}

// What a preset does, in the numbers the preset picker prints. Mirrors
// Summary() in the desktop's purple_preset_box.cpp, entry for entry: an entry
// that hides its chats is not counted at all, and an entry that said nothing
// about `show' counts as gated, because the per-kind defaults gate everything
// except channels and calling it ungated would be the wrong way round for most
// of a file.
struct PresetCounts {
	bool resolves = false;
	int lists = 0;
	int letsThrough = 0;
	int silences = 0;
	int gated = 0;
};

[[nodiscard]] PresetCounts Count(const Purple::Resolved &resolved) {
	auto result = PresetCounts();
	result.resolves = true;
	result.lists = int(resolved.lists.size());
	for (const auto &list : resolved.lists) {
		if (list.show == Purple::ShowMode::Never) {
			continue;
		}
		++result.letsThrough;
		if (!list.notify) {
			++result.silences;
		}
		if (!list.show || Purple::ShowModeWatchesUnread(*list.show)) {
			++result.gated;
		}
	}
	return result;
}

// Whether the strip the preset asks for is anything other than the account's
// own folders in the account's own order. Mirrors the desktop's
// Purple::FoldersRestricted(): while this is true a strip index no longer means
// the same thing as a server-side position, so folder reordering has to refuse.
[[nodiscard]] bool FoldersRestricted(const Purple::Resolved &resolved) {
	if (resolved.normal) {
		return false;
	} else if (!resolved.views.empty()) {
		// Extra views sit on the strip too, so a strip index is no longer a
		// folder index. Not ported to Android yet; checked anyway, so the day
		// they are the guard is already right.
		return true;
	}
	// "*ALL" on its own is every folder in the account's own order, so nothing
	// is restricted and reordering stays safe. Any other shape - a subset, a
	// chosen order, a folder carrying flags - means it is not.
	const auto &folders = resolved.folders;
	return (folders.size() != 1)
		|| !Purple::IsAllFolders(folders.front())
		|| folders.front().show.has_value()
		|| folders.front().notify.has_value()
		|| folders.front().include.has_value();
}

// The folder selection, as the strip needs it: the name to match, whether the
// entry is the "*ALL" marker, and the two flags that decide whether it puts a
// tab up. Still deliberately not badge_p or include - nothing on Android
// consumes those yet, and a field the bridge hands over is a field somebody
// will assume works. notify_p travels separately, as the resolved name list
// below, because that is the shape its consumer wants.
void AppendFoldersJson(QString &json, const Purple::Resolved &resolved) {
	json += QStringLiteral("[");
	auto first = true;
	for (const auto &folder : resolved.folders) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QStringLiteral("{\"name\":");
		AppendJsonString(json, folder.name);
		json += QStringLiteral(",\"all\":");
		AppendJsonBool(json, Purple::IsAllFolders(folder));
		json += QStringLiteral(",\"enabled\":");
		AppendJsonBool(json, Purple::FolderEnabled(folder));
		json += QStringLiteral(",\"show\":");
		AppendJsonBool(json, folder.show.value_or(true));
		json += QChar('}');
	}
	json += QChar(']');
}

// The folders a preset silenced, by name, already filtered to the enabled ones
// by Purple::SilencedFolderNames(). Names rather than entries because that is
// all the answer needs: the Java side matches them against the account's real
// folder titles and asks each match whether it holds the chat.
//
// A "*ALL" entry cannot appear here in practice - the marker is a bare string
// and a flag-carrying entry is a table - and if one ever did it would match no
// real folder title, which is the same thing the desktop does with it.
void AppendNamesJson(QString &json, const std::vector<QString> &names) {
	json += QChar('[');
	auto first = true;
	for (const auto &name : names) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		AppendJsonString(json, name);
	}
	json += QChar(']');
}

void AppendPresetJson(
		QString &json,
		const Purple::Settings &settings,
		const Purple::Preset &preset) {
	const auto resolved = Purple::Resolve(settings, preset.name);
	const auto counts = resolved ? Count(*resolved) : PresetCounts();
	json += QStringLiteral("{\"name\":");
	AppendJsonString(json, preset.name);
	json += QStringLiteral(",\"title\":");
	AppendJsonString(json, Purple::PresetTitle(preset));
	json += QStringLiteral(",\"resolves\":");
	AppendJsonBool(json, counts.resolves);
	json += QStringLiteral(",\"lists\":");
	json += QString::number(counts.lists);
	json += QStringLiteral(",\"letsThrough\":");
	json += QString::number(counts.letsThrough);
	json += QStringLiteral(",\"silences\":");
	json += QString::number(counts.silences);
	json += QStringLiteral(",\"gated\":");
	json += QString::number(counts.gated);
	json += QChar('}');
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_parseSettings(
		JNIEnv *env,
		jclass,
		jbyteArray utf8) {
	auto text = QString();
	if (!ReadUtf8(env, utf8, text)) {
		return ToJava(env, QStringLiteral(
			"{\"ok\":false,\"version\":0,"
			"\"error\":\"out of memory reading settings\","
			"\"warnings\":[]}"));
	}
	const auto result = Purple::ParseSettings(
		text,
		QStringLiteral("settings.toml"));
	return ToJava(env, ResultJson(result));
}

// Reloads both files and resolves the active preset, exactly as the desktop's
// Gate::refresh() does - including its one rule that matters more than the
// rest: an active preset that no longer resolves falls back to the last
// resolution that worked, never to Normal. Falling back to Normal would unhide
// every chat the user hid, over a typo in a preset name.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_loadNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jbyteArray stateUtf8) {
	auto settingsText = QString();
	auto stateText = QString();
	if (!ReadUtf8(env, settingsUtf8, settingsText)
		|| !ReadUtf8(env, stateUtf8, stateText)) {
		return ToJava(env, QStringLiteral(
			"{\"ok\":false,\"version\":0,"
			"\"error\":\"out of memory reading the configuration\","
			"\"warnings\":[],\"normal\":true,\"preset\":\"normal\","
			"\"title\":\"Normal\",\"lists\":0,\"usedCache\":false,"
			"\"cacheReason\":\"\",\"activeMissing\":false,"
			"\"foldersRestricted\":false,\"folders\":[],"
			"\"presets\":[],\"stateText\":null}"));
	}

	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);

	const auto parsed = Purple::ParseSettings(
		settingsText,
		QStringLiteral("settings.toml"));
	if (parsed.ok() || !gate.loaded) {
		// A file that will not parse leaves whatever was already running in
		// place, for the same reason the resolution falls back rather than
		// defaulting: a half-typed edit must not unhide anything. With nothing
		// loaded yet there is nothing to keep, and ParseSettings() left default
		// settings behind for exactly that case.
		gate.settings = parsed.settings;
	}
	gate.state = Purple::ParseState(stateText, QStringLiteral("state.toml"));

	const auto &normal = Purple::NormalPreset();
	const auto wanted = gate.state.activePreset.isEmpty()
		? normal
		: gate.state.activePreset;
	const auto wantsNormal = !wanted.compare(normal, Qt::CaseInsensitive);
	const auto activeMissing = !wantsNormal
		&& !gate.settings.preset(wanted);

	auto usedCache = false;
	auto cacheReason = QString();
	auto next = Purple::Resolve(gate.settings, wanted);
	if (!next && !wantsNormal) {
		cacheReason = parsed.ok()
			? QStringLiteral("preset '%1' not in settings.toml").arg(wanted)
			: parsed.error;
		next = Purple::FromCache(gate.state.resolvedCache);
		if (next) {
			usedCache = true;
		} else if (gate.loaded) {
			next = gate.resolved;
		} else {
			next = Purple::Resolve(gate.settings, normal);
		}
	}
	if (!next) {
		// Unreachable: Resolve() answers for Normal unconditionally.
		next = Purple::Resolved();
		next->preset = normal;
		next->viewName = QStringLiteral("Normal");
		next->normal = true;
	}
	gate.resolved = *next;
	gate.loaded = true;

	// Only ever widened, never cleared: a resolution we could not compute is
	// exactly when the cache has to still be there.
	if (!gate.resolved.normal) {
		gate.state.resolvedCache = Purple::ToCache(gate.resolved);
	}
	const auto serialized = Purple::SerializeState(gate.state);
	const auto rewrite = (serialized != stateText);

	const auto counts = Count(gate.resolved);
	auto json = QString();
	json.reserve(512);
	json += QStringLiteral("{\"ok\":");
	AppendJsonBool(json, parsed.ok());
	json += QStringLiteral(",\"error\":");
	AppendJsonString(json, parsed.error);
	json += QStringLiteral(",\"warnings\":[");
	auto first = true;
	for (const auto &warning : parsed.warnings) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		AppendJsonString(json, warning);
	}
	json += QStringLiteral("],\"version\":");
	json += QString::number(gate.settings.version);
	json += QStringLiteral(",\"normal\":");
	AppendJsonBool(json, gate.resolved.normal);
	json += QStringLiteral(",\"preset\":");
	AppendJsonString(json, gate.resolved.preset);
	json += QStringLiteral(",\"title\":");
	AppendJsonString(
		json,
		Purple::PresetTitle(gate.resolved.preset, gate.resolved.viewName));
	json += QStringLiteral(",\"lists\":");
	json += QString::number(counts.lists);
	json += QStringLiteral(",\"usedCache\":");
	AppendJsonBool(json, usedCache);
	json += QStringLiteral(",\"cacheReason\":");
	AppendJsonString(json, cacheReason);
	json += QStringLiteral(",\"activeMissing\":");
	AppendJsonBool(json, activeMissing);
	json += QStringLiteral(",\"foldersRestricted\":");
	AppendJsonBool(json, FoldersRestricted(gate.resolved));
	json += QStringLiteral(",\"folders\":");
	AppendFoldersJson(json, gate.resolved);
	json += QStringLiteral(",\"silencedFolders\":");
	AppendNamesJson(json, gate.resolved.silencedFolders);
	json += QStringLiteral(",\"presets\":[");
	first = true;
	for (const auto &preset : gate.settings.presets) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		AppendPresetJson(json, gate.settings, preset);
	}
	json += QStringLiteral("],\"stateText\":");
	if (rewrite) {
		AppendJsonString(json, serialized);
	} else {
		json += QStringLiteral("null");
	}
	json += QChar('}');
	return ToJava(env, json);
}

// The per-chat question, asked once per row per rebuild, so it answers from the
// resolution already in hand and never parses anything.
//
// One int rather than an object because a JNI object allocation per chat is the
// one cost this path cannot carry: the show mode is the low nibble and the
// notify flag is 0x10.
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleCore_visibleNative(
		JNIEnv *,
		jclass,
		jlong bareId,
		jint kind) {
	// The Java constants are these enumerators by value. Reordering either enum
	// in the core would silently turn every group into a private chat, so the
	// mapping is checked here rather than trusted.
	static_assert(int(Purple::ChatKind::Private) == 0);
	static_assert(int(Purple::ChatKind::Group) == 1);
	static_assert(int(Purple::ChatKind::Channel) == 2);
	static_assert(int(Purple::ChatKind::Bot) == 3);
	static_assert(int(Purple::ShowMode::Always) == 0);
	static_assert(int(Purple::ShowMode::Message) == 1);
	static_assert(int(Purple::ShowMode::MessageOrReaction) == 2);
	static_assert(int(Purple::ShowMode::Mention) == 3);
	static_assert(int(Purple::ShowMode::Never) == 4);

	constexpr auto kNotifyBit = jint(0x10);
	constexpr auto kStock = jint(int(Purple::ShowMode::Always)) | kNotifyBit;

	if (kind < 0 || kind > int(Purple::ChatKind::Bot)) {
		// A kind the core cannot be asked about. Answering "shown and audible"
		// is the harmless direction: the alternative is hiding a chat because
		// the caller mislabelled it.
		return kStock;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return kStock;
	}
	const auto visibility = Purple::Visible(
		gate.settings,
		gate.resolved,
		Purple::PeerIdValue(bareId),
		Purple::ChatKind(kind));
	return jint(int(visibility.show))
		| (visibility.notify ? kNotifyBit : jint(0));
}

// Turns a preset on, as a pure function of the state text: the caller writes
// what comes back and calls load() again, which is what makes the file and the
// resolution move together. It touches nothing shared, so it takes no lock -
// serialising a preset switch against a reload would only make the caller wait
// for a state it is about to replace anyway.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setPresetNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jstring preset) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	const auto name = FromJava(env, preset);
	state.activePreset = (name.isEmpty()
		|| !name.compare(Purple::NormalPreset(), Qt::CaseInsensitive))
		? Purple::NormalPreset()
		: name;
	state.activeSource = Purple::PresetSource::Manual;
	return ToJava(env, Purple::SerializeState(state));
}

// Android calls JNI_OnLoad after loading a library, and it finds the symbol
// with dlsym on the library handle - a search that reaches this library's
// dependencies too. Qt Core is one of those, and Qt's JNI_OnLoad expects to be
// started by a Qt activity with the org.qtproject.qt.android classes present;
// in an app that only borrows QString it answers JNI_ERR and the load throws.
// Defining our own here means the search stops at this library, so Qt's is
// never reached. It does nothing but name the JNI version it was built for.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	return JNI_VERSION_1_6;
}
