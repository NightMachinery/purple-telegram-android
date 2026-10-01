#include <jni.h>

#include "purple/purple_config_diff.h"
#include "purple/purple_config_sync.h"
#include "purple/purple_state.h"
#include "purple/purple_sync_config_describe.h"
#include "purple/purple_sync_config_flow.h"
#include "purple/purple_sync_inventory.h"
#include "purple/purple_sync_local_state.h"

#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>

#include <cstdint>
#include <optional>
#include <vector>

namespace {

constexpr auto kInputLimit = 4 * 1024 * 1024;

constexpr auto kTransportFetched = 0;
constexpr auto kTransportVanished = 1;
constexpr auto kTransportChanged = 2;
constexpr auto kTransportOversized = 3;
constexpr auto kTransportInaccessible = 4;
constexpr auto kTransportRequestFailed = 5;
constexpr auto kTransportCancelled = 6;
constexpr auto kTransportInvalid = 7;

constexpr auto kLocalPresent = 0;
constexpr auto kLocalAbsent = 1;
constexpr auto kLocalInvalid = 2;

[[nodiscard]] QString Name(Purple::SyncHistoryPageStatus value) {
	switch (value) {
	case Purple::SyncHistoryPageStatus::More: return u"More"_q;
	case Purple::SyncHistoryPageStatus::Complete: return u"Complete"_q;
	case Purple::SyncHistoryPageStatus::Stalled: return u"Stalled"_q;
	}
	return u"Stalled"_q;
}

[[nodiscard]] QString Name(Purple::SyncAccountInventoryStatus value) {
	switch (value) {
	case Purple::SyncAccountInventoryStatus::Complete: return u"Complete"_q;
	case Purple::SyncAccountInventoryStatus::NeedsReview:
		return u"NeedsReview"_q;
	case Purple::SyncAccountInventoryStatus::Incomplete:
		return u"Incomplete"_q;
	}
	return u"Incomplete"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigReviewStatus value) {
	using Status = Purple::SyncConfigReviewStatus;
	switch (value) {
	case Status::Ready: return u"Ready"_q;
	case Status::NeedsReview: return u"NeedsReview"_q;
	case Status::Incomplete: return u"Incomplete"_q;
	case Status::CloneDetected: return u"CloneDetected"_q;
	case Status::AccountUnavailable: return u"AccountUnavailable"_q;
	case Status::AccountUnbound: return u"AccountUnbound"_q;
	case Status::StoreError: return u"StoreError"_q;
	case Status::InvalidSettings: return u"InvalidSettings"_q;
	case Status::UsingLastGood: return u"UsingLastGood"_q;
	}
	return u"NeedsReview"_q;
}

[[nodiscard]] QString Name(Purple::ConfigSyncVerdict value) {
	using Verdict = Purple::ConfigSyncVerdict;
	switch (value) {
	case Verdict::Invalid: return u"Invalid"_q;
	case Verdict::Pending: return u"Pending"_q;
	case Verdict::Conflict: return u"Conflict"_q;
	case Verdict::Choose: return u"Choose"_q;
	case Verdict::UpdateReady: return u"UpdateReady"_q;
	case Verdict::Adopt: return u"Adopt"_q;
	case Verdict::Empty: return u"Empty"_q;
	case Verdict::LocalChanges: return u"LocalChanges"_q;
	case Verdict::UpToDate: return u"UpToDate"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigMessage value) {
	using Message = Purple::SyncConfigMessage;
	switch (value) {
	case Message::NeedsReviewWithPending: return u"NeedsReviewWithPending"_q;
	case Message::NeedsReview: return u"NeedsReview"_q;
	case Message::Incomplete: return u"Incomplete"_q;
	case Message::CloneDetected: return u"CloneDetected"_q;
	case Message::AccountUnavailable: return u"AccountUnavailable"_q;
	case Message::AccountUnbound: return u"AccountUnbound"_q;
	case Message::StoreError: return u"StoreError"_q;
	case Message::InvalidSettings: return u"InvalidSettings"_q;
	case Message::InvalidRecords: return u"InvalidRecords"_q;
	case Message::Pending: return u"Pending"_q;
	case Message::ChooseBound: return u"ChooseBound"_q;
	case Message::ChooseUnbound: return u"ChooseUnbound"_q;
	case Message::ConflictConcurrent: return u"ConflictConcurrent"_q;
	case Message::ConflictSplitBound: return u"ConflictSplitBound"_q;
	case Message::ConflictSplitUnbound: return u"ConflictSplitUnbound"_q;
	case Message::UpdateReady: return u"UpdateReady"_q;
	case Message::UpdateMissing: return u"UpdateMissing"_q;
	case Message::AdoptBound: return u"AdoptBound"_q;
	case Message::AdoptUnbound: return u"AdoptUnbound"_q;
	case Message::NotPublishableAbsent: return u"NotPublishableAbsent"_q;
	case Message::NotPublishableInvalid: return u"NotPublishableInvalid"_q;
	case Message::EmptyBound: return u"EmptyBound"_q;
	case Message::EmptyUnbound: return u"EmptyUnbound"_q;
	case Message::LocalChangesEdited: return u"LocalChangesEdited"_q;
	case Message::LocalChangesOwnStale: return u"LocalChangesOwnStale"_q;
	case Message::UpToDateAlone: return u"UpToDateAlone"_q;
	case Message::UpToDateWith: return u"UpToDateWith"_q;
	case Message::UsingLastGood: return u"UsingLastGood"_q;
	case Message::UsingLastGoodWithPending: return u"UsingLastGoodWithPending"_q;
	}
	return u"InvalidRecords"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigAction value) {
	using Action = Purple::SyncConfigAction;
	switch (value) {
	case Action::None: return u"None"_q;
	case Action::Publish: return u"Publish"_q;
	case Action::Join: return u"Join"_q;
	case Action::ReviewUpdate: return u"ReviewUpdate"_q;
	case Action::Choose: return u"Choose"_q;
	case Action::PublishChanges: return u"PublishChanges"_q;
	case Action::FinishSending: return u"FinishSending"_q;
	}
	return u"None"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigApplyPlanStatus value) {
	using Status = Purple::SyncConfigApplyPlanStatus;
	switch (value) {
	case Status::Ready: return u"Ready"_q;
	case Status::NeedsRecheck: return u"NeedsRecheck"_q;
	case Status::NeedsReview: return u"NeedsReview"_q;
	case Status::InvalidChoice: return u"InvalidChoice"_q;
	}
	return u"NeedsReview"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigApplyCompletionStatus value) {
	using Status = Purple::SyncConfigApplyCompletionStatus;
	switch (value) {
	case Status::Ready: return u"Ready"_q;
	case Status::InvalidPlan: return u"InvalidPlan"_q;
	case Status::ReadBackMismatch: return u"ReadBackMismatch"_q;
	case Status::AdoptRefused: return u"AdoptRefused"_q;
	}
	return u"InvalidPlan"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigCommitStatus value) {
	using Status = Purple::SyncConfigCommitStatus;
	switch (value) {
	case Status::Ready: return u"Ready"_q;
	case Status::Unchanged: return u"Unchanged"_q;
	case Status::InvalidTransition: return u"InvalidTransition"_q;
	case Status::InvalidState: return u"InvalidState"_q;
	}
	return u"InvalidTransition"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigPublishEntry value) {
	using Entry = Purple::SyncConfigPublishEntry;
	switch (value) {
	case Entry::Refuse: return u"Refuse"_q;
	case Entry::FinishStaged: return u"FinishStaged"_q;
	case Entry::NewContent: return u"NewContent"_q;
	}
	return u"Refuse"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigPublishStatus value) {
	using Status = Purple::SyncConfigPublishStatus;
	switch (value) {
	case Status::Confirmed: return u"Confirmed"_q;
	case Status::AlreadySynced: return u"AlreadySynced"_q;
	case Status::NeedsReview: return u"NeedsReview"_q;
	case Status::CloneDetected: return u"CloneDetected"_q;
	case Status::Incomplete: return u"Incomplete"_q;
	case Status::AccountUnavailable: return u"AccountUnavailable"_q;
	case Status::AccountUnbound: return u"AccountUnbound"_q;
	case Status::StoreError: return u"StoreError"_q;
	case Status::InvalidSettings: return u"InvalidSettings"_q;
	case Status::OutcomeUnknown: return u"OutcomeUnknown"_q;
	case Status::Cancelled: return u"Cancelled"_q;
	case Status::StillSending: return u"StillSending"_q;
	}
	return u"NeedsReview"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigPostStep value) {
	using Step = Purple::SyncConfigPostStep;
	switch (value) {
	case Step::Finish: return u"Finish"_q;
	case Step::ConfirmFound: return u"ConfirmFound"_q;
	case Step::Stage: return u"Stage"_q;
	case Step::Post: return u"Post"_q;
	}
	return u"Finish"_q;
}

[[nodiscard]] QString Name(Purple::SyncOwnInventoryStatus value) {
	using Status = Purple::SyncOwnInventoryStatus;
	switch (value) {
	case Status::Incomplete: return u"Incomplete"_q;
	case Status::NeedsReview: return u"NeedsReview"_q;
	case Status::Absent: return u"Absent"_q;
	case Status::Present: return u"Present"_q;
	case Status::PendingFound: return u"PendingFound"_q;
	case Status::CloneDetected: return u"CloneDetected"_q;
	}
	return u"NeedsReview"_q;
}

[[nodiscard]] QString Name(Purple::SyncSettingsFileStatus value) {
	using Status = Purple::SyncSettingsFileStatus;
	switch (value) {
	case Status::Present: return u"Present"_q;
	case Status::Absent: return u"Absent"_q;
	case Status::Invalid: return u"Invalid"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::ConfigDiffLineKind value) {
	using Kind = Purple::ConfigDiffLineKind;
	switch (value) {
	case Kind::Context: return u"Context"_q;
	case Kind::Removed: return u"Removed"_q;
	case Kind::Added: return u"Added"_q;
	}
	return u"Context"_q;
}

[[nodiscard]] QString Name(Purple::ConfigChangeKind value) {
	using Kind = Purple::ConfigChangeKind;
	switch (value) {
	case Kind::Added: return u"Added"_q;
	case Kind::Removed: return u"Removed"_q;
	case Kind::Changed: return u"Changed"_q;
	}
	return u"Changed"_q;
}

[[nodiscard]] QString Name(Purple::SyncConfigApplyFailureKind value) {
	using Kind = Purple::SyncConfigApplyFailureKind;
	switch (value) {
	case Kind::None: return u"None"_q;
	case Kind::JoinedNotWritten: return u"JoinedNotWritten"_q;
	case Kind::WrittenStateNotSaved: return u"WrittenStateNotSaved"_q;
	case Kind::WrittenNotReadBack: return u"WrittenNotReadBack"_q;
	case Kind::NothingDone: return u"NothingDone"_q;
	}
	return u"NothingDone"_q;
}

[[nodiscard]] std::optional<Purple::SyncConfigApplyStatus> ApplyStatusOf(
		const QString &name) {
	using Status = Purple::SyncConfigApplyStatus;
	for (const auto status : {
		Status::Applied,
		Status::NeedsRecheck,
		Status::NeedsReview,
		Status::InvalidChoice,
		Status::AccountUnavailable,
		Status::AccountUnbound,
		Status::SetupFailed,
		Status::StoreError,
		Status::InvalidSettings,
		Status::HistoryError,
		Status::WriteError,
	}) {
		const auto candidate = [&] {
			switch (status) {
			case Status::Applied: return u"Applied"_q;
			case Status::NeedsRecheck: return u"NeedsRecheck"_q;
			case Status::NeedsReview: return u"NeedsReview"_q;
			case Status::InvalidChoice: return u"InvalidChoice"_q;
			case Status::AccountUnavailable: return u"AccountUnavailable"_q;
			case Status::AccountUnbound: return u"AccountUnbound"_q;
			case Status::SetupFailed: return u"SetupFailed"_q;
			case Status::StoreError: return u"StoreError"_q;
			case Status::InvalidSettings: return u"InvalidSettings"_q;
			case Status::HistoryError: return u"HistoryError"_q;
			case Status::WriteError: return u"WriteError"_q;
			}
			return QString();
		}();
		if (candidate == name) {
			return status;
		}
	}
	return std::nullopt;
}

[[nodiscard]] std::optional<Purple::SyncConfigRestoreStatus> RestoreStatusOf(
		const QString &name) {
	using Status = Purple::SyncConfigRestoreStatus;
	for (const auto status : {
		Status::Restored,
		Status::Unchanged,
		Status::NotFound,
		Status::FileDidNotExist,
		Status::NotText,
		Status::InvalidReason,
		Status::InvalidSettings,
		Status::HistoryError,
		Status::WriteError,
	}) {
		const auto candidate = [&] {
			switch (status) {
			case Status::Restored: return u"Restored"_q;
			case Status::Unchanged: return u"Unchanged"_q;
			case Status::NotFound: return u"NotFound"_q;
			case Status::FileDidNotExist: return u"FileDidNotExist"_q;
			case Status::NotText: return u"NotText"_q;
			case Status::InvalidReason: return u"InvalidReason"_q;
			case Status::InvalidSettings: return u"InvalidSettings"_q;
			case Status::HistoryError: return u"HistoryError"_q;
			case Status::WriteError: return u"WriteError"_q;
			}
			return QString();
		}();
		if (candidate == name) {
			return status;
		}
	}
	return std::nullopt;
}

[[nodiscard]] bool ReadBytes(
		JNIEnv *env,
		jbyteArray input,
		QByteArray &bytes,
		QString &error,
		jsize limit = kInputLimit) {
	if (!input) {
		error = u"NullInput"_q;
		return false;
	}
	const auto size = env->GetArrayLength(input);
	if (size > limit) {
		error = u"SizeLimit"_q;
		return false;
	}
	bytes.resize(size);
	if (size) {
		env->GetByteArrayRegion(input, 0, size,
			reinterpret_cast<jbyte*>(bytes.data()));
	}
	if (env->ExceptionCheck()) {
		error = u"JavaException"_q;
		return false;
	}
	return true;
}

[[nodiscard]] bool ReadOptionalBytes(
		JNIEnv *env,
		jbyteArray input,
		QByteArray &bytes,
		QString &error) {
	if (!input) {
		bytes.clear();
		return true;
	}
	return ReadBytes(env, input, bytes, error);
}

[[nodiscard]] bool ReadString(JNIEnv *env, jstring input, QString &text) {
	if (!input) {
		return false;
	}
	const auto size = env->GetStringLength(input);
	const auto *chars = env->GetStringChars(input, nullptr);
	if (!chars) {
		return false;
	}
	text = QString::fromUtf16(
		reinterpret_cast<const char16_t*>(chars), size);
	env->ReleaseStringChars(input, chars);
	return true;
}

[[nodiscard]] bool ReadOptionalString(
		JNIEnv *env,
		jstring input,
		std::optional<QString> &text) {
	if (!input) {
		text = std::nullopt;
		return true;
	}
	auto value = QString();
	if (!ReadString(env, input, value)) {
		return false;
	}
	text = value;
	return true;
}

[[nodiscard]] bool ReadStrings(
		JNIEnv *env,
		jobjectArray input,
		std::vector<QString> &list) {
	if (!input) {
		return false;
	}
	const auto count = env->GetArrayLength(input);
	list.clear();
	list.reserve(count);
	for (auto index = 0; index != count; ++index) {
		const auto element = static_cast<jstring>(
			env->GetObjectArrayElement(input, index));
		auto value = QString();
		const auto read = ReadString(env, element, value);
		if (element) {
			env->DeleteLocalRef(element);
		}
		if (!read) {
			return false;
		}
		list.push_back(value);
	}
	return true;
}

[[nodiscard]] bool ReadOptionalStrings(
		JNIEnv *env,
		jobjectArray input,
		std::optional<std::vector<QString>> &list) {
	if (!input) {
		list = std::nullopt;
		return true;
	}
	auto values = std::vector<QString>();
	if (!ReadStrings(env, input, values)) {
		return false;
	}
	list = std::move(values);
	return true;
}

[[nodiscard]] jbyteArray ToBytes(JNIEnv *env, const QByteArray &bytes) {
	const auto result = env->NewByteArray(jsize(bytes.size()));
	if (result && !bytes.isEmpty()) {
		env->SetByteArrayRegion(result, 0, jsize(bytes.size()),
			reinterpret_cast<const jbyte*>(bytes.constData()));
	}
	return result;
}

[[nodiscard]] jstring ToString(JNIEnv *env, const QString &text) {
	return env->NewString(
		reinterpret_cast<const jchar*>(text.utf16()),
		jsize(text.size()));
}

[[nodiscard]] jobject Reply(
		JNIEnv *env,
		QJsonObject metadata,
		const std::optional<QByteArray> &record = std::nullopt,
		const std::optional<QByteArray> &state = std::nullopt,
		const std::optional<QByteArray> &text = std::nullopt,
		const std::vector<QByteArray> &texts = {}) {
	const auto type = env->FindClass(
		"org/telegram/messenger/purple/PurpleSyncCore$RawReply");
	if (!type) {
		return nullptr;
	}
	const auto constructor = env->GetMethodID(type, "<init>",
		"([B[B[B[[BLjava/lang/String;)V");
	if (!constructor) {
		return nullptr;
	}
	const auto recordBytes = record ? ToBytes(env, *record) : nullptr;
	const auto stateBytes = state ? ToBytes(env, *state) : nullptr;
	const auto textBytes = text ? ToBytes(env, *text) : nullptr;
	const auto arrayType = env->FindClass("[B");
	if (!arrayType) {
		return nullptr;
	}
	const auto textList = env->NewObjectArray(
		jsize(texts.size()), arrayType, nullptr);
	if (!textList) {
		return nullptr;
	}
	for (auto index = 0; index != int(texts.size()); ++index) {
		const auto element = ToBytes(env, texts[index]);
		if (!element) {
			return nullptr;
		}
		env->SetObjectArrayElement(textList, index, element);
		env->DeleteLocalRef(element);
	}
	const auto json = QJsonDocument(metadata).toJson(QJsonDocument::Compact);
	const auto javaJson = ToString(env, QString::fromUtf8(json));
	if (env->ExceptionCheck()) {
		return nullptr;
	}
	return env->NewObject(type, constructor,
		recordBytes, stateBytes, textBytes, textList, javaJson);
}

[[nodiscard]] jobject Invalid(JNIEnv *env, const QString &error) {
	return Reply(env, {
		{ u"status"_q, u"Invalid"_q }, { u"error"_q, error },
	});
}

[[nodiscard]] QJsonObject Valid() {
	return {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
	};
}

[[nodiscard]] QJsonArray Keys(const std::vector<QString> &keys) {
	auto result = QJsonArray();
	for (const auto &key : keys) {
		result.push_back(key);
	}
	return result;
}

[[nodiscard]] QJsonArray Keys(const std::vector<Purple::ConfigHead> &heads) {
	auto result = QJsonArray();
	for (const auto &head : heads) {
		result.push_back(head.key);
	}
	return result;
}

[[nodiscard]] QJsonArray Keys(
		const std::vector<Purple::ConfigVersion> &versions) {
	auto result = QJsonArray();
	for (const auto &version : versions) {
		result.push_back(version.key);
	}
	return result;
}

[[nodiscard]] QJsonObject NameJson(const Purple::SyncDeviceNameParts &name) {
	return {
		{ u"platform"_q, name.platform },
		{ u"shortId"_q, name.shortId },
	};
}

[[nodiscard]] QJsonObject HeadJson(const Purple::SyncConfigHeadRecord &record) {
	return {
		{ u"messageId"_q, int(record.messageId) },
		{ u"space"_q, record.head.space },
		{ u"install"_q, record.head.install },
		{ u"seq"_q, QString::number(record.head.seq) },
		{ u"key"_q, record.head.key },
		{ u"lineage"_q, Keys(record.head.lineage) },
		{ u"device"_q, record.device },
		{ u"platform"_q, record.platform },
		{ u"app"_q, record.app },
		{ u"at"_q, QString::number(record.at) },
		{ u"newerSchema"_q, record.newerSchema },
		{ u"name"_q, NameJson(Purple::SyncDeviceNameOf(record)) },
	};
}

[[nodiscard]] std::optional<Purple::SyncSettingsFile> ReadLocal(
		JNIEnv *env,
		jint status,
		jbyteArray text,
		jboolean usingLastGood,
		QString &error) {
	using Status = Purple::SyncSettingsFileStatus;
	const auto lastGood = (usingLastGood == JNI_TRUE);
	switch (status) {
	case kLocalPresent: {
		if (!text) {
			error = u"NullInput"_q;
			return std::nullopt;
		}
		if (env->GetArrayLength(text) > Purple::kSyncSettingsMaximumBytes) {
			return Purple::MakeSyncSettingsFile(Status::Invalid, {}, lastGood);
		}
		auto bytes = QByteArray();
		if (!ReadBytes(env, text, bytes, error)) {
			return std::nullopt;
		}
		return Purple::MakeSyncSettingsFile(Status::Present, bytes, lastGood);
	}
	case kLocalAbsent:
		return Purple::MakeSyncSettingsFile(Status::Absent, {}, lastGood);
	case kLocalInvalid:
		return Purple::MakeSyncSettingsFile(Status::Invalid, {}, lastGood);
	}
	error = u"InvalidLocalStatus"_q;
	return std::nullopt;
}

[[nodiscard]] bool ReadState(
		JNIEnv *env,
		jbyteArray input,
		std::optional<Purple::SyncLocalState> &state,
		QString &error) {
	if (!input) {
		state = std::nullopt;
		return true;
	}
	auto bytes = QByteArray();
	if (!ReadBytes(env, input, bytes, error)) {
		return false;
	}
	const auto parsed = Purple::ParseSyncLocalState(bytes);
	if (!parsed) {
		error = u"State"_q;
		return false;
	}
	state = parsed.state;
	return true;
}

[[nodiscard]] std::optional<Purple::SyncCandidateStatus> TransportStatus(
		jint code) {
	using Status = Purple::SyncCandidateStatus;
	switch (code) {
	case kTransportVanished: return Status::Vanished;
	case kTransportChanged: return Status::Changed;
	case kTransportOversized: return Status::Oversized;
	case kTransportInaccessible: return Status::Inaccessible;
	case kTransportRequestFailed: return Status::RequestFailed;
	case kTransportCancelled: return Status::Cancelled;
	case kTransportInvalid: return Status::Invalid;
	}
	return std::nullopt;
}

[[nodiscard]] std::optional<Purple::SyncAccountInventoryResult> ReadInventory(
		JNIEnv *env,
		jlong accountUserId,
		jboolean scanComplete,
		jintArray ids,
		jintArray transport,
		jlongArray documentIds,
		jlongArray editDates,
		jobjectArray records,
		QString &error) {
	if (!ids || !transport || !documentIds || !editDates || !records) {
		error = u"NullInput"_q;
		return std::nullopt;
	}
	if (accountUserId < 0) {
		error = u"InvalidAccount"_q;
		return std::nullopt;
	}
	const auto count = env->GetArrayLength(ids);
	if (env->GetArrayLength(transport) != count
		|| env->GetArrayLength(documentIds) != count
		|| env->GetArrayLength(editDates) != count
		|| env->GetArrayLength(records) != count) {
		error = u"InventoryLength"_q;
		return std::nullopt;
	}
	auto idList = std::vector<jint>(count);
	auto codes = std::vector<jint>(count);
	auto documents = std::vector<jlong>(count);
	auto edits = std::vector<jlong>(count);
	if (count) {
		env->GetIntArrayRegion(ids, 0, count, idList.data());
		env->GetIntArrayRegion(transport, 0, count, codes.data());
		env->GetLongArrayRegion(documentIds, 0, count, documents.data());
		env->GetLongArrayRegion(editDates, 0, count, edits.data());
	}
	if (env->ExceptionCheck()) {
		error = u"JavaException"_q;
		return std::nullopt;
	}
	auto scan = Purple::SyncHistoryScanResult();
	scan.status = scanComplete
		? Purple::SyncHistoryScanStatus::Complete
		: Purple::SyncHistoryScanStatus::RequestFailed;
	scan.scannedCount = uint64_t(count);
	auto read = Purple::SyncCandidateReadResult();
	read.records.reserve(count);
	for (auto index = 0; index != count; ++index) {
		const auto id = int32_t(idList[index]);
		scan.candidateIds.push_back(id);
		auto record = Purple::SyncCandidateRecord{ .id = id };
		if (codes[index] == kTransportFetched) {
			const auto element = static_cast<jbyteArray>(
				env->GetObjectArrayElement(records, index));
			if (!element) {
				error = u"NullRecord"_q;
				return std::nullopt;
			}
			if (env->GetArrayLength(element)
					> Purple::kSyncRecordMaximumBytes) {
				record.status = Purple::SyncCandidateStatus::Oversized;
			} else {
				auto bytes = QByteArray();
				const auto readBytes = ReadBytes(env, element, bytes, error);
				if (!readBytes) {
					env->DeleteLocalRef(element);
					return std::nullopt;
				}
				record = Purple::ClassifySyncCandidate(id, std::move(bytes));
			}
			env->DeleteLocalRef(element);
		} else if (const auto status = TransportStatus(codes[index])) {
			record.status = *status;
		} else {
			error = u"InvalidTransport"_q;
			return std::nullopt;
		}
		record.documentId = uint64_t(documents[index]);
		record.editDate = uint64_t(edits[index]);
		read.records.push_back(std::move(record));
	}
	read.status = Purple::AggregateSyncCandidateRead(read.records);
	return Purple::FinishSyncAccountInventory(
		uint64_t(accountUserId),
		std::move(scan),
		std::move(read));
}

struct ApplyInput {
	Purple::SyncAccountInventoryResult inventory;
	std::optional<Purple::SyncLocalState> state;
	QByteArray staged;
	Purple::SyncSettingsFile local;
	QString stamp;
	std::optional<QString> key;
	bool afterJoin = false;
	Purple::SyncSettingsFile preJoin;
};

[[nodiscard]] bool ReadApplyInput(
		JNIEnv *env,
		jlong accountUserId,
		jboolean scanComplete,
		jintArray ids,
		jintArray transport,
		jlongArray documentIds,
		jlongArray editDates,
		jobjectArray records,
		jbyteArray state,
		jbyteArray staged,
		jint localStatus,
		jbyteArray localText,
		jboolean localLastGood,
		jstring stamp,
		jstring chosenKey,
		jboolean afterJoin,
		jint preJoinStatus,
		jbyteArray preJoinText,
		jboolean preJoinLastGood,
		ApplyInput &input,
		QString &error) {
	auto inventory = ReadInventory(env, accountUserId, scanComplete, ids,
		transport, documentIds, editDates, records, error);
	if (!inventory) {
		return false;
	}
	input.inventory = std::move(*inventory);
	if (!ReadState(env, state, input.state, error)
		|| !ReadOptionalBytes(env, staged, input.staged, error)) {
		return false;
	}
	const auto local = ReadLocal(env, localStatus, localText, localLastGood,
		error);
	if (!local) {
		return false;
	}
	input.local = *local;
	if (!ReadString(env, stamp, input.stamp)
		|| !ReadOptionalString(env, chosenKey, input.key)) {
		error = u"NullInput"_q;
		return false;
	}
	input.afterJoin = afterJoin;
	if (input.afterJoin) {
		if (!input.state) {
			error = u"NullInput"_q;
			return false;
		}
		const auto preJoin = ReadLocal(env, preJoinStatus, preJoinText,
			preJoinLastGood, error);
		if (!preJoin) {
			return false;
		}
		input.preJoin = *preJoin;
	}
	return true;
}

struct ApplyPlanned {
	Purple::SyncConfigReview fresh;
	Purple::SyncConfigApplyPlan plan;
};

[[nodiscard]] ApplyPlanned PlanApply(const ApplyInput &input) {
	auto result = ApplyPlanned();
	result.fresh = Purple::ReviewSyncConfigInventory(
		input.inventory,
		input.state ? &*input.state : nullptr,
		input.staged,
		input.local);
	auto expected = input.stamp;
	if (input.afterJoin) {
		const auto before = Purple::ReviewSyncConfigInventory(
			input.inventory,
			nullptr,
			{},
			input.preJoin);
		if (Purple::SyncConfigReviewStamp(before) != input.stamp) {
			result.plan.status = Purple::SyncConfigApplyPlanStatus::NeedsRecheck;
			return result;
		}
		expected = Purple::SyncConfigReviewStamp(
			Purple::SyncConfigJoinedReview(before, *input.state));
	}
	result.plan = Purple::PlanSyncConfigApply(
		result.fresh,
		expected,
		input.key);
	return result;
}

[[nodiscard]] Purple::SyncConfigPublishRequest Request(
		bool pendingOnly,
		std::optional<QString> expectedFingerprint,
		std::optional<std::vector<QString>> expectedParents) {
	return {
		.pendingOnly = pendingOnly,
		.expectedFingerprint = std::move(expectedFingerprint),
		.expectedParents = std::move(expectedParents),
	};
}

[[nodiscard]] QJsonObject PostMetadata(const Purple::SyncConfigPostPlan &plan) {
	auto metadata = Valid();
	metadata.insert(u"step"_q, Name(plan.step));
	metadata.insert(u"publish"_q, Name(plan.status));
	metadata.insert(u"messageId"_q, int(plan.messageId));
	metadata.insert(u"key"_q, plan.nextConfigData.pending);
	metadata.insert(u"own"_q, Name(plan.own.status));
	return metadata;
}

[[nodiscard]] std::optional<QByteArray> PostRecord(
		const Purple::SyncConfigPostPlan &plan) {
	return (plan.step == Purple::SyncConfigPostStep::Finish)
		? std::nullopt
		: std::make_optional(plan.record);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_classifyHistoryPageNative(
		JNIEnv *env, jclass, jint offset, jintArray ids,
		jbooleanArray isMessage, jbooleanArray forwarded,
		jbooleanArray isDocument, jobjectArray captions,
		jobjectArray fileNames) {
	if (!ids || !isMessage || !forwarded || !isDocument || !captions
		|| !fileNames) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto count = env->GetArrayLength(ids);
	if (env->GetArrayLength(isMessage) != count
		|| env->GetArrayLength(forwarded) != count
		|| env->GetArrayLength(isDocument) != count
		|| env->GetArrayLength(captions) != count
		|| env->GetArrayLength(fileNames) != count) {
		return Invalid(env, u"PageLength"_q);
	}
	auto idList = std::vector<jint>(count);
	auto messages = std::vector<jboolean>(count);
	auto forwards = std::vector<jboolean>(count);
	auto documents = std::vector<jboolean>(count);
	if (count) {
		env->GetIntArrayRegion(ids, 0, count, idList.data());
		env->GetBooleanArrayRegion(isMessage, 0, count, messages.data());
		env->GetBooleanArrayRegion(forwarded, 0, count, forwards.data());
		env->GetBooleanArrayRegion(isDocument, 0, count, documents.data());
	}
	if (env->ExceptionCheck()) {
		return Invalid(env, u"JavaException"_q);
	}
	auto items = std::vector<Purple::SyncHistoryPageItem>();
	items.reserve(count);
	for (auto index = 0; index != count; ++index) {
		auto meta = Purple::SyncHistoryMessageMeta{
			.isMessage = bool(messages[index]),
			.forwarded = bool(forwards[index]),
			.isDocument = bool(documents[index]),
		};
		const auto caption = static_cast<jstring>(
			env->GetObjectArrayElement(captions, index));
		if (caption) {
			const auto read = ReadString(env, caption, meta.caption);
			env->DeleteLocalRef(caption);
			if (!read) {
				return Invalid(env, u"JavaException"_q);
			}
		}
		const auto fileName = static_cast<jstring>(
			env->GetObjectArrayElement(fileNames, index));
		if (fileName) {
			auto name = QString();
			const auto read = ReadString(env, fileName, name);
			env->DeleteLocalRef(fileName);
			if (!read) {
				return Invalid(env, u"JavaException"_q);
			}
			meta.fileNames.push_back(name);
		}
		items.push_back({
			.id = int64_t(idList[index]),
			.candidate = Purple::IsSyncHistoryCandidate(meta),
		});
	}
	auto pages = Purple::SyncHistoryPages(int32_t(offset));
	const auto status = pages.Add(items);
	auto candidates = QJsonArray();
	for (const auto id : pages.candidates()) {
		candidates.push_back(int(id));
	}
	auto metadata = Valid();
	metadata.insert(u"page"_q, Name(status));
	metadata.insert(u"offset"_q, int(pages.offset()));
	metadata.insert(u"count"_q, QString::number(pages.count()));
	metadata.insert(u"candidates"_q, candidates);
	return Reply(env, metadata);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_settingsFingerprintNative(
		JNIEnv *env, jclass, jbyteArray input) {
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, input, bytes, error)) {
		return nullptr;
	}
	return ToString(env, Purple::SettingsFingerprint(bytes));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_isConfigVersionKeyNative(
		JNIEnv *env, jclass, jstring key) {
	auto text = QString();
	return ReadString(env, key, text) && Purple::IsConfigVersionKey(text);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_settingsTextWritableNative(
		JNIEnv *env, jclass, jbyteArray input) {
	auto bytes = QByteArray();
	auto error = QString();
	return ReadBytes(env, input, bytes, error)
		&& Purple::SyncSettingsTextWritable(bytes);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_reviewConfigNative(
		JNIEnv *env, jclass, jlong accountUserId, jboolean scanComplete,
		jintArray ids, jintArray transport, jlongArray documentIds,
		jlongArray editDates, jobjectArray records, jbyteArray state,
		jbyteArray staged, jint localStatus, jbyteArray localText,
		jboolean localLastGood, jstring device, jstring platform,
		jstring app) {
	auto error = QString();
	const auto inventory = ReadInventory(env, accountUserId, scanComplete,
		ids, transport, documentIds, editDates, records, error);
	if (!inventory) {
		return Invalid(env, error);
	}
	auto parsed = std::optional<Purple::SyncLocalState>();
	auto stagedBytes = QByteArray();
	if (!ReadState(env, state, parsed, error)
		|| !ReadOptionalBytes(env, staged, stagedBytes, error)) {
		return Invalid(env, error);
	}
	const auto local = ReadLocal(env, localStatus, localText, localLastGood,
		error);
	if (!local) {
		return Invalid(env, error);
	}
	auto deviceId = QString();
	auto writer = Purple::SyncConfigWriter();
	if (!ReadString(env, device, deviceId)
		|| !ReadString(env, platform, writer.platform)
		|| !ReadString(env, app, writer.app)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto review = Purple::ReviewSyncConfigInventory(
		*inventory,
		parsed ? &*parsed : nullptr,
		stagedBytes,
		*local);
	const auto publishable = Purple::SyncSettingsPublishable(
		review,
		deviceId,
		writer);
	const auto description = Purple::DescribeSyncConfigReview(
		review,
		publishable);
	auto heads = QJsonArray();
	auto texts = std::vector<QByteArray>();
	for (const auto &record : review.heads) {
		heads.push_back(HeadJson(record));
		texts.push_back(record.text);
	}
	auto devices = QJsonArray();
	for (const auto &name : description.devices) {
		devices.push_back(NameJson(name));
	}
	auto choices = QJsonArray();
	for (const auto &choice : Purple::SyncConfigChoices(review, publishable)) {
		choices.push_back(QJsonObject{
			{ u"key"_q, choice.key ? QJsonValue(*choice.key) : QJsonValue() },
			{ u"head"_q, choice.head },
			{ u"publishes"_q, choice.publishes },
		});
	}
	auto metadata = Valid();
	metadata.insert(u"inventory"_q, Name(inventory->status));
	metadata.insert(u"review"_q, Name(review.status));
	metadata.insert(u"accountUserId"_q, QString::number(review.accountUserId));
	metadata.insert(u"bound"_q, review.bound);
	metadata.insert(u"space"_q, review.space);
	metadata.insert(u"install"_q, review.state.install);
	metadata.insert(u"pending"_q, !review.state.pending.isEmpty());
	metadata.insert(u"localStatus"_q, Name(review.local.status));
	metadata.insert(u"localFingerprint"_q, review.local.fingerprint);
	metadata.insert(u"localPublishable"_q, publishable);
	metadata.insert(u"verdict"_q, Name(review.plan.verdict));
	metadata.insert(u"ownStale"_q, review.plan.ownStale);
	metadata.insert(u"heads"_q, heads);
	metadata.insert(u"offered"_q, Keys(review.plan.offered));
	metadata.insert(u"same"_q, Keys(review.plan.same));
	if (review.ownHead) {
		metadata.insert(u"ownHead"_q, HeadJson(*review.ownHead));
	}
	metadata.insert(u"message"_q, Name(description.message));
	metadata.insert(u"action"_q, Name(description.action));
	metadata.insert(u"devices"_q, devices);
	metadata.insert(u"at"_q, QString::number(description.at));
	metadata.insert(u"others"_q, description.others);
	metadata.insert(u"choices"_q, choices);
	metadata.insert(u"stamp"_q, Purple::SyncConfigReviewStamp(review));
	return Reply(env, metadata, std::nullopt, std::nullopt, std::nullopt,
		texts);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_diffConfigNative(
		JNIEnv *env, jclass, jbyteArray before, jbyteArray after) {
	auto old = QByteArray();
	auto now = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, before, old, error)
		|| !ReadBytes(env, after, now, error)) {
		return Invalid(env, error);
	}
	const auto diff = Purple::DiffConfigText(old, now);
	auto hunks = QJsonArray();
	for (const auto &hunk : diff.hunks) {
		auto lines = QJsonArray();
		for (const auto &line : hunk.lines) {
			lines.push_back(QJsonObject{
				{ u"kind"_q, Name(line.kind) },
				{ u"oldLine"_q, line.oldLine },
				{ u"newLine"_q, line.newLine },
				{ u"text"_q, line.text },
			});
		}
		hunks.push_back(QJsonObject{
			{ u"oldStart"_q, hunk.oldStart },
			{ u"oldCount"_q, hunk.oldCount },
			{ u"newStart"_q, hunk.newStart },
			{ u"newCount"_q, hunk.newCount },
			{ u"lines"_q, lines },
		});
	}
	const auto summary = Purple::SummarizeConfigChange(old, now);
	auto entries = QJsonArray();
	for (const auto &entry : summary.entries) {
		entries.push_back(QJsonObject{
			{ u"kind"_q, Name(entry.kind) },
			{ u"table"_q, entry.table },
			{ u"label"_q, entry.label },
		});
	}
	auto metadata = Valid();
	metadata.insert(u"identical"_q, diff.identical);
	metadata.insert(u"truncated"_q, diff.truncated);
	metadata.insert(u"added"_q, diff.added);
	metadata.insert(u"removed"_q, diff.removed);
	metadata.insert(u"hunks"_q, hunks);
	metadata.insert(u"summaryParsed"_q, summary.parsed);
	metadata.insert(u"summary"_q, entries);
	return Reply(env, metadata);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_planConfigApplyNative(
		JNIEnv *env, jclass, jlong accountUserId, jboolean scanComplete,
		jintArray ids, jintArray transport, jlongArray documentIds,
		jlongArray editDates, jobjectArray records, jbyteArray state,
		jbyteArray staged, jint localStatus, jbyteArray localText,
		jboolean localLastGood, jstring stamp, jstring chosenKey,
		jboolean afterJoin, jint preJoinStatus, jbyteArray preJoinText,
		jboolean preJoinLastGood) {
	auto input = ApplyInput();
	auto error = QString();
	if (!ReadApplyInput(env, accountUserId, scanComplete, ids, transport,
			documentIds, editDates, records, state, staged, localStatus,
			localText, localLastGood, stamp, chosenKey, afterJoin,
			preJoinStatus, preJoinText, preJoinLastGood, input, error)) {
		return Invalid(env, error);
	}
	const auto planned = PlanApply(input);
	const auto &plan = planned.plan;
	auto metadata = Valid();
	metadata.insert(u"plan"_q, Name(plan.status));
	metadata.insert(u"join"_q, plan.join);
	metadata.insert(u"space"_q, planned.fresh.space);
	metadata.insert(u"verdict"_q, Name(plan.verdict));
	metadata.insert(u"writeRemote"_q, plan.choice.writeRemote);
	metadata.insert(u"publish"_q, plan.choice.publish);
	metadata.insert(u"adopt"_q, Keys(plan.choice.adopt));
	metadata.insert(u"parents"_q, Keys(plan.choice.parents));
	metadata.insert(u"localFingerprint"_q, plan.localFingerprint);
	metadata.insert(u"writeFingerprint"_q, plan.writeFingerprint);
	metadata.insert(u"versionKey"_q, plan.versionKey);
	metadata.insert(u"update"_q, plan.update);
	metadata.insert(u"otherVersionsRemain"_q, plan.otherVersionsRemain);
	if (plan.source) {
		metadata.insert(u"source"_q, HeadJson(*plan.source));
	}
	return Reply(env, metadata, std::nullopt, std::nullopt,
		plan.source ? std::make_optional(plan.source->text) : std::nullopt);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_completeConfigApplyNative(
		JNIEnv *env, jclass, jlong accountUserId, jboolean scanComplete,
		jintArray ids, jintArray transport, jlongArray documentIds,
		jlongArray editDates, jobjectArray records, jbyteArray state,
		jbyteArray staged, jint localStatus, jbyteArray localText,
		jboolean localLastGood, jstring stamp, jstring chosenKey,
		jboolean afterJoin, jint preJoinStatus, jbyteArray preJoinText,
		jboolean preJoinLastGood, jint readBackStatus,
		jbyteArray readBackText) {
	auto input = ApplyInput();
	auto error = QString();
	if (!ReadApplyInput(env, accountUserId, scanComplete, ids, transport,
			documentIds, editDates, records, state, staged, localStatus,
			localText, localLastGood, stamp, chosenKey, afterJoin,
			preJoinStatus, preJoinText, preJoinLastGood, input, error)) {
		return Invalid(env, error);
	}
	const auto readBack = ReadLocal(env, readBackStatus, readBackText,
		JNI_FALSE, error);
	if (!readBack) {
		return Invalid(env, error);
	}
	const auto planned = PlanApply(input);
	const auto completion = Purple::CompleteSyncConfigApply(
		planned.plan,
		*readBack);
	auto metadata = Valid();
	metadata.insert(u"plan"_q, Name(planned.plan.status));
	metadata.insert(u"completion"_q, Name(completion.status));
	metadata.insert(u"fingerprint"_q, completion.fingerprint);
	metadata.insert(u"adopted"_q, completion.adopted.has_value());
	metadata.insert(u"nextVerdict"_q, Name(completion.nextVerdict));
	metadata.insert(u"publishNeeded"_q, completion.publishNeeded);
	metadata.insert(u"expectedParents"_q, Keys(completion.expectedParents));
	metadata.insert(u"promiseKept"_q, completion.promiseKept);
	auto next = std::optional<QByteArray>();
	if (completion.status == Purple::SyncConfigApplyCompletionStatus::Ready
		&& completion.adopted
		&& input.state) {
		const auto commit = Purple::CheckSyncConfigDataCommit(
			*input.state,
			Purple::SyncLocalConfigOf(*completion.adopted));
		metadata.insert(u"commit"_q, Name(commit.status));
		if (commit.status == Purple::SyncConfigCommitStatus::Ready) {
			next = commit.canonical;
		}
	}
	return Reply(env, metadata, std::nullopt, next);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_checkConfigCommitNative(
		JNIEnv *env, jclass, jbyteArray current, jbyteArray next) {
	auto currentBytes = QByteArray();
	auto nextBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, current, currentBytes, error)
		|| !ReadBytes(env, next, nextBytes, error)) {
		return Invalid(env, error);
	}
	const auto parsed = Purple::ParseSyncLocalState(currentBytes);
	if (!parsed) {
		return Invalid(env, u"State"_q);
	}
	auto metadata = Valid();
	const auto proposed = Purple::ParseSyncLocalState(nextBytes);
	if (!proposed) {
		metadata.insert(u"commit"_q,
			Name(Purple::SyncConfigCommitStatus::InvalidState));
		return Reply(env, metadata);
	}
	const auto check = Purple::CheckSyncConfigDataCommit(
		parsed.state,
		proposed.state.configData);
	auto status = check.status;
	if ((status == Purple::SyncConfigCommitStatus::Ready
			|| status == Purple::SyncConfigCommitStatus::Unchanged)
		&& check.canonical != nextBytes) {
		status = Purple::SyncConfigCommitStatus::InvalidTransition;
	}
	metadata.insert(u"commit"_q, Name(status));
	return Reply(env, metadata, std::nullopt,
		(status == Purple::SyncConfigCommitStatus::Ready)
			? std::make_optional(check.canonical)
			: std::nullopt);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_planPostEntryNative(
		JNIEnv *env, jclass, jboolean pendingOnly,
		jstring expectedFingerprint, jobjectArray expectedParents,
		jboolean staged) {
	auto fingerprint = std::optional<QString>();
	auto parents = std::optional<std::vector<QString>>();
	if (!ReadOptionalString(env, expectedFingerprint, fingerprint)
		|| !ReadOptionalStrings(env, expectedParents, parents)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto entry = Purple::PlanSyncConfigPublishEntry(
		Request(pendingOnly, fingerprint, parents),
		staged);
	auto metadata = Valid();
	metadata.insert(u"entry"_q, Name(entry));
	return Reply(env, metadata);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_planConfigPostNative(
		JNIEnv *env, jclass, jlong accountUserId, jboolean scanComplete,
		jintArray ids, jintArray transport, jlongArray documentIds,
		jlongArray editDates, jobjectArray records, jbyteArray state,
		jbyteArray staged, jint localStatus, jbyteArray localText,
		jboolean localLastGood, jboolean pendingOnly,
		jstring expectedFingerprint,
		jobjectArray expectedParents, jbyteArray accountToken, jlong now,
		jstring platform, jstring app, jboolean sendQueued) {
	auto error = QString();
	const auto inventory = ReadInventory(env, accountUserId, scanComplete,
		ids, transport, documentIds, editDates, records, error);
	if (!inventory) {
		return Invalid(env, error);
	}
	auto parsed = std::optional<Purple::SyncLocalState>();
	auto stagedBytes = QByteArray();
	auto token = QByteArray();
	if (!ReadState(env, state, parsed, error)
		|| !ReadOptionalBytes(env, staged, stagedBytes, error)
		|| !ReadOptionalBytes(env, accountToken, token, error)) {
		return Invalid(env, error);
	}
	if (!parsed) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto local = ReadLocal(env, localStatus, localText, localLastGood,
		error);
	if (!local) {
		return Invalid(env, error);
	}
	auto fingerprint = std::optional<QString>();
	auto parents = std::optional<std::vector<QString>>();
	auto writer = Purple::SyncConfigWriter();
	if (!ReadOptionalString(env, expectedFingerprint, fingerprint)
		|| !ReadOptionalStrings(env, expectedParents, parents)
		|| !ReadString(env, platform, writer.platform)
		|| !ReadString(env, app, writer.app)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto plan = Purple::PlanSyncConfigPost(
		*parsed,
		token,
		stagedBytes,
		*inventory,
		*local,
		Request(pendingOnly, fingerprint, parents),
		int64_t(now),
		writer,
		sendQueued
			? Purple::SyncConfigSendQueue::HoldsSyncRecord
			: Purple::SyncConfigSendQueue::Empty);
	return Reply(env, PostMetadata(plan), PostRecord(plan));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_checkStagedPostNative(
		JNIEnv *env, jclass, jlong accountUserId, jboolean scanComplete,
		jintArray ids, jintArray transport, jlongArray documentIds,
		jlongArray editDates, jobjectArray records, jbyteArray state,
		jbyteArray staged, jbyteArray accountToken) {
	auto error = QString();
	const auto inventory = ReadInventory(env, accountUserId, scanComplete,
		ids, transport, documentIds, editDates, records, error);
	if (!inventory) {
		return Invalid(env, error);
	}
	auto parsed = std::optional<Purple::SyncLocalState>();
	auto stagedBytes = QByteArray();
	auto token = QByteArray();
	if (!ReadState(env, state, parsed, error)
		|| !ReadBytes(env, staged, stagedBytes, error)
		|| !ReadOptionalBytes(env, accountToken, token, error)) {
		return Invalid(env, error);
	}
	if (!parsed) {
		return Invalid(env, u"NullInput"_q);
	}
	auto scoped = *inventory;
	Purple::SelectSyncSpaceIfEmpty(scoped, parsed->space);
	const auto own = Purple::ReconcileOwnConfigInventory(
		*parsed,
		scoped,
		scoped.accountUserId,
		stagedBytes);
	const auto plan = Purple::PlanSyncConfigStagedPost(
		*parsed,
		token,
		own,
		stagedBytes);
	return Reply(env, PostMetadata(plan), PostRecord(plan));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_describeApplyFailureNative(
		JNIEnv *env, jclass, jstring status, jboolean joined,
		jboolean wroteFile, jboolean historyKept, jboolean undoAvailable,
		jboolean otherVersionsRemain) {
	auto name = QString();
	if (!ReadString(env, status, name)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto parsed = ApplyStatusOf(name);
	if (!parsed) {
		return Invalid(env, u"InvalidApplyStatus"_q);
	}
	const auto failure = Purple::DescribeSyncConfigApplyFailure({
		.status = *parsed,
		.joined = bool(joined),
		.wroteFile = bool(wroteFile),
		.historyKept = bool(historyKept),
		.undoAvailable = bool(undoAvailable),
		.otherVersionsRemain = bool(otherVersionsRemain),
	});
	auto metadata = Valid();
	metadata.insert(u"kind"_q, Name(failure.kind));
	metadata.insert(u"joined"_q, failure.joined);
	metadata.insert(u"historyKept"_q, failure.historyKept);
	metadata.insert(u"undoAvailable"_q, failure.undoAvailable);
	metadata.insert(u"otherVersionsRemain"_q, failure.otherVersionsRemain);
	return Reply(env, metadata);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleSyncCore_undoFinishedNative(
		JNIEnv *env, jclass, jstring status) {
	auto name = QString();
	if (!ReadString(env, status, name)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto parsed = RestoreStatusOf(name);
	if (!parsed) {
		return Invalid(env, u"InvalidRestoreStatus"_q);
	}
	auto metadata = Valid();
	metadata.insert(u"finished"_q, Purple::SyncConfigUndoFinished(*parsed));
	return Reply(env, metadata);
}
