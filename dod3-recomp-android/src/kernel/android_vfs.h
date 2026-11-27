#pragma once

#ifdef __ANDROID__
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Initialize the Android direct-archive VFS from `base_dir` (e.g. /sdcard/Drakengard3).
 * Scans for .iso, .pkg (update 1.01, DLCs, Spanish/mod pkgs), and MOVIES.zip,
 * mounts them directly via funopen()+pread64() without unpacking, extracts only
 * tiny runtime caches (cache/EBOOT_101.ELF, cache/PIC1.PNG), and registers the
 * ps3_vfs_virtual_* hooks in ps3recomp.
 * Returns true if at least the base ISO + 1.01 EBOOT are ready. */
bool dod3_android_vfs_init(const char* base_dir);

/* True if the Spanish translation mod packages are currently mounted & active. */
bool dod3_android_vfs_spanish_active(void);

/* Path to the cached decrypted EBOOT_101.ELF (empty string if not ready). */
const char* dod3_android_vfs_eboot_path(void);

/* Path to the virtual VFS root (<base_dir>/game/disc). */
const char* dod3_android_vfs_root_path(void);

#ifdef __cplusplus
}
#endif
#endif
