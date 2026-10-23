package org.telegram.messenger.purple;

final class PurpleWorkModeModel {
    static native String createNative(byte[] local, String key, String globalKey,
            byte[] global, String globalPath, byte[] globalGood, byte[] own, String ownPath, byte[] ownGood);
    static byte[] localForReload(byte[] current, boolean missing, byte[] previous, String global) {
        if (missing) return new byte[0];
        if (current != null && global != null && PurpleRoutedWriteModel.modeNative(current, global, global) >= 0) return current;
        return previous == null ? new byte[0] : previous;
    }
    static final class Publication<T> {
        private final java.util.concurrent.locks.ReentrantReadWriteLock lock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        private T current;
        Publication(T initial) { current = initial; }
        <R> R read(java.util.function.Function<T, R> reader) {
            lock.readLock().lock();
            try { return reader.apply(current); }
            finally { lock.readLock().unlock(); }
        }
        boolean publish(T replacement, java.util.function.BooleanSupplier valid, java.util.function.Consumer<T> retire) {
            lock.writeLock().lock();
            try {
                if (!valid.getAsBoolean()) return false;
                T previous = current;
                current = replacement;
                retire.accept(previous);
                return true;
            } finally { lock.writeLock().unlock(); }
        }
    }
    static native void retireNative(long[] handles);
    static native int visibleNative(long handle, long peer, int kind);
    static native int visibleUnpeekedNative(long handle, long peer, int kind);
    static native String deciderNative(long handle, long peer, int kind);
    static native boolean storyShownNative(long handle, long peer, int kind, boolean unseen, int folder, boolean exempt);
    private PurpleWorkModeModel() { }
}
