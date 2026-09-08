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

#include <QtCore/QByteArray>
#include <QtCore/QChar>
#include <QtCore/QDateTime>
#include <QtCore/QString>

#include <algorithm>
#include <map>
#include <mutex>
#include <optional>
#include <vector>

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

// The same bytes, undecoded. The auto-send fingerprint is a hash of the file
// exactly as it sits on disk, so it has to be taken over these and not over a
// QString that has already been through UTF-8: a byte sequence the decoder
// repaired would hash as the repair rather than as the file, and the other
// device - which hashed the real bytes - would never agree with it.
[[nodiscard]] bool ReadRaw(JNIEnv *env, jbyteArray array, QByteArray &out) {
	out = QByteArray();
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
	out = QByteArray(reinterpret_cast<const char*>(bytes), int(length));
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

	// What this install reports itself as, so a ruleset can name it. Handed
	// over by loadNative rather than worked out here: the id is a hash of an
	// Android identifier and the platform is a fact about the build, neither of
	// which the core or this file has any way to ask for.
	//
	// Kept because a caller that has no identity of its own to offer still has
	// to resolve the same schedule this device is running - see focusTickNative.
	Purple::DeviceIdentity device;
};

[[nodiscard]] Gate &TheGate() {
	static auto result = Gate();
	return result;
}

// How long a read stays worth showing, from the loaded file.
//
// The trade natives are handed state.toml and nothing else - the caller owns
// that file and the write - so the one setting they need comes from here. A
// gate that has never loaded answers with the core's own default, which is the
// same 24h the desktop uses, so a query before the first load is not wrong.
[[nodiscard]] int RememberSeconds() {
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	return gate.settings.lastSeen.tradeRememberSeconds;
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

// Where a ruleset is edited, which is not always what it is called. The
// implicit ruleset - the flat [[schedule.rules]] array wearing a ruleset's
// clothes - is named "rules" for a screen to print, but every splice op
// addresses it with an empty name, and a file is free to spell out a real
// ruleset called "rules" as well. Handing the screen the name would have it
// edit that one instead.
[[nodiscard]] QString RulesetAddress(const Purple::ScheduleRuleset &ruleset) {
	return ruleset.implicit() ? QString() : ruleset.name;
}

// One rule block the parser kept, as
// {"ruleset","index","line","enabled","days","from","to","preset"}. Times are
// minutes since midnight, which is what a time picker speaks and what the
// splice compares against, so "9:00" and "09:00" are one rule on both sides.
//
// A rule the parser threw away is not here: it has no window and no preset to
// draw. The schedule screen names those from `warnings' instead, which is the
// only place the reason it was dropped survives. `index' is the position in the
// RAW array WITHIN ITS RULESET, counting the dropped ones, because that is the
// address the splicer edits by - a rule numbered by its position in this list
// would move whenever a broken one above it was fixed. `ruleset' is the other
// half of that address.
void AppendScheduleRuleJson(
		QString &out,
		const Purple::ScheduleRule &rule,
		const QString &ruleset) {
	out += QStringLiteral("{\"ruleset\":");
	AppendJsonString(out, ruleset);
	out += QStringLiteral(",\"index\":");
	out += QString::number(rule.sourceIndex);
	out += QStringLiteral(",\"line\":");
	out += QString::number(rule.sourceLine);
	out += QStringLiteral(",\"enabled\":");
	AppendJsonBool(out, rule.enabled);
	out += QStringLiteral(",\"days\":[");
	auto firstDay = true;
	for (const auto day : rule.days) {
		if (!firstDay) {
			out += QChar(',');
		}
		firstDay = false;
		out += QString::number(day);
	}
	out += QStringLiteral("],\"from\":");
	out += QString::number(rule.from);
	out += QStringLiteral(",\"to\":");
	out += QString::number(rule.till);
	out += QStringLiteral(",\"preset\":");
	AppendJsonString(out, rule.preset);
	out += QChar('}');
}

// Every ruleset the file describes, in file order and with the implicit one
// first, as the screen listing and editing them needs it. All of them, not only
// the ones this device runs: a ruleset for the laptop is still a row on the
// phone, because the whole point of one settings.toml is that the phone is
// where you edit the laptop's half of it.
void AppendRulesetsJson(QString &out, const Purple::Schedule &schedule) {
	out += QChar('[');
	auto first = true;
	for (const auto &ruleset : schedule.rulesets) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"name\":");
		AppendJsonString(out, ruleset.name);
		out += QStringLiteral(",\"address\":");
		AppendJsonString(out, RulesetAddress(ruleset));
		out += QStringLiteral(",\"device\":");
		AppendJsonString(out, ruleset.device);
		out += QStringLiteral(",\"mode\":");
		AppendJsonString(out, Purple::RulesetModeName(ruleset.mode));
		// Null rather than "normal": a ruleset that says nothing leaves the
		// question to [schedule] outside, which is not the same answer as one
		// that names Normal outright.
		out += QStringLiteral(",\"outside\":");
		if (ruleset.outside) {
			AppendJsonString(out, *ruleset.outside);
		} else {
			out += QStringLiteral("null");
		}
		out += QStringLiteral(",\"implicit\":");
		AppendJsonBool(out, ruleset.implicit());
		out += QStringLiteral(",\"index\":");
		out += QString::number(ruleset.sourceIndex);
		out += QStringLiteral(",\"line\":");
		out += QString::number(ruleset.sourceLine);
		out += QStringLiteral(",\"rules\":[");
		auto firstRule = true;
		for (const auto &rule : ruleset.rules) {
			if (!firstRule) {
				out += QChar(',');
			}
			firstRule = false;
			AppendScheduleRuleJson(out, rule, RulesetAddress(ruleset));
		}
		out += QStringLiteral("]}");
	}
	out += QChar(']');
}

