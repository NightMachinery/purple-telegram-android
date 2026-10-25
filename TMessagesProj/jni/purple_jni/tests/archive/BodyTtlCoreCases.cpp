#include "tests/archive_test_support.h"

using namespace ArchiveTest;

int main() {
    Section = "Android D-B3A-1 targeted epoch-less body TTL";
    auto state = Empty();
    CHECK(Apply(state, PlanArchiveEvent(state, Event(state, u"base"_q, 1))));
    const auto &head = Message(state).versions.back();
    auto expiry = Event(state, head.id + u":lifecycle"_q, head.order + 1, {}, ArchiveOrigin::MediaExpiry);
    expiry.targetVersion = head.id;
    expiry.targetRevision = head.revision;
    expiry.expectedContentHash = ArchiveContentHash(head.content);
    CHECK(!expiry.lifecycleEpoch && expiry.lifecycleMediaIdentity.isEmpty());
    for (auto test = 0; test != 5; ++test) {
        auto wrong = expiry;
        if (test == 0) ++wrong.generation.account;
        if (test == 1) ++wrong.generation.chat;
        if (test == 2) wrong.targetVersion = u"other"_q;
        if (test == 3) wrong.targetRevision = u"other"_q;
        if (test == 4) wrong.expectedContentHash = ArchiveContentHash(ArchiveContent());
        const auto refused = PlanArchiveEvent(state, wrong);
        CHECK(refused.status == ArchivePlanStatus::Stale && refused.writes.empty());
        CHECK(!Message(state).deleted);
    }
    auto changed = state;
    CHECK(Apply(changed, PlanArchiveEvent(changed, Event(changed, u"edit"_q, 2, u"B"_q, ArchiveOrigin::AuthoritativeEdit))));
    CHECK(PlanArchiveEvent(changed, expiry).status == ArchivePlanStatus::Stale);
    CHECK(Apply(state, PlanArchiveEvent(state, expiry)));
    CHECK(Message(state).deleted);
    CHECK(PlanArchiveEvent(state, expiry).status == ArchivePlanStatus::NoChange);
    CHECK(Apply(state, PlanArchivePurge(state, Purge(state, ArchivePurgeKind::Trashbin))));
    CHECK(Message(state).payloadPurged);
    CHECK(PlanArchiveEvent(state, expiry).status == ArchivePlanStatus::Stale);
    expiry.generation = ArchiveGenerationFor(state, expiry.key.chat);
    CHECK(PlanArchiveEvent(state, expiry).status == ArchivePlanStatus::Stale);
    CHECK(Message(state).payloadPurged && Message(state).versions.empty());
    return Finish();
}
