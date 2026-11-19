// Ported from the cheat engine of the sibling host (the BananaNative repository): MIT, Copyright (c) 2026 seedds.
// The MIT terms apply to this file; see THIRD_PARTY_NOTICES.md.
//
// Atmosphère-format cheat codes (the dmnt cheat VM) applied to the running game: the .txt files that cheat sites share,
// named by the first 16 hex digits of the main NSO's build ID (dm_main_build(), from the rewriter's fixups header).
// The app points the host at the folder that holds them (DM_CHEATS=<folder>, the app's own cheats/ folder, where the
// file it downloaded from the community library lies) and the host
// loads the file of the build it runs (dm_cheats_open); the player turns the cheats on and off in the game's drawer (the
// app's Cheats tab, through dm_cheats_set), and the host remembers the toggles in <build ID>.enabled beside the file.
//
// The VM follows Atmosphère 1.12.1's dmnt (stratosphere/dmnt/source/cheat/impl/dmnt_cheat_vm.cpp and
// docs/features/cheats.md) in word order, conditions, loops and register semantics. Two deliberate differences:
//   - each cheat runs as its own program (the reference concatenates all enabled cheats into one), so an unclosed
//     conditional in one cheat cannot swallow the next cheat's words, and the loop tops (word indexes into the program
//     that set them) are reset per cheat: a loop end without its start jumps to the first word of its own cheat
//   - a write into the game's own code is refused and turns the cheat off with the reason: the host seals the code
//     pages read+execute after loading them, and Android has no second writable mapping of them (an alias) as the
//     Apple host has. Data writes (the heap, the game's data and read-only data) work as on a Switch.
//
// Implemented opcodes: 0 store static, 1 memory conditional, 2 end / else, 3 loop, 4 load register static, 5 load register
// from memory, 6 store static to a register address, 7 legacy arithmetic, 8 key-press conditional, 9 register arithmetic
// (integer and float), A store register to memory, C0 register conditional, C1 / C2 save and restore, C3 static registers,
// C4 extended key-press conditional. Anything else (the pause and resume opcodes FF0 / FF1, the debug log FFF, or an
// unknown form) turns the cheat off before it ever runs, with the reason the app shows.
//
// A file is read the way dmnt reads it, with the tolerance a community file needs: a line of prose is ignored, a run of
// hex digits is cut into words of eight wherever the whitespace falls (glued words included), and a line that mixes code
// words with something else turns only that cheat off (the dropped word may carry a conditional's begin or end, and
// without it the body would run every frame).
#include "dm.h"
#include <mach/mach.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <stdatomic.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHEAT_MAX 128          // entries in one file (Atmosphère's own limit)
#define INS_MAX 0x400          // code words of one cheat (Atmosphère's own limit is 0x100)
#define CHEAT_NAME_MAX 128     // characters of a [Name] / {Name} header
#define FILE_MAX (64u << 10)   // Atmosphère's own limit: a bigger file is not a cheat file
#define VM_STEP_CAP 100000     // instructions of one cheat in one pass: a runaway loop must not stall a frame

// One entry of the file: its name, its code words (one program run per pass), and what the host knows about it
typedef struct {
    char name[CHEAT_NAME_MAX];
    uint32_t *ins; int nins;
    uint8_t master;      // {Name} header: always on, not toggleable
    uint8_t on;          // switched on
    uint8_t broken;      // it cannot run (an unsupported opcode, a malformed line, a write into the game's code)
    uint8_t label;       // no code words: a credits line or a section marker, never a cheat
    uint8_t heap_wait;   // it waited for the game's heap once (logged once)
    uint32_t fails;      // accesses that failed since the file was loaded (an unmapped page)
    uint8_t pass_fail;   // an access failed in the pass running now
    uint8_t dbg_logged;  // the debug lines of this cheat are already out (DM_CHEATS_DEBUG)
    uint8_t dbg_run_logged;   // the "running" line is out (it is written when the cheat starts, not on every pass)
    char why[96];        // why it is off, shown by the app
} cheat_t;

static cheat_t *g_cheat; static int g_ncheat;
static char g_dir[1024];        // the cheats folder
static char g_build[17];        // main's build ID cut to 16 hex digits, upper case as the cheat files name it
static module_t *g_main;        // the "main" module (the loader's table)
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_debug;             // DM_CHEATS_DEBUG=1: log what the cheats write (a cheat that runs every frame would flood the log otherwise)
static uint64_t g_passes;       // the passes the cheats ran: one per game frame (the guest's present), never per displayed image

// The guest memory the host can reach (hle_svc.c): the heap's base and its opened extent (region 1), the alias region's
// base and size (region 2), the whole block (region 3, the aslr region)
void dm_guest_heap_range(uint64_t *base, size_t *open);
void dm_guest_alias_range(uint8_t **base, size_t *size);
void svc_guest_range(uint8_t **base, size_t *size);
int dm_guest_as_contains(uintptr_t a);
uint64_t dm_cheats_keys(void);   // hle_hid.c: the keys held now (the key-press conditionals)
static void states_write(void);  // the toggles file beside the cheat file (below)

// ---------- the instruction decoder ----------

// The opcode: the top nybble, and for C, D, E, F the next one (0xC0...), for 0xF0... a third (0xFF0...)
static unsigned op_kind(uint32_t w) {
    unsigned op = w >> 28;
    if (op >= 0xC) op = (op << 4) | ((w >> 24) & 0xF);
    if (op >= 0xF0) op = (op << 4) | ((w >> 20) & 0xF);
    return op;
}

// The conditional begins (1 / 8 / C0 / C4): when a failed conditional is skipped, its whole nested block must be walked
// to its own end or else, so each of these increases the depth counter
static int op_cond_begin(unsigned op) { return op == 1 || op == 8 || op == 0xC0 || op == 0xC4; }

// The width nibble every sized form shares: 1, 2, 4 or 8 bytes
static int width_ok(unsigned t) { return t == 1 || t == 2 || t == 4 || t == 8; }

// The width's mask (the reference truncates to the width)
static uint64_t width_mask(unsigned t) { return t == 8 ? UINT64_MAX : (1ull << (8 * t)) - 1; }

// The reason of the last decode failure, for the cheat's own message
static char g_dec_why[48];

// One unsupported verdict: the reason in the cheat's row and in the log
#define UNSUP(c, what) { snprintf(g_dec_why, sizeof g_dec_why, "%s", what); snprintf((c)->why, sizeof (c)->why, "%s", what); (c)->broken = 1; return 0; }

// The instruction the way the run and the load verdict both see it: rel = the relative address, val = the static value /
// a loop's iterations / the key mask, s = the source register (A) or the compared register (C0), o = the offset type (A)
// or the operand type (C0)
typedef struct {
    unsigned op, len, t, m, r, x, c, flags, s, o;
    uint64_t rel, val;
} ins_t;

