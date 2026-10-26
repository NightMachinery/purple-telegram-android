package org.telegram.messenger.purple;

final class PurpleArchiveIngress {
    static final class Ticket {
        final PurpleArchive.Worker worker;
        final PurpleArchiveStore store;
        final long epoch;
        final boolean permitted;
        Ticket(PurpleArchive.Worker worker) {
            this.worker = worker; store = worker.store; epoch = worker.purgeEpoch;
            permitted = !worker.closing && !worker.retired && (store == null || !store.maintenance && worker.status.state.equals("Ready"));
        }
        boolean current() {
            return permitted && !worker.closing && !worker.retired && worker.purgeEpoch == epoch
                && (store == null || worker.store == store) && (worker.store == null || !worker.store.maintenance && worker.status.state.equals("Ready"));
        }
        boolean current(PurpleArchive.Worker expected) { return expected == worker && current(); }
    }
    static Ticket capture(String account) {
        if (!PurpleSyncRecoveryFiles.key(account)) throw new IllegalArgumentException("Archive ingress account");
        return new Ticket(PurpleArchive.ingressWorker(account));
    }
    static Ticket key(String key) {
        try { return capture(new org.json.JSONObject(key).getString("account")); }
        catch (Exception e) { throw new IllegalArgumentException("Archive ingress key", e); }
    }
    static void committed(PurpleArchiveStore store) {
        PurpleArchive.Worker worker = PurpleArchive.boundWorker(store.binding.account);
        if (worker != null && worker.store == store) ++worker.purgeEpoch;
    }
    private PurpleArchiveIngress() {}
}
