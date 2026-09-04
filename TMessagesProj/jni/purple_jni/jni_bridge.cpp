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
#include "purple/purple_splice.h"
#include "purple/purple_state.h"

#include <QtCore/QChar>
#include <QtCore/QDateTime>
#include <QtCore/QString>

#include <map>
#include <mutex>
#include <optional>

namespace {

// Local wall-clock seconds, matching what peek_deadline_unix holds and what
// the schedule compares against. Not the server clock: a two-minute peek has
// to expire while offline too, and a nine-to-five window is about the wall the
// user is looking at.
[[nodiscard]] int64 NowUnix() {
	return QDateTime::currentSecsSinceEpoch();
}

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
// tab up. notify_p, badge_p and include travel separately, as the resolved
// lists below, because that is the shape their consumers want - the strip cares
// which folders it draws, and they care which chats a folder holds.
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

// The folders that pull their chats into the preset's view, with everything the
// decision needs: how much of the folder comes, and what mode its chats take
// once in. A folder that names no mode leaves them to the default for whatever
// they are, which is what `defaultModes' below is for - the folder chose WHICH
// chats come in, not WHEN they show, and forcing "always" here would be the
// folder answering a question it was not asked.
void AppendExemptFoldersJson(QString &json, const Purple::Resolved &resolved) {
	json += QChar('[');
	auto first = true;
	for (const auto &folder : resolved.exemptFolders) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QStringLiteral("{\"name\":");
		AppendJsonString(json, folder.name);
		json += QStringLiteral(",\"pinned\":");
		AppendJsonBool(json, folder.include == Purple::FolderInclude::Pinned);
		json += QStringLiteral(",\"showMode\":");
		// -1 for "said nothing", which is not the same as any mode: the Java
		// side substitutes the default for the chat's kind.
		json += QString::number(folder.showMode
			? int(*folder.showMode)
			: -1);
		json += QChar('}');
	}
	json += QChar(']');
}

// DefaultShowMode() for each ChatKind, by kind index. Handed over rather than
// exposed as a second native: it is four numbers that only change when the core
// does, and a chat asking for its own default is on the row-drawing path.
void AppendDefaultModesJson(QString &json) {
	static_assert(int(Purple::ChatKind::Private) == 0);
	static_assert(int(Purple::ChatKind::Group) == 1);
	static_assert(int(Purple::ChatKind::Channel) == 2);
	static_assert(int(Purple::ChatKind::Bot) == 3);
	const auto kinds = {
		Purple::ChatKind::Private,
		Purple::ChatKind::Group,
		Purple::ChatKind::Channel,
		Purple::ChatKind::Bot,
	};
	json += QChar('[');
	auto first = true;
	for (const auto kind : kinds) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QString::number(int(Purple::DefaultShowMode(kind)));
	}
	json += QChar(']');
}

// {"12345":"Some Chat"} as the splice's naming callback wants it. Hand-rolled
// rather than pulled through a JSON library: the object is flat, the keys are
// decimal ids, and the values are the only place quoting matters.
[[nodiscard]] std::map<Purple::PeerIdValue, QString> ParseTitles(
		const QString &json) {
	auto result = std::map<Purple::PeerIdValue, QString>();
	auto i = 0;
	const auto size = json.size();
	const auto readString = [&]() -> std::optional<QString> {
		while (i < size && json[i] != QChar('"')) {
			if (json[i] == QChar('}')) {
				return std::nullopt;
			}
			++i;
		}
		if (i >= size) {
			return std::nullopt;
		}
		++i;
		auto out = QString();
		while (i < size && json[i] != QChar('"')) {
			if (json[i] == QChar('\\') && i + 1 < size) {
				++i;
				const auto c = json[i];
				out += (c == QChar('n')) ? QChar('\n')
					: (c == QChar('t')) ? QChar('\t')
					: c;
			} else {
				out += json[i];
			}
			++i;
		}
		++i;
		return out;
	};
	while (i < size) {
		const auto key = readString();
		if (!key) {
			break;
		}
		const auto value = readString();
		if (!value) {
			break;
		}
		auto ok = false;
		const auto id = key->toLongLong(&ok);
		if (ok) {
			result.emplace(Purple::PeerIdValue(id), *value);
		}
	}
	return result;
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

// The preset's extra tabs, in strip order, as [{"name","pinned":[ids]}].
//
// Membership is not here: it travels one bit per view on the per-chat answer,
// which is the only shape that path can afford. What a view needs handing over
// is what the tab is called and what it pins - both of which the file owns,
// because nothing on the server has heard of a tab you invented.
void AppendViewsJson(QString &out, const Purple::Resolved &resolved) {
	out += QChar('[');
	auto first = true;
	for (const auto &view : resolved.views) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"name\":");
		AppendJsonString(out, view.name);
		out += QStringLiteral(",\"pinned\":[");
		auto firstPin = true;
		for (const auto pinned : view.pinned) {
			if (!firstPin) {
				out += QChar(',');
			}
			firstPin = false;
			out += QString::number(qint64(pinned));
		}
		out += QStringLiteral("]}");
	}
	out += QChar(']');
}

