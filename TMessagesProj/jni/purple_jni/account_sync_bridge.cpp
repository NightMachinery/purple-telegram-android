#include <jni.h>

#include "purple/purple_config_payload.h"
#include "purple/purple_sync_local_state.h"

#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>

#include <cstdint>
#include <optional>
#include <vector>

namespace {

[[nodiscard]] QString Name(Purple::SyncEnvelopeStatus value) {
	switch (value) {
	case Purple::SyncEnvelopeStatus::Valid: return u"Valid"_q;
	case Purple::SyncEnvelopeStatus::NewerMajor: return u"NewerMajor"_q;
	case Purple::SyncEnvelopeStatus::UnsupportedStream:
		return u"UnsupportedStream"_q;
	case Purple::SyncEnvelopeStatus::UnsupportedEncoding:
		return u"UnsupportedEncoding"_q;
	case Purple::SyncEnvelopeStatus::Invalid: return u"Invalid"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::SyncEnvelopeError value) {
	switch (value) {
	case Purple::SyncEnvelopeError::None: return u"None"_q;
	case Purple::SyncEnvelopeError::InvalidJson: return u"InvalidJson"_q;
	case Purple::SyncEnvelopeError::SizeLimit: return u"SizeLimit"_q;
	case Purple::SyncEnvelopeError::MissingField: return u"MissingField"_q;
	case Purple::SyncEnvelopeError::FieldType: return u"FieldType"_q;
	case Purple::SyncEnvelopeError::InvalidId: return u"InvalidId"_q;
	case Purple::SyncEnvelopeError::InvalidValue: return u"InvalidValue"_q;
	case Purple::SyncEnvelopeError::InvalidHash: return u"InvalidHash"_q;
	case Purple::SyncEnvelopeError::HashMismatch: return u"HashMismatch"_q;
	}
	return u"InvalidValue"_q;
}

[[nodiscard]] QString Name(Purple::ConfigPayloadStatus value) {
	switch (value) {
	case Purple::ConfigPayloadStatus::Valid: return u"Valid"_q;
	case Purple::ConfigPayloadStatus::NewerSchema: return u"NewerSchema"_q;
	case Purple::ConfigPayloadStatus::Invalid: return u"Invalid"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::ConfigPayloadError value) {
	switch (value) {
	case Purple::ConfigPayloadError::None: return u"None"_q;
	case Purple::ConfigPayloadError::InvalidEnvelope:
		return u"InvalidEnvelope"_q;
	case Purple::ConfigPayloadError::WrongStream: return u"WrongStream"_q;
	case Purple::ConfigPayloadError::MissingField: return u"MissingField"_q;
	case Purple::ConfigPayloadError::FieldType: return u"FieldType"_q;
	case Purple::ConfigPayloadError::InvalidValue: return u"InvalidValue"_q;
	case Purple::ConfigPayloadError::InvalidKey: return u"InvalidKey"_q;
	case Purple::ConfigPayloadError::FingerprintMismatch:
		return u"FingerprintMismatch"_q;
	case Purple::ConfigPayloadError::InvalidAncestry:
		return u"InvalidAncestry"_q;
	case Purple::ConfigPayloadError::TomlSyntax: return u"TomlSyntax"_q;
	case Purple::ConfigPayloadError::SchemaMismatch:
		return u"SchemaMismatch"_q;
	}
	return u"InvalidValue"_q;
}

[[nodiscard]] QString Name(Purple::ConfigRecordBuildStatus value) {
	switch (value) {
	case Purple::ConfigRecordBuildStatus::Valid: return u"Valid"_q;
	case Purple::ConfigRecordBuildStatus::NewerSchema:
		return u"NewerSchema"_q;
	case Purple::ConfigRecordBuildStatus::Invalid: return u"Invalid"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::ConfigRecordBuildError value) {
	switch (value) {
	case Purple::ConfigRecordBuildError::None: return u"None"_q;
	case Purple::ConfigRecordBuildError::InvalidUtf8:
		return u"InvalidUtf8"_q;
	case Purple::ConfigRecordBuildError::TomlSyntax:
		return u"TomlSyntax"_q;
	case Purple::ConfigRecordBuildError::InvalidParents:
		return u"InvalidParents"_q;
	case Purple::ConfigRecordBuildError::InvalidVersion:
		return u"InvalidVersion"_q;
	case Purple::ConfigRecordBuildError::Envelope: return u"Envelope"_q;
	case Purple::ConfigRecordBuildError::SelfInspection:
		return u"SelfInspection"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::SyncLocalStatus value) {
	switch (value) {
	case Purple::SyncLocalStatus::Valid: return u"Valid"_q;
	case Purple::SyncLocalStatus::NewerVersion: return u"NewerVersion"_q;
	case Purple::SyncLocalStatus::Invalid: return u"Invalid"_q;
	}
	return u"Invalid"_q;
}

[[nodiscard]] QString Name(Purple::SyncLocalError value) {
	switch (value) {
	case Purple::SyncLocalError::None: return u"None"_q;
	case Purple::SyncLocalError::InvalidJson: return u"InvalidJson"_q;
	case Purple::SyncLocalError::SizeLimit: return u"SizeLimit"_q;
	case Purple::SyncLocalError::MissingField: return u"MissingField"_q;
	case Purple::SyncLocalError::FieldType: return u"FieldType"_q;
	case Purple::SyncLocalError::InvalidId: return u"InvalidId"_q;
	case Purple::SyncLocalError::InvalidValue: return u"InvalidValue"_q;
	case Purple::SyncLocalError::InvalidCounters:
		return u"InvalidCounters"_q;
	case Purple::SyncLocalError::InvalidHash: return u"InvalidHash"_q;
	case Purple::SyncLocalError::InvalidConfig: return u"InvalidConfig"_q;
	case Purple::SyncLocalError::InvalidOwnMessages:
		return u"InvalidOwnMessages"_q;
	}
	return u"InvalidValue"_q;
}

[[nodiscard]] QString Name(Purple::SyncReservationError value) {
	switch (value) {
	case Purple::SyncReservationError::None: return u"None"_q;
	case Purple::SyncReservationError::InvalidState:
		return u"InvalidState"_q;
	case Purple::SyncReservationError::InvalidHash:
		return u"InvalidHash"_q;
	case Purple::SyncReservationError::SequenceExhausted:
		return u"SequenceExhausted"_q;
	}
	return u"InvalidState"_q;
}

[[nodiscard]] QString Name(Purple::SyncOwnMessageError value) {
	switch (value) {
	case Purple::SyncOwnMessageError::None: return u"None"_q;
	case Purple::SyncOwnMessageError::InvalidState: return u"InvalidState"_q;
	case Purple::SyncOwnMessageError::InvalidMessageId:
		return u"InvalidMessageId"_q;
	case Purple::SyncOwnMessageError::InvalidRecord: return u"InvalidRecord"_q;
	case Purple::SyncOwnMessageError::RecordMismatch:
		return u"RecordMismatch"_q;
	case Purple::SyncOwnMessageError::SequenceRegression:
		return u"SequenceRegression"_q;
	case Purple::SyncOwnMessageError::IdConflict: return u"IdConflict"_q;
	case Purple::SyncOwnMessageError::CapacityExceeded:
		return u"CapacityExceeded"_q;
	case Purple::SyncOwnMessageError::NotFound: return u"NotFound"_q;
	case Purple::SyncOwnMessageError::NotOlder: return u"NotOlder"_q;
	case Purple::SyncOwnMessageError::NotAbsent: return u"NotAbsent"_q;
	}
	return u"InvalidState"_q;
}

[[nodiscard]] QString Name(Purple::SyncCloneVerdict value) {
	switch (value) {
	case Purple::SyncCloneVerdict::PendingReconcile:
		return u"PendingReconcile"_q;
	case Purple::SyncCloneVerdict::NoClone: return u"NoClone"_q;
	case Purple::SyncCloneVerdict::DeviceMismatch:
		return u"DeviceMismatch"_q;
	case Purple::SyncCloneVerdict::RemoteAhead:
		return u"RemoteAhead"_q;
	case Purple::SyncCloneVerdict::HashMismatch:
		return u"HashMismatch"_q;
	case Purple::SyncCloneVerdict::InvalidObservation:
		return u"InvalidObservation"_q;
	}
	return u"InvalidObservation"_q;
}

[[nodiscard]] bool ReadBytes(
		JNIEnv *env, jbyteArray input, QByteArray &bytes, QString &error) {
	if (!input) {
		error = u"NullInput"_q;
		return false;
	}
	const auto size = env->GetArrayLength(input);
	if (size > 4 * 1024 * 1024) {
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

[[nodiscard]] jbyteArray ToBytes(JNIEnv *env, const QByteArray &bytes) {
	const auto result = env->NewByteArray(jsize(bytes.size()));
	if (result && !bytes.isEmpty()) {
		env->SetByteArrayRegion(result, 0, jsize(bytes.size()),
			reinterpret_cast<const jbyte*>(bytes.constData()));
	}
	return result;
}

[[nodiscard]] jobject Reply(
		JNIEnv *env,
		QJsonObject metadata,
		const std::optional<QByteArray> &record = std::nullopt,
		const std::optional<QByteArray> &state = std::nullopt,
		const std::optional<QByteArray> &text = std::nullopt) {
	const auto type = env->FindClass(
		"org/telegram/messenger/purple/PurpleAccountSyncCore$RawReply");
	if (!type) {
		return nullptr;
	}
	const auto constructor = env->GetMethodID(type, "<init>",
		"([B[B[BLjava/lang/String;)V");
	if (!constructor) {
		return nullptr;
	}
	const auto recordBytes = record ? ToBytes(env, *record) : nullptr;
	const auto stateBytes = state ? ToBytes(env, *state) : nullptr;
	const auto textBytes = text ? ToBytes(env, *text) : nullptr;
	const auto json = QJsonDocument(metadata).toJson(QJsonDocument::Compact);
	const auto jsonText = QString::fromUtf8(json);
	const auto javaJson = env->NewString(
		reinterpret_cast<const jchar*>(jsonText.utf16()),
		jsize(jsonText.size()));
	if (env->ExceptionCheck()) {
		return nullptr;
	}
	return env->NewObject(type, constructor,
		recordBytes, stateBytes, textBytes, javaJson);
}

[[nodiscard]] jobject Invalid(JNIEnv *env, const QString &error) {
	return Reply(env, {
		{ u"status"_q, u"Invalid"_q }, { u"error"_q, error },
	});
}

[[nodiscard]] QJsonObject StateMetadata(
		const Purple::SyncLocalState &state) {
	return {
		{ u"space"_q, state.space },
		{ u"install"_q, state.install },
		{ u"device"_q, state.createdDevice },
		{ u"seq"_q, QString::number(state.config.seq) },
		{ u"pendingSeq"_q, QString::number(state.config.pendingSeq) },
		{ u"confirmedSeq"_q, QString::number(state.config.confirmedSeq) },
		{ u"payloadHash"_q, state.config.ownHash },
		{ u"key"_q, state.configData.base },
	};
}

struct CheckedRecord {
	Purple::SyncEnvelopeParseResult envelope;
	Purple::ConfigPayloadInspection payload;
};

[[nodiscard]] CheckedRecord CheckRecord(const QByteArray &bytes) {
	auto result = CheckedRecord();
	result.envelope = Purple::ParseSyncEnvelope(bytes);
	if (result.envelope) {
		result.payload = Purple::InspectConfigPayload(result.envelope);
	}
	return result;
}

[[nodiscard]] QJsonObject RecordMetadata(const CheckedRecord &record) {
	auto result = QJsonObject{
		{ u"envelopeStatus"_q, Name(record.envelope.status) },
		{ u"envelopeError"_q, Name(record.envelope.error) },
	};
	if (const auto &header = record.envelope.header; header) {
		result.insert(u"space"_q, header->space);
		result.insert(u"stream"_q, header->stream);
		result.insert(u"install"_q, header->writerInstall);
		result.insert(u"device"_q, header->writerDevice);
		result.insert(u"platform"_q, header->writerPlatform);
		result.insert(u"app"_q, header->writerApp);
		result.insert(u"seq"_q, QString::number(header->seq));
		result.insert(u"at"_q, QString::number(header->at));
	}
	if (!record.envelope) {
		result.insert(u"status"_q, Name(record.envelope.status));
		result.insert(u"error"_q, Name(record.envelope.error));
		return result;
	}
	result.insert(u"payloadStatus"_q, Name(record.payload.status));
	result.insert(u"payloadError"_q, Name(record.payload.error));
	result.insert(u"status"_q, Name(record.payload.status));
	result.insert(u"error"_q, Name(record.payload.error));
	const auto document = record.envelope.envelope.document;
	result.insert(u"payloadHash"_q,
		document.value(u"payload_sha256"_q));
	if (record.payload) {
		result.insert(u"key"_q, record.payload.version.key);
		result.insert(u"writerWarnings"_q,
			QString::number(record.payload.writerWarnings));
		result.insert(u"localWarnings"_q,
			QString::number(record.payload.localWarnings.size()));
	}
	return result;
}

[[nodiscard]] std::optional<Purple::OwnRecordObservation> Observation(
		JNIEnv *env,
		int kind,
		jbyteArray observed,
		const Purple::SyncLocalState &state,
		QString &error,
		bool requireCanonical = false) {
	if (kind == 0 || kind == 1) {
		if (observed) {
			error = u"UnexpectedObservationBytes"_q;
			return std::nullopt;
		}
		return Purple::OwnRecordObservation{
			kind == 0
				? Purple::OwnRecordObservationKind::Unresolved
				: Purple::OwnRecordObservationKind::Absent,
			0,
			QString(),
		};
	}
	if (kind != 2 || !observed) {
		error = u"InvalidObservationKind"_q;
		return std::nullopt;
	}
	auto bytes = QByteArray();
	if (!ReadBytes(env, observed, bytes, error)) {
		return std::nullopt;
	}
	const auto record = CheckRecord(bytes);
	if (!record.envelope) {
		error = u"ObservationEnvelope"_q;
		return std::nullopt;
	}
	if (!record.payload
		|| (requireCanonical
			&& record.payload.status != Purple::ConfigPayloadStatus::Valid)) {
		error = u"ObservationPayload"_q;
		return std::nullopt;
	}
	if (requireCanonical) {
		const auto canonical = Purple::SerializeSyncEnvelope(
			record.envelope.envelope);
		if (!canonical || canonical.canonical != bytes) {
			error = u"ObservationCanonical"_q;
			return std::nullopt;
		}
	}
	const auto document = record.envelope.envelope.document;
	if (document.value(u"space"_q).toString() != state.space
		|| document.value(u"writer"_q).toObject()
			.value(u"install"_q).toString() != state.install) {
		error = u"ObservationIdentity"_q;
		return std::nullopt;
	}
	return Purple::OwnRecordObservation{
		Purple::OwnRecordObservationKind::Present,
		uint64_t(document.value(u"seq"_q).toDouble()),
		document.value(u"payload_sha256"_q).toString(),
	};
}

[[nodiscard]] jobject BuildRecordReply(
		JNIEnv *env, const Purple::ConfigRecordBuildInput &input) {
	const auto built = Purple::BuildConfigRecord(input);
	auto metadata = QJsonObject{
		{ u"status"_q, Name(built.status) },
		{ u"error"_q, Name(built.error) },
		{ u"envelopeError"_q, Name(built.envelopeError) },
		{ u"payloadError"_q, Name(built.payloadError) },
	};
	if (built) {
		metadata.insert(u"key"_q, built.version.key);
		metadata.insert(u"payloadHash"_q, built.payloadHash);
		metadata.insert(u"seq"_q, QString::number(input.seq));
		metadata.insert(u"localWarnings"_q,
			QString::number(built.localWarnings.size()));
		return Reply(env, metadata, built.canonical);
	}
	return Reply(env, metadata);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_inspectConfigRecordNative(
		JNIEnv *env, jclass, jbyteArray input) {
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, input, bytes, error)) {
		return Invalid(env, error);
	}
	const auto checked = CheckRecord(bytes);
	const auto metadata = RecordMetadata(checked);
	if (!checked.envelope) {
		return Reply(env, metadata);
	}
	const auto canonical = Purple::SerializeSyncEnvelope(checked.envelope.envelope);
	if (!canonical) {
		return Invalid(env, u"RecordSerialize"_q);
	}
	return Reply(env, metadata, canonical.canonical, std::nullopt,
		checked.payload ? std::optional(checked.payload.text) : std::nullopt);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_inspectStateNative(
		JNIEnv *env, jclass, jbyteArray input) {
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, input, bytes, error)) {
		return Invalid(env, error);
	}
	const auto parsed = Purple::ParseSyncLocalState(bytes);
	auto metadata = QJsonObject{
		{ u"status"_q, Name(parsed.status) },
		{ u"error"_q, Name(parsed.error) },
	};
	if (!parsed) {
		return Reply(env, metadata);
	}
	const auto serialized = Purple::SerializeSyncLocalState(parsed.state);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	const auto fields = StateMetadata(parsed.state);
	for (auto it = fields.begin(); it != fields.end(); ++it) {
		metadata.insert(it.key(), it.value());
	}
	return Reply(env, metadata, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_buildConfigRecordNative(
		JNIEnv *env, jclass, jbyteArray text, jobjectArray parents,
		jstring space, jstring install, jstring device, jstring platform,
		jstring app, jlong seq, jlong at) {
	auto input = Purple::ConfigRecordBuildInput();
	auto error = QString();
	if (!ReadBytes(env, text, input.text, error)) {
		return Invalid(env, error);
	}
	if (!ReadString(env, space, input.space)
		|| !ReadString(env, install, input.install)
		|| !ReadString(env, device, input.device)
		|| !ReadString(env, platform, input.platform)
		|| !ReadString(env, app, input.app)
		|| !parents) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto count = env->GetArrayLength(parents);
	if (count > 2) {
		return Invalid(env, u"TooManyParents"_q);
	}
	for (auto index = 0; index != count; ++index) {
		const auto parent = static_cast<jbyteArray>(
			env->GetObjectArrayElement(parents, index));
		auto bytes = QByteArray();
		const auto read = ReadBytes(env, parent, bytes, error);
		env->DeleteLocalRef(parent);
		if (!read) {
			return Invalid(env, error);
		}
		const auto checked = CheckRecord(bytes);
		if (!checked.envelope) {
			return Reply(env, {
				{ u"status"_q, u"Invalid"_q },
				{ u"error"_q, u"ParentEnvelope"_q },
				{ u"envelopeStatus"_q, Name(checked.envelope.status) },
				{ u"envelopeError"_q, Name(checked.envelope.error) },
			});
		}
		if (checked.envelope.envelope.document
				.value(u"space"_q).toString() != input.space) {
			return Invalid(env, u"ParentSpace"_q);
		}
		if (checked.payload.status != Purple::ConfigPayloadStatus::Valid) {
			return Reply(env, {
				{ u"status"_q, u"Invalid"_q },
				{ u"error"_q, u"ParentPayload"_q },
				{ u"payloadStatus"_q, Name(checked.payload.status) },
				{ u"payloadError"_q, Name(checked.payload.error) },
			});
		}
		input.parents.push_back(checked.payload.version);
	}
	input.seq = uint64_t(seq);
	input.at = uint64_t(at);
	return BuildRecordReply(env, input);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_buildConfigAcknowledgementNative(
		JNIEnv *env, jclass, jbyteArray text, jbyteArray remoteRecord,
		jstring space, jstring install, jstring device, jstring platform,
		jstring app, jlong seq, jlong at) {
	auto input = Purple::ConfigRecordBuildInput();
	auto remoteBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, text, input.text, error)
		|| !ReadBytes(env, remoteRecord, remoteBytes, error)) {
		return Invalid(env, error);
	}
	if (!ReadString(env, space, input.space)
		|| !ReadString(env, install, input.install)
		|| !ReadString(env, device, input.device)
		|| !ReadString(env, platform, input.platform)
		|| !ReadString(env, app, input.app)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto checked = CheckRecord(remoteBytes);
	if (!checked.envelope) {
		return Reply(env, {
			{ u"status"_q, u"Invalid"_q },
			{ u"error"_q, u"RemoteEnvelope"_q },
			{ u"envelopeStatus"_q, Name(checked.envelope.status) },
			{ u"envelopeError"_q, Name(checked.envelope.error) },
		});
	}
	if (checked.payload.status != Purple::ConfigPayloadStatus::Valid) {
		return Reply(env, {
			{ u"status"_q, u"Invalid"_q },
			{ u"error"_q, u"RemotePayload"_q },
			{ u"payloadStatus"_q, Name(checked.payload.status) },
			{ u"payloadError"_q, Name(checked.payload.error) },
		});
	}
	if (checked.envelope.envelope.document
			.value(u"space"_q).toString() != input.space) {
		return Invalid(env, u"RemoteSpace"_q);
	}
	const auto canonical = Purple::SerializeSyncEnvelope(
		checked.envelope.envelope);
	if (!canonical || canonical.canonical != remoteBytes) {
		return Invalid(env, u"RemoteCanonical"_q);
	}
	if (checked.payload.text != input.text) {
		return Invalid(env, u"TextMismatch"_q);
	}
	input.version = checked.payload.version;
	input.seq = uint64_t(seq);
	input.at = uint64_t(at);
	return BuildRecordReply(env, input);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_initializeLocalStateNative(
		JNIEnv *env, jclass, jstring installId, jstring createdDeviceId,
		jstring spaceId) {
	auto state = Purple::SyncLocalState();
	if (!ReadString(env, installId, state.install)
		|| !ReadString(env, createdDeviceId, state.createdDevice)
		|| !ReadString(env, spaceId, state.space)) {
		return Invalid(env, u"NullInput"_q);
	}
	if (!Purple::IsSyncInstallId(state.install)
		|| !Purple::IsSyncSpaceId(state.space)) {
		return Invalid(env, u"InvalidId"_q);
	}
	const auto deviceBytes = state.createdDevice.toUtf8();
	if (deviceBytes.isEmpty() || deviceBytes.size() > 256
		|| QString::fromUtf8(deviceBytes) != state.createdDevice) {
		return Invalid(env, u"InvalidDevice"_q);
	}
	const auto serialized = Purple::SerializeSyncLocalState(state);
	if (!serialized) {
		return Reply(env, {
			{ u"status"_q, Name(serialized.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(serialized.error) },
		});
	}
	auto metadata = StateMetadata(state);
	metadata.insert(u"status"_q, u"Valid"_q);
	metadata.insert(u"error"_q, u"None"_q);
	return Reply(env, metadata, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_reserveConfigRecordNative(
		JNIEnv *env, jclass, jbyteArray state, jbyteArray ownRecord) {
	auto stateBytes = QByteArray();
	auto recordBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, state, stateBytes, error)
		|| !ReadBytes(env, ownRecord, recordBytes, error)) {
		return Invalid(env, error);
	}
	const auto parsed = Purple::ParseSyncLocalState(stateBytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	if (parsed.state.createdDevice.isEmpty()) {
		return Invalid(env, u"InvalidDevice"_q);
	}
	const auto checked = CheckRecord(recordBytes);
	if (!checked.envelope) {
		return Reply(env, {
			{ u"status"_q, u"Invalid"_q },
			{ u"error"_q, u"RecordEnvelope"_q },
			{ u"envelopeStatus"_q, Name(checked.envelope.status) },
			{ u"envelopeError"_q, Name(checked.envelope.error) },
		});
	}
	if (checked.payload.status != Purple::ConfigPayloadStatus::Valid) {
		return Reply(env, {
			{ u"status"_q, u"Invalid"_q },
			{ u"error"_q, u"RecordPayload"_q },
			{ u"payloadStatus"_q, Name(checked.payload.status) },
			{ u"payloadError"_q, Name(checked.payload.error) },
		});
	}
	const auto canonical = Purple::SerializeSyncEnvelope(
		checked.envelope.envelope);
	if (!canonical || canonical.canonical != recordBytes) {
		return Invalid(env, u"RecordCanonical"_q);
	}
	const auto document = checked.envelope.envelope.document;
	const auto writer = document.value(u"writer"_q).toObject();
	if (document.value(u"space"_q).toString() != parsed.state.space
		|| writer.value(u"install"_q).toString() != parsed.state.install
		|| writer.value(u"device"_q).toString()
			!= parsed.state.createdDevice) {
		return Invalid(env, u"RecordIdentity"_q);
	}
	const auto hash = document.value(u"payload_sha256"_q).toString();
	const auto reserved = Purple::ReserveSyncSeq(parsed.state,
		Purple::SyncLocalStream::Config, hash);
	if (!reserved) {
		return Invalid(env, Name(reserved.error));
	}
	if (uint64_t(document.value(u"seq"_q).toDouble()) != reserved.seq) {
		return Invalid(env, u"RecordSequence"_q);
	}
	auto next = reserved.state;
	next.configData.pending = checked.payload.version.key;
	const auto serialized = Purple::SerializeSyncLocalState(next);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q },
		{ u"error"_q, u"None"_q },
		{ u"seq"_q, QString::number(reserved.seq) },
		{ u"pendingSeq"_q, QString::number(next.config.pendingSeq) },
		{ u"payloadHash"_q, hash },
		{ u"key"_q, checked.payload.version.key },
	}, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_reserveConfigSeqNative(
		JNIEnv *env, jclass, jbyteArray state, jstring payloadHash) {
	auto bytes = QByteArray();
	auto hash = QString();
	auto error = QString();
	if (!ReadBytes(env, state, bytes, error)) {
		return Invalid(env, error);
	}
	if (!ReadString(env, payloadHash, hash)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto parsed = Purple::ParseSyncLocalState(bytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	const auto reserved = Purple::ReserveSyncSeq(parsed.state,
		Purple::SyncLocalStream::Config, hash);
	if (!reserved) {
		return Reply(env, {
			{ u"status"_q, u"Invalid"_q },
			{ u"error"_q, Name(reserved.error) },
		});
	}
	const auto serialized = Purple::SerializeSyncLocalState(reserved.state);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
		{ u"seq"_q, QString::number(reserved.seq) },
		{ u"pendingSeq"_q,
			QString::number(reserved.state.config.pendingSeq) },
	}, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_checkOwnRecordNative(
		JNIEnv *env, jclass, jbyteArray state, jstring currentDevice,
		jint kind, jbyteArray observed) {
	auto bytes = QByteArray();
	auto device = QString();
	auto readError = QString();
	if (!ReadBytes(env, state, bytes, readError)) {
		return Invalid(env, readError);
	}
	if (!ReadString(env, currentDevice, device)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto parsed = Purple::ParseSyncLocalState(bytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	auto error = QString();
	const auto observation = Observation(
		env, kind, observed, parsed.state, error);
	if (!observation) {
		return Invalid(env, error);
	}
	const auto verdict = Purple::CheckSyncClone(parsed.state, device,
		Purple::SyncLocalStream::Config, *observation);
	return Reply(env, {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
		{ u"verdict"_q, Name(verdict) },
	});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_confirmConfigReadBackNative(
		JNIEnv *env, jclass, jbyteArray state, jbyteArray staged,
		jstring currentDevice, jint kind, jbyteArray observed,
		jint messageId) {
	auto bytes = QByteArray();
	auto stagedBytes = QByteArray();
	auto device = QString();
	auto readError = QString();
	if (!ReadBytes(env, state, bytes, readError)
		|| !ReadBytes(env, staged, stagedBytes, readError)) {
		return Invalid(env, readError);
	}
	if (!ReadString(env, currentDevice, device)) {
		return Invalid(env, u"NullInput"_q);
	}
	const auto parsed = Purple::ParseSyncLocalState(bytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	if (parsed.state.config.pendingSeq == 0) {
		return Invalid(env, u"NoPending"_q);
	}
	const auto stagedRecord = CheckRecord(stagedBytes);
	if (!stagedRecord.envelope) {
		return Invalid(env, u"StagedEnvelope"_q);
	}
	if (stagedRecord.payload.status != Purple::ConfigPayloadStatus::Valid) {
		return Invalid(env, u"StagedPayload"_q);
	}
	const auto canonical = Purple::SerializeSyncEnvelope(
		stagedRecord.envelope.envelope);
	if (!canonical || canonical.canonical != stagedBytes) {
		return Invalid(env, u"StagedCanonical"_q);
	}
	const auto stagedDocument = stagedRecord.envelope.envelope.document;
	if (stagedDocument.value(u"space"_q).toString() != parsed.state.space
		|| stagedDocument.value(u"writer"_q).toObject()
			.value(u"install"_q).toString() != parsed.state.install
		|| uint64_t(stagedDocument.value(u"seq"_q).toDouble())
			!= parsed.state.config.pendingSeq
		|| stagedDocument.value(u"payload_sha256"_q).toString()
			!= parsed.state.config.ownHash
		|| stagedRecord.payload.version.key
			!= parsed.state.configData.pending) {
		return Invalid(env, u"StagedMismatch"_q);
	}
	auto error = QString();
	const auto observation = Observation(
		env, kind, observed, parsed.state, error, true);
	if (!observation) {
		return Invalid(env, error);
	}
	if ((kind == 2 && messageId <= 0)
		|| (kind != 2 && messageId != 0)) {
		return Invalid(env, u"InvalidMessageId"_q);
	}
	const auto confirmed = Purple::ConfirmConfigReadBack(parsed.state, device,
		*observation, stagedRecord.payload.version);
	if (confirmed.verdict == Purple::SyncCloneVerdict::NoClone
		&& confirmed.changed && kind == 2) {
		auto observedBytes = QByteArray();
		if (!ReadBytes(env, observed, observedBytes, error)) {
			return Invalid(env, error);
		}
		if (observedBytes != stagedBytes) {
			return Invalid(env, u"ReadBackMismatch"_q);
		}
		const auto recorded = Purple::RecordConfirmedOwnConfigMessage(
			confirmed.state, messageId, observedBytes);
		if (!recorded) {
			return Invalid(env, Name(recorded.error));
		}
		const auto serialized = Purple::SerializeSyncLocalState(recorded.state);
		if (!serialized) {
			return Invalid(env, u"StateSerialize"_q);
		}
		return Reply(env, {
			{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
			{ u"verdict"_q, Name(confirmed.verdict) },
			{ u"changed"_q, confirmed.changed },
			{ u"seq"_q, QString::number(recorded.state.config.seq) },
			{ u"pendingSeq"_q,
				QString::number(recorded.state.config.pendingSeq) },
			{ u"confirmedSeq"_q,
				QString::number(recorded.state.config.confirmedSeq) },
		}, std::nullopt, serialized.canonical);
	}
	const auto serialized = Purple::SerializeSyncLocalState(confirmed.state);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
		{ u"verdict"_q, Name(confirmed.verdict) },
		{ u"changed"_q, confirmed.changed },
		{ u"seq"_q, QString::number(confirmed.state.config.seq) },
		{ u"pendingSeq"_q,
			QString::number(confirmed.state.config.pendingSeq) },
		{ u"confirmedSeq"_q,
			QString::number(confirmed.state.config.confirmedSeq) },
	}, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_recordConfirmedOwnConfigMessageNative(
		JNIEnv *env, jclass, jbyteArray state, jint messageId,
		jbyteArray serverRecord) {
	auto stateBytes = QByteArray();
	auto recordBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, state, stateBytes, error)
		|| !ReadBytes(env, serverRecord, recordBytes, error)) {
		return Invalid(env, error);
	}
	const auto parsed = Purple::ParseSyncLocalState(stateBytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	const auto recorded = Purple::RecordConfirmedOwnConfigMessage(
		parsed.state, messageId, recordBytes);
	if (!recorded) {
		return Invalid(env, Name(recorded.error));
	}
	const auto serialized = Purple::SerializeSyncLocalState(recorded.state);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
		{ u"changed"_q, recorded.changed },
	}, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_checkOwnConfigMessageDeletionNative(
		JNIEnv *env, jclass, jbyteArray state, jint messageId,
		jbyteArray freshServerRecord) {
	auto stateBytes = QByteArray();
	auto recordBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, state, stateBytes, error)
		|| !ReadBytes(env, freshServerRecord, recordBytes, error)) {
		return Invalid(env, error);
	}
	const auto parsed = Purple::ParseSyncLocalState(stateBytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	const auto checked = Purple::CheckOwnConfigMessageDeletion(
		parsed.state, messageId, recordBytes);
	return Reply(env, {
		{ u"status"_q, checked ? u"Valid"_q : u"Invalid"_q },
		{ u"error"_q, Name(checked.error) },
	});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_removeAbsentOwnConfigMessageNative(
		JNIEnv *env, jclass, jbyteArray state, jint messageId,
		jint presenceKind) {
	auto stateBytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, state, stateBytes, error)) {
		return Invalid(env, error);
	}
	if (presenceKind != 0 && presenceKind != 1) {
		return Invalid(env, u"InvalidPresenceKind"_q);
	}
	const auto parsed = Purple::ParseSyncLocalState(stateBytes);
	if (!parsed) {
		return Reply(env, {
			{ u"status"_q, Name(parsed.status) },
			{ u"error"_q, u"State"_q },
			{ u"stateError"_q, Name(parsed.error) },
		});
	}
	const auto removed = Purple::RemoveAbsentOwnConfigMessage(
		parsed.state, messageId,
		presenceKind == 1
			? Purple::SyncOwnMessagePresence::Absent
			: Purple::SyncOwnMessagePresence::Present);
	if (!removed) {
		return Invalid(env, Name(removed.error));
	}
	const auto serialized = Purple::SerializeSyncLocalState(removed.state);
	if (!serialized) {
		return Invalid(env, u"StateSerialize"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q }, { u"error"_q, u"None"_q },
		{ u"changed"_q, removed.changed },
	}, std::nullopt, serialized.canonical);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_formatInstallIdNative(
		JNIEnv *env, jclass, jbyteArray entropy) {
	if (!entropy) {
		return Invalid(env, u"NullInput"_q);
	}
	if (env->GetArrayLength(entropy) != 16) {
		return Invalid(env, u"InvalidEntropyLength"_q);
	}
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, entropy, bytes, error)) {
		return Invalid(env, error);
	}
	const auto id = Purple::FormatSyncInstallId(bytes);
	if (!id) {
		return Invalid(env, u"InvalidEntropyLength"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q },
		{ u"error"_q, u"None"_q },
		{ u"id"_q, *id },
	});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_formatSpaceIdNative(
		JNIEnv *env, jclass, jbyteArray entropy) {
	if (!entropy) {
		return Invalid(env, u"NullInput"_q);
	}
	if (env->GetArrayLength(entropy) != 16) {
		return Invalid(env, u"InvalidEntropyLength"_q);
	}
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, entropy, bytes, error)) {
		return Invalid(env, error);
	}
	const auto id = Purple::FormatSyncSpaceId(bytes);
	if (!id) {
		return Invalid(env, u"InvalidEntropyLength"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q },
		{ u"error"_q, u"None"_q },
		{ u"id"_q, *id },
	});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_formatTimeOrderedSpaceIdNative(
		JNIEnv *env, jclass, jlong serverMillis, jbyteArray randomTail) {
	if (serverMillis <= 0 || uint64_t(serverMillis) >= (uint64_t(1) << 48)) {
		return Invalid(env, u"InvalidServerTime"_q);
	}
	if (!randomTail) {
		return Invalid(env, u"NullInput"_q);
	}
	if (env->GetArrayLength(randomTail) != 10) {
		return Invalid(env, u"InvalidEntropyLength"_q);
	}
	auto bytes = QByteArray();
	auto error = QString();
	if (!ReadBytes(env, randomTail, bytes, error)) {
		return Invalid(env, error);
	}
	const auto id = Purple::FormatTimeOrderedSyncSpaceId(
		uint64_t(serverMillis), bytes);
	if (!id) {
		return Invalid(env, u"InvalidServerTime"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q },
		{ u"error"_q, u"None"_q },
		{ u"id"_q, *id },
	});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleAccountSyncCore_compareSpaceIdsNative(
		JNIEnv *env, jclass, jstring a, jstring b) {
	auto left = QString();
	auto right = QString();
	if (!a || !b) {
		return Invalid(env, u"NullInput"_q);
	}
	if (!ReadString(env, a, left) || !ReadString(env, b, right)) {
		return Invalid(env, u"JavaException"_q);
	}
	const auto comparison = Purple::CompareSyncSpaceIds(left, right);
	if (!comparison) {
		return Invalid(env, u"InvalidId"_q);
	}
	return Reply(env, {
		{ u"status"_q, u"Valid"_q },
		{ u"error"_q, u"None"_q },
		{ u"comparison"_q, *comparison },
	});
}
