// "Quick weapon switch" (1.1.7, wish of the project owner, 28.11.2026: "zwischen den Waffen im Koffer schnell wechseln,
// ohne den Koffer zu öffnen"), compiled with the game's flags into libre4_main.
//
// The case's weapons in its reading order (the cells top left to bottom right: cItem::y, then x; only what lies in the
// case, cItem::board 1, not the spare board), the guns and launchers of the game's weapon table (item.cpp wep_info)
// with the grenades only when the player wants them (the eggs, the knife and the empty hands never). The app asks for
// the next or the previous one or the case's n-th (re4_core_quick_weapon: the menu's switch on, the mapped "Next weapon"
// / "Previous weapon", the keyboard's 1..9, its wheel); in the core: the D-pad's left and right (a filter on PADRead's
// buttons: while the switch is on and the player plays without aiming, left and right are taken from the game, a press
// asks for the previous / next weapon; while he aims they stay the game's, its fine aim), the mouse wheel outside the
// scope and the binoculars (runtime/wheel_zoom.cpp) and a finger on the life gauge (runtime/menu_touch.cpp hands the
// app's drag events over: a tap the next weapon, a swipe to the left the next, to the right the previous; the bottom
// right corner of the picture the screen shows, where the gauge lies, picture.cpp).
//
// The switch is the game's own, as SubScreenExit does it after the case under its fade (sscrn.cpp step 4): the item
// armed (ItemMgr.arm), the weapon sounds stopped (SndBlkStop 2), the weapon released, its file and module read from
// the disc (weaponLoad: ReadWepData waits with TaskSleep, so a task of its own, priority 2 as the debug tool's weapon
// change), the bullet type, weaponInit, the player's routine back to its start. Only where the game would open the
// case (SubScreenCall: the player and Ashley alive, the sub screen enabled and no request, cPlayer::subScrCheck), in
// play (no event, no sub screen), not as Ashley, not while he aims or holds the knife, and one at a time. The new
// weapon's English name and its place in the order for the app's label (re4_core_quick_weapon's reply).
#include "types.h"
#include "global.h"
#include "item.h"
#include "em.h"
#include "player.h"
#include "scheduler.h"
#include "snd.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern GLOBAL_WK* pG;
extern cPlayer* pPL;

extern "C" void re4_log(const char* fmt, ...);
extern "C" void re4_add_frame_hook(void (*hook)(unsigned));          // runtime/pad.cpp
extern "C" void re4_pad_button_filter(unsigned short (*f)(unsigned short));  // runtime/pad.cpp (1.1.7)
extern "C" long re4_trace_free_at(void);                               // runtime/state_trace.cpp
extern "C" int re4_hud_corner_rows(int* top, int* bottom);             // runtime/picture.cpp (1.1.7)
extern "C" int re4_hud_gauge_box(int* left_e4, int* bottom_e1);       // runtime/picture.cpp (1.1.7)
extern "C" void re4_quick_weapon_touch(int phase, float u, float v);   // below

