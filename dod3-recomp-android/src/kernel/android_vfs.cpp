/*
 * Android Direct-Archive Virtual Filesystem (ISO + PKG + ZIP).
 *
 * Mounts Drakengard 3's decrypted .iso, retail Update 1.01 .pkg, retail DLC
 * .pkgs, debug/mod .pkgs (e.g. Spanish translation), and MOVIES.zip directly
 * from /sdcard/Drakengard3/ without unpacking them to storage.
 *
 * Exposes files through ps3recomp's ps3_vfs_virtual_* hooks using Android
 * Bionic funopen() + lock-free pread64() + on-the-fly PKG decryption.
 */
#ifdef __ANDROID__

#include "kernel/android_vfs.h"
#include "install/iso.h"
#include "install/pkg.h"
#include "install/zip.h"
#include "install/crypto.h"
#include "install/self.h"
#include "install/keys.h"
#include "ps3recomp_pkg_key.h"
#include "util.h"

#include <android/log.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#define VFS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DOD3VFS", __VA_ARGS__)
#define VFS_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "DOD3VFS", __VA_ARGS__)
#define VFS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DOD3VFS", __VA_ARGS__)

extern "C" {
extern FILE* (*ps3_vfs_virtual_open)(const char* host_path);
extern int (*ps3_vfs_virtual_stat)(const char* host_path, uint64_t* size_out, int* is_dir_out);
extern void* (*ps3_vfs_virtual_opendir)(const char* host_path);
extern int (*ps3_vfs_virtual_readdir)(void* vdir, char* name_out, size_t cap, int* is_dir_out);
extern void (*ps3_vfs_virtual_closedir)(void* vdir);
}

namespace fs = std::filesystem;

namespace {

enum class SourceKind {
    Iso,
    PkgRetail,
    PkgDebug,
    ZipStored,
    ZipDeflate,
    Memory,
};

struct ArchiveHandle {
    SourceKind kind = SourceKind::Iso;
    int fd = -1;
    std::string path;
    /* PKG crypto state (constant after init -> thread-safe) */
    uint64_t data_offset = 0;
    dod3setup::Aes aes;
    uint8_t riv[16] = {};
    uint8_t qa[16] = {};

    ~ArchiveHandle()
    {
        if (fd >= 0) ::close(fd);
    }
};

struct VirtualEntry {
    ArchiveHandle* archive = nullptr;   /* null for Memory */
    SourceKind kind = SourceKind::Iso;
    uint64_t file_offset = 0;           /* ISO/ZIP: byte offset in file; PKG: offset inside data area */
    uint64_t size = 0;                  /* uncompressed size */
    uint64_t csize = 0;                 /* compressed size (ZipDeflate) */
    int priority = 0;                   /* higher overrides lower */
    std::string mem_data;               /* for Memory entries */
};

struct DirEntryInfo {
    std::string name;   /* original case for readdir */
    bool is_dir = false;
};

struct VfsState {
    bool initialized = false;
    bool spanish_active = false;
    std::string base_dir;
    std::string root_path;              /* e.g. /sdcard/Drakengard3/game/disc */
    std::string root_upper;             /* /SDCARD/DRAKENGARD3/GAME/DISC */
    std::string eboot_path;             /* /sdcard/Drakengard3/cache/EBOOT_101.ELF */

