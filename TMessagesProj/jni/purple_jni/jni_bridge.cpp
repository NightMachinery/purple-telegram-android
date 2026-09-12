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
#include "purple/purple_screentime.h"
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

// The folders that said something about their people's stories. Same shape as
// the exempt folders above, and lifted out for the same reason: almost no
// preset mentions stories on a folder at all, and that has to be one empty list
// to test rather than a folder walk per story.
//
// A name and a mode and nothing else, because a story folder has nothing else
// to say - there is no "pinned only" half here, since a folder that speaks for
// its people speaks for all of them.
void AppendStoryFoldersJson(QString &json, const Purple::Resolved &resolved) {
	json += QChar('[');
	auto first = true;
	for (const auto &folder : resolved.storyFolders) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		json += QStringLiteral("{\"name\":");
		AppendJsonString(json, folder.name);
		json += QStringLiteral(",\"mode\":");
		json += QString::number(int(folder.mode));
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
// The eight sentences the status line can be, spelled for the Java side.
//
// A switch rather than a table so the compiler names any enumerator the core
// adds and nobody here notices: a status kind with no spelling would come back
// as an empty string and the screen would draw nothing at all.
[[nodiscard]] QString ScheduleStatusKindName(Purple::ScheduleStatusKind kind) {
	using Kind = Purple::ScheduleStatusKind;
	switch (kind) {
	case Kind::NotConfigured: return QStringLiteral("not_configured");
	case Kind::Paused: return QStringLiteral("paused");
	case Kind::PausedUntil: return QStringLiteral("paused_until");
	case Kind::Off: return QStringLiteral("off");
	case Kind::NoRules: return QStringLiteral("no_rules");
	case Kind::NoneHere: return QStringLiteral("none_here");
	case Kind::InsideWindow: return QStringLiteral("inside");
	case Kind::OutsideWindow: return QStringLiteral("outside");
	}
	return QStringLiteral("not_configured");
}

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

[[nodiscard]] Purple::ScreenTimeBudget ReadBudget(
		JNIEnv *env,
		jstring target,
		jint perDaySeconds,
		jstring mode,
		jint snoozeSeconds,
		jint snoozesPerDay) {
	auto budget = Purple::ScreenTimeBudget();
	budget.target = FromJava(env, target);
	budget.perDaySeconds = int(perDaySeconds);
	// Soft for a mode nothing could read, the same choice addRulesetNative
	// makes and for the same reason: this is a screen that offered two buttons
	// and got something else, not a file being read as charitably as possible.
	budget.mode = Purple::ParseBudgetMode(FromJava(env, mode))
		.value_or(Purple::BudgetMode::Soft);
	budget.snoozeSeconds = int(snoozeSeconds);
	budget.snoozesPerDay = int(snoozesPerDay);
	return budget;
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


// Purple: [screen_time]. Everything below reads screentime.log and answers in
// JSON. Nothing here keeps state between calls, because the log IS the state:
// the recorder appends events and every number a screen draws is derived from
// them at read time, so a threshold changed in settings.toml re-derives the
// history that is already on disk instead of only shaping what happens next.

// Which row of bars the caller asked for. The Java constants are these
// enumerators by value; an unknown one falls to HourOfDay, which is the only
// unit that is meaningful for any range at all.
[[nodiscard]] Purple::BucketUnit BucketUnitFrom(jint value) {
	static_assert(int(Purple::BucketUnit::HourOfDay) == 0);
	static_assert(int(Purple::BucketUnit::Day) == 1);
	static_assert(int(Purple::BucketUnit::Week) == 2);
	static_assert(int(Purple::BucketUnit::Month) == 3);
	switch (value) {
	case 1: return Purple::BucketUnit::Day;
	case 2: return Purple::BucketUnit::Week;
	case 3: return Purple::BucketUnit::Month;
	}
	return Purple::BucketUnit::HourOfDay;
}

// The zone a day boundary is decided in.
//
// The named id is tried first, so a caller that can name its zone gets it - and
// "UTC+02:00" spellings resolve without any platform data at all, since Qt
// answers those from its own UTC backend. A named IANA id, though, needs Qt's
// Android timezone backend, which reaches Java through a JavaVM that Qt's own
// JNI_OnLoad installs - and this library deliberately never lets that run (see
// the JNI_OnLoad at the bottom of this file). So the fallback is not UTC, which
// would silently move every day boundary by hours: it is QTimeZone::LocalTime,
// the device's own zone as the C library reports it, DST included and no Java
// anywhere near it.
[[nodiscard]] QTimeZone ZoneFrom(const QString &id) {
	// Only a "UTC+02:00" spelling is ever handed to the QByteArray
	// constructor. Seen on the emulator: an IANA id there is not a fallback
	// case but a crash - QTimeZone reaches for Qt's Android backend, which
	// asks QJniEnvironment for a JavaVM this library never installed, and
	// dereferences the null it gets back before isValid() could say no. So
	// the Java side sends the offset spelling, and anything else is treated
	// as "no zone named", not tried.
	const auto local = QTimeZone(QTimeZone::LocalTime);
	auto fixed = QTimeZone();
	if (id.startsWith(QStringLiteral("UTC"))) {
		const auto named = QTimeZone(id.toUtf8());
		if (named.isValid()) {
			fixed = named;
		}
	}
	if (!fixed.isValid()) {
		return local;
	}

	// Both describe the same zone and only one of them knows about daylight
	// saving. A fixed offset is the offset in force today, so a report over a
	// range that straddles a change puts every day boundary past it an hour
	// out; LocalTime is the C library's own answer, which has the whole rule
	// and reaches no Java at all - bionic reads the zone from TZ or from the
	// system property, so nothing here depends on the JavaVM that is missing.
	//
	// So LocalTime is what we want, and the fixed offset is how we check it.
	// The check is deliberately the very operation the report depends on -
	// turning an instant into a local date - rather than a cheaper question
	// about the zone object, so a build where LocalTime does not work is
	// caught by the thing that would have been wrong. Disagreeing now means
	// LocalTime is not answering for this device, and then the offset Java
	// measured is the better of the two: it is exactly what this build did
	// before, an hour out only across a change rather than wrong every day.
	const auto now = QDateTime::currentMSecsSinceEpoch();
	const auto viaLocal = QDateTime::fromMSecsSinceEpoch(now, local);
	const auto viaFixed = QDateTime::fromMSecsSinceEpoch(now, fixed);
	return (viaLocal.date() == viaFixed.date()
		&& viaLocal.time() == viaFixed.time())
		? local
		: fixed;
}

void AppendChatTotalsJson(QString &out, const std::vector<Purple::ChatTotal> &chats) {
	out += QChar('[');
	auto first = true;
	for (const auto &chat : chats) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"dialogId\":");
		out += QString::number(qint64(chat.dialogId));
		out += QStringLiteral(",\"kind\":");
		out += QString::number(int(chat.chatKind));
		out += QStringLiteral(",\"totalMs\":");
		out += QString::number(qint64(chat.totalMs));
		out += QStringLiteral(",\"activeMs\":");
		out += QString::number(qint64(chat.activeMs));
		out += QChar('}');
	}
	out += QChar(']');
}