// The "until" decisions in force under this resolution, as
// [{"peer","kind","until"}]. Expired entries and entries made under another
// preset are already invisible to OverrideFor(), and the walk below applies the
// same two tests so the Java side never has to know either rule.
void AppendOverridesJson(
		QString &out,
		const Purple::State &state,
		const Purple::Resolved &resolved) {
	out += QChar('[');
	if (resolved.normal) {
		// Normal is a bypass, and an override is a statement about a preset.
		out += QChar(']');
		return;
	}
	const auto now = NowUnix();
	auto first = true;
	for (const auto &entry : state.overrides) {
		if (entry.untilUnix <= now
			|| entry.preset.compare(resolved.preset, Qt::CaseInsensitive)) {
			continue;
		}
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"peer\":");
		out += QString::number(qint64(entry.peer));
		out += QStringLiteral(",\"kind\":");
		out += QString::number(int(entry.kind));
		out += QStringLiteral(",\"until\":");
		out += QString::number(qint64(entry.untilUnix));
		out += QChar('}');
	}
	out += QChar(']');
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

	// Peek suspends the hiding of whatever resolution is in force; it is not
	// part of the resolution, which is why it is applied here rather than in
	// Resolve(). ToCache() below has no field for it, and that is what keeps a
	// peek out of the fallback: a cached resolution restored with one in it
	// would come back revealed, with nothing left running to put it back.
	//
	// Everything else follows for free. peeking is a field of Resolved, so
	// Visible() already answers ShowMode::Always for every chat while one is
	// running, and visibleNative asks it the same question it always did.
	gate.resolved.peeking = !gate.resolved.normal
		&& Purple::PeekLive(gate.state, NowUnix());
	if (!gate.resolved.peeking && gate.state.peekActive) {
		// A peek that outlived the app, or the preset it was revealing. Cleared
		// rather than left in the file claiming a peek that is not running,
		// because that flag is what the next press reads to decide which way to
		// toggle. The rewrite below carries it to disk.
		gate.state.peekActive = false;
		gate.state.peekDeadlineUnix = 0;
	}

	// The "until" decisions expire the same way, and are pruned here for the
	// same reason: this is the one place that already rewrites state.toml, so
	// an entry that has run out leaves the file at the moment it stops being
	// true rather than at the next unrelated change.
	Purple::PruneOverrides(gate.state, NowUnix());

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
	json += QStringLiteral(",\"peeking\":");
	AppendJsonBool(json, gate.resolved.peeking);
	// Zero means auto_off is turned off, so the peek runs until it is turned
	// off by hand - which is why the countdown and the deadline are separate
	// questions rather than one number that happens to be missing.
	json += QStringLiteral(",\"peekDeadline\":");
	json += QString::number(qint64(gate.state.peekDeadlineUnix));
	json += QStringLiteral(",\"peekSeconds\":");
	json += QString::number(gate.settings.peek.autoOffSeconds);
	json += QStringLiteral(",\"schedulePaused\":");
	AppendJsonBool(json, gate.state.schedulePaused);
	// A switch that holds off nothing explains nothing, so the pause row is
	// there only when the file describes a schedule at all.
	json += QStringLiteral(",\"scheduleConfigured\":");
	AppendJsonBool(json, !gate.settings.schedule.rules.empty());
	// Handed over whole rather than asked per chat, exactly as the exempt
	// folders are: shown() runs once per row per rebuild, and a JNI call with a
	// state parse behind it is the one thing that path cannot carry. Only the
	// running preset's live ones, so the Java side is a lookup and a clock
	// comparison with nothing to filter.
	json += QStringLiteral(",\"overrides\":");
	AppendOverridesJson(json, gate.state, gate.resolved);
	json += QStringLiteral(",\"nextOverrideDeadline\":");
	json += QString::number(qint64(gate.resolved.normal
		? 0
		: Purple::NextOverrideDeadline(gate.state, gate.resolved.preset)));
	json += QStringLiteral(",\"hideScope\":");
	json += QString::number(int(gate.settings.overrides.hideScope));
	// Read on every row while a chat is in its grace period, so it travels with
	// the rest rather than being asked for. Zero disables it, and the Java side
	// re-reads it on every query - which is what makes turning [recent] off take
	// effect at once rather than at the end of whatever was already running.
	json += QStringLiteral(",\"recentSeconds\":");
	json += QString::number(gate.settings.recent.staySecondsAfterClose);
	json += QStringLiteral(",\"recentScope\":");
	json += QString::number(int(gate.settings.recent.scope));
	// The Premium gates this client is the only thing enforcing. A file that
	// never mentions [premium] means on, which is the core's default and the
	// desktop's, so one settings.toml still means one thing in both clients.
	json += QStringLiteral(",\"premium\":");
	json += (gate.settings.premium.enabled
		? QStringLiteral("true")
		: QStringLiteral("false"));
	json += QStringLiteral(",\"views\":");
	AppendViewsJson(json, gate.resolved);
	// Every list in the file, not the running preset's - the membership menu
	// offers all of them, and under `normal' the preset's own count is zero.
	json += QStringLiteral(",\"listCount\":");
	json += QString::number(int(gate.settings.lists.size()));
	json += QStringLiteral(",\"folders\":");
	AppendFoldersJson(json, gate.resolved);
	json += QStringLiteral(",\"silencedFolders\":");
	AppendNamesJson(json, gate.resolved.silencedFolders);
	json += QStringLiteral(",\"quietFolders\":");
	AppendNamesJson(json, gate.resolved.quietFolders);
	json += QStringLiteral(",\"exemptFolders\":");
	AppendExemptFoldersJson(json, gate.resolved);
	json += QStringLiteral(",\"defaultModes\":");
	AppendDefaultModesJson(json);
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
	constexpr auto kViewShift = 8;
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
	const auto id = Purple::PeerIdValue(bareId);
	const auto visibility = Purple::Visible(
		gate.settings,
		gate.resolved,
		id,
		Purple::ChatKind(kind));

	// Which extra views hold this chat, one bit each, riding home on the same
	// int rather than through a native of their own. The Java side caches this
	// value per chat and clears the cache on every reload, so a tab's
	// membership costs a shift and a mask on a path that would otherwise pay a
	// JNI call per chat per view per sort. Sixteen is the core's own view
	// limit, so the field cannot overflow.
	auto views = jint(0);
	for (auto i = 0, count = int(gate.resolved.views.size()); i != count; ++i) {
		if (Purple::ViewHolds(
				gate.settings,
				gate.resolved.views[i],
				id,
				Purple::ChatKind(kind))) {
			views |= (jint(1) << i);
		}
	}
	return jint(int(visibility.show))
		| (visibility.notify ? kNotifyBit : jint(0))
		| (views << kViewShift);
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