    std::vector<std::unique_ptr<ArchiveHandle>> archives;
    /* Key: uppercase canonical path under root, e.g. "/PS3_GAME/USRDIR/..." */
    std::unordered_map<std::string, VirtualEntry> files;
    /* Key: uppercase canonical dir path ("" for root, "/PS3_GAME", etc.) */
    std::unordered_map<std::string, std::unordered_map<std::string, DirEntryInfo>> dirs;
    /* Immutable pre-sorted directory listings built once at end of init */
    std::unordered_map<std::string, std::vector<DirEntryInfo>> dirs_sorted;
};

VfsState g_vfs;

std::string to_upper(std::string s)
{
    for (char& c : s) c = (char)toupper((unsigned char)c);
    return s;
}

std::string normalize_slash_upper(const char* p)
{
    std::string out;
    if (!p) return out;
    out.reserve(strlen(p));
    bool prev_slash = false;
    for (const char* s = p; *s; ++s) {
        char c = (*s == '\\') ? '/' : (char)toupper((unsigned char)*s);
        if (c == '/') {
            if (prev_slash) continue;
            prev_slash = true;
        } else {
            prev_slash = false;
        }
        out.push_back(c);
    }
    while (out.size() > 1 && out.back() == '/') out.pop_back();
    return out;
}

/* Convert a host_path (e.g. /sdcard/Drakengard3/game/disc/PS3_GAME/...) to
 * a canonical virtual key (e.g. /PS3_GAME/...). Returns false if outside root. */
bool host_to_vkey(const char* host_path, std::string* key_out)
{
    if (!g_vfs.initialized || !host_path || !*host_path) return false;
    std::string norm = normalize_slash_upper(host_path);
    if (norm.size() < g_vfs.root_upper.size()) return false;
    if (norm.compare(0, g_vfs.root_upper.size(), g_vfs.root_upper) != 0) return false;
    if (norm.size() == g_vfs.root_upper.size()) {
        *key_out = "";
        return true;
    }
    if (norm[g_vfs.root_upper.size()] != '/') return false;
    *key_out = norm.substr(g_vfs.root_upper.size());
    return true;
}

/* Register all parent directories for a virtual path (original case preserved for readdir). */
void register_dir_chain(const std::string& orig_vpath)
{
    /* orig_vpath starts with '/', e.g. "/PS3_GAME/USRDIR/SQEX03GAME/CORE.XXX" */
    size_t pos = 1;
    std::string parent_upper = "";
    while (pos < orig_vpath.size()) {
        size_t slash = orig_vpath.find('/', pos);
        bool is_leaf = (slash == std::string::npos);
        std::string seg = is_leaf ? orig_vpath.substr(pos) : orig_vpath.substr(pos, slash - pos);
        if (!seg.empty()) {
            std::string seg_upper = to_upper(seg);
            auto& children = g_vfs.dirs[parent_upper];
            auto it = children.find(seg_upper);
            if (it == children.end()) {
                children[seg_upper] = DirEntryInfo{ seg, !is_leaf };
            } else if (!is_leaf) {
                it->second.is_dir = true;
            }
            if (!is_leaf) {
                parent_upper += "/" + seg_upper;
                g_vfs.dirs[parent_upper];   /* ensure dir exists even if empty */
            }
        }
        if (is_leaf) break;
        pos = slash + 1;
    }
}

void mount_entry(const std::string& orig_vpath, const VirtualEntry& e)
{
    std::string key = normalize_slash_upper(orig_vpath.c_str());
    if (key.empty() || key[0] != '/') return;
    auto it = g_vfs.files.find(key);
    if (it != g_vfs.files.end() && it->second.priority > e.priority) return;
    g_vfs.files[key] = e;
    register_dir_chain(orig_vpath);
}

/* Thread-safe pread64 loop. */
ssize_t pread_all(int fd, uint8_t* buf, size_t n, uint64_t off)
{
    size_t done = 0;
    while (done < n) {
        ssize_t r = ::pread64(fd, buf + done, n - done, (off64_t)(off + done));
        if (r <= 0) break;
        done += (size_t)r;
    }
    return (ssize_t)done;
}

/* Decrypt bytes [data_off, data_off + n) of a PKG's data area (same algorithm as src/install/pkg.cpp). */
void pkg_decrypt_range(const ArchiveHandle& a, uint64_t data_off, uint8_t* buf, size_t n)
{
    if (a.kind == SourceKind::PkgRetail) {
        dod3setup::aes_ctr(a.aes, a.riv, data_off, buf, n);
        return;
    }
    /* Debug PKG: SHA-1 keystream */
    uint8_t in[64] = {}, h[20];
    memcpy(in, a.qa, 8);
    memcpy(in + 8, a.qa, 8);
    memcpy(in + 16, a.qa + 8, 8);
    memcpy(in + 24, a.qa + 8, 8);
    for (size_t i = 0; i < n;) {
        const uint64_t blk = (data_off + i) / 16;
        const uint64_t at = (data_off + i) % 16;
        for (int b = 0; b < 8; b++) in[56 + b] = (uint8_t)(blk >> (56 - 8 * b));
        dod3setup::Sha1 s;
        s.update(in, sizeof in);
        s.digest(h);
        const size_t take = std::min<size_t>(16 - (size_t)at, n - i);
        for (size_t j = 0; j < take; j++) buf[i + j] ^= h[at + j];
        i += take;
    }
}

/* ── funopen() FILE* stream implementation ─────────────────────────────── */

struct VfsStream {
    const VirtualEntry* entry = nullptr;
    uint64_t pos = 0;
    std::vector<uint8_t> inflated;   /* populated on open for ZipDeflate */
};

int vfs_readfn(void* cookie, char* buf, int n)
{
    auto* st = static_cast<VfsStream*>(cookie);
    if (!st || !st->entry || n <= 0) return 0;
    const VirtualEntry& e = *st->entry;
    if (st->pos >= e.size) return 0;
    const uint64_t rem = e.size - st->pos;
    const size_t want = (size_t)std::min<uint64_t>((uint64_t)n, rem);

    if (e.kind == SourceKind::Memory) {
        memcpy(buf, e.mem_data.data() + st->pos, want);
        st->pos += want;
        return (int)want;
    }
    if (e.kind == SourceKind::ZipDeflate) {
        memcpy(buf, st->inflated.data() + st->pos, want);
        st->pos += want;
        return (int)want;
    }

    const ArchiveHandle* a = e.archive;
    if (!a || a->fd < 0) return -1;

    uint64_t disk_off = 0;
    if (e.kind == SourceKind::PkgRetail || e.kind == SourceKind::PkgDebug) {
        disk_off = a->data_offset + e.file_offset + st->pos;
    } else {
        disk_off = e.file_offset + st->pos;
    }

    ssize_t got = pread_all(a->fd, reinterpret_cast<uint8_t*>(buf), want, disk_off);
    if (got <= 0) return (int)got;

    if (e.kind == SourceKind::PkgRetail || e.kind == SourceKind::PkgDebug) {
        pkg_decrypt_range(*a, e.file_offset + st->pos, reinterpret_cast<uint8_t*>(buf), (size_t)got);
    }

    st->pos += (uint64_t)got;
    return (int)got;
}

fpos_t vfs_seekfn(void* cookie, fpos_t offset, int whence)
{
    auto* st = static_cast<VfsStream*>(cookie);
    if (!st || !st->entry) return -1;
    int64_t base = 0;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = (int64_t)st->pos;
    else if (whence == SEEK_END) base = (int64_t)st->entry->size;
    else return -1;

    int64_t next = base + (int64_t)offset;
    if (next < 0) return -1;
    st->pos = (uint64_t)next;
    return (fpos_t)st->pos;
}

int vfs_closefn(void* cookie)
{
    delete static_cast<VfsStream*>(cookie);
    return 0;
}

/* ── ps3recomp virtual VFS callbacks ───────────────────────────────────── */

FILE* vfs_hook_open(const char* host_path)
{
    std::string key;
    if (!host_to_vkey(host_path, &key)) return nullptr;
    auto it = g_vfs.files.find(key);
    if (it == g_vfs.files.end() && key.rfind("/USRDIR/", 0) == 0) {
        it = g_vfs.files.find("/PS3_GAME" + key);
    }
    if (it == g_vfs.files.end()) return nullptr;

    const VirtualEntry& e = it->second;
    auto* st = new VfsStream();
    st->entry = &e;
    st->pos = 0;

    if (e.kind == SourceKind::ZipDeflate) {
        if (!e.archive || e.archive->fd < 0) {
            delete st;
            return nullptr;
        }
        std::vector<uint8_t> comp((size_t)e.csize);
        if (pread_all(e.archive->fd, comp.data(), comp.size(), e.file_offset) != (ssize_t)comp.size()) {
            delete st;
            return nullptr;
        }
        st->inflated.resize((size_t)e.size);
        z_stream zs{};
        zs.next_in = comp.data();
        zs.avail_in = (uInt)comp.size();
        zs.next_out = st->inflated.data();
        zs.avail_out = (uInt)st->inflated.size();
        if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) {
            delete st;
            return nullptr;
        }
        int zr = inflate(&zs, Z_FINISH);
        inflateEnd(&zs);
        if (zr != Z_STREAM_END) {
            delete st;
            return nullptr;
        }
    }

