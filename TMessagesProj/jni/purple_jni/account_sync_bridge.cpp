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
	result.insert(u"space"_q, document.value(u"space"_q));
	result.insert(u"install"_q,
		document.value(u"writer"_q).toObject().value(u"install"_q));
	result.insert(u"seq"_q,
		QString::number(uint64_t(document.value(u"seq"_q).toDouble())));
	result.insert(u"at"_q,
		QString::number(uint64_t(document.value(u"at"_q).toDouble())));
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
		jstring currentDevice, jint kind, jbyteArray observed) {
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
	const auto confirmed = Purple::ConfirmConfigReadBack(parsed.state, device,
		*observation, stagedRecord.payload.version);
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
