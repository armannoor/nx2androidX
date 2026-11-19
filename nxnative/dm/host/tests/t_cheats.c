// The cheat engine (hle_cheats.c): a community cheat file is read the way dmnt reads it, a cheat that cannot run is turned
// off with its reason, and the enabled cheats write the game's memory once per frame. No guest exists here: the "main"
// module stands in as a block of memory (its text, its data and the room past its end).
#include "../hle_cheats.c"
#include <sys/stat.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s (line %d)\n", msg, __LINE__); } } while (0)

// The host's module table (loader.c): one "main" with an image of our own
static uint8_t g_image[0x20000];
module_t g_mod[MAX_MOD]; int g_nmod;

// The guest memory the engine asks about (hle_svc.c): none in this test, so a cheat reaches only the module's image
void dm_guest_heap_range(uint64_t *base, size_t *open) { *base = 0; *open = 0; }
void dm_guest_alias_range(uint8_t **base, size_t *size) { *base = NULL; *size = 0; }
void svc_guest_range(uint8_t **base, size_t *size) { *base = NULL; *size = 0; }
int dm_guest_as_contains(uintptr_t a) { (void)a; return 0; }
uint64_t dm_cheats_keys(void) { return 0; }   // hle_hid.c: no key is held
void dm_guest_write_prepare(void *addr, size_t len) { (void)addr; (void)len; }

// The host's write into a module (loader.c dm_module_write): the test's "main" is a plain array, so the write is a
// plain copy. The code writes are counted, and the avoid range stands for the words the rewriter changed
int g_code_writes; static uint64_t g_avoid_lo, g_avoid_hi;
int dm_module_write(module_t *m, uint64_t off, const void *src, size_t len) {
    if (m != &g_mod[0] || off + len > m->size) return 0;
    memcpy(g_image + off, src, len);
    if (off < m->code_end) g_code_writes++;
    return 1;
}
int dm_module_avoided(module_t *m, uint64_t off, uint64_t len) {
    (void)m;
    return g_avoid_hi > g_avoid_lo && off < g_avoid_hi && off + len > g_avoid_lo;
}

#define BUILD "DF154E1D08E39646"   // the build ID of the main module below (the first 16 hex digits name the file)

static char g_test_dir[512];

// Write a cheat file of this build into the folder and load it
static int load(const char *text) {
    char path[600];
    snprintf(path, sizeof path, "%s/%s.txt", g_test_dir, BUILD);
    FILE *f = fopen(path, "w");
    if (!f) return 0;
    fputs(text, f);
    fclose(f);
    return dm_cheats_open(g_test_dir);
}

// The row of the i-th cheat: its name, whether it is on, why it cannot run
static void row(int i, char *name, size_t n, int *on, char *why, size_t wn) {
    const char *nm = "", *wh = "";
    *on = 0; why[0] = 0;
    if (!dm_cheats_row(i, &nm, on, &wh)) { snprintf(name, n, ""); return; }
    snprintf(name, n, "%s", nm);
    if (wh) snprintf(why, wn, "%s", wh);
}

static void t_file(void) {
    int n = load(
        "[Data write]\n"
        "04000000 00005000 12345678\n"
        "[Credits]\n"
        "by someone\n"
        "[Broken line]\n"
        "0400000\n");
    CHECK(n == 2, "the file holds two cheats (the credits line is a label, not a cheat)");
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(!strcmp(name, "Data write"), "the first cheat is the one with code words");
    CHECK(on == 0, "a cheat starts off (the player turns it on)");
    row(1, name, sizeof name, &on, why, sizeof why);
    CHECK(!strcmp(name, "Broken line"), "the cheat with the malformed line is listed");
    CHECK(strstr(why, "malformed") != NULL, "the malformed line is the reason it cannot run");
    CHECK(dm_cheats_set(1, 1) == -1, "a cheat that cannot run is not switched on");
}

static void t_write(void) {
    load("[Data write]\n"
         "04000000 00005000 12345678\n");
    CHECK(dm_cheats_count() == 1, "one cheat");
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat is switched on");
    dm_cheats_present();
    uint32_t v = 0;
    memcpy(&v, g_image + 0x5000, 4);
    CHECK(v == 0x12345678, "the cheat wrote the value into the game's data");
    // the value is written again every frame (the cheat stays on)
    memset(g_image + 0x5000, 0, 4);
    dm_cheats_present();
    memcpy(&v, g_image + 0x5000, 4);
    CHECK(v == 0x12345678, "the cheat writes on every frame");
    // switched off, the game keeps its own value
    CHECK(dm_cheats_set(0, 0) == 1, "the cheat is switched off");
    memset(g_image + 0x5000, 0, 4);
    dm_cheats_present();
    memcpy(&v, g_image + 0x5000, 4);
    CHECK(v == 0, "a cheat that is off writes nothing");
}

static void t_code(void) {
    load("[Code write]\n"
         "04000000 00000010 12345678\n");
    CHECK(dm_cheats_count() == 1, "one cheat");
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(why[0] == 0, "a cheat that writes the game's code is allowed now");
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat is switched on");
    dm_cheats_present();
    uint32_t v = 0;
    memcpy(&v, g_image + 0x10, 4);
    CHECK(v == 0x12345678, "the cheat wrote the value into the game's code");
    CHECK(g_code_writes > 0, "the write went through the host's code write");
}