    FILE* f = funopen(st, vfs_readfn, nullptr, vfs_seekfn, vfs_closefn);
    if (!f) {
        delete st;
        return nullptr;
    }
    return f;
}

int vfs_hook_stat(const char* host_path, uint64_t* size_out, int* is_dir_out)
{
    std::string key;
    if (!host_to_vkey(host_path, &key)) return 0;

    auto fit = g_vfs.files.find(key);
    if (fit == g_vfs.files.end() && key.rfind("/USRDIR/", 0) == 0) {
        fit = g_vfs.files.find("/PS3_GAME" + key);
    }
    if (fit != g_vfs.files.end()) {
        if (size_out) *size_out = fit->second.size;
        if (is_dir_out) *is_dir_out = 0;
        return 1;
    }
    auto dit = g_vfs.dirs.find(key);
    if (dit == g_vfs.dirs.end() && key.rfind("/USRDIR/", 0) == 0) {
        dit = g_vfs.dirs.find("/PS3_GAME" + key);
    }
    if (dit != g_vfs.dirs.end()) {
        if (size_out) *size_out = 0;
        if (is_dir_out) *is_dir_out = 1;
        return 1;
    }
    return 0;
}

struct VfsDirIter {
    const std::vector<DirEntryInfo>* items = nullptr;
    size_t idx = 0;
};

