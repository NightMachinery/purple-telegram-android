// Host-test interposer (macOS, DYLD_INSERT_LIBRARIES; run_modern_sync.py builds and injects it): refuses every open,
// openat and opendir of the root directory "/" with EACCES, as Android SELinux refuses an app any read of the rootfs
// root (native check A3, where the durable file layer walked from "/"). Each refusal is appended to ROOTDENY_LOG.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define INTERPOSE(replacement, replacee) \
    __attribute__((used)) static struct { const void *replacement; const void *replacee; } interpose_##replacee \
    __attribute__((section("__DATA,__interpose"))) = { (const void *)(unsigned long)&replacement, (const void *)(unsigned long)&replacee };
static void note(const char *op) {
    const char *log = getenv("ROOTDENY_LOG");
    if (!log) return;
    int fd = open(log, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char line[64];
    int n = snprintf(line, sizeof line, "%s /\n", op);
    if (n > 0 && n < (int)sizeof line) (void)write(fd, line, (size_t)n);
    close(fd);
}
static int root(const char *path) { return path && strcmp(path, "/") == 0; }
static int deny_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap); }
    if (root(path)) { note("open"); errno = EACCES; return -1; }
    return open(path, flags, mode);
}
static int deny_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap); }
    if (root(path)) { note("openat"); errno = EACCES; return -1; }
    return openat(dirfd, path, flags, mode);
}
static DIR *deny_opendir(const char *path) {
    if (root(path)) { note("opendir"); errno = EACCES; return NULL; }
    return opendir(path);
}
INTERPOSE(deny_open, open)
INTERPOSE(deny_openat, openat)
INTERPOSE(deny_opendir, opendir)
