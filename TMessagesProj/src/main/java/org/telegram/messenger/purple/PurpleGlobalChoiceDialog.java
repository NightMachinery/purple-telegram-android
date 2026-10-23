package org.telegram.messenger.purple;

import java.lang.ref.WeakReference;

public final class PurpleGlobalChoiceDialog {
    public interface Visibility { boolean showing(); }
    public static final class Token {
        private final boolean picker;
        private WeakReference<Object> identity;
        private Visibility visibility;
        private boolean shown;
        private boolean retired;
        private Token(boolean picker) { this.picker = picker; }
    }
    private Token active;
    private int stage;
    public Token picker() {
        if (stage != 0) return null;
        stage = 1;
        return active = new Token(true);
    }
    public Token confirmation() {
        if (stage != 2) return null;
        stage = 3;
        return active = new Token(false);
    }
    public void bind(Token token, Object dialog, Visibility visibility) {
        if (token == null || token != active || token.retired || dialog == null || visibility == null) {
            dismissed(token);
            return;
        }
        token.identity = new WeakReference<>(dialog);
        token.visibility = visibility;
    }
    public boolean shown(Token token, Object returned) {
        if (token == null || token != active || token.retired || token.identity == null
                || returned == null || token.identity.get() != returned || token.visibility == null || !token.visibility.showing()) {
            dismissed(token);
            return false;
        }
        token.shown = true;
        return true;
    }
    public boolean current(Token token) {
        if (token == null || token != active || token.retired || !token.shown) return false;
        if (token.identity == null || token.identity.get() == null || token.visibility == null || !token.visibility.showing()) {
            dismissed(token);
            return false;
        }
        return true;
    }
    public boolean current() { return current(active); }
    public boolean handoff(Token picker) {
        if (!current(picker) || !picker.picker || stage != 1) return false;
        clear(picker);
        active = null;
        stage = 2;
        return true;
    }
    public void dismissed(Token token) {
        if (token == null) return;
        clear(token);
        if (active == token) { active = null; stage = 4; }
    }
    private static void clear(Token token) {
        token.retired = true;
        token.shown = false;
        token.identity = null;
        token.visibility = null;
    }
}
