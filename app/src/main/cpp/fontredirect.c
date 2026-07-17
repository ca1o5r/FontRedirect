// FontRedirect native Flutter AAsset hook.
// Patches libflutter.so's GOT entries for AAsset* imports so bundled font
// assets can be redirected to replacement font files at runtime.

#include <android/asset_manager.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "FontRedirectNative"

// Portable ELF relocation helpers. Android NDK exposes ElfW() but not ELFW().
#ifdef __LP64__
#define REL_TYPE(r_info) ELF64_R_TYPE(r_info)
#define REL_SYM(r_info)  ELF64_R_SYM(r_info)
#else
#define REL_TYPE(r_info) ELF32_R_TYPE(r_info)
#define REL_SYM(r_info)  ELF32_R_SYM(r_info)
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAGIC_FAKE_ASSET 0x46524454u  // "FRT"
#define MAX_SYMBOLS 16

// Replacement font paths configured from Java.
static char s_latin_path[512];
static char s_cjk_path[512];
static volatile int s_hooked = 0;
static pthread_mutex_t s_init_lock = PTHREAD_MUTEX_INITIALIZER;

// Original function pointers saved before GOT patching.
static AAsset *(*orig_AAssetManager_open)(AAssetManager *, const char *, int);
static const void *(*orig_AAsset_getBuffer)(AAsset *);
static off_t (*orig_AAsset_getLength)(AAsset *);
static off64_t (*orig_AAsset_getLength64)(AAsset *);
static int (*orig_AAsset_read)(AAsset *, void *, size_t);
static void (*orig_AAsset_close)(AAsset *);

// Fake asset handle used to replace bundled font assets.
typedef struct {
    uint32_t magic;
    int fd;
    size_t length;
    off_t offset;
    void *buffer;
} fake_asset_t;

// -------------------------------------------------------------------------
// Helper: page alignment and mprotect.
// -------------------------------------------------------------------------
static uintptr_t page_size(void) {
    static uintptr_t ps = 0;
    if (ps == 0) ps = (uintptr_t) sysconf(_SC_PAGESIZE);
    return ps;
}

static int make_writable(void *addr) {
    uintptr_t ps = page_size();
    uintptr_t start = ((uintptr_t) addr) & ~(ps - 1);
    int r = mprotect((void *) start, ps, PROT_READ | PROT_WRITE);
    if (r != 0) {
        LOGE("mprotect failed for %p: %d (%s)", addr, errno, strerror(errno));
    }
    return r;
}

// -------------------------------------------------------------------------
// Font asset detection and replacement path selection.
// -------------------------------------------------------------------------
static const char *base_name(const char *path) {
    if (!path) return "";
    const char *p = strrchr(path, '/');
    return p ? (p + 1) : path;
}

static int is_font_asset(const char *filename) {
    if (!filename) return 0;
    const char *name = base_name(filename);
    return strstr(name, ".ttf") != NULL
        || strstr(name, ".otf") != NULL
        || strstr(name, ".ttc") != NULL
        || strstr(name, ".otc") != NULL;
}

static int looks_like_cjk(const char *filename) {
    if (!filename) return 0;
    const char *name = base_name(filename);
    static const char *hints[] = {
        "cjk", "sc", "tc", "jp", "kr", "hans", "hant", "japanese", "korean",
        "chinese", "notosanssc", "notosanstc", "notosansjp", "notosanskr",
        NULL
    };
    for (const char **h = hints; *h; ++h) {
        if (strcasestr(name, *h) != NULL) return 1;
    }
    return 0;
}

static const char *choose_replacement(const char *filename) {
    if (looks_like_cjk(filename)) return s_cjk_path;
    return s_latin_path;
}

// -------------------------------------------------------------------------
// Fake AAsset implementation.
// -------------------------------------------------------------------------
static AAsset *create_fake_asset(const char *filename) {
    const char *repl = choose_replacement(filename);
    if (repl[0] == '\0') return NULL;

    int fd = open(repl, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGW("cannot open replacement %s for %s: %d", repl, filename, errno);
        return NULL;
    }

    off_t len = lseek(fd, 0, SEEK_END);
    if (len <= 0) {
        close(fd);
        return NULL;
    }
    lseek(fd, 0, SEEK_SET);

    void *buf = mmap(NULL, (size_t) len, PROT_READ, MAP_PRIVATE, fd, 0);
    if (buf == MAP_FAILED) {
        close(fd);
        return NULL;
    }

    fake_asset_t *fake = (fake_asset_t *) calloc(1, sizeof(fake_asset_t));
    if (!fake) {
        munmap(buf, (size_t) len);
        close(fd);
        return NULL;
    }
    fake->magic = MAGIC_FAKE_ASSET;
    fake->fd = fd;
    fake->length = (size_t) len;
    fake->offset = 0;
    fake->buffer = buf;

    LOGI("replaced asset %s -> %s (%zu bytes)", filename, repl, fake->length);
    return (AAsset *) fake;
}