void AppendKindTotalsJson(QString &out, const std::vector<Purple::KindTotal> &kinds) {
	out += QChar('[');
	auto first = true;
	for (const auto &kind : kinds) {
		if (!first) {
			out += QChar(',');
		}
		first = false;
		out += QStringLiteral("{\"kind\":");
		out += QString::number(int(kind.chatKind));
		out += QStringLiteral(",\"totalMs\":");
		out += QString::number(qint64(kind.totalMs));
		out += QStringLiteral(",\"activeMs\":");
		out += QString::number(qint64(kind.activeMs));
		out += QChar('}');
	}
	out += QChar(']');
}

// One row of bars, with each bar split by chat kind so the chart can stack.
//
// The split is five passes of Buckets() over the sessions of one kind rather
// than a wider Bucket struct: the core's bucketing is the part that knows about
// calendars, and asking it five times costs nothing next to teaching this file
// what a week is. Every pass walks the same window, so the sub-buckets line up
// with the parent's index for index.
void AppendBucketsJson(
		QString &out,
		const std::vector<Purple::Session> &sessions,
		int64 fromMs,
		int64 toMs,
		Purple::BucketUnit unit,
		const QTimeZone &zone) {
	const auto whole = Purple::Buckets(sessions, fromMs, toMs, unit, zone);

	constexpr auto kKinds = 5;
	auto perKind = std::vector<std::vector<Purple::Bucket>>();
	perKind.reserve(kKinds);
	for (auto k = 0; k != kKinds; ++k) {
		auto only = std::vector<Purple::Session>();
		for (const auto &session : sessions) {
			if (int(session.chatKind) == k) {
				only.push_back(session);
			}
		}
		perKind.push_back(Purple::Buckets(only, fromMs, toMs, unit, zone));
	}

	out += QChar('[');
	for (auto i = 0, count = int(whole.size()); i != count; ++i) {
		const auto &bucket = whole[i];
		if (i) {
			out += QChar(',');
		}
		out += QStringLiteral("{\"index\":");
		out += QString::number(bucket.index);
		out += QStringLiteral(",\"startMs\":");
		out += QString::number(qint64(bucket.startMs));
		out += QStringLiteral(",\"endMs\":");
		out += QString::number(qint64(bucket.endMs));
		out += QStringLiteral(",\"label\":");
		AppendJsonString(out, bucket.label);
		out += QStringLiteral(",\"totalMs\":");
		out += QString::number(qint64(bucket.totalMs));
		out += QStringLiteral(",\"activeMs\":");
		out += QString::number(qint64(bucket.activeMs));
		out += QStringLiteral(",\"kinds\":[");
		for (auto k = 0; k != kKinds; ++k) {
			if (k) {
				out += QChar(',');
			}
			const auto &row = perKind[k];
			out += QStringLiteral("{\"totalMs\":");
			out += QString::number(qint64(
				(i < int(row.size())) ? row[i].totalMs : int64(0)));
			out += QStringLiteral(",\"activeMs\":");
			out += QString::number(qint64(
				(i < int(row.size())) ? row[i].activeMs : int64(0)));
			out += QChar('}');
		}
		out += QStringLiteral("]}");
	}
	out += QChar(']');
}

