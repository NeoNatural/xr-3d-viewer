// The CPU side of the depth map: the percentile range and the low pass
#include <stdlib.h>
#include <string.h>

#include "check.h"
#include "xr_depthmap.h"

#define N 64

static void testRobustRange(void) {
    float* v = malloc(N * N * sizeof(float));
    // A ramp from 0 to 1
    for (int i = 0; i < N * N; i++) {
        v[i] = i / (float)(N * N - 1);
    }
    float lo, hi;
    robustRange(v, N * N, &lo, &hi);
    CHECK_NEAR(lo, 0.02f, 0.01);
    CHECK_NEAR(hi, 0.98f, 0.01);

    // A few wild pixels must not own the mapping the way the raw min and max
    // would
    v[0] = -100.0f;
    v[1] = 100.0f;
    robustRange(v, N * N, &lo, &hi);
    CHECK(lo > -1.0f && lo < 1.0f);
    CHECK(hi > 0.0f && hi < 2.0f);

    // A flat map still comes back with a range to divide by
    for (int i = 0; i < N * N; i++) {
        v[i] = 0.25f;
    }
    robustRange(v, N * N, &lo, &hi);
    CHECK_NEAR(lo, 0.25f, 1e-6);
    CHECK(hi > lo);
    free(v);
}

static void testLowPass(void) {
    float* src = calloc(N * N, sizeof(float));
    float* dst = calloc(N * N, sizeof(float));
    float* scratch = calloc(N * N, sizeof(float));
    float* colSums = calloc(N, sizeof(float));

    // A flat field passes through untouched
    for (int i = 0; i < N * N; i++) {
        src[i] = 0.6f;
    }
    lowPass(src, dst, scratch, colSums, N, 3);
    CHECK_NEAR(dst[0], 0.6f, 1e-5);
    CHECK_NEAR(dst[N * N / 2 + N / 2], 0.6f, 1e-5);
    CHECK_NEAR(dst[N * N - 1], 0.6f, 1e-5);

    // A single bright pixel in the middle is spread out without any of it
    // being lost
    memset(src, 0, N * N * sizeof(float));
    src[(N / 2) * N + N / 2] = 1.0f;
    lowPass(src, dst, scratch, colSums, N, 2);
    double sum = 0.0;
    for (int i = 0; i < N * N; i++) {
        sum += dst[i];
    }
    CHECK_NEAR(sum, 1.0, 1e-4);
    CHECK(dst[(N / 2) * N + N / 2] < 1.0f);
    CHECK(dst[(N / 2) * N + N / 2 + 1] > 0.0f);

    free(src);
    free(dst);
    free(scratch);
    free(colSums);
}

static void testMotionAdaptiveDepthAlpha(void) {
    // Ordinary model noise keeps the configured smoothing.
    CHECK_NEAR(motionAdaptiveDepthAlpha(0.6f, 0.0f), 0.6f, 1e-6);
    CHECK_NEAR(motionAdaptiveDepthAlpha(0.6f, 0.03f), 0.6f, 1e-6);

    // A mid-sized change is only partly released, while a new surface is
    // accepted without retaining a ghost of the old edge.
    CHECK_NEAR(motionAdaptiveDepthAlpha(0.6f, 0.10f), 0.8f, 1e-6);
    CHECK_NEAR(motionAdaptiveDepthAlpha(0.6f, 0.16f), 1.0f, 1e-6);
    CHECK_NEAR(motionAdaptiveDepthAlpha(0.6f, -0.20f), 1.0f, 1e-6);
}

static void testDepthMotionGrid(void) {
    enum { SIZE = 32, GRID = 8 };
    float previous[SIZE * SIZE];
    float current[SIZE * SIZE];
    float dx[GRID * GRID], dy[GRID * GRID], confidence[GRID * GRID];
    for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
            // Non-periodic texture with structure in both axes.
            previous[y * SIZE + x] = ((x * 17 + y * 29 + x * y * 3) % 251) / 250.0f;
        }
    }
    // Current pixel (x,y) came from previous (x+2,y+1).
    for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
            int px = x + 2 < SIZE ? x + 2 : SIZE - 1;
            int py = y + 1 < SIZE ? y + 1 : SIZE - 1;
            current[y * SIZE + x] = previous[py * SIZE + px];
        }
    }
    float mean = estimateDepthMotionGrid(current, previous, SIZE, dx, dy, confidence,
                                         GRID, 3);
    CHECK(mean > 0.7f);
    // Use an interior cell so the expected match is not clipped by a border.
    int center = 3 * GRID + 3;
    CHECK_NEAR(dx[center], 2.0f, 1e-6);
    CHECK_NEAR(dy[center], 1.0f, 1e-6);
    CHECK(confidence[center] > 0.9f);

    // An identical flat image must resolve to zero motion, not an arbitrary
    // search corner.
    for (int i = 0; i < SIZE * SIZE; i++) previous[i] = current[i] = 0.4f;
    estimateDepthMotionGrid(current, previous, SIZE, dx, dy, confidence, GRID, 3);
    CHECK_NEAR(dx[center], 0.0f, 1e-6);
    CHECK_NEAR(dy[center], 0.0f, 1e-6);
}

int main(void) {
    testRobustRange();
    testLowPass();
    testMotionAdaptiveDepthAlpha();
    testDepthMotionGrid();
    return checksDone("xr_depthmap");
}
