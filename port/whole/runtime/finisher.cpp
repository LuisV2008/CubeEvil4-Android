// "Rear headshot finisher" (1.1.7, patch 0264; app menu, Cheats & Mods, off by default): a head hit on a Ganado
// (em10) from behind and close enough takes its whole life. Bit 31 of the aim bits (runtime/aim_options.cpp).
#include "types.h"
#include "global.h"
#include "player.h"
#include "em10.h"

#include <atomic>
#include <math.h>

extern "C" void re4_log(const char* fmt, ...);
extern cPlayer* pPL;

namespace {
std::atomic<int> gOn(0);
const float kRange = 2500.0f;
const float kBehind = -0.3f;
}  // namespace

extern "C" {

void re4_finisher_set(int on)
{
    const int want = on ? 1 : 0;
    if (gOn.exchange(want) != want) {
        re4_log("finisher: %s", want ? "on (a head hit from behind kills)" : "off (as the game)");
    }
}

int re4_finisher_dmg(cEm10* em, int dmg, int head)
{
    if (!gOn.load(std::memory_order_relaxed) || !head || em->hp <= 0 || !pPL) {
        return dmg;
    }
    const float dx = pPL->pos.x - em->pos.x;
    const float dz = pPL->pos.z - em->pos.z;
    const float len = sqrtf(dx * dx + dz * dz);
    if (len < 1.0f) {
        return dmg;
    }
    const float dot = (sinf(em->ang.y) * dx + cosf(em->ang.y) * dz) / len;
    re4_log("finisher: head hit, distance %.0f, facing dot %.2f, life %d, damage %d", len, dot, (int) em->hp, dmg);
    if (len > kRange || dot > kBehind) {
        return dmg;
    }
    return em->hp > dmg ? em->hp : dmg;
}

}  // extern "C"