// Everything a chart, a ranked list and a headline need about one set of
// sessions over one window. Written once and used twice: for the whole range,
// and again for each preset that appears in it, which is what makes the preset
// chip a lookup rather than a second query the caller has no signature for.
void AppendScopeJson(
		QString &out,
		const std::vector<Purple::Session> &sessions,
		int64 fromMs,
		int64 toMs,
		Purple::BucketUnit unit,
		const QTimeZone &zone) {
	const auto totals = Purple::RangeTotals(sessions, fromMs, toMs);
	out += QStringLiteral("\"totalMs\":");
	out += QString::number(qint64(totals.totalMs));
	out += QStringLiteral(",\"activeMs\":");
	out += QString::number(qint64(totals.activeMs));
	out += QStringLiteral(",\"hiddenMs\":");
	out += QString::number(qint64(totals.hiddenMs));
	out += QStringLiteral(",\"chats\":");
	AppendChatTotalsJson(out, totals.chats);
	out += QStringLiteral(",\"kinds\":");
	AppendKindTotalsJson(out, totals.kinds);
	out += QStringLiteral(",\"buckets\":");
	AppendBucketsJson(out, sessions, fromMs, toMs, unit, zone);
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
			"\"hideAddStory\":true,"
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
	// The other half of that answer: a peek with no clock on it, which is what
	// `auto_off = "off"' means. The deadline above is zero for it and zero for
	// "no peek is running", and the chips have to tell those apart to light the
	// last position rather than none.
	json += QStringLiteral(",\"peekUntilStopped\":");
	AppendJsonBool(json, Purple::PeekUntilStopped(gate.state));
	// How long a tap lasts ON THIS DEVICE. The overload that takes the identity
	// is the one to call from here: this is a phone, so it answers `[peek]
	// tap_mobile' and five minutes when the file does not write it, rather than
	// the `tap'-else-`auto_off' a keyboard gets. Nothing downstream picks a
	// length of its own - Java is handed the resolved number and shows it.
	// Zero is a real answer here: a tap that starts a peek with no clock.
	json += QStringLiteral(",\"peekTap\":");
	json += QString::number(
		Purple::PeekTapSeconds(gate.settings, gate.device));
	// The lengths the chips offer, shortest first. From the core for the reason
	// the core says out loud: two hand-written lists is how a phone's chips and
	// a desktop's row come to offer different minutes for the same feature.
	json += QStringLiteral(",\"peekDetents\":[");
	const auto &detents = Purple::PeekDetentsSeconds();
	for (auto i = 0; i != int(detents.size()); ++i) {
		if (i) {
			json += QChar(',');
		}
		json += QString::number(detents[i]);
	}
	json += QChar(']');
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
	// And [schedule] outside as the file spells it, which is a different
	// question: the one above is what this device runs between its windows, and
	// this is the key the schedule screen's row edits. They differ exactly when
	// a chosen ruleset overrides the key - and a row that showed the resolved
	// value would then be showing one preset while saving over another.
	json += QStringLiteral(",\"scheduleOutsideKey\":");
	AppendJsonString(json, gate.settings.schedule.outside);
	// The enabled check is here rather than inside ScheduleRuleNow(): the
	// resolved-schedule overload answers what the rules say, and a schedule
	// switched off has rules that say things it is not doing.
	const auto ruleNow = gate.settings.schedule.enabled
		? Purple::ScheduleRuleNow(activeSchedule, now)
		: nullptr;
	// Which of the eight sentences the status line is, and every part of it -
	// decided in the core, because both apps used to decide it and their
	// answers had already parted company. The wording stays here and in the
	// desktop's own strings; only the deciding moved. See ScheduleStatusNow().
	const auto status = Purple::ScheduleStatusNow(
		gate.settings,
		gate.state,
		now,
		gate.device);
	json += QStringLiteral(",\"scheduleStatus\":{\"kind\":");
	// By name, not by the enumerator's number: the Java side switches on this,
	// and a name that stops matching is a compile error there where a number
	// that quietly shifts is a wrong sentence on a screen.
	AppendJsonString(json, ScheduleStatusKindName(status.kind));
	json += QStringLiteral(",\"preset\":");
	AppendJsonString(json, status.preset);
	json += QStringLiteral(",\"till\":");
	json += QString::number(status.till);
	json += QStringLiteral(",\"outside\":");
	AppendJsonString(json, status.outside);
	json += QStringLiteral(",\"nextStart\":");
	json += QString::number(qint64(status.nextStart));
	json += QStringLiteral(",\"nextPreset\":");
	AppendJsonString(json, status.nextPreset);
	json += QStringLiteral(",\"pausedUntil\":");
	json += QString::number(qint64(status.pausedUntil));
	json += QChar('}');
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
	// Whether the "add a story" button - your own row at the head of the
	// stories strip - is off the strip while this preset runs. Its own key
	// rather than a consequence of `stories' and the lists: the row is yours,
	// so whether some list happens to name Saved Messages has nothing to say
	// about a door to posting. From the resolution for the same reason
	// hideArchive is, and a cached resolution carries it.
	json += QStringLiteral(",\"hideAddStory\":");
	AppendJsonBool(json, gate.resolved.hideAddStory);
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
	// Purple: [screen_time], the switch and the three thresholds. The recorder
	// reads all four on every touch, every keystroke burst and every chat
	// opened, so they travel with the resolution rather than being asked for -
	// a JNI call with a file parse behind it on the touch path is exactly what
	// this cannot cost. The budgets are NOT here: they are only ever needed
	// beside what has been spent against them, and that is the ledger's call.
	//
	// Off is the default and the file has to say so before anything is
	// written down. Nothing should start keeping a record of what you looked
	// at because a version number moved.
	json += QStringLiteral(",\"screenTimeEnabled\":");
	AppendJsonBool(json, gate.settings.screenTime.enabled);
	json += QStringLiteral(",\"screenTimeActionSpan\":");
	json += QString::number(gate.settings.screenTime.actionSpanSeconds);
	json += QStringLiteral(",\"screenTimeActiveGap\":");
	json += QString::number(gate.settings.screenTime.activeGapSeconds);
	json += QStringLiteral(",\"screenTimeIdleAfter\":");
	json += QString::number(gate.settings.screenTime.idleAfterSeconds);
	json += QStringLiteral(",\"screenTimeRetentionDays\":");
	json += QString::number(gate.settings.screenTime.retentionDays);
	json += QStringLiteral(",\"screenTimeBudgets\":");
	json += QString::number(int(gate.settings.screenTime.budgets.size()));
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
	// What the stories strip does while this preset runs, and whichever folders
	// overrode it for their own people. From the resolution, like hideArchive
	// and hideEverywhere: it is the preset's own decision, so a resolution
	// restored from the cache has to carry it.
	json += QStringLiteral(",\"stories\":");
	json += QString::number(int(gate.resolved.stories));
	json += QStringLiteral(",\"storyFolders\":");
	AppendStoryFoldersJson(json, gate.resolved);
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

// The three peek gestures - start, extend, stop - mirroring the desktop's
// StartPeekFor(), ExtendPeekBy() and EndPeek().
//
// They replaced one togglePeekNative(), which could only ever start the one
// length in the file. A phone taps the control for a look at the list it is
// holding and a desktop fires a key mid-sentence, so the lengths parted company
// - and once a length is a number the user picks, "the other way round" is not
// what a second tap means.
//
// The state change itself is the core's: StartPeek(), ExtendPeek() and
// StopPeek() are two lines each and both apps carried both of them. What stays
// here is the refusal, which needs the running resolution rather than the file:
// a peek over Normal has nothing to reveal, and starting one anyway would leave
// a peek running that no chat list could show the end of. That is also why
// these take the lock, unlike setPresetNative.

namespace {

// What all three answer with. `peeking' and `left' are read back off the state
// they just wrote rather than predicted, so an extension that the cap clipped
// reports where it actually landed.
[[nodiscard]] QString PeekRefusedJson() {
	return QStringLiteral("{\"refused\":true,\"peeking\":false,"
		"\"extended\":false,\"seconds\":0,\"left\":0,\"text\":null}");
}

[[nodiscard]] QString PeekChangeJson(
		const Purple::State &state,
		bool extended,
		int seconds,
		int64 nowUnix) {
	auto json = QStringLiteral("{\"refused\":false,\"peeking\":");
	AppendJsonBool(json, Purple::PeekLive(state, nowUnix));
	json += QStringLiteral(",\"extended\":");
	AppendJsonBool(json, extended);
	json += QStringLiteral(",\"seconds\":");
	json += QString::number(seconds);
	// Zero when nothing is running AND when the running peek has no clock on
	// it, exactly as the core's PeekLeftSeconds() answers it - the caller that
	// needs to tell those apart asks peekUntilStopped on the next load.
	json += QStringLiteral(",\"left\":");
	json += QString::number(Purple::PeekLeftSeconds(state, nowUnix));
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, Purple::SerializeState(state));
	json += QChar('}');
	return json;
}

} // namespace