// Starts or ends a peek, mirroring the desktop's Purple::TogglePeek().
//
// Unlike setPresetNative this one does take the lock, because the answer
// depends on the running resolution rather than only on the file: a peek over
// Normal has nothing to reveal, and starting one anyway would leave a peek
// running that no chat list could show the end of.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_togglePeekNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return ToJava(env, QStringLiteral(
			"{\"refused\":true,\"peeking\":false,\"seconds\":0,\"text\":null}"));
	}
	const auto wanted = !gate.resolved.peeking;
	const auto seconds = wanted ? gate.settings.peek.autoOffSeconds : 0;
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	state.peekActive = wanted;
	state.peekDeadlineUnix = (seconds > 0) ? (NowUnix() + seconds) : 0;

	auto json = QStringLiteral("{\"refused\":false,\"peeking\":");
	AppendJsonBool(json, wanted);
	json += QStringLiteral(",\"seconds\":");
	json += QString::number(seconds);
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, Purple::SerializeState(state));
	json += QChar('}');
	return ToJava(env, json);
}

// Holds the schedule off, or lets it catch up again. A decision about today
// rather than about the configuration, which is why it lives in state.toml and
// nothing in settings.toml turns it on.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setSchedulePausedNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jboolean paused) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	state.schedulePaused = (paused == JNI_TRUE);
	return ToJava(env, Purple::SerializeState(state));
}

