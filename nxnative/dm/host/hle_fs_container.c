// A game's RomFS in one file: the switchbrew RomFS image (the format LibHac, nstool and hactool read).
// A portable pack keeps the game's files in that one file, so it can be copied as one file and mounted
// without thousands of small files on the volume.
//
// Layout (little endian):
//   header (0x28): headerSize, dirHashOff/Size, dirTableOff/Size, fileHashOff/Size, fileTableOff/Size, dataOff
//   directory table: entries of 0x18 bytes (parent, sibling, childDir, childFile, nextHash, nameLen) and the
//                    name after them, padded to a multiple of 4
//   file table: entries of 0x20 bytes (parent, sibling, dataOff, dataSize, nextHash, nameLen) and the name
//               after them, padded to a multiple of 4
//   hash tables: one u32 a bucket, the offset of the first entry of that bucket (0xffffffff when it is empty)
//
// A file's bytes are at dataOff + the entry's own offset, and every file starts on a 16-byte boundary.
//
// The image has no magic: it is recognized by its header (the size the header says). The mount reads it
// strictly: a broken image is a failed mount, never a silent fallback to the directory form.
//
// The format is written by the app when it exports a portable pack (RomfsImage.kt) and read here.
#include "hle.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define ROMFS_HEADER 0x28
#define ROMFS_HEADER_ALT 0x50      // the same header with 64-bit fields (what LibHac's builder writes)
#define ROMFS_DIR_ENTRY 0x18
#define ROMFS_FILE_ENTRY 0x20
#define ROMFS_NONE 0xFFFFFFFFu

typedef struct romfsimg romfsimg;

void romfsimg_close(romfsimg *img);

typedef struct {
    uint64_t path_off;    // into the path pool
    uint32_t path_len;
    int is_dir;
    uint64_t size;
    uint64_t data_off;    // where the file's bytes are in the image
    uint64_t parent;
    uint64_t first_child;
    uint64_t next_sibling;
} romfsimg_entry;

// One table's entry, as the image holds it
typedef struct {
    uint64_t offset;      // where the entry sits in its table
    uint64_t parent;
    uint64_t sibling;
    uint64_t child_dir;
    uint64_t child_file;
    uint64_t next_hash;
    uint64_t data_off;    // files only: relative to the data area
    uint64_t data_size;   // files only
    uint32_t name_len;
    const char *name;
} romfsimg_raw;

struct romfsimg {
    int fd;
    uint64_t file_size;
    uint64_t count;
    romfsimg_entry *entries;
    char *paths;          // every entry's path, relative to the mount root
    uint64_t paths_len;
    uint64_t paths_cap;
    uint64_t *slots;      // the path index: the entry index plus one, zero when the slot is empty
    uint64_t mask;
};

static uint32_t romfsimg_u32(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static uint64_t romfsimg_u64(const uint8_t *p) { return (uint64_t)romfsimg_u32(p) | ((uint64_t)romfsimg_u32(p + 4) << 32); }
static uint64_t romfsimg_align4(uint64_t n) { return (n + 3) & ~(uint64_t)3; }

static int romfsimg_read_at(romfsimg *img, void *buf, size_t n, uint64_t off) {
    if (off + n > img->file_size || off + n < off) return 0;
    size_t got = 0;
    while (got < n) {
        ssize_t r = pread(img->fd, (uint8_t *)buf + got, n - got, (off_t)(off + got));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return 0;
        got += (size_t)r;
    }
    return 1;
}

// The path hash the index uses (the image's own hash is only needed to read it, not to look a path up here)
static uint64_t romfsimg_hash(const void *s, size_t n) {
    const uint8_t *p = s;
    uint64_t h = 1469598103934655603ull;
    for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ull; }
    return h;
}

// The file is a RomFS image: its header says so.
int romfsimg_is_image(const char *path) {
    uint8_t head[4];
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    int ok = pread(fd, head, sizeof head, 0) == (ssize_t)sizeof head;
    close(fd);
    if (!ok) return 0;
    uint32_t size = romfsimg_u32(head);
    return size == ROMFS_HEADER || size == ROMFS_HEADER_ALT;
}