// Starts a peek of `seconds', or one with no clock on it when `seconds' is
// zero - which is what `[peek] auto_off = "off"' means, and what the last chip
// asks for.
//
// Starting one while another is running RESTARTS it at the new length rather
// than adding to it. A chip that says "5 min" and leaves eleven on the clock is
// a chip lying about what it did; adding is the tap-again gesture's job, where
// there is no number on screen to contradict.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_startPeekNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jint seconds) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return ToJava(env, PeekRefusedJson());
	}
	const auto now = NowUnix();
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	[[maybe_unused]] const auto started = Purple::StartPeek(state, now, seconds);
	return ToJava(env, PeekChangeJson(state, false, int(seconds), now));
}

// Adds `addSeconds' to a running peek, measured from the deadline it already
// has rather than from now, so two quick taps buy two lengths and not one and a
// bit. Capped at kPeekExtendCapSeconds from now: past an hour it is not a peek
// any more, it is the preset off, and there is a plainer way to say that.
//
// `extended' comes back false, with nothing touched, when there is nothing to
// move - the cap already spent, or a peek with no clock on it, which is already
// longer than any extension could make it. The caller ends the peek instead: a
// control that can start something it cannot stop is worse than one that means
// two things.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_extendPeekNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jint addSeconds) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return ToJava(env, PeekRefusedJson());
	}
	const auto now = NowUnix();
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	const auto extended = Purple::ExtendPeek(
		state,
		now,
		addSeconds,
		Purple::kPeekExtendCapSeconds);
	return ToJava(env, PeekChangeJson(state, extended, int(addSeconds), now));
}

// Purple: the device locked, or the app did.
//
// The client reports the event and nothing more; whether it ends a peek is the
// core's answer, out of the three `[peek]' keys, and on a phone the screen lock
// is never one of them - a phone locks all day by itself. The default here is
// that NOTHING a phone does ends a peek early: `end_on_app_lock_mobile_p' is off
// until somebody turns it on, because a passcode lock on a five-minute timer
// would end a peek every few minutes.
//
// `extended' comes back false and `text' is null when nothing moved, so the
// caller writes no file and rebuilds no list for a lock that changed nothing.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_peekLockedNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8,
		jint screenLock) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return ToJava(env, PeekRefusedJson());
	}
	const auto now = NowUnix();
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	const auto ended = Purple::EndPeekForLock(
		state,
		now,
		gate.settings,
		gate.device,
		screenLock
			? Purple::LockKind::Screen
			: Purple::LockKind::App);
	if (!ended) {
		return ToJava(env, PeekRefusedJson());
	}
	return ToJava(env, PeekChangeJson(state, false, 0, now));
}

// Which chip a length is: the index into peekDetents, or its size for zero -
// "until I stop", which lives one position past the last of them.
//
// A pure function of the core's row, and it is here rather than in Java for the
// reason the row itself is: the rounding is part of the row. A length between
// two detents reads as the nearer one and a tie reads as the SHORTER, because a
// control that silently rounds a peek up is a control that reveals more than
// was asked for - and that is not a rule two apps should each have a copy of.
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleCore_peekDetentIndexNative(
		JNIEnv *,
		jclass,
		jint seconds) {
	return Purple::PeekDetentIndex(int(seconds));
}