// One tick of the schedule, mirroring the desktop's Purple::Runner::tick().
//
// A pure function of the settings already loaded, the state text handed in and
// the wall clock, so the caller can run it as often as it likes: null comes
// back whenever there is nothing to write, which is every tick but the ones at
// a boundary.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_scheduleTickNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	if (state.schedulePaused) {
		return nullptr;
	}
	const auto target = Purple::ScheduleTarget(
		gate.settings.schedule,
		QDateTime::currentDateTime());
	if (!target || *target == state.scheduleTarget) {
		// Acting on the change rather than on the value is the whole design.
		// It is what lets a preset chosen by hand stand until the next boundary
		// instead of being overwritten on the next tick, and what makes a
		// boundary missed while the app was closed still happen, once, at the
		// next launch.
		return nullptr;
	}
	const auto wanted = *target;
	const auto source = state.activeSource;
	const auto active = state.activePreset;

	// Two rules, and the asymmetry between them is deliberate. A window
	// starting is a positive instruction - "at nine, work mode" - and it
	// overrides a preset chosen by hand. A window ending only means the reason
	// for that preset has passed, which is no reason at all to undo something
	// asked for. Focus is left alone in both directions: it is the more
	// immediate signal, and a schedule fighting it would make both unreadable.
	const auto apply = (source != Purple::PresetSource::Focus)
		&& (wanted != Purple::NormalPreset()
			|| source == Purple::PresetSource::Schedule);
	state.scheduleTarget = wanted;
	if (apply) {
		state.activePreset = wanted;
		state.activeSource = Purple::PresetSource::Schedule;
	}

	auto json = QStringLiteral("{\"applied\":");
	AppendJsonBool(json, apply);
	json += QStringLiteral(",\"target\":");
	AppendJsonString(json, wanted);
	json += QStringLiteral(",\"kept\":");
	AppendJsonString(json, apply ? QString() : active);
	json += QStringLiteral(",\"keptSource\":");
	AppendJsonString(
		json,
		apply ? QString() : Purple::PresetSourceName(source));
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, Purple::SerializeState(state));
	json += QChar('}');
	return ToJava(env, json);
}

// Makes, replaces or clears one "until" decision, mirroring the desktop's
// Purple::SetOverride().
//
// Takes the lock because the answer is scoped to the running preset rather than
// to the file: the same chat can carry a different decision under each one, and
// which one is being written is a property of what is resolved right now.
//
// Zero seconds is the cancel, which is why there is no separate native for it -
// the desktop spells it the same way, as a SetOverride() that stores nothing.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setOverrideNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jlong bareId,
		jint kind,
		jint seconds) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	const auto id = Purple::PeerIdValue(bareId);
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!id || !gate.loaded || gate.resolved.normal) {
		return nullptr;
	}
	const auto preset = gate.resolved.preset;
	const auto until = NowUnix() + seconds;
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));

	// One per chat per preset: a second "until" replaces the first rather than
	// queueing behind it, because the menu offers a decision and not a schedule.
	auto kept = std::vector<Purple::Override>();
	kept.reserve(state.overrides.size() + 1);
	for (auto &entry : state.overrides) {
		if (entry.peer != id
			|| entry.preset.compare(preset, Qt::CaseInsensitive)) {
			kept.push_back(std::move(entry));
		}
	}
	if (seconds > 0) {
		kept.push_back({
			id,
			Purple::OverrideKind(kind),
			until,
			until - seconds,
			preset,
		});
	}
	state.overrides = std::move(kept);
	return ToJava(env, Purple::SerializeState(state));
}