void* vfs_hook_opendir(const char* host_path)
{
    std::string key;
    if (!host_to_vkey(host_path, &key)) return nullptr;
    auto dit = g_vfs.dirs_sorted.find(key);
    if (dit == g_vfs.dirs_sorted.end()) return nullptr;

    auto* iter = new VfsDirIter();
    iter->items = &dit->second;
    iter->idx = 0;
    return iter;
}

int vfs_hook_readdir(void* vdir, char* name_out, size_t cap, int* is_dir_out)
{
    auto* iter = static_cast<VfsDirIter*>(vdir);
    if (!iter || !iter->items || iter->idx >= iter->items->size()) return 0;
    const DirEntryInfo& d = (*iter->items)[iter->idx++];
    snprintf(name_out, cap, "%s", d.name.c_str());
    if (is_dir_out) *is_dir_out = d.is_dir ? 1 : 0;
    return 1;
}

void vfs_hook_closedir(void* vdir)
{
    delete static_cast<VfsDirIter*>(vdir);
}

/* ── Archive mounting helpers ──────────────────────────────────────────── */

bool write_file_atomic(const fs::path& dst, const void* data, size_t n)
{
    std::error_code ec;
    fs::create_directories(dst.parent_path(), ec);
    fs::path tmp = dst;
    tmp += ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, n, f) == n;
    fclose(f);
    if (!ok) {
        fs::remove(tmp, ec);
        return false;
    }
    fs::rename(tmp, dst, ec);
    return !ec;
}

bool mount_iso(const fs::path& iso_path, const fs::path& cache_dir)
{
    dod3setup::Disc disc;
    std::string err;
    if (!dod3setup::open_disc(iso_path, &disc, &err)) {
        VFS_LOGW("Skipping ISO %s: %s", iso_path.c_str(), err.c_str());
        return false;
    }

    int fd = ::open(iso_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        VFS_LOGE("Cannot open ISO fd: %s", iso_path.c_str());
        return false;
    }

    auto ah = std::make_unique<ArchiveHandle>();
    ah->kind = SourceKind::Iso;
    ah->fd = fd;
    ah->path = iso_path.string();
    ArchiveHandle* aptr = ah.get();
    g_vfs.archives.push_back(std::move(ah));

    size_t count = 0;
    for (const dod3setup::DiscFile& f : disc.files) {
        VirtualEntry ve;
        ve.archive = aptr;
        ve.kind = SourceKind::Iso;
        ve.file_offset = f.offset;
        ve.size = f.size;
        ve.priority = 10;

        std::string vpath = "/" + f.path;
        mount_entry(vpath, ve);
        count++;

        /* Game-data install aliases (same as install.cpp gamedata_layout) */
        std::string u = to_upper(f.path);
        static const std::string kCooked = "PS3_GAME/USRDIR/SQEX03GAME/COOKEDPS3/";
        static const std::string kSound = "PS3_GAME/USRDIR/SQEX03GAME/COOKEDSOUND/";
        if (u.rfind(kCooked, 0) == 0) {
            mount_entry("/game/BLES00000DATA/USRDIR/FIOS-UNREALENGINE3/SQEX03GAME/COOKEDPS3/" +
                        f.path.substr(kCooked.size()), ve);
        } else if (u.rfind(kSound, 0) == 0) {
            mount_entry("/game/BLES00000DATA/USRDIR/FIOS-UNREALENGINE3/SQEX03GAME/COOKEDSOUND/" +
                        f.path.substr(kSound.size()), ve);
        } else if (u == "PS3_GAME/ICON0.PNG") {
            mount_entry("/game/BLES00000DATA/ICON0.PNG", ve);
        } else if (u == "PS3_GAME/PIC1.PNG") {
            /* Cache PIC1.PNG once for the Android Launcher backdrop (~500 KB) */
            fs::path pic_cache = cache_dir / "PIC1.PNG";
            std::error_code ec;
            if (!fs::exists(pic_cache, ec) && f.size > 0 && f.size < 8 * 1024 * 1024) {
                std::vector<uint8_t> buf((size_t)f.size);
                if (pread_all(fd, buf.data(), buf.size(), f.offset) == (ssize_t)buf.size()) {
                    write_file_atomic(pic_cache, buf.data(), buf.size());
                    VFS_LOGI("Cached launcher backdrop: %s (%zu bytes)", pic_cache.c_str(), buf.size());
                }
            }
        }
    }

    VFS_LOGI("Mounted ISO: %s (%zu files)", iso_path.filename().c_str(), count);
    return true;
}

