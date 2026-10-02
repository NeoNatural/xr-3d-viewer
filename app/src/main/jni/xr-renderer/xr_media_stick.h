// Media navigation is one step per horizontal deflection, on either hand.
// Kept independent of OpenXR so grab/focus transitions can be regression tested.
#ifndef XR_MEDIA_STICK_H
#define XR_MEDIA_STICK_H

#include <math.h>

typedef struct {
    int latched;
} MediaStickState;

static inline int mediaStickStep(MediaStickState* state, float x, float y, int blocked) {
    if (blocked || !isfinite(x) || !isfinite(y)) {
        // A deflection spent on placement or a modal must not become media
        // input when that mode ends. Only a later neutral sample rearms it.
        state->latched = 1;
        return 0;
    }
    if (fabsf(x) <= 0.25f && fabsf(y) <= 0.25f) {
        state->latched = 0;
        return 0;
    }
    if (state->latched || fabsf(x) < 0.65f || fabsf(x) <= fabsf(y)) {
        return 0;
    }
    state->latched = 1;
    return x > 0.0f ? 1 : -1;
}

static inline int mediaSticksStep(MediaStickState states[2], const float x[2],
                                 const float y[2], const int blocked[2]) {
    int sum = 0;
    for (int h = 0; h < 2; h++) {
        sum += mediaStickStep(&states[h], x[h], y[h], blocked[h]);
    }
    // Simultaneous matching nudges count once; opposing nudges cancel.
    return (sum > 0) - (sum < 0);
}

#endif