// The rules this device actually runs: the chosen rulesets' enabled rules,
// most specific ruleset first and file order among equals, which is the order
// the first-match engine walks them in.
//
// Rebuilt from `chosen' rather than copied out of `active.rules' so each rule
// can say which ruleset it came from. The two lists are the same rules in the
// same order by construction - ActiveSchedule() fills one from the other.
void AppendActiveRulesJson(
		QString &out,
		const Purple::ScheduleForDevice &active) {
	out += QChar('[');
	auto first = true;
	const auto append = [&](
			const Purple::ScheduleRule &rule,
			const QString &ruleset) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		AppendScheduleRuleJson(out, rule, ruleset);
	};
	if (active.chosen.empty()) {
		// A Schedule nothing parsed - one assembled by a caller filling in the
		// flat list - has no ruleset to stand for its rules. ActiveSchedule()
		// takes them as they are, and so does this.
		for (const auto rule : active.rules) {
			append(*rule, QString());
		}
	} else {
		for (const auto ruleset : active.chosen) {
			for (const auto &rule : ruleset->rules) {
				if (rule.enabled) {
					append(rule, RulesetAddress(*ruleset));
				}
			}
		}
	}
	out += QChar(']');
}

// Which rule the moment is inside, as {"ruleset","index"} - the two halves of
// its splice address - or null when none is.
//
// The ruleset is found by which one's rules the returned pointer sits in, since
// ActiveSchedule() hands back pointers into the Schedule it was built from and
// a rule carries no name of its own.
void AppendScheduleNowJson(
		QString &out,
		const Purple::ScheduleForDevice &active,
		const Purple::ScheduleRule *rule) {
	if (!rule) {
		out += QStringLiteral("null");
		return;
	}
	auto address = QString();
	for (const auto ruleset : active.chosen) {
		const auto &rules = ruleset->rules;
		if (rule >= rules.data() && rule < rules.data() + rules.size()) {
			address = RulesetAddress(*ruleset);
			break;
		}
	}
	out += QStringLiteral("{\"ruleset\":");
	AppendJsonString(out, address);
	out += QStringLiteral(",\"index\":");
	out += QString::number(rule->sourceIndex);
	out += QChar('}');
}

// The names of the rulesets this device chose, in the order their rules were
// merged. Display names - "rules" for the implicit one - because this list is
// only ever printed.
void AppendChosenJson(
		QString &out,
		const Purple::ScheduleForDevice &active) {
	out += QChar('[');
	auto first = true;
	for (const auto ruleset : active.chosen) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		AppendJsonString(out, ruleset->name);
	}
	out += QChar(']');
}

// The friendly names [devices] gives ids, in file order, so every screen that
// has an id to print can print what it is called instead.
void AppendDevicesJson(QString &out, const Purple::Settings &settings) {
	out += QChar('[');
	auto first = true;
	for (const auto &device : settings.devices) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"id\":");
		AppendJsonString(out, device.id);
		out += QStringLiteral(",\"label\":");
		AppendJsonString(out, device.label);
		out += QChar('}');
	}
	out += QChar(']');
}

// What every splice answers with. One shape for all of them, because the Java
// side unpacks them all through the same reader: a refusal is `error' with no
// text, and "already how it was asked to be" is changed=false with no error.
[[nodiscard]] QString SpliceJson(const Purple::SpliceResult &result) {
	auto json = QStringLiteral("{\"changed\":");
	AppendJsonBool(json, result.changed);
	json += QStringLiteral(",\"error\":");
	AppendJsonString(json, result.error);
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, result.ok() ? result.text : QString());
	json += QChar('}');
	return json;
}

// The weekdays a schedule op was handed. False means the VM could not hand the
// array over, and it has thrown by the time it says so - the same contract as
// ReadUtf8 above. A null array is an empty list, which the core refuses on its
// own terms rather than here.
[[nodiscard]] bool ReadInts(JNIEnv *env, jintArray array, std::vector<int> &out) {
	out.clear();
	if (!array) {
		return true;
	}
	const auto length = env->GetArrayLength(array);
	if (length <= 0) {
		return true;
	}
	auto *values = env->GetIntArrayElements(array, nullptr);
	if (!values) {
		return false;
	}
	out.assign(values, values + length);
	env->ReleaseIntArrayElements(array, values, JNI_ABORT);
	return true;
}

// What the screen believed it was editing. Every schedule op carries one and
// the core refuses when the rule at that index no longer says it, so a dialog
// left open while the file moved underneath cannot rewrite a different rule.
[[nodiscard]] Purple::ScheduleRuleExpected ReadExpected(
		JNIEnv *env,
		jint from,
		jint till,
		jstring preset) {
	auto result = Purple::ScheduleRuleExpected();
	result.from = int(from);
	result.till = int(till);
	result.preset = FromJava(env, preset);
	return result;
}

