package android.security.keystore;

public final class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
    public String alias; public int purposes, size; public String[] modes, paddings;
    public boolean randomized, authentication;
    private KeyGenParameterSpec() {}
    public static final class Builder {
        private final KeyGenParameterSpec value = new KeyGenParameterSpec();
        public Builder(String alias, int purposes) { value.alias = alias; value.purposes = purposes; }
        public Builder setKeySize(int size) { value.size = size; return this; }
        public Builder setBlockModes(String... modes) { value.modes = modes; return this; }
        public Builder setEncryptionPaddings(String... paddings) { value.paddings = paddings; return this; }
        public Builder setRandomizedEncryptionRequired(boolean enabled) { value.randomized = enabled; return this; }
        public Builder setUserAuthenticationRequired(boolean enabled) { value.authentication = enabled; return this; }
        public KeyGenParameterSpec build() { return value; }
    }
}
