// "First person" (1.1.8, app menu, Cheats & Mods, off by default): the shoulder camera (CameraQuasiFPS::move) sits at
// Leon's eyes and keeps its own viewing direction, so aiming with the stick still works. Bit 31 of the aim bits.
#include "types.h"
#include "vec.h"
#include "global.h"
#include "player.h"

#include <atomic>

extern "C" void re4_log(const char* fmt, ...);
extern cPlayer* pPL;

namespace {
std::atomic<int> gOn(0);
const float kEyeHeight = 1650.0f;  // game units above Leon's feet; tune by testing
}  // namespace

extern "C" {

void re4_first_person_set(int on)
{
    const int want = on ? 1 : 0;
    if (gOn.exchange(want) != want) {
        re4_log("first person: %s", want ? "on" : "off (as the game)");
    }
}

// cam_qfps.cpp: the camera position and target in world space, after the game computed them.
void re4_first_person(Vec* campos, Vec* target)
{
    if (!gOn.load(std::memory_order_relaxed) || !pPL) {
        return;
    }
    const float dx = target->x - campos->x;
    const float dy = target->y - campos->y;
    const float dz = target->z - campos->z;
    campos->x = pPL->pos.x;
    campos->y = pPL->pos.y + kEyeHeight;
    campos->z = pPL->pos.z;
    target->x = campos->x + dx;
    target->y = campos->y + dy;
    target->z = campos->z + dz;
}

}  // extern "C"