// Decode the instruction at p (n words left) or 0 when it cannot run here. Mirrors the reference's DecodeNextOpcode
// field by field, so the run never miscounts a word
static int ins_dec(cheat_t *c, const uint32_t *p, int n, ins_t *o) {
    unsigned t;
    if (n <= 0) UNSUP(c, "truncated");
    memset(o, 0, sizeof *o);
    uint32_t w = p[0];
    o->op = op_kind(w); o->len = 1;
    switch (o->op) {
        case 0: {   // 0TMR00AA AAAAAAAA VVVVVVVV: store the value at base + rel + reg[r] (the reference adds the offset
                    // register unconditionally: all registers start at 0)
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->m = (w >> 20) & 0xF;
            o->t = t; o->r = (w >> 16) & 0xF;
            if (n < 3) UNSUP(c, "truncated");
            o->rel = ((uint64_t)(w & 0xFF) << 32) | p[1];
            o->len = 3;
            if (o->t == 8) {   // a width-8 value comes in the reference's word order: first dword high, second low
                if (n < 4) UNSUP(c, "truncated");
                o->val = ((uint64_t)p[2] << 32) | p[3]; o->len = 4;
            } else o->val = p[2];
        } break;
        case 1: {   // 1TMCXrAA AAAAAAAA VVVVVVVV: compare memory at base + rel (+ reg[x] for X=1) with the value
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->m = (w >> 20) & 0xF;
            o->t = t; o->c = (w >> 16) & 0xF;
            if (o->c < 1 || o->c > 6) UNSUP(c, "unsupported condition");   // only the six comparisons run
            o->flags = ((w >> 12) & 0xF) != 0;                 // X, taken as a boolean like the reference does
            o->x = (w >> 8) & 0xF;
            if (n < 3) UNSUP(c, "truncated");
            o->rel = ((uint64_t)(w & 0xFF) << 32) | p[1];
            o->len = 3;
            if (o->t == 8) {
                if (n < 4) UNSUP(c, "truncated");
                o->val = ((uint64_t)p[2] << 32) | p[3]; o->len = 4;
            } else o->val = p[2];
        } break;
        case 2:     // 2X000000: end of a conditional, else for X = 1
            o->flags = ((w >> 24) & 0xF) == 1;
            break;
        case 3: {   // 300R0000 VVVVVVVV starts the loop (the counter set), 310R0000 ends it (the same r again)
            o->flags = ((w >> 24) & 0xF) != 0;
            o->r = (w >> 16) & 0xF;
            if (!o->flags) {
                if (n < 2) UNSUP(c, "truncated");
                o->val = p[1]; o->len = 2;
            }
        } break;
        case 4: {   // 400R0000 VVVVVVVV VVVVVVVV: reg[r] = the 64-bit value
            if (n < 3) UNSUP(c, "truncated");
            o->r = (w >> 16) & 0xF;
            o->val = ((uint64_t)p[1] << 32) | p[2]; o->len = 3;
        } break;
        case 5: {   // 5TMR00AA / 5T0R10AA / 5T0R2SAA / 5TMR3SAA AAAAAAAA: reg[r] = the width bytes of one address
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->t = t; o->m = (w >> 20) & 0xF; o->r = (w >> 16) & 0xF;
            o->flags = (w >> 12) & 0xF; o->x = (w >> 8) & 0xF;   // 0 fixed, 1 reg[r] + rel, 2 reg[x] + rel, 3 region + reg[x] + rel
            if (o->flags > 3) UNSUP(c, "unsupported operand");
            if (n < 2) UNSUP(c, "truncated");
            o->rel = ((uint64_t)(w & 0xFF) << 32) | p[1]; o->len = 2;
        } break;
        case 6: {   // 6T0RIor0 VVVVVVVV VVVVVVVV: the value at reg[r] (+ reg[x] for o=1), reg[r] + width for I=1
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            if (n < 3) UNSUP(c, "truncated");
            o->t = t; o->r = (w >> 16) & 0xF;
            o->flags = (((w >> 12) & 0xF) != 0) | (((w >> 8) & 0xF) != 0) << 1;   // 1 = increment reg[r], 2 = add reg[x]
            o->x = (w >> 4) & 0xF;
            o->val = ((uint64_t)p[1] << 32) | p[2]; o->len = 3;
        } break;
        case 7: {   // 7T0RC000 VVVVVVVV: reg[r] performs the width arithmetic with the value
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            if ((o->c = (w >> 12) & 0xF) > 4) UNSUP(c, "unsupported arithmetic");
            o->t = t; o->r = (w >> 16) & 0xF;
            if (n < 2) UNSUP(c, "truncated");
            o->val = p[1]; o->len = 2;
        } break;
        case 8:     // 8kkkkkkk: a 28-bit mask, every key of it held on any controller
            o->val = w & 0x0FFFFFFFu;
            break;
        case 9: {   // 9TCRSIs0 (VVVVVVVV (VVVVVVVV)): reg[r] = reg[s] op operand, the operand reg[x] or, for I != 0, an
                    // immediate of T bytes; C the math type (0..9 integer, 10..13 float)
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->t = t; o->c = (w >> 20) & 0xF; o->r = (w >> 16) & 0xF; o->s = (w >> 12) & 0xF;
            o->flags = ((w >> 8) & 0xF) != 0; o->x = (w >> 4) & 0xF;
            if (o->flags) {
                if (n < (t == 8 ? 3 : 2)) UNSUP(c, "truncated");
                o->val = t == 8 ? ((uint64_t)p[1] << 32) | p[2] : p[1] & width_mask(t);
                o->len = t == 8 ? 3 : 2;
            }
        } break;
        case 0xA: { // ATSRIOxa (aaaaaaaa): store reg[s] (width T) at an address from reg[r] and the offset type O:
                    // 0 reg[r], 1 reg[r] + reg[x], 2 reg[r] + a, 3 region x + reg[r], 4 region x + a, 5 region x + reg[r] + a
            if ((t = (w >> 24) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->t = t; o->s = (w >> 20) & 0xF; o->r = (w >> 16) & 0xF;
            o->flags = ((w >> 12) & 0xF) != 0; o->o = (w >> 8) & 0xF; o->x = (w >> 4) & 0xF;
            if (o->o > 5) UNSUP(c, "unsupported operand");
            if (o->o >= 3) o->m = o->x;
            if (o->o == 2 || o->o == 4 || o->o == 5) {
                if (n < 2) UNSUP(c, "truncated");
                o->rel = ((uint64_t)(w & 0xF) << 32) | p[1]; o->len = 2;
            }
        } break;
        case 0xC0: {   // C0TcSX..: compare reg[s] (width T) with the operand X under the condition c
            if ((t = (w >> 20) & 0xF, !width_ok(t))) UNSUP(c, "unsupported width");
            o->t = t; o->c = (w >> 16) & 0xF;
            if (o->c < 1 || o->c > 6) UNSUP(c, "unsupported condition");
            o->s = (w >> 12) & 0xF; o->o = (w >> 8) & 0xF;
            switch (o->o) {
                case 0: case 1:
                    o->m = (w >> 4) & 0xF;
                    if (o->o == 1) { o->x = w & 0xF; break; }
                    if (n < 2) UNSUP(c, "truncated");
                    o->rel = ((uint64_t)(w & 0xF) << 32) | p[1]; o->len = 2;
                    break;
                case 2:
                    o->r = (w >> 4) & 0xF;
                    if (n < 2) UNSUP(c, "truncated");
                    o->rel = ((uint64_t)(w & 0xF) << 32) | p[1]; o->len = 2;
                    break;
                case 3: o->r = (w >> 4) & 0xF; o->x = w & 0xF; break;
                case 4:
                    if (n < (t == 8 ? 3 : 2)) UNSUP(c, "truncated");
                    o->val = t == 8 ? ((uint64_t)p[1] << 32) | p[2] : p[1];
                    o->len = t == 8 ? 3 : 2;
                    break;
                case 5: o->x = (w >> 4) & 0xF; break;
                default: UNSUP(c, "unsupported operand");
            }
        } break;
        case 0xC1:  // C10D0Sx0: 0 reg[d] = saved[s], 1 saved[d] = reg[s], 2 saved[d] = 0, 3 reg[d] = 0
            o->r = (w >> 16) & 0xF; o->s = (w >> 8) & 0xF; o->c = (w >> 4) & 0xF;
            break;
        case 0xC2:  // C2x0XXXX: the C1 operation on every register whose bit of the 16-bit mask is set
            o->c = (w >> 20) & 0xF; o->val = w & 0xFFFF;
            break;
        case 0xC4: {   // C4r00000 kkkkkkkk kkkkkkkk: a 64-bit key mask (high word first); r != 0: auto-repeat
            if (n < 3) UNSUP(c, "truncated");
            o->flags = ((w >> 20) & 0xF) != 0;
            o->val = ((uint64_t)p[1] << 32) | p[2]; o->len = 3;
        } break;
        case 0xC3:  // C3000XXx: static register XX: 0x00..0x7F read into reg[x], 0x80..0xFF written from reg[x]
            o->x = (w >> 4) & 0xFF; o->r = w & 0xF;
            break;
        default:    // B, C5..CF, FF0 / FF1 / FFF (pause, resume, the debug log) and any unknown form
            snprintf(g_dec_why, sizeof g_dec_why, "unsupported opcode %X", o->op);
            UNSUP(c, g_dec_why);
    }
    if (o->len > (unsigned)n) UNSUP(c, "truncated");   // the cheat's words end in the middle of an instruction
    return (int)o->len;
}

// ---------- guest memory: what a cheat may reach ----------

// Where the guest range [addr, addr+len) lies:
//   WH_MAIN    main's mapped image: read directly (the loader's protections never change after load)
//   WH_HEAP    the opened heap: read and written through the kernel, so a SetHeapSize that shrinks the heap under the
//              copy fails the access instead of faulting
//   WH_MODULE  another module (an NSO of the profile, an NRO): read directly; only its data is written
//   WH_GUEST   any other page of the reserved guest block: the thread stacks, the alias region's virtual address
//              memory, the pages the game maps itself. The game maps and unmaps these while a pass runs, so the copies
//              go through the kernel (vm_read_overwrite / vm_write), which fail instead of faulting
//   -1         none of these (the host's own memory, a null pointer): refused without touching it
enum { WH_MAIN = 0, WH_HEAP = 1, WH_MODULE = 2, WH_GUEST = 3 };
typedef struct { int kind; module_t *mod; uint64_t base; } where_t;

static int where_get(uint64_t addr, uint64_t len, where_t *w) {
    w->kind = -1; w->mod = NULL; w->base = 0;
    if (!len || addr + len < addr) return -1;
    if (g_main) {
        uint64_t b = (uint64_t)(uintptr_t)g_main->base, off = addr - b;
        if (addr >= b && off < g_main->size && len <= g_main->size - off) { w->kind = WH_MAIN; w->mod = g_main; w->base = b; return 0; }
    }
    for (int i = 0; i < g_nmod; i++) {   // the other modules of the loader's table
        module_t *m = &g_mod[i];
        if (m == g_main || !m->base || !m->size) continue;
        uint64_t b = (uint64_t)(uintptr_t)m->base, off = addr - b;
        if (addr >= b && off < m->size && len <= m->size - off) { w->kind = WH_MODULE; w->mod = m; w->base = b; return 0; }
    }
    uint64_t hb; size_t hopen;
    dm_guest_heap_range(&hb, &hopen);
    if (hb && hopen && addr >= hb && addr - hb < hopen && len <= hopen - (addr - hb)) { w->kind = WH_HEAP; w->base = hb; return 0; }
    if (dm_guest_as_contains((uintptr_t)addr) && dm_guest_as_contains((uintptr_t)(addr + len - 1))) {
        uint8_t *as = NULL; size_t n = 0; svc_guest_range(&as, &n);
        w->kind = WH_GUEST; w->base = (uint64_t)(uintptr_t)as; return 0;
    }
    return -1;
}

// Read len bytes at a guest address into a zero-extended little-endian value: 0, or -1 when the range is not readable
// right now (where_get refused it, or the page went away)
static int mem_read(uint64_t addr, uint64_t len, uint64_t *out) {
    where_t w; uint64_t v = 0;
    if (where_get(addr, len, &w)) return -1;
    if (w.kind == WH_HEAP || w.kind == WH_GUEST) {
        // through the kernel: the heap's pages are protected again when the game shrinks its heap, and the game maps and
        // unmaps its own pages while a pass runs; a copy would fault on a page that went away in between
        vm_size_t got = 0;
        if (vm_read_overwrite(mach_task_self(), (vm_address_t)addr, (vm_size_t)len, (vm_address_t)&v, &got) != KERN_SUCCESS || got != len) return -1;
    } else memcpy(&v, (void *)(uintptr_t)addr, len);
    *out = v;
    return 0;
}

// The reason of a failed access, counted into the cheat's row: the first one of a cheat logs a line, the rest are counted
static void cheat_fail(cheat_t *c, const char *what, uint64_t addr, const char *why) {
    c->fails++; c->pass_fail = 1;
    if (c->fails == 1) LOG("[RED] cheat \"%s\": %s at guest address 0x%llx failed (%s): the cheat stays on and retries", c->name, what, (unsigned long long)addr, why);
}

// A write a cheat must not do: the host's own pages (the key page it keeps its thread keys in, the patch area with its
// own stubs) and the words the rewriter changed or hooked (writing there would break the game's own code in a way
// nobody can undo). Writing the game's code is allowed now: the host opens the write bit on the pages it needs and
// flushes the instruction cache (loader.c dm_module_write)
static int module_write_refused(cheat_t *c, where_t *w, uint64_t addr, uint64_t len, char *why, size_t cap) {
    module_t *m = w->mod;
    uint64_t off = addr - w->base;
    uint64_t kp0 = m->key_page_off, kp1 = kp0 + m->key_page_size;
    if (kp1 > kp0 && off < kp1 && off + len > kp0) { snprintf(why, cap, "it writes the host's key page"); return 1; }
    if (m->patch_off >= 0) {
        uint64_t po = (uint64_t)m->patch_off & ~0x3FFFull;   // the sealed patch area (the host's own stubs)
        if (off >= po && off < m->size) { snprintf(why, cap, "it writes the host's patch area"); return 1; }
    }
    if (dm_module_avoided(m, off, len)) { snprintf(why, cap, "it writes a word the rewriter changed (main+%#llx)", (unsigned long long)off); return 1; }
    (void)c;
    return 0;
}

// A write into guest memory that does not take the host down when the page is not writable. On Android the kernel does
// the copy (process_vm_writev) and reports the failure for a page that is not writable; on the desktop host the mach call
// cannot write into its own task, so the copy is done directly there (the engine refuses the ranges the loader sealed, so
// the copy never meets a read-only page of a module)
static int guest_write_bytes(uint64_t addr, const void *src, size_t len) {
#if defined(__APPLE__)
    memcpy((void *)(uintptr_t)addr, src, len);
    return 0;
#else
    return vm_write(mach_task_self(), (vm_address_t)addr, (vm_size_t)len, (vm_offset_t)(uintptr_t)src) == KERN_SUCCESS ? 0 : -1;
#endif
}

// dmnt's WriteCheatProcessMemoryUnsafe for this host: the value's low len bytes, little-endian (the guest is aarch64
// as the host is), into the page where_get finds. A range that is not writable (unmapped, the game's sealed code) is a
// failed access: nothing is written, the cheat goes on and retries (as on a Switch)
static void cheat_write(cheat_t *c, uint64_t addr, uint64_t len, uint64_t val) {
    where_t w; char why[96];
    if (where_get(addr, len, &w)) { cheat_fail(c, "write", addr, "not mapped"); return; }
    if ((w.kind == WH_MAIN || w.kind == WH_MODULE) && module_write_refused(c, &w, addr, len, why, sizeof why)) {
        // the game's read-only area: the cheat cannot run here, so it is turned off with the reason (the app shows it)
        // and the switch is dropped from the toggles file: a cheat that cannot run must not come back on every launch
        c->broken = 1; c->on = 0;
        snprintf(c->why, sizeof c->why, "%s", why);
        LOG("[RED] cheat \"%s\": %s: the cheat is turned off", c->name, why);
        states_write();
        return;
    }
    // The value is already there: nothing to write. A cheat that keeps a value up to date would otherwise rewrite it
    // every frame, and a write into the game's code costs two page protections and an instruction cache flush
    uint64_t cur = 0;
    if (mem_read(addr, len, &cur) == 0 && cur == (val & width_mask(len))) return;
    dm_guest_write_prepare((void *)(uintptr_t)addr, (size_t)len);   // the graphics layer's dirty tracking
    int bad;
    if (w.kind == WH_MAIN || w.kind == WH_MODULE) bad = !dm_module_write(w.mod, addr - w.base, &val, (size_t)len);
    else bad = guest_write_bytes(addr, &val, (size_t)len) != 0;
    if (bad) cheat_fail(c, "write", addr, "the page is not writable");
    else if (g_debug && c->dbg_logged < 8) {   // DM_CHEATS_DEBUG: what the cheat wrote, the first few times only
        c->dbg_logged++;
        LOG("cheat \"%s\": wrote %llu byte%s at guest address 0x%llx: 0x%llx", c->name, (unsigned long long)len,
            len == 1 ? "" : "s", (unsigned long long)addr, (unsigned long long)val);
    }
}

// dmnt's ReadCheatProcessMemoryUnsafe for this host: the value is assembled zero-extended little-endian; on a failure
// the cheat's destination keeps what it had (opcode 5 keeps the register, 1 / C0 compare against the 0 they started from)
static int cheat_read(cheat_t *c, uint64_t addr, uint64_t len, uint64_t *out) {
    if (mem_read(addr, len, out) == 0) return 0;
    cheat_fail(c, "read", addr, "not mapped");
    return -1;
}

// ---------- the cheat VM ----------

static uint64_t g_reg[16];     // the reference's registers: reset once per pass, shared by the pass's cheats
static uint64_t g_saved[16];   // the reference's saved values (C1 / C2): reset once per pass with the registers
static uint64_t g_held;        // the keys held, read once at the start of the pass
static uint64_t g_keyold;      // the keys held at the end of the last pass that ran a cheat (the reference's s_keyold)
static uint64_t g_looptop[16]; // the reference's loop tops, word indexes into the program that set them
static uint64_t g_static[256]; // the reference's static registers (C3): kept across passes, cleared when the file is loaded

// Region base for the memory-type nybble m, the reference's GetCheatProcessAddress table: 0 main NSO, 1 heap, 2 alias,
// 3 aslr, 4 non-relative (the offset is an absolute address), 5..F main (the reference's default case)
static uint64_t region_base(int m) {
    uint64_t hb; size_t hopen;
    switch (m) {
        case 1: dm_guest_heap_range(&hb, &hopen); return hb && hopen ? hb : 0;
        case 2: { uint8_t *ab = NULL; size_t an = 0; dm_guest_alias_range(&ab, &an); return (uint64_t)(uintptr_t)ab; }
        case 3: { uint8_t *as = NULL; size_t n = 0; svc_guest_range(&as, &n); return (uint64_t)(uintptr_t)as; }
        case 4: return 0;
        default: return g_main ? (uint64_t)(uintptr_t)g_main->base : 0;
    }
}

// The named region must have its base right now: a cheat that names the heap before the game's first SetHeapSize would
// compute its addresses from base 0, so its pass stops here and it waits (logged once)
static int region_ready(cheat_t *c, int m) {
    if (m == 1) {
        uint64_t hb; size_t hopen;
        dm_guest_heap_range(&hb, &hopen);
        if (hb && hopen) return 1;
        if (!c->heap_wait) { c->heap_wait = 1; LOG("cheat \"%s\": waits for the game's heap (region 1 has no span opened yet)", c->name); }
        return 0;
    }
    if (m == 2) {
        uint8_t *ab = NULL; size_t an = 0;
        dm_guest_alias_range(&ab, &an);
        if (!ab) return 0;   // the block is not reserved yet: wait, silently
        uint8_t *as = NULL; size_t n = 0; svc_guest_range(&as, &n);
        if (as && (uint64_t)(uintptr_t)ab == (uint64_t)(uintptr_t)as + 0x10000000ull) return 1;
        LOG("[RED] cheat \"%s\": region 2 (alias) refused: its base 0x%llx is not the guest block 0x%llx + 256 MB", c->name,
            (unsigned long long)(uintptr_t)ab, (unsigned long long)(uintptr_t)as);
        return 0;
    }
    if (m == 3) return region_base(3) != 0;
    if (m == 4) return 1;
    return g_main != NULL;
}

// Skipping a failed or finished conditional the reference's way: walk the nested begins and ends, and a not-taken if
// also meets its else at the same depth as the skip's end (the else block is skipped too)
static int vm_skip(cheat_t *c, const uint32_t *ins, int nins, int *pidx, int *pdepth, int is_if) {
    int desired = *pdepth - 1;
    while (*pdepth > desired && *pidx < nins) {
        ins_t in;
        int len = ins_dec(c, ins + *pidx, nins - *pidx, &in);
        if (len <= 0) return -1;
        if (op_cond_begin(in.op)) (*pdepth)++;
        else if (in.op == 2) {
            if (!in.flags) (*pdepth)--;
            else if (is_if && *pdepth - 1 == desired) { *pidx += len; return 0; }   // an else of a not-taken if
        }
        *pidx += len;
    }
    return 0;
}

// The float operations of opcode 9 (c = 10 add, 11 sub, 12 mul, 13 div) on the bit patterns of the two operands: T = 4
// single precision on the low 32 bits, T = 8 double, any other width 0
static uint64_t float_math(unsigned c, unsigned t, uint64_t a, uint64_t b) {
    if (t == 4) {
        uint32_t ua = (uint32_t)a, ub = (uint32_t)b, ur; float x, y, r;
        memcpy(&x, &ua, 4); memcpy(&y, &ub, 4);
        r = c == 10 ? x + y : c == 11 ? x - y : c == 12 ? x * y : x / y;
        memcpy(&ur, &r, 4);
        return ur;
    }
    if (t == 8) {
        uint64_t ur; double x, y, r;
        memcpy(&x, &a, 8); memcpy(&y, &b, 8);
        r = c == 10 ? x + y : c == 11 ? x - y : c == 12 ? x * y : x / y;
        memcpy(&ur, &r, 8);
        return ur;
    }
    return 0;
}

// The six comparisons in the reference's number order (opcodes 1 and C0, both unsigned): 1 >, 2 >=, 3 <, 4 <=, 5 ==, 6 !=
static int cond_met(unsigned cond, uint64_t got, uint64_t want) {
    return cond == 1 ? got > want : cond == 2 ? got >= want : cond == 3 ? got < want :
           cond == 4 ? got <= want : cond == 5 ? got == want : got != want;
}

// One pass of the cheat c; the VM state is whatever the caller reset. Returns -1 when the cheat killed itself
static int vm_run(cheat_t *c) {
    const uint32_t *ins = c->ins; int nins = c->nins;
    int idx = 0, depth = 0;   // like the reference's Execute: one instruction pointer, one conditional depth
    long steps = 0;
    memset(g_looptop, 0, sizeof g_looptop);   // the loop tops index this cheat's own words
    while (idx < nins) {
        if (++steps > VM_STEP_CAP) { snprintf(c->why, sizeof c->why, "over %d instructions in one pass", VM_STEP_CAP); c->broken = 1; c->on = 0; return -1; }
        ins_t in;
        int len = ins_dec(c, ins + idx, nins - idx, &in);
        if (len <= 0) { c->broken = 1; c->on = 0; return -1; }
        switch (in.op) {
            case 0:   // static store: base + rel + reg[r]
                if (!region_ready(c, in.m)) return -1;
                cheat_write(c, region_base(in.m) + in.rel + g_reg[in.r], in.t, in.val & width_mask(in.t));
                break;
            case 8: case 0xC4: case 1: {   // the key-press conditionals (8, C4) and the memory conditional (1)
                depth++;
                int met = 1;
                if (in.op == 8) met = (g_held & in.val) == in.val;
                else if (in.op == 0xC4) {
                    // every key of the 64-bit mask held now; without auto-repeat only if the mask was not fully held at
                    // the end of the last pass (the reference's one s_keyold, which no C4 of the pass changes)
                    int now = (g_held & in.val) == in.val;
                    met = in.flags ? now : now && (g_keyold & in.val) != in.val;
                } else {
                    if (!region_ready(c, in.m)) return -1;
                    uint64_t src = 0;   // the reference reads into a zeroed value: a failed read compares against 0
                    cheat_read(c, region_base(in.m) + in.rel + (in.flags ? g_reg[in.x] : 0), in.t, &src);
                    met = cond_met(in.c, src & width_mask(in.t), in.val & width_mask(in.t));
                }
                if (!met) { idx += len; if (vm_skip(c, ins, nins, &idx, &depth, 1)) return -1; continue; }
            } break;
            case 0xC0: {   // register conditional: reg[s] against memory, a static value or another register
                depth++;
                uint64_t want = 0, got = g_reg[in.s] & width_mask(in.t);
                switch (in.o) {
                    case 0: if (!region_ready(c, in.m)) return -1; cheat_read(c, region_base(in.m) + in.rel, in.t, &want); break;
                    case 1: if (!region_ready(c, in.m)) return -1; cheat_read(c, region_base(in.m) + g_reg[in.x], in.t, &want); break;
                    case 2: cheat_read(c, g_reg[in.r] + in.rel, in.t, &want); break;
                    case 3: cheat_read(c, g_reg[in.r] + g_reg[in.x], in.t, &want); break;
                    case 4: want = in.val; break;
                    default: want = g_reg[in.x]; break;
                }
                if (!cond_met(in.c, got, want & width_mask(in.t))) { idx += len; if (vm_skip(c, ins, nins, &idx, &depth, 1)) return -1; continue; }
            } break;
            case 2:   // end / else of a conditional
                if (in.flags) {   // an else skips its block; an else at depth 0 is mismatched: the reference aborts the
                                  // whole of dmnt there, which no host can copy, so that one cheat is turned off
                    if (depth == 0) { snprintf(c->why, sizeof c->why, "mismatched else"); c->broken = 1; c->on = 0; return -1; }
                    idx += len; if (vm_skip(c, ins, nins, &idx, &depth, 0)) return -1; continue;
                }
                if (depth > 0) depth--;   // a plain end (a no-op at depth 0, the reference's own gracious assumption)
                break;
            case 3:   // loop: start (the counter set, the jump-back point kept), end (decrement, jump back until it reaches 0)
                if (!in.flags) { g_reg[in.r] = in.val; g_looptop[in.r] = idx + 2; idx += (int)in.len; continue; }
                g_reg[in.r]--;
                if (g_reg[in.r]) { idx = (int)g_looptop[in.r]; continue; }
                break;
            case 4:   // register = the static 64-bit value
                g_reg[in.r] = in.val;
                break;
            case 5: {   // register = the width bytes of one address (fixed, by register, or region + register)
                uint64_t addr, v0 = 0;
                switch (in.flags) {
                    case 0: if (!region_ready(c, in.m)) return -1; addr = region_base(in.m) + in.rel; break;
                    case 1: addr = g_reg[in.r] + in.rel; break;
                    case 2: addr = g_reg[in.x] + in.rel; break;
                    default: if (!region_ready(c, in.m)) return -1; addr = region_base(in.m) + g_reg[in.x] + in.rel; break;
                }
                // the reference reads the T bytes straight into the register's storage: a load narrower than 8 bytes
                // replaces the low T bytes and keeps the upper ones, it does not zero-extend
                if (cheat_read(c, addr, in.t, &v0) == 0) g_reg[in.r] = (g_reg[in.r] & ~width_mask(in.t)) | (v0 & width_mask(in.t));
            } break;
            case 6: {   // store the value at reg[r] (+ reg[x] for o=1); reg[r] gains the width for I=1
                cheat_write(c, g_reg[in.r] + (in.flags & 2 ? g_reg[in.x] : 0), in.t, in.val & width_mask(in.t));
                if (in.flags & 1) g_reg[in.r] += in.t;
            } break;
            case 7: {   // register arithmetic the reference's order: the full dword goes into the operation and only the
                        // result is truncated to the width; the shift counts are masked to 63 bits
                uint64_t v = in.val, r = g_reg[in.r];
                switch (in.c) {
                    case 0: r += v; break;
                    case 1: r -= v; break;
                    case 2: r *= v; break;
                    case 3: r <<= (v & 63); break;
                    case 4: r >>= (v & 63); break;
                }
                g_reg[in.r] = r & width_mask(in.t);
            } break;
            case 9: {   // register arithmetic: reg[r] = reg[s] op (reg[x] or the immediate); an unknown math type stores 0
                uint64_t a = g_reg[in.s], b = in.flags ? in.val : g_reg[in.x], r;
                switch (in.c) {
                    case 0: r = a + b; break;
                    case 1: r = a - b; break;
                    case 2: r = a * b; break;
                    case 3: r = a << (b & 63); break;
                    case 4: r = a >> (b & 63); break;
                    case 5: r = a & b; break;
                    case 6: r = a | b; break;
                    case 7: r = ~a; break;
                    case 8: r = a ^ b; break;
                    case 9: r = a; break;
                    case 10: case 11: case 12: case 13: r = float_math(in.c, in.t, a, b); break;
                    default: r = 0; break;
                }
                g_reg[in.r] = r & width_mask(in.t);
            } break;
            case 0xC1:   // save / restore one register
                switch (in.c) {
                    case 1: g_saved[in.r] = g_reg[in.s]; break;
                    case 2: g_saved[in.r] = 0; break;
                    case 3: g_reg[in.r] = 0; break;
                    default: g_reg[in.r] = g_saved[in.s]; break;
                }
                break;
            case 0xC2:   // the same operation on every register of the 16-bit mask
                for (unsigned i = 0; i < 16; i++) {
                    if (!(in.val & (1u << i))) continue;
                    if (in.c == 1) g_saved[i] = g_reg[i]; else if (in.c == 2) g_saved[i] = 0; else if (in.c == 3) g_reg[i] = 0; else g_reg[i] = g_saved[i];
                }
                break;
            case 0xA: {   // store register: reg[s] (width) at the address of the offset type, reg[r] += width for I=1
                uint64_t addr;
                switch (in.o) {
                    case 0: addr = g_reg[in.r]; break;
                    case 1: addr = g_reg[in.r] + g_reg[in.x]; break;
                    case 2: addr = g_reg[in.r] + in.rel; break;
                    case 3: if (!region_ready(c, in.m)) return -1; addr = region_base(in.m) + g_reg[in.r]; break;
                    case 4: if (!region_ready(c, in.m)) return -1; addr = region_base(in.m) + in.rel; break;
                    default: if (!region_ready(c, in.m)) return -1; addr = region_base(in.m) + g_reg[in.r] + in.rel; break;
                }
                cheat_write(c, addr, in.t, g_reg[in.s] & width_mask(in.t));
                if (in.flags) g_reg[in.r] += in.t;
            } break;
            case 0xC3:   // static registers: 0x00..0x7F read into a register, 0x80..0xFF written from one
                if (in.x < 0x80) g_reg[in.r] = g_static[in.x]; else g_static[in.x] = g_reg[in.r];
                break;
            default:
                c->broken = 1; c->on = 0;
                return -1;
        }
        idx += len;
    }
    return 0;
}

// ---------- the file ----------

// The register an instruction writes, or -1 when it writes none
static int ins_writes_reg(const ins_t *in) {
    switch (in->op) {
        case 4: case 5: case 7: case 9: case 0xC3: return (int)in->r;
        case 0xC1: return (in->c == 0 || in->c == 3) ? (int)in->r : -1;   // the two forms that put a value in a register
        case 0xC2: return -2;   // every register of the mask
        default: return -1;
    }
}

// The registers any cheat of the file writes: a register one of them writes is not 0 at the start of a pass (the
// registers are shared by the cheats of a pass), so a store that adds such a register to its address cannot be judged
static void file_dirty_regs(uint8_t *dirty) {
    memset(dirty, 0, 16);
    for (int i = 0; i < g_ncheat; i++) {
        cheat_t *c = &g_cheat[i];
        if (c->broken || c->label) continue;
        for (int a = 0; a < c->nins;) {
            ins_t in;
            int len = ins_dec(c, c->ins + a, c->nins - a, &in);
            if (len <= 0) break;
            int w = ins_writes_reg(&in);
            if (w == -2) memset(dirty, 1, 16);
            else if (w >= 0 && w < 16) dirty[w] = 1;
            a += len;
        }
    }
}

// A static look at a cheat's words: a store into a module's sealed area is refused before the cheat ever runs, so the
// player sees why it cannot run instead of turning it on and having it switch itself off a frame later. Only the stores
// whose address is known statically are judged (a register set by an earlier word counts when it was set by a constant);
// the runtime check still judges everything else, so this only ever marks a cheat that would be refused anyway
static void static_check(cheat_t *c, const uint8_t *dirty) {
    if (c->broken || !g_main) return;
    uint64_t reg[16]; uint8_t known[16];
    for (int i = 0; i < 16; i++) { reg[i] = 0; known[i] = dirty[i] ? 0 : 1; }   // a register nobody writes is 0
    for (int i = 0; i < c->nins;) {
        ins_t in;
        int len = ins_dec(c, c->ins + i, c->nins - i, &in);
        if (len <= 0) return;   // the decoder already marked the cheat
        switch (in.op) {
            case 4: reg[in.r] = in.val; known[in.r] = 1; break;                      // reg[r] = a constant
            case 9: if (in.c == 9 && known[in.s]) { reg[in.r] = reg[in.s]; known[in.r] = 1; }   // reg[r] = reg[s]
                    else known[in.r] = 0;
                    break;
            case 7: known[in.r] = 0; break;                                          // arithmetic: the result is not tracked
            case 5: known[in.r] = 0; break;                                          // a load from memory: not tracked
            case 0: {   // a store at base + rel + reg[r]
                if (!known[in.r]) break;
                if (in.m == 1 || in.m == 2 || in.m == 3 || in.m == 4) break;   // the heap, the alias region, the aslr region, an absolute address: not the module's code
                uint64_t base = (uint64_t)(uintptr_t)g_main->base;
                uint64_t off = region_base(in.m) + in.rel + reg[in.r] - base;
                if (dm_module_avoided(g_main, off, in.t)) {
                    c->broken = 1;
                    snprintf(c->why, sizeof c->why, "it writes a word the rewriter changed (main+%#llx)", (unsigned long long)off);
                    LOG("[RED] cheat \"%s\": %s: it cannot run in this version", c->name, c->why);
                    return;
                }
            } break;
            default: break;
        }
        i += len;
    }
}

// The file's text, with a header that follows a whole code word on the same line split off (a sloppy site file):
// "00000008[Next]" is two entries, not one
static char *read_text(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    char *raw = malloc(FILE_MAX + 2);
    size_t n = raw ? fread(raw, 1, FILE_MAX + 1, f) : 0;
    fclose(f);
    if (!raw) return NULL;
    if (n > FILE_MAX) { LOG("[RED] cheats: %s is larger than %u bytes: not a cheat file", path, FILE_MAX); free(raw); errno = EFBIG; return NULL; }
    raw[n] = 0;
    char *out = malloc(2 * n + 1);
    if (!out) { free(raw); return NULL; }
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        if ((raw[i] == '[' || raw[i] == '{') && i >= 8) {   // a header right after a whole code word
            int word = 1;
            for (size_t k = i - 8; k < i && word; k++) word = isxdigit((unsigned char)raw[k]) != 0;
            if (word && (i == 8 || raw[i - 9] == ' ' || raw[i - 9] == '\t' || raw[i - 9] == '\n' || raw[i - 9] == '\r')) out[o++] = '\v';
        }
        out[o++] = raw[i];
    }
    out[o] = 0;
    free(raw);
    return out;
}

// A new entry: the header's name, and the words that follow it
static cheat_t *cheat_new(const char *name, int master) {
    if (g_ncheat >= CHEAT_MAX) return NULL;
    cheat_t *c = &g_cheat[g_ncheat++];
    memset(c, 0, sizeof *c);
    snprintf(c->name, sizeof c->name, "%s", name);
    c->master = (uint8_t)master;
    c->on = (uint8_t)master;
    return c;
}

// Parse the file the way dmnt reads it, with the tolerance a community file needs
static void parse_file(const char *path) {
    char *text = read_text(path);
    if (!text) { LOG("[RED] cheats: cannot read %s (errno %d)", path, errno); return; }
    cheat_t *cur = NULL;
    int lineno = 1, warned = 0;
    static uint32_t got[INS_MAX];   // one line's words
    for (char *line = text, *next; line; line = next) {
        char *e = line + strcspn(line, "\n\v");   // one piece: a file line, or part of one when a glued header was split off
        char sep = *e;
        next = sep ? e + 1 : NULL;
        *e = 0;
        int ln = lineno;
        if (sep == '\n') lineno++;
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        char end = 0;
        if (*p == '[') end = ']';
        else if (*p == '{') end = '}';
        if (end) {   // a header starts a new cheat; the name is what lies inside
            char *q = strchr(p, end);
            size_t nl = q && q > p ? (size_t)(q - p - 1) : 0;
            if (nl == 0 || g_ncheat >= CHEAT_MAX) {
                if (g_ncheat >= CHEAT_MAX && !warned++) LOG("[RED] cheats: more than %d cheats in %s, the rest is skipped", CHEAT_MAX, path);
                cur = NULL;
                continue;
            }
            char name[CHEAT_NAME_MAX];
            int keep = (int)(nl > CHEAT_NAME_MAX - 1 ? CHEAT_NAME_MAX - 1 : nl);
            if (nl > (size_t)keep) while (keep > 0 && ((unsigned char)p[1 + keep] & 0xC0) == 0x80) keep--;   // a cut name ends on a character boundary
            snprintf(name, sizeof name, "%.*s", keep, p + 1);
            cur = cheat_new(name, end == '}');
            if (!cur) continue;
            p = q + 1;                 // dmnt reads on after the header: code words may follow on the same line
            while (*p == ' ' || *p == '\t' || *p == '\r') p++;
            if (!*p) continue;
        }
        if (!cur) continue;   // words before any header belong to nothing: the line is ignored (site files carry prose)
        // The words of the line: dmnt accumulates hex digits and cuts a word every eight, whitespace only separating, so a
        // token of hex digits whose length is a multiple of eight is that many words (glued ones too). A token of hex
        // digits of any other length is not code on a Switch either, and here makes the line malformed: a seven-digit word
        // is a typo, never prose. A token with a non-hex character is prose: alone it makes the line one to ignore, beside
        // words it makes the line malformed
        int ngot = 0, prose = 0, oddhex = 0;
        const char *badt = "";
        for (char *s2 = NULL, *t = strtok_r(p, " \t\r", &s2); t; t = strtok_r(NULL, " \t\r", &s2)) {
            size_t L = strlen(t), h = 0;
            while (h < L && isxdigit((unsigned char)t[h])) h++;
            if (h < L) { prose = 1; if (!badt[0]) badt = t; continue; }
            if (L % 8) { oddhex = 1; badt = t; continue; }
            for (size_t k = 0; k < L; k += 8) {
                uint32_t w = 0;
                for (size_t j = k; j < k + 8; j++) {
                    char a = (char)tolower((unsigned char)t[j]);
                    w = w << 4 | (a >= '0' && a <= '9' ? (unsigned)(a - '0') : (unsigned)(a - 'a' + 10));
                }
                if (ngot < INS_MAX) got[ngot++] = w;
                else { LOG("[RED] cheat \"%s\": line %d has more than %d code words, the rest of the line is ignored", cur->name, ln, INS_MAX); break; }
            }
        }
        if (!ngot && !prose && !oddhex) continue;   // only whitespace
        if (prose && !ngot && !oddhex) {   // prose: the line is ignored, once warned
            if (!warned++) LOG("[RED] cheats: %s line %d is not code words and is ignored", path, ln);
            continue;
        }
        if (prose || oddhex) {
            // words would be dropped from a line that held them (or a hex token is not whole words): the dropped token may
            // carry an instruction of its own (a vanished begin or end leaves the body running every frame), so the cheat
            // stays off entirely instead of silently running a fragment of itself
            if (!cur->broken) {
                cur->broken = 1; cur->on = 0;
                snprintf(cur->why, sizeof cur->why, "malformed line %d", ln);
                LOG("[RED] cheats: %s: \"%s\" line %d %s \"%s\", the cheat stays off", path, cur->name, ln,
                    oddhex ? "holds hex digits that are not whole words:" : "mixes code words with", badt);
            }
            continue;
        }
        if (cur->nins + ngot > INS_MAX) { LOG("[RED] cheat \"%s\": over %d code words, skipped", cur->name, INS_MAX); continue; }
        if (!cur->ins) {
            cur->ins = malloc(sizeof(uint32_t) * INS_MAX);
            if (!cur->ins) { LOG("[RED] cheats: out of memory for the cheat file"); break; }
        }
        memcpy(cur->ins + cur->nins, got, sizeof(uint32_t) * (size_t)ngot);
        cur->nins += ngot;
    }
    free(text);
    // The verdict per cheat: no code words at all is a label (a credits line or a section marker), never a cheat; anything
    // else is decoded word by word, and one instruction the VM does not run turns the whole cheat off, with the reason
    for (int i = 0; i < g_ncheat; i++) {
        cheat_t *c = &g_cheat[i];
        if (c->broken) continue;
        if (c->nins == 0) { c->label = 1; c->on = 0; continue; }
        for (int a = 0; a < c->nins;) {
            ins_t in;
            int len = ins_dec(c, c->ins + a, c->nins - a, &in);
            if (len <= 0) break;   // the reason is already in c->why and c->broken is set
            a += len;
        }
    }
}

// ---------- the toggles: <build ID>.enabled beside the file ----------

// The name of a cheat in the toggles file: a duplicate name's later entries are "Name #2", "Name #3", ...
static void cheat_key(const cheat_t *c, char *out, size_t n) {
    int dup = 0;
    for (int i = 0; i < g_ncheat; i++) if (&g_cheat[i] < c && !strcmp(g_cheat[i].name, c->name)) dup++;
    if (dup) snprintf(out, n, "%s #%d", c->name, dup + 1);
    else snprintf(out, n, "%s", c->name);
}

// Read the toggles file: the name of each cheat that is on, one a line
static void states_read(void) {
    char path[1200];
    snprintf(path, sizeof path, "%s/%s.enabled", g_dir, g_build);
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof line, f)) {
        size_t n = strlen(line);
        while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        if (!n) continue;
        for (int i = 0; i < g_ncheat; i++) {
            char key[CHEAT_NAME_MAX];
            cheat_key(&g_cheat[i], key, sizeof key);
            if (!strcmp(key, line)) g_cheat[i].on = 1;
        }
    }
    fclose(f);
}

