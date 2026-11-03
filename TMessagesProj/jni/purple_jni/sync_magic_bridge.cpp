#include <jni.h>
#include "purple/purple_sync_chat_magic.h"
#include "purple/purple_local_choice.h"
#include "purple/purple_sync_auto.h"
#include "purple/purple_sync_local_state.h"
#include "purple/purple_types.h"
#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <limits>

// JSON in, JSON out for PurpleSyncMagicCore: the Magic Purple Sync lookup, binding and checks.
namespace {

using namespace Purple;

QJsonObject Failure() {
    return QJsonObject{ { u"ok"_q, false } };
}

bool Boolean(const QJsonObject &object, const QString &name, bool &result) {
    const auto value = object.value(name);
    if (!value.isBool()) return false;
    result = value.toBool();
    return true;
}

bool Decimal(const QJsonValue &value, int64_t &result) {
    if (!value.isString()) return false;
    auto ok = false;
    const auto parsed = value.toString().toLongLong(&ok);
    if (!ok || QString::number(parsed) != value.toString()) return false;
    result = parsed;
    return true;
}

bool OptionalCount(const QJsonObject &object, const QString &name, std::optional<uint64_t> &result) {
    if (!object.contains(name)) return true;
    auto value = int64_t(0);
    if (!Decimal(object.value(name), value) || value < 0) return false;
    result = uint64_t(value);
    return true;
}

bool Kind(const QJsonValue &value, SyncChatKind &result) {
    const auto text = value.toString();
    if (text == u"Self"_q) result = SyncChatKind::Self;
    else if (text == u"Private"_q) result = SyncChatKind::Private;
    else if (text == u"Bot"_q) result = SyncChatKind::Bot;
    else if (text == u"BasicGroup"_q) result = SyncChatKind::BasicGroup;
    else if (text == u"Supergroup"_q) result = SyncChatKind::Supergroup;
    else if (text == u"Broadcast"_q) result = SyncChatKind::Broadcast;
    else if (text == u"Secret"_q) result = SyncChatKind::Secret;
    else if (text == u"ChannelDirectMessages"_q) result = SyncChatKind::ChannelDirectMessages;
    else if (text == u"Unknown"_q) result = SyncChatKind::Unknown;
    else return false;
    return true;
}

bool Candidate(const QJsonValue &value, MagicChatCandidate &result) {
    if (!value.isObject()) return false;
    const auto object = value.toObject();
    auto id = int64_t(0);
    if (!Decimal(object.value(u"channelId"_q), id) || id < 0) return false;
    result.channelId = uint64_t(id);
    result.title = object.value(u"title"_q).toString();
    auto &facts = result.facts;
    return Boolean(object, u"member"_q, result.member)
        && Boolean(object, u"megagroup"_q, result.megagroup)
        && Boolean(object, u"gigagroup"_q, result.gigagroup)
        && Kind(object.value(u"kind"_q), facts.kind)
        && Boolean(object, u"publicUsername"_q, facts.publicUsername)
        && Boolean(object, u"protectedContent"_q, facts.protectedContent)
        && Boolean(object, u"forum"_q, facts.forum)
        && Boolean(object, u"linkedDiscussion"_q, facts.linkedDiscussion)
        && Boolean(object, u"autoDelete"_q, facts.autoDelete)
        && Boolean(object, u"paidMessages"_q, facts.paidMessages)
        && Boolean(object, u"canSendFiles"_q, facts.canSendFiles)
        && Boolean(object, u"anonymousAdmin"_q, facts.anonymousAdmin)
        && Boolean(object, u"postsAsOther"_q, facts.postsAsOther)
        && Boolean(object, u"migrated"_q, facts.migrated)
        && Boolean(object, u"slowMode"_q, facts.slowMode)
        && Boolean(object, u"admin"_q, facts.admin)
        && Boolean(object, u"creator"_q, facts.creator)
        && Boolean(object, u"soleAdmin"_q, facts.soleAdmin)
        && OptionalCount(object, u"otherMembers"_q, facts.otherMembers)
        && OptionalCount(object, u"otherAdmins"_q, facts.otherAdmins);
}

bool Candidates(const QJsonValue &value, std::vector<MagicChatCandidate> &result) {
    if (!value.isArray() || value.toArray().size() > 4096) return false;
    for (const auto &row : value.toArray()) {
        auto chat = MagicChatCandidate();
        if (!Candidate(row, chat)) return false;
        result.push_back(chat);
    }
    return true;
}

bool Binding(const QJsonValue &value, SyncChatBinding &result) {
    if (!value.isString()) return false;
    if (value.toString().isEmpty()) {
        result = SyncChatBinding();
        return true;
    }
    const auto parsed = ParseSyncChatBinding(value.toString().toUtf8());
    if (!parsed) return false;
    result = *parsed;
    return true;
}

bool Put(QJsonObject &result, const SyncChatBinding &binding) {
    const auto bytes = SerializeSyncChatBinding(binding);
    if (!bytes) return false;
    result.insert(u"binding"_q, QString::fromUtf8(*bytes));
    return true;
}

QString ActionName(SyncChatLookupAction action) {
    switch (action) {
    case SyncChatLookupAction::Use: return u"Use"_q;
    case SyncChatLookupAction::Verify: return u"Verify"_q;
    case SyncChatLookupAction::Wait: return u"Wait"_q;
    case SyncChatLookupAction::Create: return u"Create"_q;
    case SyncChatLookupAction::Problem: return u"Problem"_q;
    }
    return QString();
}

QString ErrorKindName(SyncChatErrorKind kind) {
    switch (kind) {
    case SyncChatErrorKind::Wait: return u"Wait"_q;
    case SyncChatErrorKind::Transient: return u"Transient"_q;
    case SyncChatErrorKind::Refused: return u"Refused"_q;
    }
    return QString();
}

QString VerdictName(MagicChatVerdict verdict) {
    switch (verdict) {
    case MagicChatVerdict::Eligible: return u"Eligible"_q;
    case MagicChatVerdict::Unverified: return u"Unverified"_q;
    case MagicChatVerdict::Ineligible: return u"Ineligible"_q;
    }
    return QString();
}

QString MigrationName(SyncChatMigrationStep step) {
    switch (step) {
    case SyncChatMigrationStep::None: return u"none"_q;
    case SyncChatMigrationStep::Moved: return u"moved"_q;
    case SyncChatMigrationStep::Published: return u"published"_q;
    case SyncChatMigrationStep::Joined: return u"joined"_q;
    case SyncChatMigrationStep::Done: return u"done"_q;
    }
    return QString();
}

bool Bytes(const QJsonValue &value, QByteArray &result) {
    if (!value.isString()) return false;
    const auto text = value.toString().toLatin1();
    const auto decoded = QByteArray::fromBase64Encoding(text, QByteArray::AbortOnBase64DecodingErrors);
    if (!decoded || decoded.decoded.toBase64() != text) return false;
    result = decoded.decoded;
    return true;
}

QString Encoded(const QByteArray &bytes) {
    return QString::fromLatin1(bytes.toBase64());
}

// The state moved into the bound chat, or a failure without changes.
QJsonObject MoveState(const QJsonObject &object) {
    auto bytes = QByteArray();
    auto self = int64_t(0);
    if (!Bytes(object.value(u"state"_q), bytes) || !Decimal(object.value(u"self"_q), self) || self <= 0) return Failure();
    const auto parsed = ParseSyncLocalState(bytes);
    const auto chat = ParsePeerToken(object.value(u"chat"_q).toString(), uint64_t(self));
    if (!parsed || !chat) return Failure();
    const auto moved = MoveSyncStateToChat(parsed.state, *chat, uint64_t(self));
    if (!moved.move) {
        return QJsonObject{ { u"ok"_q, false }, { u"error"_q, moved.move.error == SyncChatMoveError::PendingStage
            ? u"PendingStage"_q : u"Refused"_q } };
    }
    const auto written = SerializeSyncLocalState(moved.move.state);
    if (!written) return Failure();
    return QJsonObject{
        { u"ok"_q, true },
        { u"state"_q, Encoded(written.canonical) },
        { u"changed"_q, moved.move.changed },
        { u"abandoned"_q, QString::number(moved.abandonedSeq) },
    };
}

// The automatic record carried across a move, for the account `key`.
QJsonObject MoveAuto(const QJsonObject &object) {
    auto bytes = QByteArray();
    const auto account = ParseAccountKey(object.value(u"key"_q).toString());
    if (!account || !Bytes(object.value(u"record"_q), bytes)) return Failure();
    const auto parsed = ParseSyncAutoRecord(bytes);
    if (parsed.status != SyncAutoCodecStatus::Valid || !parsed.record || parsed.record->account != *account) {
        return Failure();
    }
    const auto moved = MoveSyncAutoRecord(*parsed.record, object.value(u"fingerprint"_q).toString());
    const auto written = moved ? SerializeSyncAutoRecord(*moved) : std::nullopt;
    if (!written) return Failure();
    return QJsonObject{ { u"ok"_q, true }, { u"record"_q, Encoded(*written) } };
}

QJsonObject Evaluate(const QString &operation, const QJsonObject &object) {
    auto result = QJsonObject{ { u"ok"_q, true } };
    auto binding = SyncChatBinding();
    if (operation == u"moveState"_q) {
        return MoveState(object);
    } else if (operation == u"moveAuto"_q) {
        return MoveAuto(object);
    }
    if (operation == u"title"_q) {
        result.insert(u"magic"_q, IsMagicSyncChatTitle(object.value(u"title"_q).toString()));
    } else if (operation == u"check"_q) {
        auto chat = MagicChatCandidate();
        if (!Candidate(object.value(u"chat"_q), chat)) return Failure();
        const auto check = CheckMagicChat(chat);
        result.insert(u"verdict"_q, VerdictName(check.verdict));
        result.insert(u"problem"_q, SyncChatProblemCode(check.problem));
    } else if (operation == u"plan"_q) {
        auto input = SyncChatLookupInput();
        auto now = int64_t(0);
        const auto state = object.value(u"boundState"_q).toString();
        if (!Binding(object.value(u"binding"_q), input.binding) || !Decimal(object.value(u"now"_q), now) || now <= 0
            || !Boolean(object, u"fresh"_q, input.fresh) || !Boolean(object, u"listsComplete"_q, input.listsComplete)
            || !Candidates(object.value(u"visible"_q), input.visible)) return Failure();
        input.now = now;
        if (state == u"Present"_q) {
            input.boundState = SyncChatBoundState::Present;
            if (!Candidate(object.value(u"bound"_q), input.bound)) return Failure();
        } else if (state == u"Gone"_q) {
            input.boundState = SyncChatBoundState::Gone;
        } else if (state != u"Unknown"_q) {
            return Failure();
        }
        const auto plan = PlanSyncChatLookup(input);
        result.insert(u"action"_q, ActionName(plan.action));
        if (plan.chat) result.insert(u"chat"_q, *PeerTokenText(*plan.chat));
        result.insert(u"problem"_q, SyncChatProblemCode(plan.problem));
        result.insert(u"show"_q, plan.show);
        result.insert(u"retryAt"_q, QString::number(plan.retryAt));
        result.insert(u"telegram"_q, plan.telegram);
        result.insert(u"rebind"_q, plan.rebind);
    } else if (operation == u"verify"_q) {
        auto chat = MagicChatCandidate();
        auto now = int64_t(0);
        if (!Binding(object.value(u"binding"_q), binding) || !Candidate(object.value(u"chat"_q), chat)
            || !Decimal(object.value(u"now"_q), now) || now <= 0) return Failure();
        const auto problem = CompleteSyncChatVerify(binding, chat, now);
        result.insert(u"problem"_q, SyncChatProblemCode(problem));
        result.insert(u"rejected"_q, problem != SyncChatProblem::None && SyncChatCandidateRejected(binding, chat.channelId));
        if (!Put(result, binding)) return Failure();
    } else if (operation == u"verifyProblem"_q) {
        auto id = int64_t(0);
        auto now = int64_t(0);
        const auto problem = ParseSyncChatProblemCode(object.value(u"problem"_q).toString());
        if (!Binding(object.value(u"binding"_q), binding) || !Decimal(object.value(u"channelId"_q), id) || id <= 0
            || !Decimal(object.value(u"now"_q), now) || now <= 0 || !problem || *problem == SyncChatProblem::None) return Failure();
        RecordSyncChatVerifyProblem(binding, uint64_t(id), *problem, now);
        result.insert(u"problem"_q, SyncChatProblemCode(*problem));
        result.insert(u"rejected"_q, SyncChatCandidateRejected(binding, uint64_t(id)));
        if (!Put(result, binding)) return Failure();
    } else if (operation == u"classify"_q) {
        auto code = int64_t(0);
        auto timeout = false;
        if (!Decimal(object.value(u"code"_q), code) || code < std::numeric_limits<int>::min() || code > std::numeric_limits<int>::max()
            || !object.value(u"type"_q).isString() || !Boolean(object, u"timeout"_q, timeout)) return Failure();
        const auto error = ClassifySyncChatError(int(code), object.value(u"type"_q).toString(), timeout);
        result.insert(u"kind"_q, ErrorKindName(error.kind));
        result.insert(u"seconds"_q, QString::number(error.seconds));
    } else if (operation == u"refusalProblem"_q) {
        if (!object.value(u"method"_q).isString() || !object.value(u"type"_q).isString()) return Failure();
        result.insert(u"problem"_q, SyncChatProblemCode(SyncChatRefusalProblem(object.value(u"method"_q).toString(),
            object.value(u"type"_q).toString())));
    } else if (operation == u"wait"_q) {
        auto retryAt = int64_t(0);
        if (!Binding(object.value(u"binding"_q), binding) || !Decimal(object.value(u"retryAt"_q), retryAt)) return Failure();
        RecordSyncChatWait(binding, object.value(u"method"_q).toString(), retryAt);
        if (!Put(result, binding)) return Failure();
    } else if (operation == u"waitUntil"_q) {
        auto now = int64_t(0);
        if (!Binding(object.value(u"binding"_q), binding) || !Decimal(object.value(u"now"_q), now)) return Failure();
        result.insert(u"until"_q, QString::number(SyncChatWaitUntil(binding, object.value(u"method"_q).toString(), now)));
    } else if (operation == u"raise"_q || operation == u"clear"_q || operation == u"shown"_q || operation == u"reset"_q
            || operation == u"readmit"_q) {
        if (!Binding(object.value(u"binding"_q), binding)) return Failure();
        if (operation == u"raise"_q) {
            const auto problem = ParseSyncChatProblemCode(object.value(u"problem"_q).toString());
            if (!problem) return Failure();
            RaiseSyncChatProblem(binding, *problem);
        } else if (operation == u"clear"_q) {
            ClearSyncChatProblem(binding);
        } else if (operation == u"shown"_q) {
            binding.problemShown = binding.problem != SyncChatProblem::None;
        } else if (operation == u"readmit"_q) {
            ReadmitSyncChatCandidates(binding);
        } else {
            auto next = SyncChatBinding();
            next.waits = binding.waits;
            next.login = binding.login;
            next.migration = binding.migration;
            next.listed = binding.listed;
            next.preserved = binding.preserved;
            binding = next;
        }
        if (!Put(result, binding)) return Failure();
    } else if (operation == u"edit"_q) {
        if (!Binding(object.value(u"binding"_q), binding)) return Failure();
        const auto fields = object.value(u"set"_q).toObject();
        if (fields.contains(u"mode"_q)) {
            const auto mode = fields.value(u"mode"_q).toString();
            const auto chat = ParsePeerToken(fields.value(u"chat"_q).toString());
            if (mode == u"manual"_q && chat) { binding.mode = SyncChatBindingMode::Manual; binding.chat = chat; binding.verifiedAt = 0; }
            else return Failure();
        }
        if (fields.contains(u"createStep"_q)) {
            const auto step = fields.value(u"createStep"_q).toString();
            auto at = int64_t(0);
            if (!Decimal(fields.value(u"createAt"_q), at) || at < 0) return Failure();
            if (step == u"none"_q) binding.createStep = SyncChatCreateStep::None;
            else if (step == u"requested"_q) binding.createStep = SyncChatCreateStep::Requested;
            else if (step == u"uncertain"_q) binding.createStep = SyncChatCreateStep::Uncertain;
            else if (step == u"created"_q) binding.createStep = SyncChatCreateStep::Created;
            else if (step == u"archived"_q) binding.createStep = SyncChatCreateStep::Archived;
            else if (step == u"muted"_q) binding.createStep = SyncChatCreateStep::Muted;
            else return Failure();
            binding.createAt = at;
            if (fields.contains(u"created"_q)) {
                binding.created = ParsePeerToken(fields.value(u"created"_q).toString());
                if (!binding.created) return Failure();
            }
        }
        if (fields.contains(u"migration"_q)) {
            const auto step = fields.value(u"migration"_q).toString();
            auto found = false;
            for (const auto value : { SyncChatMigrationStep::None, SyncChatMigrationStep::Moved, SyncChatMigrationStep::Published, SyncChatMigrationStep::Joined, SyncChatMigrationStep::Done }) {
                if (MigrationName(value) == step) { binding.migration = value; found = true; }
            }
            if (!found) return Failure();
        }
        if (fields.contains(u"login"_q)) binding.login = fields.value(u"login"_q).toString();
        if (fields.contains(u"listed"_q)) {
            if (!fields.value(u"listed"_q).isBool()) return Failure();
            binding.listed = fields.value(u"listed"_q).toBool();
        }
        if (!Put(result, binding)) return Failure();
    } else if (operation == u"parse"_q) {
        if (!Binding(object.value(u"binding"_q), binding)) return Failure();
        if (!Put(result, binding)) return Failure();
        result.insert(u"mode"_q, binding.mode == SyncChatBindingMode::Magic ? u"magic"_q
            : binding.mode == SyncChatBindingMode::Manual ? u"manual"_q : u"none"_q);
        if (binding.chat) result.insert(u"chat"_q, *PeerTokenText(*binding.chat));
        result.insert(u"problem"_q, SyncChatProblemCode(binding.problem));
        result.insert(u"problemShown"_q, binding.problemShown);
        result.insert(u"verifiedAt"_q, QString::number(binding.verifiedAt));
        result.insert(u"migration"_q, MigrationName(binding.migration));
        result.insert(u"login"_q, binding.login);
        result.insert(u"listed"_q, binding.listed);
    } else {
        return Failure();
    }
    return result;
}

bool String(JNIEnv *env, jstring value, QString &result, int limit) {
    if (!value || env->GetStringLength(value) > limit) return false;
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return false;
    result = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return true;
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleSyncMagicCore_requestNative(JNIEnv *env, jclass, jstring operation, jstring input) {
    auto op = QString(); auto text = QString(); auto answer = Failure();
    if (String(env, operation, op, 32) && String(env, input, text, 1024 * 1024)) {
        auto error = QJsonParseError(); const auto document = QJsonDocument::fromJson(text.toUtf8(), &error);
        if (error.error == QJsonParseError::NoError && document.isObject()) answer = Evaluate(op, document.object());
    }
    const auto reply = QString::fromUtf8(QJsonDocument(answer).toJson(QJsonDocument::Compact));
    return env->NewString(reinterpret_cast<const jchar*>(reply.utf16()), reply.size());
}