// What the caller says this device is. Nothing here validates it: the core
// matches a ruleset's `device' against all three fields, ignoring case, and an
// identity nothing in the file names simply matches the rulesets that asked for
// no device in particular.
[[nodiscard]] Purple::DeviceIdentity ReadDevice(
		JNIEnv *env,
		jstring id,
		jstring platform,
		jstring cls) {
	auto result = Purple::DeviceIdentity();
	result.id = FromJava(env, id);
	result.platform = FromJava(env, platform);
	result.cls = FromJava(env, cls);
	return result;
}

// The two halves of the desktop's focus policy (purple_focus.cpp), which the
// bridge carries verbatim because Android has nowhere else to put them: there
// is no second process holding an rpl subscription, only the receiver that
// reads the filter and the state write that has to follow it.

// Entering: remember what was running and why, so leaving can put both back,
// and let the preset [focus_sync] names take over.
void FocusEnter(Purple::State &state, const Purple::Settings &settings) {
	const auto from = state.activePreset;

	// Focus cannot be what we remember returning to, or a hand-edited state
	// file could leave the two pointing at each other.
	const auto fromSource = (state.activeSource == Purple::PresetSource::Focus)
		? Purple::PresetSource::Manual
		: state.activeSource;
	state.focusSeen = true;
	state.previousPreset = from;
	state.previousSource = fromSource;
	state.activePreset = settings.focusSync.enterPreset;
	state.activeSource = Purple::PresetSource::Focus;
}

// Leaving. Answers which of the four things it did, for the log line.
//
// `enterTarget' is what the schedule wanted at the moment focus took over, and
// `knownEnterTarget' is false when nothing remembers - see the missed-window
// case below.
[[nodiscard]] QString FocusLeave(
		Purple::State &state,
		const Purple::Settings &settings,
		const Purple::DeviceIdentity &device,
		bool knownEnterTarget,
		const QString &enterTarget) {
	if (state.activeSource != Purple::PresetSource::Focus) {
		// The preset in force is not the one focus imposed: it was chosen while
		// focus was on, and that choice outlives the focus session.
		state.focusSeen = false;
		return QStringLiteral("kept");
	}
	const auto &sync = settings.focusSync;
	const auto restore = Purple::IsPreviousPresetName(sync.exitPreset);
	const auto previous = state.previousPreset;
	const auto previousSource = state.previousSource;
	state.focusSeen = false;
	state.previousPreset = QString();
	state.previousSource = Purple::PresetSource::Manual;
	if (restore) {
		// A schedule window that opened - or closed - while focus held the
		// preset was recorded by the tick and never applied, because focus is
		// the more immediate signal. Putting the pre-focus preset back now would
		// leave the tick nothing to do, since the target it compares against has
		// already moved, and the window would be missed until the next boundary.
		// So the boundary rule runs here instead, on the same asymmetry the tick
		// uses: a window that has opened overrides what was there, and one that
		// has closed only undoes a preset the schedule itself set - which is why
		// it is the pre-focus source, not the focus one, that decides.
		//
		// Only when something remembers what the schedule wanted when focus took
		// over. That is not a key state.toml has, so the caller keeps it beside
		// the file and it is simply absent after a restart, in which case this
		// restores exactly as it did before.
		const auto target = state.schedulePaused
			? std::optional<QString>()
			: Purple::ScheduleTarget(
				settings.schedule,
				QDateTime::currentDateTime(),
				device);
		const auto moved = knownEnterTarget
			&& target
			&& (*target != enterTarget);

		// The same boundary rule the tick runs, asked of the core rather than
		// spelled out again here: with rulesets and an `outside' key, "a window
		// ending" is no longer "the target is Normal", and the two copies of
		// that sentence would have drifted the moment one of them was fixed.
		if (moved
			&& Purple::ScheduleApplies(
				settings.schedule,
				device,
				*target,
				previousSource)) {
			state.activePreset = *target;
			state.activeSource = Purple::PresetSource::Schedule;
			state.scheduleTarget = *target;
			return QStringLiteral("schedule");
		}
	}
	const auto wanted = restore ? previous : sync.exitPreset;

	// Restoring puts back the reason as well as the preset, so a window the
	// schedule had opened still closes at its own boundary afterwards. A preset
	// named outright was not put there by either, so it is the user's until
	// something moves it.
	state.activePreset = wanted.isEmpty() ? Purple::NormalPreset() : wanted;
	state.activeSource = restore
		? previousSource
		: Purple::PresetSource::Manual;
	return restore ? QStringLiteral("restored") : QStringLiteral("exited");
}