bool is_mod_disabled(const fs::path& cache_dir, const fs::path& mod_path)
{
    std::error_code ec;
    const std::string fname = mod_path.filename().string();
    if (fs::exists(cache_dir / "mods_off" / (fname + ".off"), ec)) return true;
    const std::string fn_u = to_upper(fname);
    if ((fn_u.find("ES") != std::string::npos || fn_u == "MOVIES.ZIP") &&
        fs::exists(cache_dir / "ES-DATA.off", ec)) {
        return true;
    }
    return false;
}

bool mount_pkg(const fs::path& pkg_path, const fs::path& cache_dir, bool allow_mods)
{
    dod3setup::Pkg pkg;
    std::string err;
    if (!dod3setup::pkg_open(pkg_path, &pkg, &err)) {
        VFS_LOGW("Skipping PKG %s: %s", pkg_path.c_str(), err.c_str());
        return false;
    }

    if (pkg.debug && (!allow_mods || is_mod_disabled(cache_dir, pkg_path))) {
        VFS_LOGI("Skipping disabled mod PKG: %s", pkg_path.filename().c_str());
        return false;
    }

    int fd = ::open(pkg_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        VFS_LOGE("Cannot open PKG fd: %s", pkg_path.c_str());
        return false;
    }

    auto ah = std::make_unique<ArchiveHandle>();
    ah->kind = pkg.debug ? SourceKind::PkgDebug : SourceKind::PkgRetail;
    ah->fd = fd;
    ah->path = pkg_path.string();
    ah->data_offset = pkg.data_offset;
    ah->aes.set_key(k_ps3recomp_pkg_key, 128);
    memcpy(ah->riv, pkg.riv, 16);
    memcpy(ah->qa, pkg.qa, 16);
    ArchiveHandle* aptr = ah.get();
    g_vfs.archives.push_back(std::move(ah));

    std::string cid_u = to_upper(pkg.content_id);
    const bool is_update = !pkg.debug && (cid_u.find("DOD3PATCH") != std::string::npos ||
                                          cid_u.find("BLUS31197") != std::string::npos);
    const bool is_dlc = !pkg.debug && (cid_u.find("NPUB31251") != std::string::npos);

    /* If this is the official 1.01 Update PKG, ensure cache/EBOOT_101.ELF exists */
    if (is_update) {
        fs::path eboot_dst = cache_dir / "EBOOT_101.ELF";
        std::error_code ec;
        uintmax_t sz = fs::exists(eboot_dst, ec) ? fs::file_size(eboot_dst, ec) : 0;
        if (sz < 20000000) {
            VFS_LOGI("Decrypting Update 1.01 EBOOT.BIN -> %s ...", eboot_dst.c_str());
            std::vector<uint8_t> self_bytes, elf_bytes;
            std::string derr;
            bool wrong_keys = false;
            if (dod3setup::pkg_read_file(pkg, "USRDIR/EBOOT.BIN", &self_bytes, &derr) &&
                dod3setup::self_decrypt(self_bytes, dod3setup::builtin_keys().self_keys(),
                                        &elf_bytes, &wrong_keys, &derr)) {
                if (write_file_atomic(eboot_dst, elf_bytes.data(), elf_bytes.size())) {
                    VFS_LOGI("Cached EBOOT_101.ELF (%zu bytes)", elf_bytes.size());
                }
            } else {
                VFS_LOGE("Failed to decrypt EBOOT.BIN: %s", derr.c_str());
            }
        }
        if (fs::exists(eboot_dst, ec) && fs::file_size(eboot_dst, ec) >= 20000000) {
            g_vfs.eboot_path = eboot_dst.string();
        }
    }

    size_t mounted = 0;
    for (const dod3setup::PkgEntry& e : pkg.entries) {
        if (e.dir) continue;
        std::string eu = to_upper(e.name);

        VirtualEntry ve;
        ve.archive = aptr;
        ve.kind = aptr->kind;
        ve.file_offset = e.offset;
        ve.size = e.size;

        if (is_update) {
            ve.priority = 20;
            mount_entry("/game/BLES00000/" + e.name, ve);
            mounted++;
        } else if (is_dlc) {
            ve.priority = 15;
            if (eu.size() > 5 && eu.compare(eu.size() - 5, 5, ".EDAT") == 0 &&
                e.size >= 0x90 && e.size <= 4096) {
                uint8_t hdr[0x90];
                if (pread_all(aptr->fd, hdr, sizeof hdr, aptr->data_offset + e.offset) == (ssize_t)sizeof hdr) {
                    pkg_decrypt_range(*aptr, e.offset, hdr, sizeof hdr);
                    if (hdr[0] == 'N' && hdr[1] == 'P' && hdr[2] == 'D' && hdr[3] == 0) {
                        uint64_t esz = dod3_be64(hdr + 0x88);
                        if (esz == 0) {
                            ve.archive = nullptr;
                            ve.kind = SourceKind::Memory;
                            ve.file_offset = 0;
                            ve.size = 0;
                        }
                    }
                }
            }
            mount_entry("/game/NPUB31251/" + e.name, ve);
            mounted++;
        } else if (pkg.debug) {
            ve.priority = 30;
            if (eu.rfind("USRDIR/PATCH/", 0) == 0) {
                mount_entry("/game/BLES00000/" + e.name, ve);
                mounted++;
            } else if (eu.rfind("USRDIR/FIOS-UNREALENGINE3/", 0) == 0) {
                mount_entry("/game/BLES00000DATA/" + e.name, ve);
                mounted++;
            } else if (eu.rfind("USRDIR/DLC/", 0) == 0 || eu.rfind("USRDIR/DLC_JPV/", 0) == 0) {
                mount_entry("/game/NPUB31251/" + e.name, ve);
                mounted++;
            }
        }
    }

    if (pkg.debug && mounted > 0) {
        std::string fn_u = to_upper(pkg_path.filename().string());
        if (fn_u.find("ES") != std::string::npos || fn_u.find("ESP") != std::string::npos) {
            g_vfs.spanish_active = true;
        }
    }

    VFS_LOGI("Mounted %s PKG: %s (cid=%s, %zu files)",
             pkg.debug ? "MOD/DEBUG" : (is_update ? "UPDATE" : (is_dlc ? "DLC" : "OTHER")),
             pkg_path.filename().c_str(), pkg.content_id.c_str(), mounted);
    return mounted > 0;
}