namespace {

struct Weapon {
    u16 id;
    const char* name;
    bool thrown;  // a grenade: only with the player's "Grenades in the cycle"
};
// the game's weapon table (item.cpp wep_info) without the knife (0x38), the empty hands (0x800), the eggs (0x08..0x0A)
// and 0x52's twin entries; the names as the game's English item names
const Weapon kWeapons[] = {
    {0x23, "Handgun", false}, {0x24, "Handgun", false}, {0x25, "Red9", false}, {0x26, "Red9", false},
    {0x21, "Punisher", false}, {0x22, "Punisher", false}, {0x40, "Handgun", false}, {0x27, "Blacktail", false},
    {0x28, "Blacktail", false}, {0x03, "Matilda", false}, {0x29, "Broken Butterfly", false},
    {0x2A, "Killer7", false}, {0x2B, "Killer7", false}, {0x37, "Handcannon", false}, {0x2C, "Shotgun", false},
    {0x94, "Riot Gun", false}, {0x2D, "Striker", false}, {0x2E, "Rifle", false}, {0x6B, "Rifle", false},
    {0x99, "Rifle", false}, {0x2F, "Rifle (semi-auto)", false}, {0x6C, "Rifle (semi-auto)", false},
    {0x51, "Rifle (semi-auto)", false}, {0x30, "TMP", false}, {0x31, "TMP", false}, {0x32, "TMP", false},
    {0x33, "TMP", false}, {0x3E, "TMP", false}, {0x34, "Chicago Typewriter", false}, {0x36, "Mine Thrower", false},
    {0xAB, "Mine Thrower", false}, {0x35, "Rocket Launcher", false}, {0x17, "Rocket Launcher", false},
    {0x6D, "Infinite Launcher", false}, {0x52, "Bow", false}, {0x01, "Hand Grenade", true},
    {0x02, "Incendiary Grenade", true}, {0x0E, "Flash Grenade", true},
};

const Weapon* weaponOf(u16 id)
{
    for (const Weapon& w : kWeapons) {
        if (w.id == id) {
            return &w;
        }
    }
    return 0;
}

int gOn, gGrenades;            // the menu's switches (re4_core_quick_weapon 0x40000000 | on | grenades << 1)
volatile int gWant;            // a request: +1 / -1, 100 + n (the case's n-th), 0 none
volatile int gBusy;            // the switch task runs
cItem* gTarget;                // its weapon
int gTargetIndex, gTargetCount;
pthread_mutex_t gLock = PTHREAD_MUTEX_INITIALIZER;
char gName[40];                // the last switch's weapon (the app's label)
int gIndex, gCount;
unsigned gSerial;              // switches done
bool gHooked;
unsigned gRefused;             // requests the state refused (the log's first lines)

// a finger on the life gauge (the app's drag: down, moves, lifted), the app's thread writes
volatile int gFinger;          // 1 while a finger that touched down on the gauge is on the picture
float gDownU, gDownV, gLastU, gLastV;
long long gDownMs;
volatile long long gTookMs;    // when a gesture on the gauge asked for a switch (the knife's swipe ignores it)

long long nowMs()
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

// In play, the player free to act as the case would open (SubScreenCall's checks); `why` the refusal
bool allowed(const char** why)
{
    GLOBAL_WK* g = pG;
    cPlayer* pl = pPL;
    if (!g || !pl || g->Rno0 != 3) {
        *why = "not in play";
        return false;
    }
    if (g->pl_type == 1) {
        *why = "Ashley has no weapons";
        return false;
    }
    if (StaFlagChk(g, STA_SUB_SCRN) || StaFlagChk(g, STA_EVENT) || StaFlagChk(g, STA_SSCRN_REQUEST)) {
        *why = "an event or a sub screen";
        return false;
    }
    if (!StaFlagChk(g, STA_SSCRN_ENABLE) || (s16) g->pl_life <= 0 ||
        (pSUB && pSUB->id == 3 && (s16) g->ashley_life <= 0)) {
        *why = "the case could not open now";
        return false;
    }
    if (StaFlagChk(g, STA_SCOPE_CAMERA) || StaFlagChk(g, STA_BINOCULAR)) {
        *why = "the scope or the binoculars";
        return false;
    }
    if (pl->r_no_0 != 0 || pl->r_no_1 == 6 || pl->r_no_1 == 0xB) {
        *why = "aiming, the knife or a move of his own";
        return false;
    }
    if (pl->subScrCheck() != 1) {
        *why = "the player is busy";
        return false;
    }
    return true;
}

struct Slot {
    cItem* p;
    int key;
};

// The case's weapons in its reading order; returns how many
int caseWeapons(Slot* out, int max)
{
    const int chr = pG->pl_type == 1;
    int n = 0;
    for (int i = 0; i < ItemMgr.m_array_num && n < max; i++) {
        cItem* p = ItemMgr.at(i);
        if (!p || !p->isAlive(chr) || p->board != 1) {
            continue;
        }
        const Weapon* w = weaponOf(p->id);
        if (!w || (w->thrown && !gGrenades)) {
            continue;
        }
        out[n].p = p;
        out[n].key = (int) p->y * 256 + (int) p->x;
        n++;
    }
    for (int i = 1; i < n; i++) {  // (a handful: insertion sort)
        Slot s = out[i];
        int j = i - 1;
        while (j >= 0 && out[j].key > s.key) {
            out[j + 1] = out[j];
            j--;
        }
        out[j + 1] = s;
    }
    return n;
}

// The switch, the game's own steps after the case (sscrn.cpp SubScreenExit step 4), as a task: weaponLoad waits
void switchTask()
{
    cPlayer* pl = pPL;
    cItem* p = gTarget;
    const Weapon* w = p ? weaponOf(p->id) : 0;
    if (pl && p && w) {
        const int no = WeaponId2WeaponNo((ITEM_ID) p->id), type = WeaponId2WeaponType((ITEM_ID) p->id);
        ItemMgr.arm(p);
        SndBlkStop(2);
        pl->weaponRelease();
        pl->weaponLoad(no, type);
        pG->bullet_type = p->getBulletType();
        pl->weaponInit();
        pl->r_no_1 = 0;
        pl->r_no_2 = 0;
        pl->r_no_3 = 0;
        pthread_mutex_lock(&gLock);
        strncpy(gName, w->name, sizeof(gName) - 1);
        gIndex = gTargetIndex;
        gCount = gTargetCount;
        gSerial++;
        pthread_mutex_unlock(&gLock);
        int boxLeft = -1, boxBottom = -1;
        re4_hud_gauge_box(&boxLeft, &boxBottom);
        re4_log("quick weapon: %s (item 0x%02x, weapon %d type %d), %d of %d in the case at frame %u (the gauge from "
                "%d/10000 across, its lowest row %d/10)", w->name, (unsigned) p->id, no, type, gTargetIndex,
                gTargetCount, (unsigned) pG->Frame_cnt, boxLeft, boxBottom);
    }
    gTarget = 0;
    gBusy = 0;
    TaskExit();
}

// A request in the game's thread (the frame hook): the weapon it means, the task started
void serve(int want)
{
    const char* why = "";
    if (!gOn) {
        return;
    }
    if (gBusy || TaskStatus(2) != 0) {
        why = "a switch is on its way";
    } else if (allowed(&why)) {
        Slot list[48];
        const int n = caseWeapons(list, 48);
        cItem* cur = ItemMgr.weapon();
        int at = -1;
        for (int i = 0; i < n; i++) {
            if (list[i].p == cur) {
                at = i;
            }
        }
        int to = -1;
        if (n > 0 && (want == 1 || want == -1)) {
            to = at < 0 ? (want > 0 ? 0 : n - 1) : (at + want + n) % n;
        } else if (want >= 101 && want - 101 < n) {
            to = want - 101;
        }
        if (n == 0) {
            why = "no weapon in the case";
        } else if (to < 0) {
            why = "no such place in the order";
        } else if (to == at) {
            why = "the weapon in hand already";
        } else {
            gTarget = list[to].p;
            gTargetIndex = to + 1;
            gTargetCount = n;
            gBusy = 1;
            if (TaskExec(2, (TaskFunc) switchTask, 0)) {
                return;
            }
            gBusy = 0;
            gTarget = 0;
            why = "no task free";
        }
    }
    if (gRefused++ < 12) {
        re4_log("quick weapon: not now (%s)", why);
    }
}

// PC tests: RE4_TEST_QUICK_WEAPON="free+N:H[;free+M:H...]" (H +1, -1 or nK for the case's K-th; frames after the
// player is free), RE4_QUICK_WEAPON="1[,1]" the switch (and the grenades) on from the start;
// RE4_TEST_QUICK_TOUCH="free+N:G[;...]" a finger on the gauge (G tap, left, right), as the app's drag events
struct Test {
    long at;
    int want;   // a request, or 1000 + 0/1/2 a gesture (tap, left, right)
};
Test gTests[16];
int gTestCount;

// A gesture on the gauge as the app's drag events bring it (PC tests): a tap, a swipe to the left or to the right
void touchGesture(int kind)
{
    const float u = 0.85f, v = 0.80f;
    re4_quick_weapon_touch(0, u, v);
    re4_quick_weapon_touch(2, kind == 1 ? u - 0.12f : kind == 2 ? u + 0.12f : u, v);
}

void hook(unsigned frame)
{
    const long freeAt = re4_trace_free_at();
    for (int i = 0; freeAt >= 0 && i < gTestCount; i++) {
        if (gTests[i].at >= 0 && (long) frame == freeAt + gTests[i].at) {
            const int w = gTests[i].want;
            re4_log("quick weapon: test %s at frame %u", w >= 1000 ? "touch" : "request", frame);
            if (w >= 1000) {
                touchGesture(w - 1000);
            } else {
                gWant = w;
            }
        }
    }
    const int want = __atomic_exchange_n(&gWant, 0, __ATOMIC_ACQ_REL);
    if (want) {
        serve(want);
    }
}

void ensureHook();

// PADRead's buttons (the game thread): the D-pad's left and right while the switch is on and the player plays without
// aiming: taken from the game, a press a request
unsigned short dpadFilter(unsigned short buttons)
{
    static unsigned short last;
    const unsigned short lr = buttons & 3;  // PAD_BUTTON_LEFT 1, RIGHT 2
    const unsigned short pressed = lr & ~last;
    last = lr;
    GLOBAL_WK* g = pG;
    cPlayer* pl = pPL;
    if (!gOn || !g || !pl || g->Rno0 != 3 || StaFlagChk(g, STA_SUB_SCRN) || StaFlagChk(g, STA_EVENT) ||
        g->pl_type == 1 || pl->r_no_1 == 6 || pl->r_no_1 == 0xB || StaFlagChk(g, STA_SCOPE_CAMERA) ||
        StaFlagChk(g, STA_BINOCULAR)) {
        return buttons;
    }
    if (pressed & 1) {
        gWant = -1;
    } else if (pressed & 2) {
        gWant = 1;
    }
    return (unsigned short) (buttons & ~3);
}

void ensureHook()
{
    if (!__atomic_exchange_n(&gHooked, true, __ATOMIC_ACQ_REL)) {
        re4_add_frame_hook(hook);
        re4_pad_button_filter(dpadFilter);
    }
}

// The life gauge's corner of the picture the screen shows (u across the picture, v down the game's 448 rows): the
// right 30 % of the width, the lowest 38 % of the shown rows
bool onGauge(float u, float v)
{
    int top = 0, bottom = 448;
    re4_hud_corner_rows(&top, &bottom);
    const float vt = (float) top / 448.0f, vb = (float) bottom / 448.0f;
    return u >= 0.70f && u <= 1.0f && v <= vb && v >= vb - 0.38f * (vb - vt);
}

struct Init {
    Init()
    {
        const char* on = getenv("RE4_QUICK_WEAPON");
        if (on && *on) {
            gOn = atoi(on) != 0;
            const char* c = strchr(on, ',');
            gGrenades = c && atoi(c + 1) != 0;
            ensureHook();
            re4_log("quick weapon: %s%s (RE4_QUICK_WEAPON)", gOn ? "on" : "off", gGrenades ? ", grenades too" : "");
        }
        for (int k = 0; k < 2; k++) {
            const char* t = getenv(k ? "RE4_TEST_QUICK_TOUCH" : "RE4_TEST_QUICK_WEAPON");
            for (const char* s = t; s && *s && gTestCount < 16;) {
                Test x = {-1, 0};
                char* e;
                if (strncmp(s, "free+", 5) != 0) {
                    break;
                }
                x.at = strtol(s + 5, &e, 10);
                if (*e != ':') {
                    break;
                }
                e++;
                if (k) {
                    x.want = 1000 + (*e == 'l' ? 1 : *e == 'r' ? 2 : 0);
                } else if (*e == 'n') {
                    x.want = 100 + (int) strtol(e + 1, &e, 10);
                } else {
                    x.want = (int) strtol(e, &e, 10) >= 0 ? 1 : -1;
                }
                gTests[gTestCount++] = x;
                const char* next = strchr(e, ';');
                s = next ? next + 1 : 0;
            }
        }
        if (gTestCount) {
            ensureHook();
        }
    }
} gInit;

}  // namespace