// Writes down which settings.toml this device last sent, or last wrote because
// the other device sent it.
//
// Two fields and one function, because the two events differ only in which one
// they claim: what stops the ping-pong is that both are remembered, not that
// they are remembered differently.
[[nodiscard]] QString NoteFingerprint(
		const QString &stateText,
		const QByteArray &bytes,
		bool sent) {
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	const auto fingerprint = Purple::SettingsFingerprint(bytes);
	if (sent) {
		state.lastSentFingerprint = fingerprint;
	} else {
		state.lastImportedFingerprint = fingerprint;
	}
	return Purple::SerializeState(state);
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
// [{"peer","kind","from","until"}]. Expired entries and entries made under
// another preset are already invisible to OverrideFor(), and the walk below
// applies the same two tests so the Java side never has to know either rule.
//
// "from" is the moment the decision was made, and it is here for one reader:
// the chat-list mark, which draws how much of a span is left and so needs both
// ends of it. Nothing that only asks "is it still in force" looks at it.
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
		out += QStringLiteral(",\"from\":");
		out += QString::number(qint64(entry.startedUnix));
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
		jbyteArray stateUtf8,
		jstring deviceId,
		jstring devicePlatform,
		jstring deviceClass) {
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
			"\"hideInvisibleSuggestions\":true,\"hideArchive\":true,"
			"\"presets\":[],\"stateText\":null}"));
	}

	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);

	// Kept for the calls that resolve the same schedule without being handed an
	// identity of their own. It is a constant of the install, so re-reading it
	// on every load costs nothing and there is nowhere for it to go stale.
	gate.device = ReadDevice(env, deviceId, devicePlatform, deviceClass);

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
	// Zero means a pause that lasts until it is lifted by hand, which is what a
	// pause has always been and what an older state.toml still says. Separate
	// from the flag for the same reason peekDeadline is: "paused" and "paused
	// until Monday" are two answers, not one number that happens to be missing.
	json += QStringLiteral(",\"schedulePausedUntil\":");
	json += QString::number(qint64(gate.state.schedulePausedUntil));
	// A switch that holds off nothing explains nothing, so the pause row is
	// there only when the file describes a schedule at all. Rulesets widen what
	// that means: a file whose only rules are the laptop's still has a schedule
	// to pause, even though this device runs none of it.
	const auto activeSchedule = Purple::ActiveSchedule(
		gate.settings.schedule,
		gate.device);
	json += QStringLiteral(",\"scheduleConfigured\":");
	AppendJsonBool(json, !activeSchedule.rules.empty()
		|| !gate.settings.schedule.rulesets.empty());
	// The schedule as the screens editing it need it: the master switch, every
	// ruleset in the file, the rules this device actually runs, and which of
	// them - if any - the moment is inside.
	//
	// The rule now is worked out here rather than on the Java side because
	// ScheduleTarget() already has to find it, and a second implementation of
	// the midnight-crossing case is a second thing to keep in step. It is named
	// by ruleset and sourceIndex rather than by a position in the list above, so
	// the screen highlights the right row even when a broken rule sits among
	// them.
	json += QStringLiteral(",\"scheduleEnabled\":");
	AppendJsonBool(json, gate.settings.schedule.enabled);
	const auto now = QDateTime::currentDateTime();
	const auto target = Purple::ScheduleTarget(
		gate.settings.schedule,
		now,
		gate.device);
	json += QStringLiteral(",\"scheduleTarget\":");
	if (target) {
		AppendJsonString(json, *target);
	} else {
		json += QStringLiteral("null");
	}
	// What this device wants between its windows: the most specific chosen
	// ruleset that names one, or [schedule] outside. Not always "normal" any
	// more, which is why the status line has to be told rather than assume.
	json += QStringLiteral(",\"scheduleOutside\":");
	AppendJsonString(json, activeSchedule.outside);
	// The enabled check is here rather than inside ScheduleRuleNow(): the
	// resolved-schedule overload answers what the rules say, and a schedule
	// switched off has rules that say things it is not doing.
	const auto ruleNow = gate.settings.schedule.enabled
		? Purple::ScheduleRuleNow(activeSchedule, now)
		: nullptr;
	json += QStringLiteral(",\"scheduleNow\":");
	AppendScheduleNowJson(json, activeSchedule, ruleNow);
	json += QStringLiteral(",\"scheduleChosen\":");
	AppendChosenJson(json, activeSchedule);
	json += QStringLiteral(",\"scheduleRulesets\":");
	AppendRulesetsJson(json, gate.settings.schedule);
	json += QStringLiteral(",\"scheduleRules\":");
	AppendActiveRulesJson(json, activeSchedule);
	// Who this device says it is, and what [devices] calls the ids it knows -
	// so a ruleset naming an id can be shown as "the phone" and this device can
	// be told apart from the rest of them.
	json += QStringLiteral(",\"deviceId\":");
	AppendJsonString(json, gate.device.id);
	json += QStringLiteral(",\"devices\":");
	AppendDevicesJson(json, gate.settings);
	// [focus_sync] as the row showing it needs it. The enabled flag is the
	// parser's rather than the file's: it turns focus sync off itself when
	// enter_preset names nothing that exists, and a switch reading the file
	// instead would sit on while nothing happened. The two preset names travel
	// with it so the row can say what it will do and not only that it is on.
	json += QStringLiteral(",\"focusSyncEnabled\":");
	AppendJsonBool(json, gate.settings.focusSync.enabled);
	json += QStringLiteral(",\"focusSyncEnter\":");
	AppendJsonString(json, gate.settings.focusSync.enterPreset);
	json += QStringLiteral(",\"focusSyncExit\":");
	AppendJsonString(json, gate.settings.focusSync.exitPreset);
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
	// The preset-wide switch the scope above reuses: under it a chat the preset
	// hides is gone from the app rather than only from the preset's own view of
	// the chat list. Taken from the resolved preset rather than from the file,
	// so a resolution restored from the cache carries it too.
	json += QStringLiteral(",\"hideEverywhere\":");
	AppendJsonBool(json, gate.resolved.hideEverywhere);
	// Whether a chat the preset hides is also left out of the recent and
	// frequent strips. Read straight from the file rather than from the
	// resolution: it is one switch for the whole app, not a property of the
	// preset, so a resolution restored from the cache has nothing to say
	// about it.
	json += QStringLiteral(",\"hideInvisibleSuggestions\":");
	AppendJsonBool(json, gate.settings.suggestions.hideInvisible);
	// Whether the Channels tab's "similar channels" section is drawn at all.
	// The one suggestion in the app that is not assembled out of your own
	// chats - the server picks it - which is why it is off by default and why
	// a preset that is hiding things does not get to show it uninvited.
	json += QStringLiteral(",\"recommendedChannels\":");
	AppendJsonBool(json, gate.settings.suggestions.recommendedChannels);
	// Whether the archive is out of the way while this preset runs. From the
	// resolution, like hideEverywhere and for the same reason: it is the
	// preset's own decision, and a cached resolution has to carry it.
	json += QStringLiteral(",\"hideArchive\":");
	AppendJsonBool(json, gate.resolved.hideArchive);
	// Read on every row while a chat is in its grace period, so it travels with
	// the rest rather than being asked for. Zero disables it, and the Java side
	// re-reads it on every query - which is what makes turning [recent] off take
	// effect at once rather than at the end of whatever was already running.
	json += QStringLiteral(",\"recentSeconds\":");
	json += QString::number(gate.settings.recent.staySecondsAfterClose);
	json += QStringLiteral(",\"recentScope\":");
	json += QString::number(int(gate.settings.recent.scope));
	// How the chat list marks a row that is only there on a clock. Read once
	// per row per paint, so it travels with the rest for the same reason the
	// two above do.
	json += QStringLiteral(",\"recentStyle\":");
	json += QString::number(int(gate.settings.recent.style));
	// The Premium gates this client is the only thing enforcing. A file that
	// never mentions [premium] means on, which is the core's default and the
	// desktop's, so one settings.toml still means one thing in both clients.
	json += QStringLiteral(",\"premium\":");
	json += (gate.settings.premium.enabled
		? QStringLiteral("true")
		: QStringLiteral("false"));
	// Whether a save also posts the file to Saved Messages. Handed over rather
	// than asked for at the moment of a write, so the switch on the settings
	// screen has something to draw itself from - the write path parses the
	// file it just wrote and reads the key out of that.
	json += QStringLiteral(",\"sendAfterSave\":");
	AppendJsonBool(json, gate.settings.sync.sendAfterSave);
	// Purple: [last_seen], handed over whole. The two switches are read on
	// every status line the chat header and the profile draw, and the three
	// durations by the trade; a JNI call per paint to ask whether a reason may
	// be appended is exactly the cost this path cannot carry. Read from the
	// file rather than from the resolution because none of it belongs to a
	// preset - it is how a status line reads, not what gets through.
	json += QStringLiteral(",\"lastSeenReasons\":");
	AppendJsonBool(json, gate.settings.lastSeen.reasons);
	json += QStringLiteral(",\"lastSeenTrade\":");
	AppendJsonBool(json, gate.settings.lastSeen.trade);
	json += QStringLiteral(",\"lastSeenTradeHold\":");
	json += QString::number(gate.settings.lastSeen.tradeHoldSeconds);
	json += QStringLiteral(",\"lastSeenTradeRemember\":");
	json += QString::number(gate.settings.lastSeen.tradeRememberSeconds);
	json += QStringLiteral(",\"lastSeenTradeCooldown\":");
	json += QString::number(gate.settings.lastSeen.tradeCooldownSeconds);
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
//
// `until' is unix seconds, or zero for the pause that lasts until it is lifted
// by hand - which is what a pause has always been. The deadline is cleared
// alongside the flag on the way out, so an unpause never leaves a moment behind
// for the next pause to inherit.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setSchedulePausedNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jboolean paused,
		jlong until) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	state.schedulePaused = (paused == JNI_TRUE);
	state.schedulePausedUntil = state.schedulePaused ? int64(until) : 0;
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
		jbyteArray stateUtf8,
		jstring deviceId,
		jstring devicePlatform,
		jstring deviceClass) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded) {
		return nullptr;
	}
	const auto device = ReadDevice(env, deviceId, devicePlatform, deviceClass);
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));

	// A pause with a deadline lifts itself. Both fields go at once and the
	// ordinary boundary rule then runs below in this same tick, so the windows
	// missed while it was held off are caught up on immediately rather than at
	// the next window edge - which for a pause lifting on a Sunday evening
	// could be a whole day away.
	auto unpaused = false;
	if (state.schedulePaused) {
		if (!Purple::ScheduleUnpauseDue(state, NowUnix())) {
			return nullptr;
		}
		state.schedulePaused = false;
		state.schedulePausedUntil = 0;
		unpaused = true;
	}
	const auto target = Purple::ScheduleTarget(
		gate.settings.schedule,
		QDateTime::currentDateTime(),
		device);
	const auto boundary = target && (*target != state.scheduleTarget);
	if (!boundary && !unpaused) {
		// Acting on the change rather than on the value is the whole design.
		// It is what lets a preset chosen by hand stand until the next boundary
		// instead of being overwritten on the next tick, and what makes a
		// boundary missed while the app was closed still happen, once, at the
		// next launch.
		return nullptr;
	}
	const auto wanted = boundary ? *target : state.scheduleTarget;
	const auto source = state.activeSource;
	const auto active = state.activePreset;

	// Two rules, and the asymmetry between them is deliberate. A window
	// starting is a positive instruction - "at nine, work mode" - and it
	// overrides a preset chosen by hand. A window ending only means the reason
	// for that preset has passed, which is no reason at all to undo something
	// asked for. Focus is left alone in both directions: it is the more
	// immediate signal, and a schedule fighting it would make both unreadable.
	//
	// Asked of the core rather than spelled out here, because "a window ending"
	// is a move to this device's own `outside' and no longer to Normal, and a
	// second copy of that sentence is a second thing to get subtly wrong.
	const auto apply = boundary
		&& Purple::ScheduleApplies(
			gate.settings.schedule,
			device,
			wanted,
			source);
	if (boundary) {
		state.scheduleTarget = wanted;
	}
	if (apply) {
		state.activePreset = wanted;
		state.activeSource = Purple::PresetSource::Schedule;
	}

	auto json = QStringLiteral("{\"applied\":");
	AppendJsonBool(json, apply);
	// Whether this tick is the one that lifted a pause that had run out. The
	// caller reloads on it as well as on `applied': the pause is what its own
	// ticking is conditioned on, so a cleared pause nothing reread would stop
	// the clock that had just cleared it.
	json += QStringLiteral(",\"unpaused\":");
	AppendJsonBool(json, unpaused);
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