// Ends one. The deadline is cleared with the flag, so a peek started again
// later cannot inherit a stale one.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_stopPeekNative(
		JNIEnv *env,
		jclass,
		jbyteArray stateUtf8) {
	auto stateText = QString();
	if (!ReadUtf8(env, stateUtf8, stateText)) {
		return nullptr;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	// Refused under Normal like the other two, although stopping there could do
	// no harm: nothing offers it under Normal, and a flag left over from before
	// a switch to Normal is cleared by the load itself rather than by a caller
	// who would have to know to ask.
	if (!gate.loaded || gate.resolved.normal) {
		return ToJava(env, PeekRefusedJson());
	}
	auto state = Purple::ParseState(stateText, QStringLiteral("state.toml"));
	Purple::StopPeek(state);
	return ToJava(env, PeekChangeJson(state, false, 0, NowUnix()));
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

// One tick of the schedule.
//
// Every word of the policy is ScheduleStep() in the core now - the pause that
// lifts itself and then catches up in the same pass, and the asymmetry where a
// window starting overrides a preset chosen by hand while a window ending only
// undoes one the schedule itself put there. It was written here and in the
// desktop's Runner::tick(), twice, with the same comments copied between them
// and a test on neither.
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
	const auto step = Purple::ScheduleStep(
		gate.settings,
		Purple::ParseState(stateText, QStringLiteral("state.toml")),
		QDateTime::currentDateTime(),
		device);
	if (!step) {
		return nullptr;
	}

	auto json = QStringLiteral("{\"applied\":");
	AppendJsonBool(json, step->applied);
	// Whether this tick is the one that lifted a pause that had run out. The
	// caller reloads on it as well as on `applied': the pause is what its own
	// ticking is conditioned on, so a cleared pause nothing reread would stop
	// the clock that had just cleared it.
	json += QStringLiteral(",\"unpaused\":");
	AppendJsonBool(json, step->unpaused);
	json += QStringLiteral(",\"target\":");
	AppendJsonString(json, step->target);
	// The core fills these in on every step; the caller's line only reads them
	// when nothing was applied, and emptying them here is what keeps the JSON
	// exactly the shape the Java side has always parsed.
	json += QStringLiteral(",\"kept\":");
	AppendJsonString(json, step->applied ? QString() : step->kept);
	json += QStringLiteral(",\"keptSource\":");
	AppendJsonString(json, step->applied
		? QString()
		: Purple::PresetSourceName(step->keptSource));
	json += QStringLiteral(",\"text\":");
	AppendJsonString(json, Purple::SerializeState(step->state));
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
	// Null on the Java side means nothing remembers what the schedule wanted
	// when this session began, which is what the core's optional says too - so
	// the `known' flag this used to carry beside the string is gone.
	const auto remembered = enterTarget
		? std::optional<QString>(FromJava(env, enterTarget))
		: std::nullopt;

	// The policy is FocusStep() in the core now, both halves of it. It was
	// written here and in the desktop's purple_focus.cpp, and the two had
	// already parted company: the missed-window rule on the way out was only
	// ever in this copy.
	const auto step = Purple::FocusStep(
		gate.settings,
		Purple::ParseState(stateText, QStringLiteral("state.toml")),
		(active == JNI_TRUE),
		remembered,
		QDateTime::currentDateTime(),
		gate.device);
	if (!step) {
		return nullptr;
	}
	const auto &state = step->state;

	auto json = QStringLiteral("{\"change\":");
	AppendJsonString(json, Purple::FocusChangeName(step->change));
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
	if (step->enterTarget) {
		AppendJsonString(json, *step->enterTarget);
	} else {
		json += QStringLiteral("null");
	}
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

// Purple: the three budget ops.
//
// A budget is addressed by `index' - ScreenTimeBudget::sourceIndex, its place
// in the raw [[screen_time.budgets]] array counting the ones the parser threw
// away - together with `expectedTarget', the target the screen read off it. The
// core refuses when the budget there says something else, which is what stops a
// dialog left open across somebody else's edit from rewriting the wrong budget.
// The target is the only identity a budget has: everything else in the block is
// what the dialog is open to change.
//
// The five fields are the five keys the core writes. `kind', `chat' and
// `preset' are not among them: they are what the parser makes of the target
// string, and a bridge that sent them too would be a second copy of a grammar
// that already lives in one place.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_appendBudgetNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jstring target,
		jint perDaySeconds,
		jstring mode,
		jint snoozeSeconds,
		jint snoozesPerDay) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::AppendBudget(
		text,
		QStringLiteral("settings.toml"),
		ReadBudget(
			env,
			target,
			perDaySeconds,
			mode,
			snoozeSeconds,
			snoozesPerDay))));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_setBudgetNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jint index,
		jstring expectedTarget,
		jstring target,
		jint perDaySeconds,
		jstring mode,
		jint snoozeSeconds,
		jint snoozesPerDay) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::SetBudget(
		text,
		QStringLiteral("settings.toml"),
		int(index),
		FromJava(env, expectedTarget),
		ReadBudget(
			env,
			target,
			perDaySeconds,
			mode,
			snoozeSeconds,
			snoozesPerDay))));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_removeBudgetNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jint index,
		jstring expectedTarget) {
	auto text = QString();
	if (!ReadUtf8(env, settingsUtf8, text)) {
		return nullptr;
	}
	return ToJava(env, SpliceJson(Purple::RemoveBudget(
		text,
		QStringLiteral("settings.toml"),
		int(index),
		FromJava(env, expectedTarget))));
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

namespace {

// The int Java sends for a status shape, as the core's enum.
//
// The numbering is the wire format rather than an implementation detail - it
// crosses JNI as a plain int - so it is pinned here, at the boundary it crosses,
// the way storyShownNative pins StoryMode at its own. The core pins it a second
// time in purple_settings.h, where a reordering would actually be typed.
//
// Anything outside the three is the caller's bug, and Exact is the answer that
// changes nothing: it is the shape with nothing to explain and nothing to put
// over the top of, so a status the fork cannot read is left as the app wrote it
// - the same direction visibleNative and storyShownNative take when asked about
// something they cannot place.
[[nodiscard]] Purple::LastSeenShape ShapeOrExact(jint shape) {
	static_assert(int(Purple::LastSeenShape::Exact) == 0);
	static_assert(int(Purple::LastSeenShape::Coarse) == 1);
	static_assert(int(Purple::LastSeenShape::LongAgo) == 2);

	return (shape >= jint(Purple::LastSeenShape::Exact)
		&& shape <= jint(Purple::LastSeenShape::LongAgo))
		? Purple::LastSeenShape(shape)
		: Purple::LastSeenShape::Exact;
}

} // namespace

// Why a last seen reads the way it does, as the core's LastSeenReason numbers
// it.
//
// A shape and a flag rather than the status object because the core has no idea
// what a TL_userStatusRecently is and must not learn: the client that owns the
// TL layer flattens its status to one of the three shapes, and the one shared
// rule answers the same way in both forks. One value rather than the pair of
// booleans this used to take, so that "exact AND coarse" is not a state Java
// can hand over for the core to have an opinion about.
//
// Touches neither the gate nor any file, so it costs no lock - it is asked
// once per status line drawn.
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleCore_lastSeenReasonNative(
		JNIEnv *,
		jclass,
		jint shape,
		jboolean byMe) {
	return jint(Purple::ReasonFor(
		ShapeOrExact(shape),
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

// The whole last-seen decision for one person: which of the three lines to
// draw, whether tapping it opens the trade, and the numbers each needs.
//
// From the gate rather than from a handed-over state.toml, unlike every other
// trade native above it. This one sits on the drawing path - a status line
// asks it once per bind, and a member list binds a row at a time while it
// scrolls - so parsing the file per row is not a thing to do. visibleNative is
// asked the same way for the same reason, and the gate is kept current the
// same way too: the trade writes state.toml and then reloads, which is what
// every other state write in this app already does.
//
// Five longs rather than JSON because the caller wants numbers and this is the
// path where a parse would show. The order is line, tappable, was_online,
// read_at, cooldown_left - see PurpleCore.LastSeenNote, which is the only
// reader.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleCore_lastSeenNoteNative(
		JNIEnv *env,
		jclass,
		jlong bareId,
		jint reason,
		jint shape) {
	// A reason from outside the enum is the caller's bug, and None is the
	// answer that adds nothing rather than the one that invents a tail.
	const auto known = (reason >= jint(Purple::LastSeenReason::None))
		&& (reason <= jint(Purple::LastSeenReason::HiddenByThem));
	auto note = Purple::LastSeenNote();
	{
		auto &gate = TheGate();
		const auto lock = std::lock_guard(gate.mutex);
		note = Purple::LastSeenNoteNow(
			gate.settings,
			gate.state,
			Purple::PeerIdValue(bareId),
			known
				? Purple::LastSeenReason(reason)
				: Purple::LastSeenReason::None,
			ShapeOrExact(shape),
			NowUnix());
	}
	const auto result = env->NewLongArray(5);
	if (!result) {
		return nullptr;
	}
	const jlong values[5] = {
		jlong(note.line),
		jlong(note.tappable ? 1 : 0),
		jlong(note.wasOnlineUnix),
		jlong(note.readAtUnix),
		jlong(note.cooldownLeftSeconds),
	};
	env->SetLongArrayRegion(result, 0, 5, values);
	return result;
}

// Purple: whether the running preset would hide this chat with the peek set
// aside - which is the only way to answer "time in hidden chats while peeking".
//
// visibleNative cannot do it. A peek is a field of the resolution, so Visible()
// already answers Always for every chat while one runs, and every caller of
// that native gets the revealed answer for free - which is exactly right for
// drawing a chat list and exactly wrong for asking why the chat is on it.
//
// Same packed int as visibleNative, minus the view bits: this is asked once per
// chat opened, not once per row, so there is nothing to ride along.
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleCore_visibleUnpeekedNative(
		JNIEnv *,
		jclass,
		jlong bareId,
		jint kind) {
	constexpr auto kNotifyBit = jint(0x10);
	constexpr auto kStock = jint(int(Purple::ShowMode::Always)) | kNotifyBit;

	if (kind < 0 || kind > int(Purple::ChatKind::Bot)) {
		return kStock;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return kStock;
	}
	// A copy with the peek put back, rather than clearing it on the live one:
	// the gate is shared and the chat list is asking it the other question from
	// another thread while this runs.
	auto unpeeked = gate.resolved;
	unpeeked.peeking = false;
	const auto visibility = Purple::Visible(
		gate.settings,
		unpeeked,
		Purple::PeerIdValue(bareId),
		Purple::ChatKind(kind));
	return jint(int(visibility.show))
		| (visibility.notify ? kNotifyBit : jint(0));
}

// Purple: whether this peer belongs on the stories strip.
//
// The desktop's Purple::StoryShown(), with the same precedence: a peek reveals
// everything, then a folder beats a list entry and both beat the preset's own
// policy, and `follow' means "whoever the preset does not exclude outright".
//
// Two arguments the desktop does not need, and both for the same reason the
// exempt-folder plumbing has them: the core has never heard of a Telegram
// folder, so folder membership is Java's answer to give. `folderMode' is what
// the folders holding this chat said about its stories - -1 for "none of them
// said anything", which is the usual case - and `exemptFolder' is whether a
// folder pulls the chat into the preset's view, which speaks for it here the
// way it already does for hiding.
//
// `hasUnseen' is passed in rather than looked up, exactly as the desktop passes
// it: the caller has the unread state right where the filter runs, so the seen
// half of the ladder costs nothing and this needs no access to story state.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleCore_storyShownNative(
		JNIEnv *,
		jclass,
		jlong bareId,
		jint kind,
		jboolean hasUnseen,
		jint folderMode,
		jboolean exemptFolder) {
	static_assert(int(Purple::StoryMode::Always) == 0);
	static_assert(int(Purple::StoryMode::Unseen) == 1);
	static_assert(int(Purple::StoryMode::Never) == 2);

	if (kind < 0 || kind > int(Purple::ChatKind::Bot)) {
		// A kind the core cannot be asked about. Shown is the harmless
		// direction, the same one visibleNative takes.
		return JNI_TRUE;
	}
	auto &gate = TheGate();
	const auto lock = std::lock_guard(gate.mutex);
	if (!gate.loaded || gate.resolved.normal) {
		return JNI_TRUE;
	}
	if (gate.resolved.stories == Purple::StoryPolicy::None) {
		return JNI_FALSE;
	} else if (gate.resolved.peeking) {
		// A peek reveals stories along with the chats they belong to. It is one
		// deliberate look at what the preset is keeping from you, and a strip
		// that stayed filtered through it would be answering a question nobody
		// asked twice.
		return JNI_TRUE;
	}

	// A folder beats a list entry, the same way a folder already beats one for
	// hiding, and both beat the preset's own policy.
	auto mode = std::optional<Purple::StoryMode>();
	if (folderMode >= int(Purple::StoryMode::Always)
		&& folderMode <= int(Purple::StoryMode::Never)) {
		mode = Purple::StoryMode(folderMode);
	}
	const auto id = Purple::PeerIdValue(bareId);
	if (!mode) {
		if (const auto entry = Purple::MatchList(
				gate.settings,
				gate.resolved,
				id,
				Purple::ChatKind(kind))) {
			mode = entry->stories;
		}
	}
	if (mode) {
		switch (*mode) {
		case Purple::StoryMode::Always: return JNI_TRUE;
		case Purple::StoryMode::Unseen: return hasUnseen;
		case Purple::StoryMode::Never: return JNI_FALSE;
		}
	}

	// Whether the preset excludes this chat outright, rather than merely
	// holding it back for being quiet. The distinction is the whole of what
	// `follow' means: a story IS new activity, so somebody the preset admits
	// under `message' or `mention' keeps theirs, and only somebody it refuses
	// altogether loses it. The peek is already ruled out above, so asking the
	// live resolution here asks it unpeeked.
	const auto excluded = [&] {
		if (exemptFolder == JNI_TRUE) {
			return false;
		}
		return Purple::Visible(
			gate.settings,
			gate.resolved,
			id,
			Purple::ChatKind(kind)).show == Purple::ShowMode::Never;
	};
	switch (gate.resolved.stories) {
	case Purple::StoryPolicy::All: return JNI_TRUE;
	case Purple::StoryPolicy::AllUnseen: return hasUnseen;
	case Purple::StoryPolicy::Follow:
		return excluded() ? JNI_FALSE : JNI_TRUE;
	case Purple::StoryPolicy::FollowUnseen:
		return (hasUnseen == JNI_TRUE && !excluded()) ? JNI_TRUE : JNI_FALSE;
	case Purple::StoryPolicy::None: return JNI_FALSE;
	}
	return JNI_TRUE;
}

// Purple: the whole screen-time report for one window, in one call.
//
// One native rather than one per view because every number on that screen comes
// out of the same derivation: parsing the log and deriving its sessions is the
// expensive half, and asking for the headline, the chart, the ranks, the heat
// map and the comparison separately would pay for it five times.
//
// `settingsUtf8' rather than the loaded gate: the thresholds have to be the
// same ones the caller is about to name on screen, and the gate can reload
// underneath a screen that is mid-draw. The log arrives as bytes for the same
// reason - the caller owns the file.
//
// Filters are not a parameter. The scope object is written once for the whole
// range and once per preset that appears in it, and each carries its chats,
// its per-kind totals and its buckets split by kind - so "active only", a kind
// chip and a preset chip are all lookups in what came back rather than a
// second query.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_screenTimeReportNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jbyteArray logUtf8,
		jlong fromMs,
		jlong toMs,
		jint bucketUnit,
		jstring timeZone) {
	auto settingsText = QString();
	auto logText = QString();
	if (!ReadUtf8(env, settingsUtf8, settingsText)
		|| !ReadUtf8(env, logUtf8, logText)) {
		return nullptr;
	}
	const auto parsed = Purple::ParseSettings(
		settingsText,
		QStringLiteral("settings.toml"));
	const auto &screenTime = parsed.settings.screenTime;
	const auto events = Purple::ParseEventLog(logText);
	const auto sessions = Purple::DeriveSessions(events, screenTime);
	const auto zone = ZoneFrom(FromJava(env, timeZone));
	const auto unit = BucketUnitFrom(bucketUnit);
	const auto from = int64(fromMs);
	const auto to = int64(toMs);

	auto json = QString();
	json.reserve(4096);
	json += QChar('{');
	AppendScopeJson(json, sessions, from, to, unit, zone);

	// The reading load: every day in the range folded onto one clock. Always
	// asked for, whatever unit the bars are in, because it answers a different
	// question - not how much, but when in a day - and it is what a schedule
	// window gets placed by.
	json += QStringLiteral(",\"reading\":");
	AppendBucketsJson(
		json,
		sessions,
		from,
		to,
		Purple::BucketUnit::HourOfDay,
		zone);

	// Hour by weekday. Drawn for a month, where a row of thirty-one bars has
	// stopped saying anything about the shape of a week.
	const auto heat = Purple::HeatMapFor(sessions, from, to, zone);
	json += QStringLiteral(",\"heat\":{\"totalMs\":[");
	for (auto day = 0; day != 7; ++day) {
		if (day) {
			json += QChar(',');
		}
		json += QChar('[');
		for (auto hour = 0; hour != 24; ++hour) {
			if (hour) {
				json += QChar(',');
			}
			json += QString::number(qint64(heat.totalMs[day][hour]));
		}
		json += QChar(']');
	}
	json += QStringLiteral("],\"activeMs\":[");
	for (auto day = 0; day != 7; ++day) {
		if (day) {
			json += QChar(',');
		}
		json += QChar('[');
		for (auto hour = 0; hour != 24; ++hour) {
			if (hour) {
				json += QChar(',');
			}
			json += QString::number(qint64(heat.activeMs[day][hour]));
		}
		json += QChar(']');
	}
	json += QStringLiteral("]}");

	// This range against the one of the same length before it. `changePercent'
	// is absent rather than zero for a previous range with nothing in it: there
	// is no percentage change from zero, and a number written there would be
	// invented.
	const auto compare = Purple::Compare(sessions, from, to);
	json += QStringLiteral(",\"compare\":{\"deltaMs\":");
	json += QString::number(qint64(compare.deltaMs));
	json += QStringLiteral(",\"previousTotalMs\":");
	json += QString::number(qint64(compare.previous.totalMs));
	json += QStringLiteral(",\"previousActiveMs\":");
	json += QString::number(qint64(compare.previous.activeMs));
	json += QStringLiteral(",\"changePercent\":");
	if (compare.changePercent) {
		json += QString::number(*compare.changePercent);
	} else {
		json += QStringLiteral("null");
	}
	json += QChar('}');

	// How much peek there was: how many and how long. Derived from the events
	// rather than from the sessions, because a peek is not a chat being in
	// front of you - most are started to look at the list itself - and it is a
	// different question from the `hiddenMs' in the scope above, which is time
	// spent IN the chats the preset hides.
	const auto peeked = Purple::PeekUsageIn(
		Purple::DerivePeeks(events),
		from,
		to);
	json += QStringLiteral(",\"peekCount\":");
	json += QString::number(peeked.count);
	json += QStringLiteral(",\"peekMs\":");
	json += QString::number(qint64(peeked.totalMs));

	// One scope per preset that actually appears, in the core's own rank order,
	// so the chips are the presets the range HAS rather than the ones the file
	// declares. An empty name is Normal, spelled as the file spells it.
	const auto totals = Purple::RangeTotals(sessions, from, to);
	json += QStringLiteral(",\"presets\":[");
	auto firstPreset = true;
	for (const auto &preset : totals.presets) {
		if (!firstPreset) {
			json += QChar(',');
		}
		firstPreset = false;
		auto only = std::vector<Purple::Session>();
		for (const auto &session : sessions) {
			if (!session.preset.compare(preset.preset, Qt::CaseInsensitive)) {
				only.push_back(session);
			}
		}
		json += QStringLiteral("{\"preset\":");
		AppendJsonString(json, preset.preset);
		json += QChar(',');
		AppendScopeJson(json, only, from, to, unit, zone);
		json += QChar('}');
	}
	json += QStringLiteral("]}");
	return ToJava(env, json);
}

// Purple: what every budget has spent on one day.
//
// The day is handed over as a moment inside it rather than as a date, because
// which day a moment belongs to is a local-time question and the answer has to
// be the same one the buckets used. `settings' carries the thresholds AND the
// budgets for the reason BudgetLedger takes them together: counting today with
// one set of rules and judging it by another would be a ledger nobody could
// check.
//
// Each entry carries the budget as written as well as what it spent, so the
// cover can name it and the screen can list it without a second call to work
// out what "kind:groups" meant.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_screenTimeLedgerNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jbyteArray logUtf8,
		jlong dayStartMs,
		jstring timeZone) {
	auto settingsText = QString();
	auto logText = QString();
	if (!ReadUtf8(env, settingsUtf8, settingsText)
		|| !ReadUtf8(env, logUtf8, logText)) {
		return nullptr;
	}
	const auto parsed = Purple::ParseSettings(
		settingsText,
		QStringLiteral("settings.toml"));
	const auto &screenTime = parsed.settings.screenTime;
	const auto events = Purple::ParseEventLog(logText);
	const auto zone = ZoneFrom(FromJava(env, timeZone));
	const auto day = QDateTime::fromMSecsSinceEpoch(int64(dayStartMs), zone)
		.date();
	const auto ledger = Purple::BudgetLedger(events, screenTime, day, zone);

	auto json = QString();
	json += QChar('[');
	auto first = true;
	for (const auto &entry : ledger) {
		if (!first) {
			json += QChar(',');
		}
		first = false;
		const auto &budget = screenTime.budgets[entry.index];
		json += QStringLiteral("{\"index\":");
		json += QString::number(entry.index);
		// Where the block actually sits in the file, counting the budgets the
		// parser threw away. `index' above addresses the parsed list - which is
		// what the cover keys its snooze counts on - while this is the address
		// the splice ops take, and a file with a broken budget in the middle is
		// the case where the two differ.
		json += QStringLiteral(",\"sourceIndex\":");
		json += QString::number(budget.sourceIndex);
		json += QStringLiteral(",\"target\":");
		AppendJsonString(json, budget.target);
		json += QStringLiteral(",\"targetKind\":");
		json += QString::number(int(budget.kind));
		json += QStringLiteral(",\"chat\":");
		json += QString::number(qint64(budget.chat));
		json += QStringLiteral(",\"chatKind\":");
		json += QString::number(int(budget.chatKind));
		json += QStringLiteral(",\"preset\":");
		AppendJsonString(json, budget.preset);
		json += QStringLiteral(",\"spentMs\":");
		json += QString::number(qint64(entry.spentMs));
		json += QStringLiteral(",\"perDayMs\":");
		json += QString::number(qint64(entry.perDayMs));
		json += QStringLiteral(",\"reached\":");
		AppendJsonBool(json, entry.reached);
		json += QStringLiteral(",\"mode\":");
		json += QString::number(int(budget.mode));
		json += QStringLiteral(",\"snoozeSeconds\":");
		json += QString::number(budget.snoozeSeconds);
		json += QStringLiteral(",\"snoozesPerDay\":");
		json += QString::number(budget.snoozesPerDay);
		// Whether one more snooze is left is the core's rule and not a
		// subtraction the caller should be doing: a soft budget never puts a
		// cover up at all, and a `snoozes_per_day = 0' makes a hard one
		// absolute. The count of snoozes already taken is the caller's, since
		// nothing about it is in the log.
		json += QStringLiteral(",\"snoozable\":");
		AppendJsonBool(json, Purple::CoverAllowed(0, budget));
		json += QChar('}');
	}
	json += QChar(']');
	return ToJava(env, json);
}