// the words the rewriter changed: a cheat must not write there (the game's own code would break in a way nobody can undo)
static void t_rewriter_words(void) {
    g_avoid_lo = 0x100; g_avoid_hi = 0x104;
    load("[Rewriter's word]\n"
         "04000000 00000100 12345678\n");
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(strstr(why, "rewriter") != NULL, "a cheat that writes a word the rewriter changed says so");
    CHECK(dm_cheats_set(0, 1) == -1, "it cannot be switched on");
    uint32_t v = 0;
    memcpy(&v, g_image + 0x100, 4);
    CHECK(v == 0, "nothing was written there");
    g_avoid_lo = g_avoid_hi = 0;
}

static void t_text_page_tail(void) {
    // the padding at the end of the text page: the host opens the page for the write (the write bit), so it lands
    load("[Code page tail]\n"
         "04000000 00003FFC 12345678\n");
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(why[0] == 0, "it is allowed");
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat is switched on");
    dm_cheats_present();
    uint32_t v = 0;
    memcpy(&v, g_image + 0x3FFC, 4);
    CHECK(v == 0x12345678, "the write into the text page's tail landed");
}

static void t_register_code(void) {
    // the address is only known at run time (a register holds it), so the static look cannot judge the cheat: the run
    // writes it anyway (the host opens the page for that)
    uint64_t at = (uint64_t)(uintptr_t)g_image + 0x10;
    char text[256];
    snprintf(text, sizeof text, "[Code through a register]\n40000000 %08X %08X\n64000000 00000000 12345678\n",
             (unsigned)(at >> 32), (unsigned)(uint32_t)at);
    load(text);
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(why[0] == 0, "the static look cannot judge a register's address: the cheat stays as it was");
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat is switched on");
    dm_cheats_present();
    uint32_t v = 0;
    memcpy(&v, g_image + 0x10, 4);
    CHECK(v == 0x12345678, "the value went to the address the register holds");
}

static void t_unmapped(void) {
    load("[Unmapped]\n"
         "04000000 00F00000 12345678\n");
    char name[128], why[128]; int on;
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(strstr(why, "code") == NULL, "a cheat that writes past the image is not blamed on the code");
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat can be switched on (it may work later)");
    dm_cheats_present();
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(on == 1, "a write that failed leaves the cheat on: it retries on the next frame");
}

static void t_register(void) {
    // opcode 6 writes to an absolute address: the register holds the address itself, so it holds a data address of the image
    uint64_t at = (uint64_t)(uintptr_t)g_image + 0x5000;
    char text[256];
    snprintf(text, sizeof text, "[Through a register]\n40000000 %08X %08X\n64000000 00000000 12345678\n",
             (unsigned)(at >> 32), (unsigned)(uint32_t)at);
    load(text);
    CHECK(dm_cheats_set(0, 1) == 1, "the cheat is switched on");
    dm_cheats_present();
    uint32_t v = 0;
    memcpy(&v, g_image + 0x5000, 4);
    CHECK(v == 0x12345678, "the value went to the address the register holds");
}

static void t_toggles(void) {
    load("[One]\n"
         "04000000 00005000 12345678\n"
         "[Two]\n"
         "04000000 00005004 87654321\n");
    CHECK(dm_cheats_count() == 2, "two cheats");
    CHECK(dm_cheats_set(1, 1) == 1, "the second cheat is switched on");
    char path[600];
    snprintf(path, sizeof path, "%s/%s.enabled", g_test_dir, BUILD);
    FILE *f = fopen(path, "r");
    CHECK(f != NULL, "the toggles are written beside the file");
    if (f) {
        char line[128] = "";
        if (!fgets(line, sizeof line, f)) line[0] = 0;
        fclose(f);
        CHECK(!strcmp(line, "Two\n"), "the toggles file names the cheat that is on");
    }
    // the next launch reads them back
    load("[One]\n"
         "04000000 00005000 12345678\n"
         "[Two]\n"
         "04000000 00005004 87654321\n");
    char name[128], why[128]; int on;
    row(1, name, sizeof name, &on, why, sizeof why);
    CHECK(on == 1, "the cheat that was on is on again");
    row(0, name, sizeof name, &on, why, sizeof why);
    CHECK(on == 0, "the other one is still off");
}

static void t_other_build(void) {
    char path[600];
    snprintf(path, sizeof path, "%s/%s.txt", g_test_dir, BUILD);
    remove(path);
    snprintf(path, sizeof path, "%s/0011223344556677.txt", g_test_dir);
    FILE *f = fopen(path, "w");
    if (f) { fputs("[Other build]\n04000000 00001000 12345678\n", f); fclose(f); }
    CHECK(dm_cheats_open(g_test_dir) == 0, "a file of another build is not loaded");
}

int main(void) {
    snprintf(g_test_dir, sizeof g_test_dir, "/tmp/nx2a-cheats-test-%d", (int)getpid());
    mkdir(g_test_dir, 0755);
    // the main module: an image with a text (the first page) and data after it
    memset(g_mod, 0, sizeof g_mod);
    snprintf(g_mod[0].name, sizeof g_mod[0].name, "main");
    g_mod[0].base = g_image;
    g_mod[0].size = sizeof g_image;
    g_mod[0].text_end = 0x1000;
    g_mod[0].data_off = 0x1000;
    g_mod[0].code_end = 0x4000;   // the sealed code pages (the loader's rule: the text rounded up to the page)
    g_mod[0].patch_off = -1;
    snprintf(g_mod[0].build_id, sizeof g_mod[0].build_id, "%s0123456789ABCDEF", BUILD);
    g_nmod = 1;

    t_file();
    t_write();
    t_code();
    t_text_page_tail();
    t_register_code();
    t_rewriter_words();
    t_unmapped();
    t_register();
    t_toggles();
    t_other_build();

    dm_cheats_close();
    printf("%s: %d checks, %d failures\n", fails ? "FAIL" : "ok", checks, fails);
    return fails ? 1 : 0;
}