extern "C" {

// The app: how 0x40000000 | on | grenades << 1 the menu's switches; +1 / -1 the next / previous weapon; 100 + n the
// case's n-th (the keyboard's 1..9); 0 asks only. The reply: the switches done so far (the app's label shows a new
// one), the last one's weapon name into `name` (`size` bytes) and info[0] its place, info[1] the case's count; info[2]
// and info[3] where the life gauge lies for the label to its left (its left end across the picture x 10000, its lowest
// row of the game's 448 x 10; -1 without the app's layout). `info` holds 4. Any thread.
int re4_core_quick_weapon(int how, char* name, int size, int* info)
{
    if (how & 0x40000000) {
        const int on = how & 1, grenades = (how >> 1) & 1;
        if (on != gOn || grenades != gGrenades) {
            re4_log("quick weapon: %s%s (the menu)", on ? "on" : "off", on && grenades ? ", grenades too" : "");
        }
        gOn = on;
        gGrenades = grenades;
        if (on) {
            ensureHook();
        }
    } else if (how == 1 || how == -1 || (how >= 101 && how <= 109)) {
        if (gOn) {
            ensureHook();
            gWant = how;
        }
    }
    pthread_mutex_lock(&gLock);
    const unsigned serial = gSerial;
    if (name && size > 0) {
        strncpy(name, gName, (size_t) size - 1);
        name[size - 1] = 0;
    }
    if (info) {
        info[0] = gIndex;
        info[1] = gCount;
    }
    pthread_mutex_unlock(&gLock);
    if (info) {
        re4_hud_gauge_box(&info[2], &info[3]);
    }
    return (int) serial;
}

// The mouse wheel outside the scope and the binoculars (runtime/wheel_zoom.cpp): a notch down the next weapon, up the
// previous; 1 when taken
int re4_quick_weapon_wheel(int notches)
{
    if (!gOn || notches == 0) {
        return 0;
    }
    gWant = notches < 0 ? 1 : -1;
    return 1;
}

// The app's finger beside the touch controls (runtime/menu_touch.cpp re4_core_touch's drag: phase 0 down, 1 moved, 2
// lifted, 3 cancelled; u across the picture, v down the game's rows, both 0..1): on the life gauge while the player
// plays, a tap the next weapon, a swipe to the left the next, to the right the previous. Any thread.
void re4_quick_weapon_touch(int phase, float u, float v)
{
    GLOBAL_WK* g = pG;
    if (!gOn || !g || g->Rno0 != 3 || StaFlagChk(g, STA_SUB_SCRN)) {
        gFinger = 0;
        return;
    }
    if (phase == 0) {
        gFinger = onGauge(u, v);
        gDownU = gLastU = u;
        gDownV = gLastV = v;
        gDownMs = nowMs();
        return;
    }
    if (!gFinger) {
        return;
    }
    if (phase == 1) {
        gLastU = u;
        gLastV = v;
        return;
    }
    gFinger = 0;
    if (phase != 2) {
        return;
    }
    const float du = u - gDownU, dv = v - gDownV;
    const long long ms = nowMs() - gDownMs;
    int want = 0;
    if (du * du + dv * dv < 0.03f * 0.03f && ms <= 400) {
        want = 1;  // a tap
    } else if (ms <= 600 && (du > 0.06f || du < -0.06f) && (du > 0 ? du : -du) >= 2.0f * (dv > 0 ? dv : -dv)) {
        want = du < 0 ? 1 : -1;  // a swipe to the left: the next (as the sub screens' tabs)
    }
    if (want) {
        __atomic_store_n(&gTookMs, nowMs(), __ATOMIC_RELAXED);
        ensureHook();
        gWant = want;
    }
}

// The knife's swipe (runtime/knife_swipe.cpp): 1 when the swipe was one on the gauge just now (no slash)
int re4_quick_weapon_took_swipe(void)
{
    const long long took = __atomic_load_n(&gTookMs, __ATOMIC_RELAXED);
    return took && nowMs() - took < 300;
}

}  // extern "C"