// Write the toggles file: the name of each cheat that is on, one a line
static void states_write(void) {
    if (!g_dir[0] || !g_build[0]) return;
    char path[1200];
    snprintf(path, sizeof path, "%s/%s.enabled", g_dir, g_build);
    FILE *f = fopen(path, "w");
    if (!f) { LOG("[RED] cheats: cannot write %s (errno %d)", path, errno); return; }
    for (int i = 0; i < g_ncheat; i++) {
        if (!g_cheat[i].on || g_cheat[i].label) continue;
        char key[CHEAT_NAME_MAX];
        cheat_key(&g_cheat[i], key, sizeof key);
        fprintf(f, "%s\n", key);
    }
    fclose(f);
}

// ---------- the pass ----------

// One pass: the enabled cheats in file order, the masters first. The registers and the saved values are reset once per
// pass and shared between the cheats, as the reference's ResetState over its one concatenated program has them; each
// cheat is its own program, so its conditional depth and its loop tops start fresh
static void cheats_pass(void) {
    pthread_mutex_lock(&g_lock);
    if (!g_ncheat) { pthread_mutex_unlock(&g_lock); return; }
    g_held = dm_cheats_keys();
    memset(g_reg, 0, sizeof g_reg);
    memset(g_saved, 0, sizeof g_saved);
    for (int i = 0; i < g_ncheat; i++) {
        cheat_t *c = &g_cheat[i];
        if (c->label || c->broken || !c->on || !c->nins) continue;
        c->pass_fail = 0;
        if (g_debug && !c->dbg_run_logged) { c->dbg_run_logged = 1; LOG("cheat \"%s\": running (%d words)", c->name, c->nins); }
        vm_run(c);
    }
    g_keyold = g_held;   // the reference's s_keyold: the keys at the end of the last pass that ran a cheat
    g_passes++;
    if (g_debug && g_passes % 300 == 0) LOG("cheats: %llu passes", (unsigned long long)g_passes);
    pthread_mutex_unlock(&g_lock);
}