static inline int is_fake(AAsset *asset) {
    if (!asset) return 0;
    fake_asset_t *fake = (fake_asset_t *) asset;
    return fake->magic == MAGIC_FAKE_ASSET;
}

// Replacement AAsset* functions exposed to libflutter.so.
static AAsset *my_AAssetManager_open(AAssetManager *mgr, const char *filename, int mode) {
    if (!is_font_asset(filename)) {
        return orig_AAssetManager_open(mgr, filename, mode);
    }
    AAsset *fake = create_fake_asset(filename);
    if (fake) return fake;
    LOGW("falling back to original asset %s", filename);
    return orig_AAssetManager_open(mgr, filename, mode);
}

static const void *my_AAsset_getBuffer(AAsset *asset) {
    if (is_fake(asset)) {
        return ((fake_asset_t *) asset)->buffer;
    }
    return orig_AAsset_getBuffer(asset);
}

static off_t my_AAsset_getLength(AAsset *asset) {
    if (is_fake(asset)) {
        return (off_t) ((fake_asset_t *) asset)->length;
    }
    return orig_AAsset_getLength(asset);
}

static off64_t my_AAsset_getLength64(AAsset *asset) {
    if (is_fake(asset)) {
        return (off64_t) ((fake_asset_t *) asset)->length;
    }
    return orig_AAsset_getLength64(asset);
}

static int my_AAsset_read(AAsset *asset, void *buf, size_t count) {
    if (is_fake(asset)) {
        fake_asset_t *fake = (fake_asset_t *) asset;
        size_t remain = fake->length - (size_t) fake->offset;
        size_t n = count < remain ? count : remain;
        if (n > 0) {
            memcpy(buf, (char *) fake->buffer + fake->offset, n);
            fake->offset += (off_t) n;
        }
        return (int) n;
    }
    return orig_AAsset_read(asset, buf, count);
}

static void my_AAsset_close(AAsset *asset) {
    if (is_fake(asset)) {
        fake_asset_t *fake = (fake_asset_t *) asset;
        if (fake->buffer && fake->buffer != MAP_FAILED) {
            munmap(fake->buffer, fake->length);
        }
        if (fake->fd >= 0) close(fake->fd);
        free(fake);
        return;
    }
    orig_AAsset_close(asset);
}

// -------------------------------------------------------------------------
// ELF parsing to locate GOT slots in libflutter.so.
// -------------------------------------------------------------------------
typedef struct {
    const char *lib_name;
    const char *names[MAX_SYMBOLS];
    void *slots[MAX_SYMBOLS];
    void *originals[MAX_SYMBOLS];
    int count;
    int found;
} got_find_ctx;

static int is_target_relocation(int type) {
#if defined(__aarch64__)
    return type == R_AARCH64_GLOB_DAT || type == R_AARCH64_JUMP_SLOT;
#elif defined(__arm__)
    return type == R_ARM_GLOB_DAT || type == R_ARM_JUMP_SLOT;
#elif defined(__i386__)
    return type == R_386_GLOB_DAT || type == R_386_JMP_SLOT;
#elif defined(__x86_64__)
    return type == R_X86_64_GLOB_DAT || type == R_X86_64_JUMP_SLOT;
#else
    return 0;
#endif
}