// One pass of OS focus sync, mirroring the desktop's purple_focus.cpp.
//
// Both halves in one call, unlike the desktop, where a detector writes the flag
// and a policy watching state.toml acts on it. Android has one caller - the
// receiver that read the interruption filter - and splitting the write in two
// would only mean two rewrites of state.toml for one change of focus.
//
// A pure function of the settings already loaded, the state text handed in, the
// filter and the wall clock. Null comes back whenever there is nothing to
// write, which is every call but the ones at an edge.
//
// `enterTarget' is what the schedule wanted when the running focus session
// began, or null when nothing remembers; the result hands back a fresh one to
// keep whenever a session starts. It is not in state.toml because this client
// does not own that schema - see FocusLeave() for what it is for.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_focusTickNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jboolean active,
		jstring enterTarget) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded) {
		return nullptr;
	}
	const auto known = (enterTarget != nullptr);
	const auto remembered = FromJava(env, enterTarget);
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	state.focusActive = (active == JNI_TRUE);

	const auto &sync = gate.settings.focusSync;
	auto change = QStringLiteral("none");
	auto entered = std::optional<QString>();
	if (!sync.enabled) {
		// Switching focus sync off while it is holding a preset has to hand that
		// preset back. Leaving it in force would be a preset nothing on screen
		// explains and nothing left running would ever lift.
		if (state.activeSource == Purple::PresetSource::Focus) {
			change = FocusLeave(
				state,
				gate.settings,
				gate.device,
				known,
				remembered);
		} else if (state.focusSeen) {
			state.focusSeen = false;
		}
	} else if (state.focusActive == state.focusSeen) {
		// No edge, so nothing happens - which is exactly what makes a preset
		// chosen by hand mid-session stand until focus itself changes. The flag
		// above may still have moved on its own, and that write is the point of
		// this call being made at all.
	} else if (state.focusActive) {
		FocusEnter(state, gate.settings);
		change = QStringLiteral("entered");
		// Handed back for the caller to keep until the session ends. Empty means
		// the schedule wanted nothing, which is a different answer from wanting
		// Normal and stays distinguishable from it.
		entered = Purple::ScheduleTarget(
			gate.settings.schedule,
			QDateTime::currentDateTime(),
			gate.device).value_or(QString());
	} else {
		change = FocusLeave(
			state,
			gate.settings,
			gate.device,
			known,
			remembered);
	}

	const auto serialized = Purple::SerializeState(state);
	if (serialized == stateText) {
		return nullptr;
	}
	auto json = QStringLiteral("{\"change\":");
	AppendJsonString(json, change);
	json += QStringLiteral(",\"preset\":");
	AppendJsonString(json, state.activePreset.isEmpty()
		? Purple::NormalPreset()
		: state.activePreset);
	json += QStringLiteral(",\"source\":");
	AppendJsonString(json, Purple::PresetSourceName(state.activeSource));
	// Whether focus is holding the preset now. False is the caller's cue to
	// forget the entry target, so a session that ended leaves nothing behind.
	json += QStringLiteral(",\"session\":");
	AppendJsonBool(json, state.activeSource == Purple::PresetSource::Focus);
	json += QStringLiteral(",\"enterTarget\":");
	if (entered) {
		AppendJsonString(json, *entered);
	} else {
		json += QStringLiteral("null");
	}
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, serialized);
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
	return ToJava(env, SpliceJson(result));
}

