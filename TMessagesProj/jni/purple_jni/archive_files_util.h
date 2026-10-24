#pragma once
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <string>
#include <cstdint>
#if !defined(__APPLE__)
#include <sys/syscall.h>
#else
#include <stdio.h>
#endif

namespace ArchiveFiles {
struct Fd {
    int value = -1;
    explicit Fd(int value = -1) : value(value) {}
    Fd(const Fd &) = delete;
    Fd &operator=(const Fd &) = delete;
    ~Fd() { if (value >= 0) ::close(value); }
    bool close() { auto old = value; value = -1; return old < 0 || ::close(old) == 0; }
};
inline std::string Identity(const struct stat &value) {
    return std::to_string(uint64_t(value.st_dev)) + ":" + std::to_string(uint64_t(value.st_ino));
}
inline std::string Stamp(const struct stat &value) {
#if defined(__APPLE__)
    auto modified = value.st_mtimespec; auto changed = value.st_ctimespec;
#else
    auto modified = value.st_mtim; auto changed = value.st_ctim;
#endif
    return Identity(value) + ":" + std::to_string(value.st_mode) + ":" + std::to_string(value.st_uid)
        + ":" + std::to_string(value.st_nlink) + ":" + std::to_string(value.st_size)
        + ":" + std::to_string(modified.tv_sec) + ":" + std::to_string(modified.tv_nsec)
        + ":" + std::to_string(changed.tv_sec) + ":" + std::to_string(changed.tv_nsec);
}
inline bool Leaf(const std::string &name) {
    if (name.empty() || name.size() > 160 || name == "." || name == "..") return false;
    for (auto c : name) if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) return false;
    return true;
}
inline int OpenDirectory(const std::string &path, const std::string &expected = {}) {
    if (path.empty() || path[0] != '/' || path.find('\0') != std::string::npos) return -1;
#if defined(__APPLE__)
    constexpr auto traverse = O_SEARCH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
#else
    constexpr auto traverse = O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
#endif
    Fd current(::open("/", traverse));
    if (current.value < 0) return -1;
    size_t offset = 1;
    while (offset < path.size()) {
        auto end = path.find('/', offset); if (end == std::string::npos) end = path.size();
        auto part = path.substr(offset, end - offset);
        if (part.empty() || part == "." || part == "..") return -1;
        auto flags = end == path.size() ? O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC : traverse;
        auto next = ::openat(current.value, part.c_str(), flags);
        if (next < 0) return -1;
        if (!current.close()) { ::close(next); return -1; }
        current.value = next; offset = end + 1;
    }
    struct stat value{};
    if (::fstat(current.value, &value) != 0 || !S_ISDIR(value.st_mode) || value.st_uid != getuid()
        || (!expected.empty() && Identity(value) != expected)) return -1;
    auto result = current.value; current.value = -1; return result;
}
inline bool RenameNew(int directory, const std::string &from, const std::string &to) {
#if defined(__APPLE__)
    return ::renameatx_np(directory,from.c_str(),directory,to.c_str(),RENAME_EXCL) == 0;
#elif defined(SYS_renameat2)
    return ::syscall(SYS_renameat2,directory,from.c_str(),directory,to.c_str(),1) == 0;
#else
    return false;
#endif
}
inline bool Regular(const struct stat &value) {
    return S_ISREG(value.st_mode) && value.st_uid == getuid() && value.st_nlink == 1 && value.st_size >= 0;
}
}