// The index over the paths: the entry a path names, or none. The path is relative to the mount root ("A/B/C").
int romfsimg_lookup(romfsimg *img, const char *path, uint64_t *out) {
    size_t len = strlen(path);
    uint64_t s = romfsimg_hash(path, len) & img->mask;
    while (img->slots[s]) {
        uint64_t i = img->slots[s] - 1;
        if (img->entries[i].path_len == len && !memcmp(img->paths + img->entries[i].path_off, path, len)) { *out = i; return 1; }
        s = (s + 1) & img->mask;
    }
    return 0;
}

// The entry's own name: the part of its path after the last '/'.
int romfsimg_entry_name(romfsimg *img, uint64_t i, char *out, size_t n) {
    if (i >= img->count) return 0;
    const char *p = img->paths + img->entries[i].path_off;
    uint32_t len = img->entries[i].path_len;
    for (uint32_t k = len; k > 0; k--) if (p[k - 1] == '/') { p += k; len -= k; break; }
    if (!len || len >= n) return 0;
    memcpy(out, p, len); out[len] = 0; return 1;
}
int romfsimg_entry_is_dir(romfsimg *img, uint64_t i) { return i < img->count && img->entries[i].is_dir; }
uint64_t romfsimg_entry_size(romfsimg *img, uint64_t i) { return i < img->count ? img->entries[i].size : 0; }
uint64_t romfsimg_entry_data_off(romfsimg *img, uint64_t i) { return i < img->count ? img->entries[i].data_off : 0; }
uint64_t romfsimg_first_child(romfsimg *img, uint64_t i) { return i < img->count ? img->entries[i].first_child : (uint64_t)-1; }
uint64_t romfsimg_next_sibling(romfsimg *img, uint64_t i) { return i < img->count ? img->entries[i].next_sibling : (uint64_t)-1; }

// Read a file's bytes: the guest's offset is inside the file, the image's offset is where it starts.
int romfsimg_read(romfsimg *img, uint64_t i, uint64_t off, void *buf, size_t sz, size_t *got) {
    *got = 0;
    if (i >= img->count) return 0;
    uint64_t len = img->entries[i].size;
    if (off >= len) return 1;
    if (sz > len - off) sz = (size_t)(len - off);
    if (!romfsimg_read_at(img, buf, sz, img->entries[i].data_off + off)) return 0;
    *got = sz; return 1;
}

// The image's own file descriptor: the host reads the bytes out of it.
int romfsimg_fd(romfsimg *img) { return img ? img->fd : -1; }

// How many entries the image holds (the root counts as one).
uint64_t romfsimg_count(romfsimg *img) { return img ? img->count : 0; }

// ---------- opening ----------

// The map from a table offset to the entry's index
typedef struct { uint64_t *keys; uint64_t *vals; uint64_t mask; } romfsimg_map;

static int romfsimg_map_init(romfsimg_map *m, uint64_t n) {
    uint64_t slots = 16;
    while (slots < n * 2) slots <<= 1;
    m->keys = calloc(slots, sizeof *m->keys);
    m->vals = calloc(slots, sizeof *m->vals);
    if (!m->keys || !m->vals) { free(m->keys); free(m->vals); m->keys = NULL; m->vals = NULL; return 0; }
    m->mask = slots - 1;
    for (uint64_t i = 0; i < slots; i++) m->keys[i] = ROMFS_NONE;
    return 1;
}
static void romfsimg_map_free(romfsimg_map *m) { free(m->keys); free(m->vals); m->keys = NULL; m->vals = NULL; }
static void romfsimg_map_put(romfsimg_map *m, uint64_t key, uint64_t val) {
    uint64_t s = romfsimg_hash(&key, sizeof key) & m->mask;
    while (m->keys[s] != ROMFS_NONE) { if (m->keys[s] == key) { m->vals[s] = val; return; } s = (s + 1) & m->mask; }
    m->keys[s] = key; m->vals[s] = val;
}
static int romfsimg_map_get(romfsimg_map *m, uint64_t key, uint64_t *val) {
    uint64_t s = romfsimg_hash(&key, sizeof key) & m->mask;
    while (m->keys[s] != ROMFS_NONE) { if (m->keys[s] == key) { *val = m->vals[s]; return 1; } s = (s + 1) & m->mask; }
    return 0;
}