// A new empty [lists.x], for the "New list..." row in the membership box.
//
// No naming callback, unlike spliceMemberNative: there is no member line yet to
// put a comment on. `title' is written only when it says something the name
// does not, so handing over the name twice writes no title line at all.
//
// The core refuses an empty name, one starting with '*' - which the parser
// would refuse too, since nobody reading the file could tell such a list from a
// "*set" spread - and a name already taken. Those refusals are the message the
// box shows, so nothing here checks them a second time.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_addListNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring name,
		jstring title) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::AddList(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, name),
		FromJava(env, title))));
}

// One boolean under one table - the Premium switch and the two Work Mode flags
// the settings screen owns. No naming callback: there is no member line to put
// a comment on, and the splice keeps whatever the user wrote after the value.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setTableBoolNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring table,
		jstring key,
		jboolean value) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::SetTableBool(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, table),
		FromJava(env, key),
		value == JNI_TRUE)));
}

// One string under one table - [schedule] outside, and the label a device is
// given in [devices]. The string half of setTableBoolNative and there for the
// same reason: the file is hand-owned, so the app rewrites the value and leaves
// the key, the spacing and any trailing comment where the user put them.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setTableStringNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring table,
		jstring key,
		jstring value) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::SetTableString(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, table),
		FromJava(env, key),
		FromJava(env, value))));
}

