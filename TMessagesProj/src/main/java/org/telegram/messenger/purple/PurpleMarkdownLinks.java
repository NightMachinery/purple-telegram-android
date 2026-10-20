package org.telegram.messenger.purple;

public final class PurpleMarkdownLinks {
    private PurpleMarkdownLinks() {
    }

    public static String userMention(String target) {
        if (target == null || !target.matches("[0-9]+")) return null;
        try {
            long id = Long.parseLong(target);
            return id > 0 ? "tg://user?id=" + id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String noUnderline(String target) {
        if (target != null && target.startsWith("@")) {
            String username = target.substring(1);
            return username.matches("[A-Za-z0-9_]+") ? "https://t.me/" + username : null;
        }
        return target != null && target.regionMatches(true, 0, "card:", 0, 5)
                ? null : absolute(target);
    }

    public static String absolute(String target) {
        if (target == null || !target.matches("(?s)[A-Za-z][A-Za-z0-9+.-]*:.+")) return null;
        for (int i = 0; i < target.length(); ++i) {
            if (Character.isISOControl(target.charAt(i))) return null;
        }
        return target;
    }
}
