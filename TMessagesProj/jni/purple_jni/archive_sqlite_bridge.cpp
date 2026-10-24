#include <jni.h>
#include "sqlite/sqlite3.h"
#include "archive_files_util.h"
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

namespace {
struct Connection {
    sqlite3 *db = nullptr;
    std::string parentPath, parentIdentity, leaf;
    std::thread::id owner = std::this_thread::get_id();
    ~Connection() { if (db && sqlite3_get_autocommit(db)) sqlite3_close_v2(db); }
};
struct Cell {
    int type = 0;
    int64_t integer = 0;
    std::vector<unsigned char> bytes;
};
std::mutex connectionsMutex;
std::unordered_map<jlong, std::shared_ptr<Connection>> connections;
jlong nextHandle = 1;

void fail(JNIEnv *env, int code) {
    auto cls = env->FindClass("java/lang/IllegalStateException");
    auto text = std::string("archive sqlite ") + std::to_string(code);
    if (cls) env->ThrowNew(cls, text.c_str());
}
bool readBytes(JNIEnv *env, jbyteArray input, std::vector<unsigned char> &result, size_t limit) {
    if (!input) return false;
    auto size = env->GetArrayLength(input);
    if (size < 0 || static_cast<size_t>(size) > limit) return false;
    result.resize(size);
    if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(result.data()));
    return !env->ExceptionCheck();
}
std::shared_ptr<Connection> owned(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(connectionsMutex);
    auto found = connections.find(handle);
    if (found == connections.end() || found->second->owner != std::this_thread::get_id()) {
        fail(env, SQLITE_MISUSE); return nullptr;
    }
    return found->second;
}
bool OwnedFiles(int parent, const std::string &leaf) {
    for (const auto suffix : {"", "-wal", "-shm", "-journal"}) {
        auto name = leaf + suffix; struct stat value{};
        if (::fstatat(parent,name.c_str(),&value,AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT) continue;
            return false;
        }
        if (!ArchiveFiles::Regular(value) || (value.st_mode & 0777) != 0600) return false;
    }
    return true;
}
jobject result(JNIEnv *env, int code, int extended, int reset, int finalized,
        int64_t count, int64_t direct, int64_t total, const std::vector<std::vector<Cell>> &rows) {
    auto cellClass = env->FindClass("org/telegram/messenger/purple/PurpleArchiveSqlite$Value");
    auto rowClass = env->FindClass("[Lorg/telegram/messenger/purple/PurpleArchiveSqlite$Value;");
    auto resultClass = env->FindClass("org/telegram/messenger/purple/PurpleArchiveSqlite$Result");
    if (!cellClass || !rowClass || !resultClass) return nullptr;
    auto cellConstructor = env->GetMethodID(cellClass, "<init>", "(IJ[B)V");
    auto resultConstructor = env->GetMethodID(resultClass, "<init>", "(IIIIJJJ[[Lorg/telegram/messenger/purple/PurpleArchiveSqlite$Value;)V");
    if (!cellConstructor || !resultConstructor) return nullptr;
    auto data = env->NewObjectArray(rows.size(), rowClass, nullptr);
    if (!data) return nullptr;
    for (size_t i = 0; i < rows.size(); ++i) {
        auto row = env->NewObjectArray(rows[i].size(), cellClass, nullptr);
        if (!row) return nullptr;
        for (size_t j = 0; j < rows[i].size(); ++j) {
            const auto &cell = rows[i][j];
            auto bytes = (cell.type == 2 || cell.type == 3) ? env->NewByteArray(cell.bytes.size()) : nullptr;
            if ((cell.type == 2 || cell.type == 3) && !bytes) return nullptr;
            if (bytes && !cell.bytes.empty()) env->SetByteArrayRegion(bytes, 0, cell.bytes.size(), reinterpret_cast<const jbyte*>(cell.bytes.data()));
            auto value = env->NewObject(cellClass, cellConstructor, jint(cell.type), jlong(cell.integer), bytes);
            if (!value || env->ExceptionCheck()) return nullptr;
            env->SetObjectArrayElement(row, j, value);
            env->DeleteLocalRef(value);
            if (bytes) env->DeleteLocalRef(bytes);
        }
        env->SetObjectArrayElement(data, i, row);
        env->DeleteLocalRef(row);
    }
    return env->NewObject(resultClass, resultConstructor, jint(code), jint(extended), jint(reset), jint(finalized),
        jlong(count), jlong(direct), jlong(total), data);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveSqlite_openNative(JNIEnv *env, jclass, jbyteArray path, jboolean create, jboolean readOnly) {
    std::vector<unsigned char> bytes;
    if (!readBytes(env, path, bytes, 8192) || bytes.empty() || std::find(bytes.begin(), bytes.end(), 0) != bytes.end()) {
        fail(env, SQLITE_CANTOPEN); return 0;
    }
    auto name = std::string(bytes.begin(), bytes.end());
    auto slash = name.find_last_of('/');
    if (slash == std::string::npos || !ArchiveFiles::Leaf(name.substr(slash+1))) { fail(env, SQLITE_CANTOPEN); return 0; }
    auto parentPath = name.substr(0,slash);
    ArchiveFiles::Fd parent(ArchiveFiles::OpenDirectory(parentPath));
    struct stat parentMark{};
    if (parent.value < 0 || ::fstat(parent.value,&parentMark) != 0) { fail(env, SQLITE_CANTOPEN); return 0; }
    struct stat before{};
    auto exists = ::fstatat(parent.value,name.substr(slash+1).c_str(),&before,AT_SYMLINK_NOFOLLOW) == 0;
    if (!exists && errno != ENOENT) { fail(env, SQLITE_CANTOPEN); return 0; }
    if (!exists) {
        if (!create || readOnly) { fail(env,SQLITE_CANTOPEN); return 0; }
        ArchiveFiles::Fd created(::openat(parent.value,name.substr(slash+1).c_str(),O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));
        if (created.value < 0 || ::fstat(created.value,&before) != 0 || !ArchiveFiles::Regular(before)
            || ::fsync(created.value) != 0 || !created.close() || ::fsync(parent.value) != 0) { fail(env,SQLITE_CANTOPEN); return 0; }
    }
    if (!ArchiveFiles::Regular(before) || (before.st_mode & 0777) != 0600 || !OwnedFiles(parent.value,name.substr(slash+1))) { fail(env,SQLITE_CANTOPEN); return 0; }
    auto connection = std::make_shared<Connection>();
    auto code = sqlite3_open_v2(name.c_str(), &connection->db,
        (readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE) | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_NOFOLLOW, nullptr);
    if (code != SQLITE_OK) { fail(env, code); return 0; }
    struct stat after{};
    if (lstat(name.c_str(), &after) != 0 || !ArchiveFiles::Regular(after)
            || before.st_dev != after.st_dev || before.st_ino != after.st_ino) { fail(env, SQLITE_CANTOPEN); return 0; }
    connection->parentPath = parentPath; connection->parentIdentity = ArchiveFiles::Identity(parentMark); connection->leaf = name.substr(slash+1);
    ArchiveFiles::Fd checkedParent(ArchiveFiles::OpenDirectory(parentPath,connection->parentIdentity));
    int enabled = 0, persist = 1, moved = 0;
    code = sqlite3_db_config(connection->db, SQLITE_DBCONFIG_NO_CKPT_ON_CLOSE, 1, &enabled);
    if (code == SQLITE_OK && enabled != 1) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) code = sqlite3_db_config(connection->db,SQLITE_DBCONFIG_DEFENSIVE,1,&enabled);
    if (code == SQLITE_OK && enabled != 1) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) code = sqlite3_db_config(connection->db,SQLITE_DBCONFIG_TRUSTED_SCHEMA,0,&enabled);
    if (code == SQLITE_OK && enabled != 0) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) code = sqlite3_db_config(connection->db,SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION,0,&enabled);
    if (code == SQLITE_OK && enabled != 0) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) code = sqlite3_file_control(connection->db,"main",SQLITE_FCNTL_PERSIST_WAL,&persist);
    if (code == SQLITE_OK && persist != 1) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) code = sqlite3_file_control(connection->db,"main",SQLITE_FCNTL_HAS_MOVED,&moved);
    if (code == SQLITE_OK && (moved || checkedParent.value < 0 || !checkedParent.close() || !parent.close())) code = SQLITE_READONLY_DBMOVED;
    if (code == SQLITE_OK) code = sqlite3_extended_result_codes(connection->db, 1);
    if (code == SQLITE_OK) code = sqlite3_busy_timeout(connection->db, 0);
    std::lock_guard<std::mutex> lock(connectionsMutex);
    if (code != SQLITE_OK || nextHandle == std::numeric_limits<jlong>::max()) { fail(env, code ? code : SQLITE_FULL); return 0; }
    auto handle = nextHandle++;
    connections.emplace(handle, std::move(connection));
    return handle;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveSqlite_runNative(JNIEnv *env, jclass, jlong handle,
        jbyteArray query, jintArray typesInput, jlongArray integersInput, jobjectArray bytesInput,
        jint maxRows, jint maxBytes) {
    auto connection = owned(env, handle);
    if (!connection) return nullptr;
    ArchiveFiles::Fd parent(ArchiveFiles::OpenDirectory(connection->parentPath,connection->parentIdentity));
    int moved = 0;
    if (parent.value < 0 || !OwnedFiles(parent.value,connection->leaf) || sqlite3_file_control(connection->db,"main",SQLITE_FCNTL_HAS_MOVED,&moved) != SQLITE_OK
        || moved || !parent.close()) { fail(env,SQLITE_READONLY_DBMOVED); return nullptr; }
    std::vector<unsigned char> sql;
    if (!readBytes(env, query, sql, 1024 * 1024) || sql.empty()
            || std::find(sql.begin(), sql.end(), 0) != sql.end() || !typesInput || !integersInput || !bytesInput
            || maxRows < 0 || maxRows > 128 || maxBytes < 0 || maxBytes > 16 * 1024 * 1024) {
        fail(env, SQLITE_MISUSE); return nullptr;
    }
    auto size = env->GetArrayLength(typesInput);
    if (size > 32766 || size != env->GetArrayLength(integersInput) || size != env->GetArrayLength(bytesInput)) {
        fail(env, SQLITE_RANGE); return nullptr;
    }
    std::vector<jint> types(size);
    std::vector<jlong> integers(size);
    if (size) {
        env->GetIntArrayRegion(typesInput, 0, size, types.data());
        env->GetLongArrayRegion(integersInput, 0, size, integers.data());
    }
    if (env->ExceptionCheck()) return nullptr;
    auto statement = static_cast<sqlite3_stmt*>(nullptr);
    const char *tail = nullptr;
    auto code = sqlite3_prepare_v2(connection->db, reinterpret_cast<const char*>(sql.data()), sql.size(), &statement, &tail);
    if (code == SQLITE_OK && !statement) code = SQLITE_MISUSE;
    if (code == SQLITE_OK) {
        auto end = reinterpret_cast<const char*>(sql.data() + sql.size());
        while (tail < end && (*tail == ' ' || *tail == '\n' || *tail == '\r' || *tail == '\t')) ++tail;
        if (tail != end || sqlite3_bind_parameter_count(statement) != size) code = SQLITE_MISUSE;
    }
    size_t bindBytes = 0;
    for (int i = 0; code == SQLITE_OK && i < size; ++i) {
        if (types[i] == 0) code = sqlite3_bind_null(statement, i + 1);
        else if (types[i] == 1) code = sqlite3_bind_int64(statement, i + 1, integers[i]);
        else if (types[i] == 2 || types[i] == 3) {
            auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(bytesInput, i));
            std::vector<unsigned char> bytes;
            auto valid = readBytes(env, value, bytes, 16 * 1024 * 1024 - bindBytes);
            if (value) env->DeleteLocalRef(value);
            if (!valid) { code = SQLITE_TOOBIG; break; }
            bindBytes += bytes.size();
            static const char empty = 0;
            auto data = bytes.empty() ? &empty : reinterpret_cast<const char*>(bytes.data());
            code = types[i] == 2 ? sqlite3_bind_text(statement, i + 1, data, bytes.size(), SQLITE_TRANSIENT)
                : sqlite3_bind_blob(statement, i + 1, data, bytes.size(), SQLITE_TRANSIENT);
        } else code = SQLITE_MISMATCH;
    }
    auto startChanges = sqlite3_total_changes64(connection->db);
    std::vector<std::vector<Cell>> rows;
    size_t readBytesCount = 0;
    int64_t count = 0;
    if (code == SQLITE_OK) {
        while ((code = sqlite3_step(statement)) == SQLITE_ROW) {
            if (++count > maxRows) { code = SQLITE_TOOBIG; break; }
            auto columns = sqlite3_column_count(statement);
            if (columns > 256) { code = SQLITE_TOOBIG; break; }
            std::vector<Cell> row;
            for (int i = 0; i < columns; ++i) {
                Cell cell;
                auto type = sqlite3_column_type(statement, i);
                if (type == SQLITE_INTEGER) { cell.type = 1; cell.integer = sqlite3_column_int64(statement, i); }
                else if (type == SQLITE_TEXT || type == SQLITE_BLOB) {
                    cell.type = type == SQLITE_TEXT ? 2 : 3;
                    auto length = sqlite3_column_bytes(statement, i);
                    auto data = static_cast<const unsigned char*>(type == SQLITE_TEXT
                        ? static_cast<const void*>(sqlite3_column_text(statement, i)) : sqlite3_column_blob(statement, i));
                    if (length < 0 || size_t(length) > size_t(maxBytes) - readBytesCount || (length && !data)) {
                        code = SQLITE_TOOBIG; break;
                    }
                    readBytesCount += length;
                    if (length) cell.bytes.assign(data, data + length);
                } else if (type != SQLITE_NULL) { code = SQLITE_MISMATCH; break; }
                row.push_back(std::move(cell));
            }
            if (code != SQLITE_ROW) break;
            rows.push_back(std::move(row));
        }
    }
    auto extended = code == SQLITE_DONE ? SQLITE_OK : sqlite3_extended_errcode(connection->db);
    auto direct = sqlite3_changes64(connection->db);
    auto total = sqlite3_total_changes64(connection->db) - startChanges;
    auto reset = statement ? sqlite3_reset(statement) : SQLITE_OK;
    auto finalized = statement ? sqlite3_finalize(statement) : SQLITE_OK;
    if (env->ExceptionCheck()) return nullptr;
    return result(env, code, extended, reset, finalized, count, direct, total, rows);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveSqlite_autocommitNative(JNIEnv *env, jclass, jlong handle) {
    auto connection = owned(env, handle);
    return connection && sqlite3_get_autocommit(connection->db) != 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveSqlite_closeNative(JNIEnv *env, jclass, jlong handle) {
    auto connection = owned(env, handle);
    if (!connection) return SQLITE_MISUSE;
    if (!sqlite3_get_autocommit(connection->db)) return SQLITE_BUSY;
    auto code = sqlite3_close(connection->db);
    if (code == SQLITE_OK) {
        connection->db = nullptr;
        std::lock_guard<std::mutex> lock(connectionsMutex);
        connections.erase(handle);
    }
    return code;
}
