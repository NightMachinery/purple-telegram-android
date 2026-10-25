ArchiveStoreEpoch WorkEpoch(const QJsonObject &args) {
    return {Account(args),Text(args,u"store"_q),Unsigned(args.value(u"workGeneration"_q)),false};
}
ArchiveMediaSource MediaSource(const QJsonObject &value) {
    auto epoch=value.value(u"epoch"_q).toObject();
    return {{Account(epoch),Text(epoch,u"store"_q),Unsigned(epoch.value(u"generation"_q)),epoch.value(u"removalPending"_q).toBool()},
        Key(value.value(u"key"_q).toObject()),Text(value,u"version"_q),Text(value,u"revision"_q),Text(value,u"contentHash"_q),
        Text(value,u"mediaIdentity"_q),{Unsigned(value.value(u"accountGeneration"_q)),Unsigned(value.value(u"chatGeneration"_q))}};
}
ArchiveStagingBinding StagingBinding(const QJsonObject &value) {
    auto result=ArchiveStagingBinding{MediaSource(value.value(u"source"_q).toObject()),Text(value,u"job"_q),Unsigned(value.value(u"total"_q)),{}};
    if (value.contains(u"hash"_q)) result.expectedBlobHash=Text(value,u"hash"_q);
    return result;
}
QJsonObject RecordValue(const ArchiveStagingRecord &record, const ArchiveStagingBinding &binding, const ArchiveSqlSecurity &security) {
    auto address=ArchiveStagingAddress(record); auto frame=SealArchiveStagingRecord(binding,record,security);
    if (!frame) throw std::runtime_error("archive staging record seal");
    return {{u"kind"_q,int(address.kind)},{u"position"_q,QString::number(address.position)},{u"checkpoint"_q,QString::number(address.checkpoint)},
        {u"frame"_q,Encoded(*frame)}};
}
ArchiveStagingRecord OpenStaging(const QJsonObject &value,const ArchiveStagingBinding &binding,const ArchiveSqlSecurity &security) {
    auto kind=value.value(u"kind"_q).toInt(-1); if(kind<0||kind>2) throw std::runtime_error("archive staging record kind");
    auto frame=Bytes(value.value(u"frame"_q)); if(frame.size()>kArchiveStagingRecordByteLimit+4608) throw std::runtime_error("archive staging record limit");
    auto result=OpenArchiveStagingRecord(binding,{ArchiveStagingRecordKind(kind),Unsigned(value.value(u"position"_q)),Unsigned(value.value(u"checkpoint"_q))},frame,security);
    if(!result.record) throw std::runtime_error("archive staging record authentication"); return *result.record;
}
template<typename T> T StagingRecord(const ArchiveStagingRecord &record) {
    auto value=std::get_if<T>(&record); if(!value)throw std::runtime_error("archive staging record type");return *value;
}
ArchiveStagingProof StagingProof(const QJsonValue &value) {
    if(!value.isArray()||value.toArray().size()!=kArchiveStagingProofDepth)throw std::runtime_error("archive staging proof");
    auto result=ArchiveStagingProof();for(const auto &item:value.toArray()){if(!item.isString()||!IsArchiveHash(item.toString()))throw std::runtime_error("archive staging sibling");result.siblings.push_back(item.toString());}return result;
}
QJsonArray ProofValue(const ArchiveStagingProof &proof) {auto result=QJsonArray();for(const auto &s:proof.siblings)result.push_back(s);return result;}
QJsonObject HeaderValue(const ArchiveStagingHeader &header,const ArchiveSqlSecurity &security) {
    auto value=RecordValue(header,header.binding,security);value.insert(u"hash"_q,ArchiveStagingHeaderHash(header));
    value.insert(u"covered"_q,QString::number(header.coveredBytes));return value;
}
QJsonArray NodesValue(const ArchiveStagingCheckpoint &checkpoint) {
    auto result=QJsonArray();auto nodes=ArchiveStagingNodes(checkpoint);if(!nodes)throw std::runtime_error("archive staging nodes");
    if(checkpoint.pageIndex)result.push_back(QJsonObject{{u"level"_q,0},{u"index"_q,QString::number(*checkpoint.pageIndex)},{u"hash"_q,checkpoint.pageHash}});
    for(const auto &node:*nodes)result.push_back(QJsonObject{{u"level"_q,node.level},{u"index"_q,QString::number(node.index)},{u"hash"_q,node.hash}});return result;
}
struct StagingSession {
    QString account,store,key,binding;
    std::unique_ptr<ArchiveStagingFinalizer> value;
};
std::mutex stagingMutex;
std::map<uint64_t,StagingSession> stagingSessions;
uint64_t nextStagingSession=0;
QJsonObject FinalizeStep(const ArchiveStagingFinalizeStep &step,const ArchiveSqlSlice &slice,const ArchiveSqlSecurity &security) {
    auto request=step.request;auto result=QJsonObject{{u"status"_q,int(step.status)},{u"phase"_q,int(step.phase)},
        {u"kind"_q,int(request.kind)},{u"request"_q,QString::number(request.id)},{u"page"_q,QString::number(request.pageIndex)},
        {u"chunk"_q,QString::number(request.chunkIndex)},{u"offset"_q,QString::number(request.range.offset)},
        {u"length"_q,int(request.range.length)},{u"pieceHash"_q,request.range.pieceHash}};
    if(step.publication){const auto &p=*step.publication;result.insert(u"publication"_q,QJsonObject{
        {u"bindingId"_q,p.bindingId},{u"checkpointHash"_q,p.checkpointHash},{u"blobHash"_q,p.blobHash},{u"blobToken"_q,p.blobToken},
        {u"request"_q,QString::number(p.requestId)},{u"chunk"_q,QString::number(p.chunkIndex)},{u"bytes"_q,int(p.plaintextBytes)},
        {u"plaintextHash"_q,p.plaintextHash},{u"frameHash"_q,p.frameHash},{u"frame"_q,Encoded(p.frame)}});}
    if(step.completion)result.insert(u"completion"_q,Program(TranslateArchiveSql(*step.completion,slice,security)));
    return result;
}
std::optional<QJsonObject> ArchiveMediaDispatch(const QString &operation,const QJsonObject &args,const Crypto &crypto) {
    if(!operation.startsWith(u"staging"_q)&&operation!=u"mediaStage"_q&&operation!=u"mediaLifecycle"_q)return {};
    auto account=Account(args);auto security=Security(args,crypto);auto epoch=WorkEpoch(args);
    if(operation==u"stagingCloseAll"_q){auto lock=std::lock_guard(stagingMutex);
        for(auto it=stagingSessions.begin();it!=stagingSessions.end();){
            if(it->second.account==AccountKeyText(account.environment,account.userId).value_or(QString())&&it->second.store==security.storeId&&it->second.key==security.keyId)it=stagingSessions.erase(it);else ++it;
        }
        return QJsonObject{{u"status"_q,0}};
    }
    if(operation==u"stagingClose"_q){auto lock=std::lock_guard(stagingMutex);auto id=Unsigned(args.value(u"handle"_q));auto it=stagingSessions.find(id);
        if(it!=stagingSessions.end()&&it->second.account==AccountKeyText(account.environment,account.userId).value_or(QString())&&it->second.store==security.storeId&&it->second.key==security.keyId)stagingSessions.erase(it);
        return QJsonObject{{u"status"_q,0}};}
    auto slice=Slice(args,account,security);
    if(operation==u"mediaLifecycle"_q){
        auto f=args.value(u"fact"_q).toObject();auto lifetime=f.value(u"lifetime"_q).toInt(-1),transition=f.value(u"transition"_q).toInt(-1),available=f.value(u"available"_q).toInt(-1);
        if(lifetime<0||lifetime>3||transition<0||transition>3||available<0||available>2)throw std::runtime_error("archive lifecycle enum");
        auto fact=ArchiveMediaLifecycleFact{MediaSource(f.value(u"source"_q).toObject()),ArchiveMediaLifetime(lifetime),ArchiveMediaTransition(transition),ArchiveAvailableBytes(available),f.value(u"destroys"_q).toBool(),Text(f,u"event"_q),Unsigned(f.value(u"order"_q)),Signed(f.value(u"observedAt"_q))};
        auto plan=PlanArchiveMediaLifecycle(slice.state,epoch,fact);auto out=Program(TranslateArchiveSql(plan.transaction,slice,security));out.insert(u"modelStatus"_q,int(plan.transaction.status));out.insert(u"retention"_q,int(plan.retention));return out;
    }
    auto binding=StagingBinding(args.value(u"binding"_q).toObject());
    if(operation==u"stagingId"_q)return QJsonObject{{u"status"_q,0},{u"bindingId"_q,ArchiveStagingBindingId(binding,security)}};
    if(operation==u"stagingStatus"_q){
        auto status=CheckArchiveStagingBinding(binding,slice.state,epoch);
        if(status!=ArchiveStagingStatus::Ready&&CanRunArchiveWork(binding.source.epoch,epoch)
            &&binding.source.generation==ArchiveGenerationFor(slice.state,binding.source.key.chat)){
            for(const auto &job:slice.state.jobs)if(job.id==binding.jobId&&job.state==ArchiveJobState::Complete
                &&SameArchiveMessage(job.key,binding.source.key)&&job.version==binding.source.version
                &&job.revision==binding.source.revision&&job.contentHash==binding.source.contentHash
                &&job.mediaIdentity==binding.source.mediaIdentity&&job.generation==binding.source.generation
                &&job.completedSize==binding.totalBytes&&(!job.expectedSize||*job.expectedSize==binding.totalBytes)
                &&job.expectedHash==binding.expectedBlobHash.value_or(QString()))status=ArchiveStagingStatus::Done;
        }
        return QJsonObject{{u"status"_q,int(status)}};
    }
    if(operation==u"mediaStage"_q){auto s=binding.source;auto job=ArchiveMediaJob();job.id=binding.jobId;job.key=s.key;job.version=s.version;job.revision=s.revision;job.contentHash=s.contentHash;job.mediaIdentity=s.mediaIdentity;job.generation=s.generation;job.expectedSize=binding.totalBytes;job.expectedHash=binding.expectedBlobHash.value_or(QString());
        if(!CanRunArchiveWork(s.epoch,epoch))return Program(ArchiveSqlProgram());
        auto plan=PlanArchiveMediaStage(slice.state,job);return Program(TranslateArchiveSql(plan,slice,security));}
    if(operation==u"stagingReject"_q){
        if(CheckArchiveStagingBinding(binding,slice.state,epoch)!=ArchiveStagingStatus::Ready)return Program(ArchiveSqlProgram());
        auto result=ArchiveMediaResult();result.failure=u"source_consistency_unadmitted"_q;
        return Program(TranslateArchiveSql(PlanArchiveMediaCompletion(slice.state,binding.jobId,result),slice,security));
    }
    if(operation==u"stagingBegin"_q){auto header=BeginArchiveStaging(binding,slice.state,epoch);if(!header)return QJsonObject{{u"status"_q,int(CheckArchiveStagingBinding(binding,slice.state,epoch))}};
        return QJsonObject{{u"status"_q,0},{u"bindingId"_q,ArchiveStagingBindingId(binding,security)},{u"header"_q,HeaderValue(*header,security)},{u"emptyProof"_q,ProofValue(ArchiveStagingEmptyProof())}};}
    if(operation==u"stagingEquivalent"_q){
        auto before=OpenStaging(args.value(u"before"_q).toObject(),binding,security);
        auto after=OpenStaging(args.value(u"after"_q).toObject(),binding,security);
        auto a=EncodeArchiveStagingRecord(before),b=EncodeArchiveStagingRecord(after);
        return QJsonObject{{u"status"_q,a&&b&&*a==*b?0:int(ArchiveStagingStatus::Conflict)}};
    }
    auto header=StagingRecord<ArchiveStagingHeader>(OpenStaging(args.value(u"header"_q).toObject(),binding,security));
    if(operation==u"stagingInspect"_q){auto status=CheckArchiveStagingBinding(binding,slice.state,epoch);return QJsonObject{{u"status"_q,int(status)},{u"hash"_q,ArchiveStagingHeaderHash(header)},{u"covered"_q,QString::number(header.coveredBytes)},{u"bindingId"_q,ArchiveStagingBindingId(binding,security)},{u"root"_q,header.rootHash},{u"emptyRoot"_q,ArchiveStagingEmptyRoot()},{u"checkpoint"_q,QString::number(header.checkpoint)}};}
    if(operation==u"stagingPage"_q){
        auto index=Unsigned(args.value(u"index"_q));auto proof=StagingProof(args.value(u"proof"_q));
        auto leaf=ArchiveStagingEmptyProof().siblings.front();
        if(args.contains(u"page"_q)){
            auto page=StagingRecord<ArchiveStagingCoveragePage>(OpenStaging(args.value(u"page"_q).toObject(),binding,security));
            if(page.index!=index||page.checkpoint>header.checkpoint)throw std::runtime_error("archive staging page address");
            leaf=ArchiveStagingPageHash(page);
        }
        if(!header.checkpoint){if(args.contains(u"page"_q)||header.rootHash!=ArchiveStagingEmptyRoot()||proof!=ArchiveStagingEmptyProof())throw std::runtime_error("archive staging initial proof");}
        else if(!ArchiveStagingNodes({header,ArchiveStagingHeaderHash(header),index,leaf,proof}))throw std::runtime_error("archive staging cache proof");
        return QJsonObject{{u"status"_q,int(CheckArchiveStagingBinding(binding,slice.state,epoch))}};
    }
    if(operation==u"stagingRecover"_q){
        auto checkpoint=StagingRecord<ArchiveStagingCheckpoint>(OpenStaging(args.value(u"checkpoint"_q).toObject(),binding,security));
        if(CheckArchiveStagingBinding(binding,slice.state,epoch)!=ArchiveStagingStatus::Ready
            ||checkpoint.header.checkpoint!=Unsigned(args.value(u"expectedCheckpoint"_q))
            ||checkpoint.header.checkpoint>header.checkpoint||!checkpoint.pageIndex)throw std::runtime_error("archive staging checkpoint scope");
        if(args.contains(u"page"_q)){
            auto page=StagingRecord<ArchiveStagingCoveragePage>(OpenStaging(args.value(u"page"_q).toObject(),binding,security));
            if(page.index!=*checkpoint.pageIndex||page.checkpoint!=checkpoint.header.checkpoint
                ||ArchiveStagingPageHash(page)!=checkpoint.pageHash)throw std::runtime_error("archive staging recovered page mismatch");
        }
        auto nodes=NodesValue(checkpoint);auto out=QJsonObject{{u"status"_q,0},{u"nodes"_q,nodes},
            {u"previous"_q,checkpoint.previousHeaderHash},{u"headerHash"_q,ArchiveStagingHeaderHash(checkpoint.header)},
            {u"pageHash"_q,checkpoint.pageHash},{u"proof"_q,ProofValue(checkpoint.proof)}};
        if(checkpoint.pageIndex)out.insert(u"page"_q,QString::number(*checkpoint.pageIndex));
        return out;
    }
    if(operation==u"stagingPiece"_q){
        auto page=std::optional<ArchiveStagingCoveragePage>();if(args.contains(u"page"_q))page=StagingRecord<ArchiveStagingCoveragePage>(OpenStaging(args.value(u"page"_q).toObject(),binding,security));
        auto fact=ArchiveStagingPieceFact{Unsigned(args.value(u"offset"_q)),Bytes(args.value(u"bytes"_q))};if(fact.bytes.size()>kArchiveStagingWindowBytes)throw std::runtime_error("archive staging input limit");
        auto old=args.contains(u"oldPiece"_q)?Bytes(args.value(u"oldPiece"_q)):QByteArray();if(old.size()>kArchiveStagingWindowBytes+4608)throw std::runtime_error("archive staging piece limit");
        auto update=PlanArchiveStagingPiece(header,page,StagingProof(args.value(u"proof"_q)),old,fact,security,slice.state,epoch);
        auto out=QJsonObject{{u"status"_q,int(update.status)}};if(!update)return out;
        out.insert(u"page"_q,RecordValue(update.page,binding,security));out.insert(u"delta"_q,RecordValue(update.next,binding,security));out.insert(u"header"_q,HeaderValue(update.next.header,security));
        out.insert(u"pieceId"_q,update.pieceId);out.insert(u"pieceFrame"_q,Encoded(update.pieceFrame));out.insert(u"pieceHash"_q,update.piece.pieceHash);
        out.insert(u"nodes"_q,NodesValue(update.next));return out;
    }
    if(operation==u"stagingAck"_q){
        auto value=args.value(u"update"_q).toObject();auto update=ArchiveStagingUpdate();update.status=ArchiveStagingStatus::Ready;
        update.expected=StagingRecord<ArchiveStagingHeader>(OpenStaging(args.value(u"oldHeader"_q).toObject(),binding,security));
        update.next=StagingRecord<ArchiveStagingCheckpoint>(OpenStaging(value.value(u"delta"_q).toObject(),binding,security));
        update.page=StagingRecord<ArchiveStagingCoveragePage>(OpenStaging(value.value(u"page"_q).toObject(),binding,security));
        if(update.page.ranges.size()!=1)throw std::runtime_error("archive staging image");update.piece=update.page.ranges[0];update.pieceId=Text(value,u"pieceId"_q);update.pieceFrame=Bytes(value.value(u"pieceFrame"_q));
        if(args.contains(u"page"_q))update.previousPage=StagingRecord<ArchiveStagingCoveragePage>(OpenStaging(args.value(u"page"_q).toObject(),binding,security));
        if(args.contains(u"oldPiece"_q))update.previousPieceFrame=Bytes(args.value(u"oldPiece"_q));
        auto ack=ArchiveStagingDurability{args.value(u"pieceDurable"_q).toBool(),args.value(u"checkpointDurable"_q).toBool(),ArchiveStagingBindingId(binding,security),update.pieceId,ArchiveStagingHeaderHash(update.expected),ArchiveStagingHeaderHash(update.next.header)};
        auto result=CompleteArchiveStagingPiece(header,update,ack,security,slice.state,epoch);return QJsonObject{{u"status"_q,int(result.status)}};
    }
    if(operation==u"stagingFreeze"_q){auto checkpoint=FreezeArchiveStagingCheckpoint(header,slice.state,epoch);if(!checkpoint)return QJsonObject{{u"status"_q,int(ArchiveStagingStatus::Incomplete)}};
        return QJsonObject{{u"status"_q,0},{u"frozen"_q,RecordValue(*checkpoint,binding,security)}};}
    if(operation==u"stagingFinalize"_q){auto frozen=StagingRecord<ArchiveStagingCheckpoint>(OpenStaging(args.value(u"frozen"_q).toObject(),binding,security));
        auto start=BeginArchiveStagingFinalize(header,frozen,security,slice.state,epoch);if(!start.session)return QJsonObject{{u"status"_q,int(start.status)}};
        auto lock=std::lock_guard(stagingMutex);if(stagingSessions.size()>=8||nextStagingSession==UINT64_MAX)throw std::runtime_error("archive staging finalizer limit");
        auto id=++nextStagingSession;stagingSessions.emplace(id,StagingSession{AccountKeyText(account.environment,account.userId).value_or(QString()),security.storeId,security.keyId,ArchiveStagingBindingId(binding,security),std::move(start.session)});
        auto out=FinalizeStep(start.step,slice,security);out.insert(u"handle"_q,QString::number(id));return out;}
    if(operation==u"stagingAdvance"_q||operation==u"stagingPublication"_q){auto lock=std::lock_guard(stagingMutex);auto it=stagingSessions.find(Unsigned(args.value(u"handle"_q)));
        if(it==stagingSessions.end()||it->second.account!=AccountKeyText(account.environment,account.userId).value_or(QString())||it->second.store!=security.storeId||it->second.key!=security.keyId||it->second.binding!=ArchiveStagingBindingId(binding,security))throw std::runtime_error("archive staging session");
        if(operation==u"stagingPublication"_q){auto p=args.value(u"publication"_q).toObject();auto publication=ArchiveStagingPublication{binding,Text(p,u"bindingId"_q),Text(p,u"checkpointHash"_q),Text(p,u"blobHash"_q),Text(p,u"blobToken"_q),Unsigned(p.value(u"request"_q)),Unsigned(p.value(u"chunk"_q)),uint32_t(p.value(u"bytes"_q).toInt(-1)),Text(p,u"plaintextHash"_q),Text(p,u"frameHash"_q),Bytes(p.value(u"frame"_q))};
            return QJsonObject{{u"status"_q,int(CheckArchiveStagingPublication(publication,header,security,slice.state,epoch))}};}
        auto input=args.value(u"input"_q).toObject();auto kind=input.value(u"kind"_q).toInt(-1);auto request=Unsigned(input.value(u"request"_q));auto data=ArchiveStagingFinalizeInput();
        if(kind==0)data=ArchiveStagingPageInput{request,Unsigned(input.value(u"checkpoint"_q)),Bytes(input.value(u"frame"_q)),StagingProof(input.value(u"proof"_q))};
        else if(kind==1)data=ArchiveStagingPieceInput{request,Bytes(input.value(u"frame"_q))};
        else if(kind==2){auto p=input.value(u"publication"_q).toObject();data=ArchiveStagingPublishAck{request,Text(p,u"bindingId"_q),Text(p,u"checkpointHash"_q),Text(p,u"blobToken"_q),Unsigned(p.value(u"chunk"_q)),Text(input,u"frameHash"_q),input.value(u"durable"_q).toBool(),input.contains(u"existingFrame"_q)?Bytes(input.value(u"existingFrame"_q)):QByteArray()};}
        else if(kind==3)data=ArchiveStagingFinish{request};else throw std::runtime_error("archive staging input");
        auto step=AdvanceArchiveStagingFinalize(*it->second.value,data,header,security,slice.state,epoch);auto out=FinalizeStep(step,slice,security);
        if(step.phase==ArchiveStagingFinalizePhase::Finished||step.phase==ArchiveStagingFinalizePhase::Refused)stagingSessions.erase(it);return out;}
    return QJsonObject{{u"status"_q,int(ArchiveStagingStatus::Invalid)}};
}
