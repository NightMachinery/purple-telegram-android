package org.telegram.messenger.purple;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

final class PurpleSyncManagedReads {
    private final LongSupplier clock;
    private int active;
    private long revision,completedAt;
    PurpleSyncManagedReads(LongSupplier clock){this.clock=clock;}
    synchronized Runnable begin(){
        ++active;AtomicBoolean ended=new AtomicBoolean();
        return ()->{if(ended.compareAndSet(false,true))finish();};
    }
    private synchronized void finish(){--active;++revision;completedAt=clock.getAsLong();}
    synchronized PurpleSyncClient.ManagedReads state(){return new PurpleSyncClient.ManagedReads(active>0,revision,completedAt);}
}