// The host's per-frame hook: the game presents a frame (hle_nvn.c nvnQueuePresentTexture), and the cheats run once
void dm_cheats_present(void) {
    if (!g_ncheat) return;
    cheats_pass();
}

// ---------- loading ----------

// The file of this build in the folder: <build ID>.txt, the name Atmosphère gives it
static char *find_file(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return NULL;
    struct dirent *e;
    char path[1200];
    char *found = NULL;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n < 5 || strcasecmp(e->d_name + n - 4, ".txt")) continue;
        char stem[64];
        snprintf(stem, sizeof stem, "%.*s", (int)(n - 4 > sizeof stem - 1 ? sizeof stem - 1 : n - 4), e->d_name);
        if (!strcasecmp(stem, g_build)) { snprintf(path, sizeof path, "%s/%s", dir, e->d_name); found = strdup(path); break; }
    }
    closedir(d);
    return found;
}

// The build ID as the cheat files name it: the first 16 hex digits of main's build ID, upper case ("" when the build
// is not known)
static void build16(const char *build, char out[17]) {
    if (strlen(build) < 16) { out[0] = 0; return; }
    for (int i = 0; i < 16; i++) out[i] = (char)toupper((unsigned char)build[i]);
    out[16] = 0;
}

// Load the cheats of the running build from the folder: the file whose name is the build ID, its entries parsed and the
// toggles read from <build ID>.enabled beside it. Returns the number of entries (0 when the folder holds no file of this
// build: another build's cheats would write the wrong places, so none of them is loaded)
int dm_cheats_open(const char *dir) {
    pthread_mutex_lock(&g_lock);
    if (g_cheat) {   // a file is loaded: drop it
        for (int i = 0; i < g_ncheat; i++) free(g_cheat[i].ins);
        free(g_cheat); g_cheat = NULL; g_ncheat = 0;
    }
    memset(g_static, 0, sizeof g_static);
    g_keyold = 0;
    g_dir[0] = 0; g_build[0] = 0;
    g_main = NULL;
    for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main")) g_main = &g_mod[i];
    if (!g_main) { pthread_mutex_unlock(&g_lock); return 0; }
    const char *build = dm_main_build();
    build16(build, g_build);
    if (!g_build[0]) { pthread_mutex_unlock(&g_lock); return 0; }
    if (!dir || !dir[0]) { pthread_mutex_unlock(&g_lock); return 0; }
    snprintf(g_dir, sizeof g_dir, "%s", dir);
    char *file = find_file(g_dir);
    if (!file) { pthread_mutex_unlock(&g_lock); return 0; }
    g_cheat = calloc(CHEAT_MAX, sizeof *g_cheat);
    if (!g_cheat) { free(file); pthread_mutex_unlock(&g_lock); return 0; }
    parse_file(file);
    free(file);
    {   // the ones that would write the game's code are marked before they ever run
        uint8_t dirty[16];
        file_dirty_regs(dirty);
        for (int i = 0; i < g_ncheat; i++) static_check(&g_cheat[i], dirty);
    }
    states_read();
    int n = g_ncheat, real = 0;
    for (int i = 0; i < n; i++) if (!g_cheat[i].label) real++;
    LOG("cheats: %s loaded: %d entries (%d cheats, %d labels)", g_build, n, real, n - real);
    pthread_mutex_unlock(&g_lock);
    return real;
}