bool mount_movies_zip(const fs::path& zip_path)
{
    std::vector<dod3setup::ZipEntry> entries;
    std::string err;
    if (!dod3setup::zip_list(zip_path, &entries, &err)) {
        VFS_LOGW("Skipping ZIP %s: %s", zip_path.c_str(), err.c_str());
        return false;
    }

    /* Check if this ZIP contains .BIK movie files */
    size_t bik_count = 0;
    for (const auto& e : entries) {
        if (!e.dir && to_upper(e.name).ends_with(".BIK")) bik_count++;
    }
    if (bik_count == 0) return false;

    int fd = ::open(zip_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    auto ah = std::make_unique<ArchiveHandle>();
    ah->kind = SourceKind::ZipStored;
    ah->fd = fd;
    ah->path = zip_path.string();
    ArchiveHandle* aptr = ah.get();
    g_vfs.archives.push_back(std::move(ah));

    size_t mounted = 0;
    for (const auto& e : entries) {
        if (e.dir) continue;
        std::string u = to_upper(e.name);
        if (!u.ends_with(".BIK")) continue;
        if (e.method != 0 && e.method != 8) continue;

        /* Read 30-byte local header to get exact data offset */
        uint8_t lh[30];
        if (pread_all(fd, lh, 30, e.header) != 30) continue;
        if (lh[0] != 'P' || lh[1] != 'K' || lh[2] != 3 || lh[3] != 4) continue;
        uint16_t name_len = (uint16_t)lh[26] | ((uint16_t)lh[27] << 8);
        uint16_t extra_len = (uint16_t)lh[28] | ((uint16_t)lh[29] << 8);
        uint64_t data_off = e.header + 30 + name_len + extra_len;

        std::string fname = e.name;
        size_t sl = fname.find_last_of('/');
        if (sl != std::string::npos) fname = fname.substr(sl + 1);

        VirtualEntry ve;
        ve.archive = aptr;
        ve.kind = (e.method == 0) ? SourceKind::ZipStored : SourceKind::ZipDeflate;
        ve.file_offset = data_off;
        ve.size = e.usize;
        ve.csize = e.csize;
        ve.priority = 30;

        mount_entry("/PS3_GAME/USRDIR/SQEX03GAME/MOVIES/" + fname, ve);
        mounted++;
    }

    VFS_LOGI("Mounted MOVIES ZIP: %s (%zu .BIK files)", zip_path.filename().c_str(), mounted);
    return mounted > 0;
}

/* Synthesize JPV_PKG_FILES.TXT and JPV_NON_PKG_FILES.TXT in memory if Japanese Voice DLC is mounted. */
void synthesize_jpv_lists()
{
    static const std::string kPrefix = "/GAME/NPUB31251/USRDIR/DLC_JPV/SQEX03GAME/";
    std::vector<std::string> pkgs, others;
    for (const auto& kv : g_vfs.files) {
        if (kv.first.rfind(kPrefix, 0) != 0) continue;
        std::string rel = kv.first.substr(strlen("/GAME/NPUB31251/USRDIR"));   /* "/DLC_JPV/SQEX03GAME/..." */
        if (rel.ends_with(".BIK") || rel.ends_with(".EDAT") || rel.ends_with(".TXT")) continue;
        if (rel.ends_with(".XXX")) pkgs.push_back(rel);
        else others.push_back(rel);
    }
    if (pkgs.empty()) return;

    std::sort(pkgs.begin(), pkgs.end());
    std::sort(others.begin(), others.end());

    auto make_mem = [](const std::vector<std::string>& lines) {
        VirtualEntry ve;
        ve.kind = SourceKind::Memory;
        ve.priority = 25;
        for (const auto& l : lines) ve.mem_data += l + "\r\n";
        ve.size = ve.mem_data.size();
        return ve;
    };

    mount_entry("/game/NPUB31251/USRDIR/DLC_JPV/JPV_PKG_FILES.TXT", make_mem(pkgs));
    mount_entry("/game/NPUB31251/USRDIR/DLC_JPV/JPV_NON_PKG_FILES.TXT", make_mem(others));
    VFS_LOGI("Synthesized JPV file lists in memory (%zu pkgs, %zu others)", pkgs.size(), others.size());
}

void collect_archives(const fs::path& dir, int depth,
                      std::vector<fs::path>& isos,
                      std::vector<fs::path>& pkgs,
                      std::vector<fs::path>& zips)
{
    if (depth > 2) return;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        if (ec) break;
        std::string name = entry.path().filename().string();
        std::string name_u = to_upper(name);
        if (entry.is_directory(ec)) {
            if (name_u == "CACHE" || name_u == "GAMEDATA" || name_u == "GAME" ||
                name_u == "SHADERS" || name_u == "SAVES") continue;
            collect_archives(entry.path(), depth + 1, isos, pkgs, zips);
        } else if (entry.is_regular_file(ec)) {
            std::string ext = to_upper(entry.path().extension().string());
            if (ext == ".ISO") isos.push_back(entry.path());
            else if (ext == ".PKG") pkgs.push_back(entry.path());
            else if (ext == ".ZIP") zips.push_back(entry.path());
        }
    }
}

}  // namespace

