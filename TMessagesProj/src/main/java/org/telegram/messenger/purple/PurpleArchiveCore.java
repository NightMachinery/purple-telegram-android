package org.telegram.messenger.purple;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

@androidx.annotation.Keep
final class PurpleArchiveCore {
    static { PurpleCore.ensureLoaded(); }
    static final class Statement {
        final String sql;
        final PurpleArchiveSqlite.Value[] binds;
        final long minRows, maxRows, minChanges, maxChanges;
        final int expectation, counter;
        Statement(JSONObject value) throws Exception {
            sql = value.getString("sql"); expectation = value.getInt("expectation"); counter = value.getInt("counter");
            minRows = number(value.get("minRows")); maxRows = number(value.get("maxRows"));
            minChanges = number(value.get("minChanges")); maxChanges = number(value.get("maxChanges"));
            if (sql.isEmpty() || sql.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024
                || expectation < 0 || expectation > 5 || counter < 0 || counter > 1 || minRows < 0 || maxRows < -1
                || minChanges < 0 || maxChanges < -1 || (maxRows >= 0 && minRows > maxRows)
                || (maxChanges >= 0 && minChanges > maxChanges)) throw new IllegalArgumentException("archive statement");
            JSONArray input = value.getJSONArray("binds");
            if (input.length() > 32766) throw new IllegalArgumentException("archive binds");
            binds = new PurpleArchiveSqlite.Value[input.length()];
            for (int i = 0; i < binds.length; ++i) {
                JSONArray bind = input.getJSONArray(i); int type = bind.getInt(0);
                if (type == PurpleArchiveSqlite.NULL && bind.length() == 1) binds[i] = PurpleArchiveSqlite.Value.nil();
                else if (type == PurpleArchiveSqlite.INTEGER && bind.length() == 2) binds[i] = PurpleArchiveSqlite.Value.integer(number(bind.get(1)));
                else if (type == PurpleArchiveSqlite.TEXT && bind.length() == 2 && bind.get(1) instanceof String)
                    binds[i] = PurpleArchiveSqlite.Value.text(bind.getString(1));
                else if (type == PurpleArchiveSqlite.BLOB && bind.length() == 2 && bind.get(1) instanceof String)
                    binds[i] = PurpleArchiveSqlite.Value.blob(unbase64(bind.getString(1)));
                else throw new IllegalArgumentException("archive bind type");
            }
        }
        boolean accepts(PurpleArchiveSqlite.Result result) {
            return result != null && result.done() && checkNative(expectation, minRows, maxRows, minChanges, maxChanges,
                counter, true, result.rows, result.directChanges, result.totalChanges);
        }
    }
    static final class Program {
        final String status, retentionGap;
        final List<Statement> statements = new ArrayList<>();
        Program(JSONObject input) throws Exception {
            status = input.getString("status"); retentionGap = input.optString("retentionGap");
            JSONArray rows = input.getJSONArray("statements");
            if (rows.length() > 2048 || (!status.equals("Ready") && rows.length() != 0))
                throw new IllegalArgumentException("archive program");
            long totalBytes = 0;
            for (int i = 0; i < rows.length(); ++i) {
                Statement statement = new Statement(rows.getJSONObject(i));
                for (PurpleArchiveSqlite.Value bind : statement.binds) {
                    totalBytes += bind.bytes == null ? 0 : bind.bytes.length;
                    if (totalBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("archive program bytes");
                }
                statements.add(statement);
            }
        }
    }
    static final class Execution {
        final boolean committed;
        final List<PurpleArchiveSqlite.Result> results;
        Execution(boolean committed, List<PurpleArchiveSqlite.Result> results) { this.committed = committed; this.results = results; }
    }
    static JSONObject call(String operation, JSONObject arguments, PurpleArchiveCrypto crypto) throws Exception {
        JSONObject request = new JSONObject().put("operation", operation).put("arguments", arguments);
        byte[] encoded = request.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 64 * 1024 * 1024) throw new IllegalArgumentException("archive bridge size");
        byte[] response = callNative(encoded, crypto);
        if (response == null || response.length > 64 * 1024 * 1024) throw new IllegalStateException("archive bridge refused");
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }
    static Execution execute(PurpleArchiveSqlite database, Program program, BooleanSupplier current) {
        return execute(database, program, current, new Object());
    }
    static Execution execute(PurpleArchiveSqlite database, Program program, BooleanSupplier current, Object publicationLock) {
        return execute(database, program, current, publicationLock, () -> true);
    }
    static Execution execute(PurpleArchiveSqlite database, Program program, BooleanSupplier current, Object publicationLock, BooleanSupplier beforeCommit) {
        List<PurpleArchiveSqlite.Result> results = new ArrayList<>();
        if (!program.status.equals("Ready") || !current.getAsBoolean() || !database.autocommit()) return new Execution(false, results);
        if (program.statements.isEmpty()) return new Execution(acknowledgeNative(true, true), results);
        if (!program.statements.get(0).sql.equals("BEGIN IMMEDIATE")
            || !program.statements.get(program.statements.size() - 1).sql.equals("COMMIT"))
            return new Execution(false, results);
        try {
            for (int i = 0; i < program.statements.size(); ++i) {
                Statement statement = program.statements.get(i);
                PurpleArchiveSqlite.Result result;
                synchronized (publicationLock) {
                    if (!current.getAsBoolean()) throw new IllegalStateException("archive work revoked");
                    if (i == program.statements.size() - 1 && !beforeCommit.getAsBoolean()) throw new IllegalStateException("Archive commit authority changed");
                    result = database.run(statement.sql, statement.binds);
                }
                if (!statement.accepts(result)) throw new IllegalStateException("archive statement refused");
                results.add(result);
                if (i == 0) {
                    if (database.autocommit()) throw new IllegalStateException("archive transaction not active");
                }
            }
            if (!database.autocommit()) throw new IllegalStateException("archive commit uncertain");
            synchronized (publicationLock) {
                if (!current.getAsBoolean()) throw new IllegalStateException("archive completion revoked");
                return new Execution(acknowledgeNative(true, true), results);
            }
        } catch (RuntimeException e) {
            results.clear();
            if (!database.autocommit()) {
                requireDone(database.run("ROLLBACK"));
                if (!database.autocommit()) throw new IllegalStateException("archive rollback uncertain");
            }
            return new Execution(acknowledgeNative(false, false), results);
        }
    }
    static void requireDone(PurpleArchiveSqlite.Result result) {
        if (result == null || !result.counts(0, 0, 0, 0, true)) throw new IllegalStateException("archive SQL barrier refused");
    }
    static long number(Object input) {
        if (!(input instanceof String) || !((String) input).matches("-?(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("archive integer encoding");
        long value = Long.parseLong((String) input);
        if (!Long.toString(value).equals(input)) throw new IllegalArgumentException("archive integer encoding");
        return value;
    }
    static String base64(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    static byte[] unbase64(String value) {
        byte[] decoded = Base64.decode(value, Base64.NO_WRAP);
        if (!base64(decoded).equals(value)) throw new IllegalArgumentException("archive base64 encoding");
        return decoded;
    }
    static native byte[] envelopeNative(String account, String store, String dataKeyId, String platformKeyId,
        byte[] value, boolean wrap, PurpleArchiveCrypto crypto);
    static native byte[] mediaNative(String account, String store, String keyId, String blobToken,
        long total, long index, byte[] bytes, boolean seal, PurpleArchiveCrypto crypto);
    private static native byte[] callNative(byte[] request, PurpleArchiveCrypto crypto);
    private static native boolean checkNative(int expectation, long minRows, long maxRows, long minChanges, long maxChanges,
        int counter, boolean reachedDone, long rows, long directChanges, long totalChanges);
    private static native boolean acknowledgeNative(boolean checked, boolean durable);
    private PurpleArchiveCore() {}
}