// Make room in the path pool for what is about to be written into it
static int romfsimg_paths_room(romfsimg *img, uint64_t need) {
    if (img->paths_len + need + 1 <= img->paths_cap) return 1;
    uint64_t cap = img->paths_cap ? img->paths_cap : 0x1000;
    while (cap < img->paths_len + need + 1) cap *= 2;
    char *p = realloc(img->paths, cap);
    if (!p) return 0;
    img->paths = p; img->paths_cap = cap;
    return 1;
}

// Walk one table and split it into its entries. The names are in the table's bytes.
static int romfsimg_parse_table(const uint8_t *table, uint64_t size, int is_file, romfsimg_raw **out, uint64_t *count) {
    uint64_t entry_size = is_file ? ROMFS_FILE_ENTRY : ROMFS_DIR_ENTRY;
    uint64_t n = 0, off = 0;
    while (off + entry_size <= size) {
        uint32_t name_len = romfsimg_u32(table + off + entry_size - 4);
        if (name_len > 0x400 || off + entry_size + romfsimg_align4(name_len) > size) return 0;
        n++;
        off += entry_size + romfsimg_align4(name_len);
    }
    if (off != size) return 0;               // the table does not end on an entry: it is not this format
    romfsimg_raw *raw = calloc(n ? n : 1, sizeof *raw);
    if (!raw) return 0;
    uint64_t i = 0; off = 0;
    while (off < size) {
        raw[i].offset = off;
        raw[i].parent = romfsimg_u32(table + off);
        raw[i].sibling = romfsimg_u32(table + off + 4);
        raw[i].next_hash = romfsimg_u32(table + off + entry_size - 8);
        raw[i].name_len = romfsimg_u32(table + off + entry_size - 4);
        raw[i].name = (const char *)(table + off + entry_size);
        if (is_file) {
            raw[i].data_off = romfsimg_u64(table + off + 8);
            raw[i].data_size = romfsimg_u64(table + off + 16);
        } else {
            raw[i].child_dir = romfsimg_u32(table + off + 8);
            raw[i].child_file = romfsimg_u32(table + off + 12);
        }
        i++;
        off += entry_size + romfsimg_align4(raw[i - 1].name_len);
    }
    *out = raw; *count = n;
    return 1;
}

// The image's header, as the tables' places
typedef struct {
    uint64_t dir_hash_off, dir_hash_size;
    uint64_t dir_table_off, dir_table_size;
    uint64_t file_hash_off, file_hash_size;
    uint64_t file_table_off, file_table_size;
    uint64_t data_off;
} romfsimg_header;

static int romfsimg_read_header(romfsimg *img, romfsimg_header *h) {
    uint8_t head[ROMFS_HEADER_ALT];
    if (!romfsimg_read_at(img, head, sizeof head, 0)) return 0;
    uint32_t size = romfsimg_u32(head);
    if (size == ROMFS_HEADER) {
        h->dir_hash_off = romfsimg_u32(head + 0x04); h->dir_hash_size = romfsimg_u32(head + 0x08);
        h->dir_table_off = romfsimg_u32(head + 0x0C); h->dir_table_size = romfsimg_u32(head + 0x10);
        h->file_hash_off = romfsimg_u32(head + 0x14); h->file_hash_size = romfsimg_u32(head + 0x18);
        h->file_table_off = romfsimg_u32(head + 0x1C); h->file_table_size = romfsimg_u32(head + 0x20);
        h->data_off = romfsimg_u32(head + 0x24);
        return 1;
    }
    if (size == ROMFS_HEADER_ALT) {
        h->dir_hash_off = romfsimg_u64(head + 0x08); h->dir_hash_size = romfsimg_u64(head + 0x10);
        h->dir_table_off = romfsimg_u64(head + 0x18); h->dir_table_size = romfsimg_u64(head + 0x20);
        h->file_hash_off = romfsimg_u64(head + 0x28); h->file_hash_size = romfsimg_u64(head + 0x30);
        h->file_table_off = romfsimg_u64(head + 0x38); h->file_table_size = romfsimg_u64(head + 0x40);
        h->data_off = romfsimg_u64(head + 0x48);
        return 1;
    }
    return 0;
}

