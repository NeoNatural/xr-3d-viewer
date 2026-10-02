#include "check.h"
#include "xr_media_stick.h"

static void testDeflectionAndNoise(void) {
    MediaStickState state = {0};
    CHECK(mediaStickStep(&state, 0.3f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0.8f, 0, 0) == 1);
    for (int frame = 0; frame < 100; frame++) {
        CHECK(mediaStickStep(&state, 0.9f, 0, 0) == 0);
    }
    CHECK(mediaStickStep(&state, 0.4f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, -0.9f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0, 0, 0) == 0);
    CHECK(mediaStickStep(&state, -0.8f, 0, 0) == -1);
    CHECK(mediaStickStep(&state, 0, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0.7f, 0.9f, 0) == 0);
    CHECK(mediaStickStep(&state, 0.9f, 0.9f, 0) == 0);
    CHECK(mediaStickStep(&state, -0.9f, 0.2f, 0) == -1);
}

static void testGrabAndFocusTransitions(void) {
    MediaStickState state = {0};
    CHECK(mediaStickStep(&state, 0.9f, 0, 1) == 0);
    CHECK(mediaStickStep(&state, 0.9f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, -0.9f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0, 0.8f, 0) == 0);
    CHECK(mediaStickStep(&state, 0, 0, 0) == 0);
    CHECK(mediaStickStep(&state, -0.9f, 0, 0) == -1);
    CHECK(mediaStickStep(&state, 0, 0, 1) == 0);
    CHECK(mediaStickStep(&state, 0.9f, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0, 0, 0) == 0);
    CHECK(mediaStickStep(&state, 0.9f, 0, 0) == 1);
}

static void testEitherHandAndSimultaneousInput(void) {
    MediaStickState states[2] = {{0}, {0}};
    float x[2] = {0, 0.9f}, y[2] = {0, 0};
    int blocked[2] = {0, 0};
    CHECK(mediaSticksStep(states, x, y, blocked) == 1);
    x[0] = -0.9f;
    CHECK(mediaSticksStep(states, x, y, blocked) == -1);
    x[0] = x[1] = 0;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    x[0] = x[1] = 0.9f;
    CHECK(mediaSticksStep(states, x, y, blocked) == 1);
    x[0] = x[1] = 0;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    x[0] = -0.9f; x[1] = 0.9f;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    blocked[0] = blocked[1] = 1;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    blocked[0] = blocked[1] = 0;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    x[0] = x[1] = 0;
    CHECK(mediaSticksStep(states, x, y, blocked) == 0);
    x[1] = -0.9f;
    CHECK(mediaSticksStep(states, x, y, blocked) == -1);
}

int main(void) {
    testDeflectionAndNoise();
    testGrabAndFocusTransitions();
    testEitherHandAndSimultaneousInput();
    return checksDone("media stick");
}