// Drop the loaded cheats
void dm_cheats_close(void) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_ncheat; i++) free(g_cheat[i].ins);
    free(g_cheat); g_cheat = NULL; g_ncheat = 0;
    g_dir[0] = 0; g_build[0] = 0;
    pthread_mutex_unlock(&g_lock);
}

// The build ID the cheat files are named by: the first 16 hex digits of main's build ID, upper case ("" when the
// running build is not known). The app asks the host for it: the community library holds one file per build, and the
// app needs to know which one belongs to the game it runs.
const char *dm_cheats_build(void) {
    static char build[17];
    pthread_mutex_lock(&g_lock);
    build16(dm_main_build(), build);
    pthread_mutex_unlock(&g_lock);
    return build;
}

// The host's start hook (main.c): the folder the app named (DM_CHEATS), if any
void dm_cheats_init(void) {
    const char *dbg = getenv("DM_CHEATS_DEBUG");
    g_debug = dbg && atoi(dbg) > 0;
    const char *dir = getenv("DM_CHEATS");
    if (!dir || !dir[0]) return;
    dm_cheats_open(dir);
}

// ---------- the host's interface for the app ----------

// The entries the app shows: the cheats (not the labels), in file order
int dm_cheats_count(void) {
    pthread_mutex_lock(&g_lock);
    int n = 0;
    for (int i = 0; i < g_ncheat; i++) if (!g_cheat[i].label) n++;
    pthread_mutex_unlock(&g_lock);
    return n;
}