romfsimg *romfsimg_open(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); return NULL; }
    romfsimg *img = calloc(1, sizeof *img);
    if (!img) { close(fd); return NULL; }
    img->fd = fd; img->file_size = (uint64_t)st.st_size;
    if (img->file_size < ROMFS_HEADER) { romfsimg_close(img); return NULL; }

    romfsimg_header h;
    if (!romfsimg_read_header(img, &h)) { romfsimg_close(img); return NULL; }
    // every table and the data must lie inside the file
    if (h.dir_table_size < ROMFS_DIR_ENTRY || h.file_table_size < ROMFS_FILE_ENTRY ||
        h.dir_hash_off + h.dir_hash_size > img->file_size ||
        h.file_hash_off + h.file_hash_size > img->file_size ||
        h.dir_table_off + h.dir_table_size > img->file_size ||
        h.file_table_off + h.file_table_size > img->file_size ||
        h.data_off > img->file_size) { romfsimg_close(img); return NULL; }

    uint8_t *dir_table = malloc(h.dir_table_size);
    uint8_t *file_table = malloc(h.file_table_size);
    if (!dir_table || !file_table) { free(dir_table); free(file_table); romfsimg_close(img); return NULL; }
    if (!romfsimg_read_at(img, dir_table, h.dir_table_size, h.dir_table_off) ||
        !romfsimg_read_at(img, file_table, h.file_table_size, h.file_table_off)) {
        free(dir_table); free(file_table); romfsimg_close(img); return NULL; }

    romfsimg_raw *dirs = NULL, *files = NULL;
    uint64_t ndirs = 0, nfiles = 0;
    if (!romfsimg_parse_table(dir_table, h.dir_table_size, 0, &dirs, &ndirs) ||
        !romfsimg_parse_table(file_table, h.file_table_size, 1, &files, &nfiles)) {
        free(dirs); free(files); free(dir_table); free(file_table); romfsimg_close(img); return NULL; }
    // the tables' bytes stay until the paths are built: the names point into them

    // the root is the first directory, with no name and itself as its parent
    if (!ndirs || dirs[0].offset != 0 || dirs[0].name_len != 0 || dirs[0].parent != 0) {
        free(dirs); free(files); romfsimg_close(img); return NULL; }

    uint64_t count = ndirs + nfiles;
    img->entries = calloc(count ? count : 1, sizeof *img->entries);
    img->paths = malloc(0x1000);
    if (!img->entries || !img->paths) { free(dirs); free(files); romfsimg_close(img); return NULL; }
    img->count = count;
    img->paths_len = 0;
    uint64_t paths_cap = 0x1000;
    for (uint64_t i = 0; i < count; i++) { img->entries[i].first_child = (uint64_t)-1; img->entries[i].next_sibling = (uint64_t)-1; }

    romfsimg_map dir_index, file_index;
    if (!romfsimg_map_init(&dir_index, ndirs) || !romfsimg_map_init(&file_index, nfiles)) {
        free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }

    // the directories: their paths, their parents and their children
    for (uint64_t i = 0; i < ndirs; i++) {
        romfsimg_entry *e = &img->entries[i];
        uint64_t parent = 0;
        if (i) {
            if (!romfsimg_map_get(&dir_index, dirs[i].parent, &parent) || parent >= i) {
                free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        }
        e->is_dir = 1;
        e->parent = parent;
        e->path_off = img->paths_len;
        if (i) {
            uint32_t parent_len = img->entries[parent].path_len;
            if (!romfsimg_paths_room(img, parent_len + 1 + dirs[i].name_len)) {
                free(dirs); free(files); free(dir_table); free(file_table); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
            if (parent_len) {   // the root's children have no separator before their name
                memcpy(img->paths + img->paths_len, img->paths + img->entries[parent].path_off, parent_len);
                img->paths[img->paths_len + parent_len] = '/';
                img->paths_len += parent_len + 1;
            }
            memcpy(img->paths + img->paths_len, dirs[i].name, dirs[i].name_len);
            e->path_len = img->paths_len - e->path_off + dirs[i].name_len;
            img->paths_len += dirs[i].name_len;
        } else e->path_len = 0;
        img->paths[img->paths_len] = 0;
        romfsimg_map_put(&dir_index, dirs[i].offset, i);
    }

    // the files: their paths, their parent and where their bytes are
    for (uint64_t i = 0; i < nfiles; i++) {
        romfsimg_entry *e = &img->entries[ndirs + i];
        uint64_t parent = 0;
        if (!romfsimg_map_get(&dir_index, files[i].parent, &parent)) {
            free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        e->is_dir = 0;
        e->parent = parent;
        e->size = files[i].data_size;
        e->data_off = h.data_off + files[i].data_off;
        if (e->data_off < h.data_off || e->data_off + e->size > img->file_size || (files[i].data_off & 15)) {
            free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        e->path_off = img->paths_len;
        uint32_t parent_len = img->entries[parent].path_len;
        if (!romfsimg_paths_room(img, parent_len + 1 + files[i].name_len)) {
            free(dirs); free(files); free(dir_table); free(file_table); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        if (parent_len) {   // the root's files have no separator before their name
            memcpy(img->paths + img->paths_len, img->paths + img->entries[parent].path_off, parent_len);
            img->paths[img->paths_len + parent_len] = '/';
            img->paths_len += parent_len + 1;
        }
        memcpy(img->paths + img->paths_len, files[i].name, files[i].name_len);
        e->path_len = img->paths_len - e->path_off + files[i].name_len;
        img->paths_len += files[i].name_len;
        img->paths[img->paths_len] = 0;
        romfsimg_map_put(&file_index, files[i].offset, ndirs + i);
    }

    // the children: every directory's files first, then its directories, in one chain
    for (uint64_t i = 0; i < ndirs; i++) {
        romfsimg_entry *d = &img->entries[i];
        uint64_t file_child = ROMFS_NONE, dir_child = ROMFS_NONE;
        if (dirs[i].child_file != ROMFS_NONE) {
            if (!romfsimg_map_get(&file_index, dirs[i].child_file, &file_child)) {
                free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        }
        if (dirs[i].child_dir != ROMFS_NONE) {
            if (!romfsimg_map_get(&dir_index, dirs[i].child_dir, &dir_child)) {
                free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
        }
        d->first_child = file_child != ROMFS_NONE ? file_child : dir_child;
        // the file chain: every file of this directory, in the image's order
        uint64_t last = ROMFS_NONE;
        for (uint64_t f = file_child; f != ROMFS_NONE; ) {
            romfsimg_entry *e = &img->entries[f];
            if (e->parent != i) { free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
            last = f;
            uint64_t next = files[f - ndirs].sibling;
            if (next == ROMFS_NONE) break;
            uint64_t ni = 0;
            if (!romfsimg_map_get(&file_index, next, &ni)) { free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
            e->next_sibling = ni;
            f = ni;
        }
        // then the directory chain, whose own links are the image's
        for (uint64_t c = dir_child; c != ROMFS_NONE; ) {
            romfsimg_entry *e = &img->entries[c];
            if (e->parent != i) { free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
            uint64_t next = dirs[c].sibling;
            if (next == ROMFS_NONE) break;
            uint64_t ni = 0;
            if (!romfsimg_map_get(&dir_index, next, &ni)) { free(dirs); free(files); romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index); romfsimg_close(img); return NULL; }
            e->next_sibling = ni;
            c = ni;
        }
        // the two chains in one: the files, then the directories
        if (last != ROMFS_NONE && dir_child != ROMFS_NONE) img->entries[last].next_sibling = dir_child;
    }

    romfsimg_map_free(&dir_index); romfsimg_map_free(&file_index);
    free(dirs); free(files);
    free(dir_table); free(file_table);

    // the path index: the entry a path names
    uint64_t slots = 16;
    while (slots < count * 2) slots <<= 1;
    img->slots = calloc(slots, sizeof *img->slots);
    if (!img->slots) { romfsimg_close(img); return NULL; }
    img->mask = slots - 1;
    for (uint64_t i = 0; i < count; i++) {
        const char *p = img->paths + img->entries[i].path_off;
        uint64_t s = romfsimg_hash(p, img->entries[i].path_len) & img->mask;
        while (img->slots[s]) s = (s + 1) & img->mask;
        img->slots[s] = i + 1;
    }
    return img;
}

void romfsimg_close(romfsimg *img) {
    if (!img) return;
    if (img->fd >= 0) close(img->fd);
    free(img->entries); free(img->paths); free(img->slots); free(img);
}