extern "C" bool dod3_android_vfs_init(const char* base_dir)
{
    if (g_vfs.initialized) return !g_vfs.eboot_path.empty();
    if (!base_dir || !*base_dir) return false;

    fs::path base(base_dir);
    fs::path cache_dir = base / "cache";
    fs::path root = base / "game" / "disc";

    g_vfs.base_dir = base.string();
    g_vfs.root_path = root.string();
    g_vfs.root_upper = normalize_slash_upper(g_vfs.root_path.c_str());

    std::error_code ec;
    fs::create_directories(cache_dir, ec);
    const bool allow_mods = !fs::exists(cache_dir / "ES-DATA.off", ec);

    std::vector<fs::path> isos, pkgs, zips;
    collect_archives(base, 0, isos, pkgs, zips);
    std::sort(isos.begin(), isos.end());
    std::sort(pkgs.begin(), pkgs.end());
    std::sort(zips.begin(), zips.end());

    VFS_LOGI("Scanning %s: found %zu ISO, %zu PKG, %zu ZIP (mods=%s)",
             base_dir, isos.size(), pkgs.size(), zips.size(), allow_mods ? "ON" : "OFF");

    bool iso_ok = false;
    for (const auto& p : isos) {
        if (mount_iso(p, cache_dir)) {
            iso_ok = true;
            break;   /* mount first valid Drakengard 3 ISO */
        }
    }

    for (const auto& p : pkgs) {
        mount_pkg(p, cache_dir, allow_mods);
    }

    if (allow_mods) {
        for (const auto& p : zips) {
            if (is_mod_disabled(cache_dir, p)) {
                VFS_LOGI("Skipping disabled mod ZIP: %s", p.filename().c_str());
                continue;
            }
            mount_movies_zip(p);
        }
    }

    synthesize_jpv_lists();

    /* Also check if EBOOT_101.ELF was already cached even if no update pkg was re-decrypted */
    if (g_vfs.eboot_path.empty()) {
        fs::path eboot_dst = cache_dir / "EBOOT_101.ELF";
        if (fs::exists(eboot_dst, ec) && fs::file_size(eboot_dst, ec) >= 20000000) {
            g_vfs.eboot_path = eboot_dst.string();
        }
    }

    if (iso_ok || !g_vfs.files.empty()) {
        for (const auto& kv : g_vfs.dirs) {
            auto& vec = g_vfs.dirs_sorted[kv.first];
            vec.reserve(kv.second.size());
            for (const auto& entry : kv.second) vec.push_back(entry.second);
            std::sort(vec.begin(), vec.end(),
                      [](const DirEntryInfo& a, const DirEntryInfo& b) { return a.name < b.name; });
        }
        g_vfs.initialized = true;
        ps3_vfs_virtual_open = vfs_hook_open;
        ps3_vfs_virtual_stat = vfs_hook_stat;
        ps3_vfs_virtual_opendir = vfs_hook_opendir;
        ps3_vfs_virtual_readdir = vfs_hook_readdir;
        ps3_vfs_virtual_closedir = vfs_hook_closedir;
        VFS_LOGI("Direct-Archive VFS READY: %zu virtual files, %zu virtual dirs, spanish=%d, eboot=%s",
                 g_vfs.files.size(), g_vfs.dirs.size(), g_vfs.spanish_active ? 1 : 0,
                 g_vfs.eboot_path.empty() ? "(missing)" : g_vfs.eboot_path.c_str());
    } else {
        VFS_LOGW("Direct-Archive VFS: no ISO/PKG found in %s", base_dir);
    }

    return iso_ok && !g_vfs.eboot_path.empty();
}

extern "C" bool dod3_android_vfs_spanish_active(void)
{
    return g_vfs.spanish_active;
}

extern "C" const char* dod3_android_vfs_eboot_path(void)
{
    return g_vfs.eboot_path.c_str();
}

extern "C" const char* dod3_android_vfs_root_path(void)
{
    return g_vfs.root_path.c_str();
}

#endif  /* __ANDROID__ */