// The i-th cheat (the labels are not cheats and are not listed), or NULL
static cheat_t *cheat_at(int i) {
    for (int j = 0, k = 0; j < g_ncheat; j++) {
        if (g_cheat[j].label) continue;
        if (k++ == i) return &g_cheat[j];
    }
    return NULL;
}

// The i-th cheat's row: its name, whether it is on, and why it cannot run ("" when it can). The strings belong to the host
// and stay valid until the cheats are closed: the caller copies them out at once
int dm_cheats_row(int i, const char **name, int *on, const char **why) {
    pthread_mutex_lock(&g_lock);
    cheat_t *c = cheat_at(i);
    if (c) { *name = c->name; *on = c->on; *why = c->why; }
    pthread_mutex_unlock(&g_lock);
    return c != NULL;
}

// Turn a cheat on or off: a cheat that cannot run (an unsupported opcode, a malformed line, a write into the game's code)
// is refused. The toggles are remembered in <build ID>.enabled beside the file
int dm_cheats_set(int i, int on) {
    pthread_mutex_lock(&g_lock);
    cheat_t *c = cheat_at(i);
    int ok = 0;
    if (c) {
        if (c->broken) ok = -1;   // it cannot run
        else { c->on = on ? 1 : 0; states_write(); ok = 1; }
    }
    pthread_mutex_unlock(&g_lock);
    return ok;
}
