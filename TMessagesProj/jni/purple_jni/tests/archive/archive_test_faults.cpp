#include <jni.h>
#include "sqlite/sqlite3.h"
#include <cstring>
namespace {
thread_local int fault = 0;
bool Take(int value) { if (fault != value) return false; fault = 0; return true; }
}
extern "C" int PurpleArchiveTestPrepare(sqlite3 *db, const char *sql, int size, sqlite3_stmt **statement, const char **tail) {
    return Take(1) ? SQLITE_NOMEM : sqlite3_prepare_v2(db, sql, size, statement, tail);
}
extern "C" int PurpleArchiveTestBind(sqlite3_stmt *statement, int position, sqlite3_int64 value) {
    return Take(2) ? SQLITE_RANGE : sqlite3_bind_int64(statement, position, value);
}
extern "C" int PurpleArchiveTestStep(sqlite3_stmt *statement) {
    auto sql = sqlite3_sql(statement);
    if (sql && std::strcmp(sql, "COMMIT") == 0 && Take(3)) return SQLITE_IOERR_FSYNC;
    if (sql && std::strncmp(sql, "INSERT", 6) == 0 && Take(4)) return SQLITE_FULL;
    return sqlite3_step(statement);
}
extern "C" int PurpleArchiveTestReset(sqlite3_stmt *statement) {
    auto result = sqlite3_reset(statement); return Take(7) ? SQLITE_IOERR : result;
}
extern "C" int PurpleArchiveTestFinalize(sqlite3_stmt *statement) {
    auto result = sqlite3_finalize(statement); return Take(5) ? SQLITE_IOERR : result;
}
extern "C" int PurpleArchiveTestClose(sqlite3 *db) { return Take(6) ? SQLITE_BUSY : sqlite3_close(db); }
extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveFaults_set(JNIEnv *, jclass, jint value) { fault = value; }