static void scan_relocations(ElfW(Addr) base,
                             ElfW(Sym) *symtab,
                             const char *strtab,
                             void *rel,
                             size_t rel_count,
                             size_t rel_ent,
                             int use_rela,
                             got_find_ctx *ctx) {
    for (size_t i = 0; i < rel_count; ++i) {
        ElfW(Addr) r_offset;
        ElfW(Xword) r_info;
        ElfW(Sxword) r_addend = 0;

        if (use_rela) {
            ElfW(Rela) *rela = (ElfW(Rela) *) rel + i;
            r_offset = rela->r_offset;
            r_info = rela->r_info;
            r_addend = rela->r_addend;
        } else {
            ElfW(Rel) *r = (ElfW(Rel) *) rel + i;
            r_offset = r->r_offset;
            r_info = r->r_info;
        }

        int type = (int) REL_TYPE(r_info);
        if (!is_target_relocation(type)) continue;

        size_t sym_idx = REL_SYM(r_info);
        // REL_SYM works for both ELF32 and ELF64 on the fields we use.
        if (sym_idx == 0) continue;
        const char *sym_name = strtab + symtab[sym_idx].st_name;

        for (int j = 0; j < ctx->count; ++j) {
            if (ctx->slots[j] != NULL) continue;
            if (strcmp(sym_name, ctx->names[j]) == 0) {
                void *slot = (void *) (base + r_offset);
                ctx->slots[j] = slot;
                ctx->originals[j] = *(void **) slot;
                ctx->found++;
                LOGI("found GOT slot for %s at %p -> %p", sym_name, slot, ctx->originals[j]);
                break;
            }
        }
    }
}

static int dl_iterate_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    got_find_ctx *ctx = (got_find_ctx *) data;
    if (!info->dlpi_name) return 0;

    const char *name = info->dlpi_name;
    const char *bn = strrchr(name, '/');
    bn = bn ? bn + 1 : name;
    if (strstr(bn, ctx->lib_name) == NULL) return 0;

    LOGI("scanning module %s base=0x%llx", name, (unsigned long long) info->dlpi_addr);

    ElfW(Addr) base = info->dlpi_addr;
    ElfW(Sym) *symtab = NULL;
    const char *strtab = NULL;
    void *jmprel = NULL;
    size_t pltrelsz = 0;
    int pltrel = 0;  // DT_REL or DT_RELA
    void *rela = NULL;
    size_t relasz = 0;
    size_t relaent = sizeof(ElfW(Rela));
    void *rel = NULL;
    size_t relsz = 0;
    size_t relent = sizeof(ElfW(Rel));

    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type != PT_DYNAMIC) continue;
        ElfW(Dyn) *dyn = (ElfW(Dyn) *) (base + info->dlpi_phdr[i].p_vaddr);
        for (ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
            switch (d->d_tag) {
                case DT_SYMTAB:  symtab = (ElfW(Sym) *) (base + d->d_un.d_ptr); break;
                case DT_STRTAB:  strtab = (const char *) (base + d->d_un.d_ptr); break;
                case DT_JMPREL:  jmprel = (void *) (base + d->d_un.d_ptr); break;
                case DT_PLTRELSZ: pltrelsz = d->d_un.d_val; break;
                case DT_PLTREL:  pltrel = d->d_un.d_val; break;
                case DT_RELA:    rela = (void *) (base + d->d_un.d_ptr); break;
                case DT_RELASZ:  relasz = d->d_un.d_val; break;
                case DT_RELAENT: relaent = d->d_un.d_val; break;
                case DT_REL:     rel = (void *) (base + d->d_un.d_ptr); break;
                case DT_RELSZ:   relsz = d->d_un.d_val; break;
                case DT_RELENT:  relent = d->d_un.d_val; break;
                default: break;
            }
        }
        break;
    }

    if (!symtab || !strtab) {
        LOGW("missing dynamic symbol/string table for %s", name);
        return 0;
    }

    if (jmprel && pltrelsz > 0) {
        size_t count = pltrelsz / (pltrel == DT_RELA ? relaent : relent);
        scan_relocations(base, symtab, strtab, jmprel, count,
                         pltrel == DT_RELA ? relaent : relent,
                         pltrel == DT_RELA, ctx);
    }
    if (rela && relasz > 0) {
        size_t count = relasz / relaent;
        scan_relocations(base, symtab, strtab, rela, count, relaent, 1, ctx);
    }
    if (rel && relsz > 0) {
        size_t count = relsz / relent;
        scan_relocations(base, symtab, strtab, rel, count, relent, 0, ctx);
    }

    return ctx->found == ctx->count ? 1 : 0;
}

static int find_got_slots(const char *lib_name, got_find_ctx *ctx) {
    ctx->lib_name = lib_name;
    ctx->found = 0;
    for (int i = 0; i < ctx->count; ++i) {
        ctx->slots[i] = NULL;
        ctx->originals[i] = NULL;
    }
    dl_iterate_phdr(dl_iterate_callback, ctx);
    return ctx->found;
}

