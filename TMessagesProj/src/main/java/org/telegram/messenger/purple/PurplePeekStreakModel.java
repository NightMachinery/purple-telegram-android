package org.telegram.messenger.purple;

public final class PurplePeekStreakModel {
    public long count;
    public boolean active;
    public long endElapsed, endWall, deadlineWall, deadlineElapsed;
    public String boot = "";
    public long revision;

    public static PurplePeekStreakModel decode(String text) {
        PurplePeekStreakModel value = new PurplePeekStreakModel();
        if (text.isEmpty()) return value;
        if (text.length() > 256) throw new IllegalArgumentException("Peek streak size");
        String[] parts = text.split(";", -1);
        if (parts.length != 8 || !parts[0].equals("1")) throw new IllegalArgumentException("Peek streak format");
        value.count = Long.parseLong(parts[1]);
        if (!parts[2].equals("0") && !parts[2].equals("1")) throw new IllegalArgumentException("Peek streak activity");
        value.active = parts[2].equals("1"); value.endElapsed = Long.parseLong(parts[3]);
        value.endWall = Long.parseLong(parts[4]); value.deadlineWall = Long.parseLong(parts[5]); value.deadlineElapsed = Long.parseLong(parts[6]); value.boot = parts[7];
        if (value.count < 0 || value.count > Integer.MAX_VALUE || value.endElapsed < 0 || value.endWall < 0 || value.deadlineWall < 0 || value.deadlineElapsed < 0)
            throw new IllegalArgumentException("Peek streak clock");
        return value;
    }
    public String encode() {
        return "1;" + count + ";" + (active ? 1 : 0) + ";" + endElapsed + ";" + endWall + ";" + deadlineWall + ";" + deadlineElapsed + ";" + boot;
    }
    public void started(long deadline, long elapsed, long wall, String currentBoot) {
        count = count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : count + 1;
        active = true; deadlineWall = deadline;
        deadlineElapsed = deadline > 0 ? elapsed + Math.min(Long.MAX_VALUE - elapsed, Math.max(0, deadline - wall)) : 0;
        boot = currentBoot; ++revision;
    }
    public void observe(boolean running, long deadline, long elapsed, long wall, String currentBoot) {
        if (running) {
            if (!active) { active = true; boot = currentBoot; ++revision; }
            if (deadlineWall != deadline) {
                deadlineWall = deadline;
                deadlineElapsed = deadline > 0 ? elapsed + Math.min(Long.MAX_VALUE - elapsed, Math.max(0, deadline - wall)) : 0;
            }
        } else if (active) {
            long end = deadlineWall > 0 ? Math.min(wall, deadlineWall) : wall;
            long ago = Math.max(0, wall - end);
            if (!boot.isEmpty() && boot.equals(currentBoot) && deadlineElapsed > 0 && wall >= deadlineWall) {
                endElapsed = Math.min(elapsed, deadlineElapsed);
                endWall = Math.max(0, wall - (elapsed - endElapsed));
            } else {
                endWall = end; endElapsed = Math.max(0, elapsed - Math.min(elapsed, ago));
            }
            if (!boot.isEmpty() && !boot.equals(currentBoot)) {
                endWall = end;
                endElapsed = 0;
                long age = wall - endWall;
                if (age >= 0 && age <= elapsed) {
                    endElapsed = elapsed - age; boot = currentBoot;
                }
            } else boot = currentBoot;
            active = false; deadlineWall = 0; deadlineElapsed = 0; ++revision;
        }
    }
    public boolean reset(long elapsed, long wall, String currentBoot, int minutes) {
        if (active || count == 0 || boot.isEmpty() || currentBoot.isEmpty()) return false;
        if (!boot.equals(currentBoot)) {
            long age = wall - endWall;
            if (age >= 0 && age <= elapsed) { endElapsed = elapsed - age; boot = currentBoot; }
        }
        long delta = boot.equals(currentBoot) ? elapsed - endElapsed : wall - endWall;
        if (delta < minutes * 60000L) return false;
        count = 0; ++revision; return true;
    }
    public int extra(boolean enabled, int step, int maximum) {
        return enabled ? (int) Math.min(maximum, Math.min(count, 3600L) * step) : 0;
    }
}