// Which of the preset's entries is deciding this chat, by the title its list
// carries. Null means nothing claimed it, which is the fall-through the caller
// has to name differently - "in no list this view names" rather than "in none".
//
// Answered from the resolution rather than from the file, unlike listsForNative
// above: this is a question about what is happening now, and a list the preset
// does not name has no say in it however many members it has.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_deciderNative(
		JNIEnv *env,
		jclass,
		jlong bareId,
		jint kind) {
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return nullptr;
	}
	const auto effective = Purple::MatchList(
		gate.settings,
		gate.resolved,
		Purple::PeerIdValue(bareId),
		Purple::ChatKind(kind));
	if (!effective) {
		return nullptr;
	}
	const auto list = gate.settings.list(effective->list);
	if (!list) {
		// The resolution named a list the file no longer has, which is what a
		// half-finished edit looks like. The key is still the honest answer.
		return ToJava(env, effective->list);
	}
	return ToJava(env, list->title.isEmpty() ? list->name : list->title);
}

// The lists a chat could be put in, and which of them already hold it.
//
// Parses the text it is handed rather than reading the loaded gate, the same
// way setPresetNative does: the menu is about the file on disk, and answering
// from a resolution that was loaded some time ago would offer to add a chat to
// a list that has since been renamed.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_listsForNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jlong bareId) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	const auto parsed = Purple::ParseSettings(
		text,
		QStringLiteral("settings.toml"));
	const auto id = Purple::PeerIdValue(bareId);
	auto json = QStringLiteral("[");
	auto first = true;
	for (const auto &list : parsed.settings.lists) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QStringLiteral("{\"name\":");
		AppendJsonString(json, list.name);
		json += QStringLiteral(",\"title\":");
		AppendJsonString(json, list.title.isEmpty() ? list.name : list.title);
		json += QStringLiteral(",\"member\":");
		auto member = false;
		for (const auto held : list.members) {
			if (held == id) {
				member = true;
				break;
			}
		}
		AppendJsonBool(json, member);
		// The ids too, so the caller can name every line the splice might
		// rewrite. Converting an inline array to one line per member rewrites
		// all of them, and a comment regenerated without a name would silently
		// drop the one that was there.
		json += QStringLiteral(",\"members\":[");
		auto firstId = true;
		for (const auto held : list.members) {
			if (!firstId) {
				json += QChar(',');
			}
			firstId = false;
			json += QString::number(qlonglong(held));
		}
		json += QChar(']');
		json += QChar('}');
	}
	json += QChar(']');
	return ToJava(env, json);
}

// Adds or removes one member, through the splice rather than by re-serialising:
// the file is hand-owned and its comments are the point of it being TOML at all.
//
// The C++ side takes a callback to name each line it rewrites. Calling back into
// Java per id from here would mean holding a JNIEnv across the splice, so the
// caller hands over the names it already knows as a JSON object instead, and a
// name it does not have falls back to the bare id - which is what the desktop
// shows for a peer it cannot resolve either.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_spliceMemberNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring listName,
		jlong bareId,
		jboolean add,
		jstring titlesJson) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	const auto titles = ParseTitles(FromJava(env, titlesJson));
	const auto naming = [&](Purple::PeerIdValue id) {
		const auto i = titles.find(id);
		return (i != titles.end()) ? i->second : QString::number(id);
	};
	const auto path = QStringLiteral("settings.toml");
	const auto list = FromJava(env, listName);
	const auto id = Purple::PeerIdValue(bareId);
	const auto result = add
		? Purple::AddListMember(text, path, list, id, naming)
		: Purple::RemoveListMember(text, path, list, id, naming);

	auto json = QStringLiteral("{\"changed\":");
	AppendJsonBool(json, result.changed);
	json += QStringLiteral(",\"error\":");
	AppendJsonString(json, result.error);
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, result.ok() ? result.text : QString());
	json += QChar('}');
	return ToJava(env, json);
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