// The three ruleset ops. A ruleset is addressed by name and never by position,
// because its position moves whenever one above it is added or taken away and
// an index a screen read a minute ago would then edit the wrong one.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_addRulesetNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring name,
		jstring device,
		jstring mode) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	// Enabled for a mode nothing could read, which is the opposite of the
	// parser's choice for the same input and deliberately so: the parser is
	// reading a sentence somebody wrote and cannot finish, while this is a
	// screen that offered three buttons and got something else.
	const auto parsed = Purple::ParseRulesetMode(FromJava(env, mode));
	return ToJava(env, SpliceJson(Purple::AddRuleset(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, name),
		FromJava(env, device),
		parsed.value_or(Purple::RulesetMode::Enabled))));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_removeRulesetNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring name) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::RemoveRuleset(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, name))));
}

// One of a ruleset's own string keys - 'device', 'mode', 'outside', or 'name'
// for a rename. An empty value takes the key out of the file, which is how a
// screen says "back to the default" without writing the default down.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setRulesetStringNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring name,
		jstring key,
		jstring value) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::SetRulesetString(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, name),
		FromJava(env, key),
		FromJava(env, value))));
}

// The three schedule ops. Each carries the window and preset the screen read
// off the rule, and the core refuses when the rule at that index no longer says
// them - which is what stops a dialog left open across an edit from rewriting
// or deleting the wrong rule.
//
// `ruleset' is where the rule lives: empty for the flat [[schedule.rules]]
// array, a name for a [[schedule.rulesets]] block, and `index' then counts
// within that one.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setScheduleRuleNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring ruleset,
		jint index,
		jint expectedFrom,
		jint expectedTill,
		jstring expectedPreset,
		jboolean enabled,
		jintArray days,
		jint from,
		jint till,
		jstring preset) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	auto rule = Purple::ScheduleRule();
	if (!ReadInts(env, days, rule.days)) {
		return nullptr;
	}
	rule.enabled = (enabled == JNI_TRUE);
	rule.from = int(from);
	rule.till = int(till);
	rule.preset = FromJava(env, preset);
	return ToJava(env, SpliceJson(Purple::SetScheduleRule(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, ruleset),
		int(index),
		ReadExpected(env, expectedFrom, expectedTill, expectedPreset),
		rule)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_appendScheduleRuleNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring ruleset,
		jboolean enabled,
		jintArray days,
		jint from,
		jint till,
		jstring preset) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	auto rule = Purple::ScheduleRule();
	if (!ReadInts(env, days, rule.days)) {
		return nullptr;
	}
	rule.enabled = (enabled == JNI_TRUE);
	rule.from = int(from);
	rule.till = int(till);
	rule.preset = FromJava(env, preset);
	return ToJava(env, SpliceJson(Purple::AppendScheduleRule(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, ruleset),
		rule)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_removeScheduleRuleNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring ruleset,
		jint index,
		jint expectedFrom,
		jint expectedTill,
		jstring expectedPreset) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::RemoveScheduleRule(
		text,
		QStringLiteral("settings.toml"),
		FromJava(env, ruleset),
		int(index),
		ReadExpected(env, expectedFrom, expectedTill, expectedPreset))));
}

// Whether saving these bytes should also post them to Saved Messages.
//
// Four arguments where three would seem to do, because `settingsUtf8' and
// `fileBytes' are the same file read for two different purposes: the switch
// comes out of parsing it, and the fingerprint is taken over the bytes exactly
// as they were written. Keeping them apart is what lets a caller ask the
// question about a file it has not installed yet.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleCore_shouldAutoSendNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jbyteArray stateUtf8,
		jbyteArray fileBytes,
		jboolean wroteFromImport) {
	auto settingsText = QString();
	auto stateText = QString();
	auto bytes = QByteArray();
	if (!ReadUtf8(env, settingsUtf8, settingsText)
		|| !ReadUtf8(env, stateUtf8, stateText)
		|| !ReadRaw(env, fileBytes, bytes)) {
		return JNI_FALSE;
	}
	// A file that will not parse leaves default settings behind, whose
	// send_after_save_p is false - which is the answer this should give for one
	// anyway, since the send itself refuses a file that does not parse.
	const auto parsed = Purple::ParseSettings(
		settingsText,
		QStringLiteral("settings.toml"));
	const auto state = Purple::ParseState(
		stateText,
		QStringLiteral("state.toml"));
	return Purple::ShouldAutoSend(
		parsed.settings,
		state,
		bytes,
		wroteFromImport == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

// Records the file this device has just sent, so that saving the same bytes
// again is not a second document in the same chat.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_noteSentNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jbyteArray fileBytes) {
	auto stateText = QString();
	auto bytes = QByteArray();
	if (!ReadUtf8(env, stateUtf8, stateText)
		|| !ReadRaw(env, fileBytes, bytes)) {
		return nullptr;
	}
	return ToJava(env, NoteFingerprint(stateText, bytes, true));
}

// Records the file this device has just written because the other one sent it,
// so it is not sent straight back. That is the ping-pong: A saves and sends, B
// imports and saves, B sends what it just received, A imports it, and a file
// two machines already agree about bounces between them.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_noteImportedNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jbyteArray fileBytes) {
	auto stateText = QString();
	auto bytes = QByteArray();
	if (!ReadUtf8(env, stateUtf8, stateText)
		|| !ReadRaw(env, fileBytes, bytes)) {
		return nullptr;
	}
	return ToJava(env, NoteFingerprint(stateText, bytes, false));
}