// Purple: the log with everything past `retention_days' dropped, as the text to
// write back.
//
// Pure, like the core's Prune: this hands back what the file should say and the
// caller does the writing, because the caller is the only thing that knows how
// to replace a file it is also appending to. Null for a log that could not be
// read; the caller leaves the file alone on a null rather than truncating it.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_screenTimePruneNative(
		JNIEnv *env,
		jclass,
		jbyteArray settingsUtf8,
		jbyteArray logUtf8,
		jlong nowMs) {
	auto settingsText = QString();
	auto logText = QString();
	if (!ReadUtf8(env, settingsUtf8, settingsText)
		|| !ReadUtf8(env, logUtf8, logText)) {
		return nullptr;
	}
	const auto parsed = Purple::ParseSettings(
		settingsText,
		QStringLiteral("settings.toml"));
	const auto kept = Purple::Prune(
		Purple::ParseEventLog(logText),
		int64(nowMs),
		parsed.settings.screenTime.retentionDays);
	auto out = QString();
	out.reserve(logText.size());
	for (const auto &event : kept) {
		out += Purple::FormatEvent(event);
		out += QChar('\n');
	}
	return ToJava(env, out);
}

// Purple: a span of milliseconds as the one string both clients print.
//
// The rules - which units appear, where the seconds stop, what zero says - are
// the core's and are tested there. This client used to carry its own copy in
// Java, and the copy had already drifted: it knew no day unit, so a chat with
// twenty-seven hours in it read as "27 h", and it said a bare "0" where the
// core says "0 s".
//
// Pure: no gate, no files, nothing to fail. A row that calls it once per bind
// pays one JNI call for an answer nobody would want cached separately from the
// formatter that produces it.
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_formatSpanNative(
		JNIEnv *env,
		jclass,
		jlong ms) {
	return ToJava(env, Purple::FormatSpan(int64(ms)));
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
