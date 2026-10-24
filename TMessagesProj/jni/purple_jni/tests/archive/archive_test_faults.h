#pragma once
#include "sqlite/sqlite3.h"
extern "C" int PurpleArchiveTestPrepare(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
extern "C" int PurpleArchiveTestBind(sqlite3_stmt *, int, sqlite3_int64);
extern "C" int PurpleArchiveTestStep(sqlite3_stmt *);
extern "C" int PurpleArchiveTestReset(sqlite3_stmt *);
extern "C" int PurpleArchiveTestFinalize(sqlite3_stmt *);
extern "C" int PurpleArchiveTestClose(sqlite3 *);
#define sqlite3_prepare_v2 PurpleArchiveTestPrepare
#define sqlite3_bind_int64 PurpleArchiveTestBind
#define sqlite3_step PurpleArchiveTestStep
#define sqlite3_reset PurpleArchiveTestReset
#define sqlite3_finalize PurpleArchiveTestFinalize
#define sqlite3_close PurpleArchiveTestClose