// Why a last seen reads as coarse, as the core's LastSeenReason numbers it.
//
// Three booleans rather than the status object because the core has no idea
// what a TL_userStatusRecently is and must not learn: the client that owns the
// TL layer flattens it to "is there a real moment in this", "is it one of the
// three coarse spellings", "does it carry by_me", and the one shared rule
// answers the same way in both forks.
//
// Touches neither the gate nor any file, so it costs no lock - it is asked
// once per status line drawn.
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleCore_lastSeenReasonNative(
		JNIEnv *,
		jclass,
		jboolean exactKnown,
		jboolean coarse,
		jboolean byMe) {
	return jint(Purple::ReasonFor(
		exactKnown == JNI_TRUE,
		coarse == JNI_TRUE,
		byMe == JNI_TRUE));
}

// Writes down one finished trade and hands back the state.toml text to save.
//
// A `wasOnline' of zero is not a failure to record: a trade whose hold ran out
// without an exact status ever arriving is still a moment of exposure that
// happened, and it is what the cooldown counts. Parses the text it is given
// rather than the loaded gate, like every other state writer here, because the
// caller owns the file and the write.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_rememberTradeNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jlong peer,
		jlong readAt,
		jlong wasOnline) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	const auto id = Purple::PeerIdValue(peer);
	if (!id || !readAt) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	Purple::RememberTrade(state, id, readAt, wasOnline);
	// Dropped here rather than at the next load, so a file that has been
	// traded against for a year does not carry a year of stale records that
	// nothing will ever show. The remember window comes from the gate because
	// it is a setting and this is handed only state.
	Purple::PruneLastSeenTrades(state, readAt, RememberSeconds());
	return ToJava(env, Purple::SerializeState(state));
}

// What was read for this person, if it is still worth showing: a two-element
// array of `read_at' and `was_online', or null for nothing remembered.
//
// Two numbers rather than one because both status lines it feeds need both:
// "last seen 14:32" is the moment and "as of 3 min ago" is the age of the
// news, and a reader given only the moment would have to pretend it was read
// just now.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleCore_rememberedTradeNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jlong peer,
		jlong now) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	const auto state = Purple::ParseState(
		stateText,
		QStringLiteral("state.toml"));
	const auto trade = Purple::RememberedTrade(
		state,
		Purple::PeerIdValue(peer),
		now ? int64(now) : NowUnix(),
		RememberSeconds());
	if (!trade) {
		return nullptr;
	}
	const auto result = env->NewLongArray(2);
	if (!result) {
		return nullptr;
	}
	const jlong values[2] = { jlong(trade->readAtUnix), jlong(trade->wasOnlineUnix) };
	env->SetLongArrayRegion(result, 0, 2, values);
	return result;
}

// Whether a trade with this person may be offered now, or whether the last one
// is still inside its cooldown. The cooldown is the gate's, for the same reason
// the remember window is: it is a setting, and only state was handed over.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleCore_tradeAllowedNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jlong peer,
		jlong now) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return JNI_FALSE;
	}
	const auto state = Purple::ParseState(
		stateText,
		QStringLiteral("state.toml"));
	auto &gate = TheGate();
	auto cooldown = 0;
	{
		const auto lock = std::lock_guard(gate.mutex);
		cooldown = gate.settings.lastSeen.tradeCooldownSeconds;
	}
	return Purple::TradeAllowed(
		state,
		Purple::PeerIdValue(peer),
		now ? int64(now) : NowUnix(),
		cooldown) ? JNI_TRUE : JNI_FALSE;
}

// Every trade still worth showing, newest read first, as JSON for the trade log
// on the settings screen.
//
// A list native as well as the per-peer one above because the log is the one
// caller that has no peer in hand - it is asking what happened, not about
// somebody - and walking the file peer by peer would need the list first
// anyway.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_tradesNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jlong now) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	const auto at = now ? int64(now) : NowUnix();
	Purple::PruneLastSeenTrades(state, at, RememberSeconds());
	auto trades = state.lastSeenTrades;
	std::sort(trades.begin(), trades.end(), [](
			const Purple::LastSeenTrade &a,
			const Purple::LastSeenTrade &b) {
		return a.readAtUnix > b.readAtUnix;
	});
	auto json = QString();
	json += QChar('[');
	auto first = true;
	for (const auto &trade : trades) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QStringLiteral("{\"peer\":");
		json += QString::number(qint64(trade.peer));
		json += QStringLiteral(",\"readAt\":");
		json += QString::number(qint64(trade.readAtUnix));
		json += QStringLiteral(",\"wasOnline\":");
		json += QString::number(qint64(trade.wasOnlineUnix));
		json += QChar('}');
	}
	json += QChar(']');
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