static int patch_got(got_find_ctx *ctx, void *replacements[]) {
    int patched = 0;
    for (int i = 0; i < ctx->count; ++i) {
        if (ctx->slots[i] == NULL) {
            LOGI("GOT slot not present for %s (optional)", ctx->names[i]);
            continue;
        }
        if (make_writable(ctx->slots[i]) != 0) {
            LOGW("mprotect failed for %s", ctx->names[i]);
            continue;
        }
        *(void **) ctx->slots[i] = replacements[i];
        patched++;
        LOGI("patched %s -> %p", ctx->names[i], replacements[i]);
    }
    return patched;
}

// -------------------------------------------------------------------------
// Hook installation.
// -------------------------------------------------------------------------
static int do_hook_internal(void) {
    if (__atomic_load_n(&s_hooked, __ATOMIC_ACQUIRE)) return 1;

    pthread_mutex_lock(&s_init_lock);
    if (__atomic_load_n(&s_hooked, __ATOMIC_RELAXED)) {
        pthread_mutex_unlock(&s_init_lock);
        return 1;
    }

    got_find_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    const char *names[] = {
        "AAssetManager_open",
        "AAsset_getBuffer",
        "AAsset_getLength",
        "AAsset_getLength64",
        "AAsset_read",
        "AAsset_close",
    };
    void *replacements[] = {
        my_AAssetManager_open,
        my_AAsset_getBuffer,
        my_AAsset_getLength,
        my_AAsset_getLength64,
        my_AAsset_read,
        my_AAsset_close,
    };
    ctx.count = sizeof(names) / sizeof(names[0]);
    for (int i = 0; i < ctx.count; ++i) ctx.names[i] = names[i];

    int found = find_got_slots("libflutter.so", &ctx);
    if (found == 0) {
        LOGW("libflutter.so not loaded or no AAsset symbols found, deferring");
        pthread_mutex_unlock(&s_init_lock);
        return 0;
    }

    int patched = patch_got(&ctx, replacements);
    if (patched == found) {
        // Save originals for the slots we actually patched.
        orig_AAssetManager_open = ctx.originals[0];
        orig_AAsset_getBuffer = ctx.originals[1];
        orig_AAsset_getLength = ctx.originals[2];
        orig_AAsset_getLength64 = ctx.originals[3];
        orig_AAsset_read = ctx.originals[4];
        orig_AAsset_close = ctx.originals[5];
        __atomic_store_n(&s_hooked, 1, __ATOMIC_RELEASE);
        LOGI("Flutter AAsset GOT hook applied (%d/%d slots)", patched, ctx.count);
        pthread_mutex_unlock(&s_init_lock);
        return 1;
    }

    LOGW("Flutter hook incomplete: patched %d/%d found %d", patched, ctx.count, found);
    pthread_mutex_unlock(&s_init_lock);
    return 0;
}

static void *poll_hook_thread(void *arg) {
    (void) arg;
    for (int i = 0; i < 50; ++i) {
        if (do_hook_internal()) {
            LOGI("Flutter hook applied from poll thread");
            return NULL;
        }
        usleep(100 * 1000);  // 100 ms
    }
    LOGW("Flutter hook poll thread timed out");
    return NULL;
}

static void start_poll_if_needed(void) {
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&tid, &attr, poll_hook_thread, NULL);
    pthread_attr_destroy(&attr);
}

// -------------------------------------------------------------------------
// JNI entry point.
// -------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_org_c0fle4_FontRedirect_hook_FontRedirectNative_hookFlutter(
        JNIEnv *env,
        jclass clazz,
        jstring latinPath,
        jstring cjkPath) {
    (void) clazz;

    const char *latin = (*env)->GetStringUTFChars(env, latinPath, NULL);
    const char *cjk = (*env)->GetStringUTFChars(env, cjkPath, NULL);
    if (!latin || !cjk) {
        if (latin) (*env)->ReleaseStringUTFChars(env, latinPath, latin);
        if (cjk) (*env)->ReleaseStringUTFChars(env, cjkPath, cjk);
        LOGE("null font path passed to hookFlutter");
        return JNI_FALSE;
    }
    strncpy(s_latin_path, latin, sizeof(s_latin_path) - 1);
    strncpy(s_cjk_path, cjk, sizeof(s_cjk_path) - 1);
    (*env)->ReleaseStringUTFChars(env, latinPath, latin);
    (*env)->ReleaseStringUTFChars(env, cjkPath, cjk);

    LOGI("hookFlutter called latin=%s cjk=%s", s_latin_path, s_cjk_path);

    if (do_hook_internal()) {
        return JNI_TRUE;
    }
    // libflutter.so may not be loaded yet; start a background poll.
    start_poll_if_needed();
    return JNI_FALSE;
}
