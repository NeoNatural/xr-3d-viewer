#include <math.h>
#include <string.h>

#include "xr_depthmap.h"

float motionAdaptiveDepthAlpha(float baseAlpha, float delta) {
    // Below four percent of the normalized range is normally model noise.
    // Above sixteen percent is a changed surface or a moving silhouette and
    // must not retain the preceding frame. Smoothstep avoids a visible mode
    // switch between those cases.
    float t = (fabsf(delta) - 0.04f) / 0.12f;
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    t = t * t * (3.0f - 2.0f * t);
    return baseAlpha + (1.0f - baseAlpha) * t;
}

float estimateDepthMotionGrid(const float* current, const float* previous, int size,
                              float* outDx, float* outDy, float* outConfidence,
                              int gridSize, int searchRadius) {
    if (current == NULL || previous == NULL || outDx == NULL || outDy == NULL
            || outConfidence == NULL || size < 8 || gridSize < 1
            || size % gridSize != 0 || searchRadius < 0) {
        return 0.0f;
    }
    const int step = size / gridSize;
    const int patchRadius = 1;
    float confidenceSum = 0.0f;

    for (int gy = 0; gy < gridSize; gy++) {
        int cy = step / 2 + gy * step;
        if (cy >= size - patchRadius) cy = size - patchRadius - 1;
        for (int gx = 0; gx < gridSize; gx++) {
            int cx = step / 2 + gx * step;
            if (cx >= size - patchRadius) cx = size - patchRadius - 1;
            float bestScore = 1e30f;
            float bestError = 1.0f;
            int bestDx = 0, bestDy = 0;

            for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                int py = cy + dy;
                if (py - patchRadius < 0 || py + patchRadius >= size) continue;
                for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                    int px = cx + dx;
                    if (px - patchRadius < 0 || px + patchRadius >= size) continue;
                    float error = 0.0f;
                    for (int yy = -patchRadius; yy <= patchRadius; yy++) {
                        const float* a = current + (size_t)(cy + yy) * size + cx;
                        const float* b = previous + (size_t)(py + yy) * size + px;
                        for (int xx = -patchRadius; xx <= patchRadius; xx++) {
                            error += fabsf(a[xx] - b[xx]);
                        }
                    }
                    error *= 1.0f / 9.0f;
                    // Textureless or repeated patches should prefer no motion
                    // instead of selecting an arbitrary corner of the search.
                    float score = error + 0.00025f * (dx * dx + dy * dy);
                    if (score < bestScore) {
                        bestScore = score;
                        bestError = error;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }

            int index = gy * gridSize + gx;
            outDx[index] = (float)bestDx;
            outDy[index] = (float)bestDy;
            float confidence = 1.0f - bestError / 0.12f;
            if (confidence < 0.0f) confidence = 0.0f;
            if (confidence > 1.0f) confidence = 1.0f;
            outConfidence[index] = confidence;
            confidenceSum += confidence;
        }
    }
    return confidenceSum / (float)(gridSize * gridSize);
}

// 2nd and 98th percentile of the model output, via a histogram. Using the
// raw min and max lets one stray pixel own the whole mapping: on a measured
// frame the 2..98 span was 638 of an 805 wide min/max range, so a fifth of
// the output range was being spent on a handful of pixels.
void robustRange(const float* v, int count, float* outLo, float* outHi) {
    float lo = v[0], hi = v[0];
    for (int i = 1; i < count; i++) {
        if (v[i] < lo) lo = v[i];
        if (v[i] > hi) hi = v[i];
    }
    if (hi <= lo) {
        *outLo = lo;
        *outHi = lo + 1.0f;
        return;
    }

    int hist[DEPTH_HIST_BINS];
    memset(hist, 0, sizeof(hist));
    float scale = DEPTH_HIST_BINS / (hi - lo);
    for (int i = 0; i < count; i++) {
        int b = (int)((v[i] - lo) * scale);
        if (b < 0) b = 0;
        if (b >= DEPTH_HIST_BINS) b = DEPTH_HIST_BINS - 1;
        hist[b]++;
    }

    int loTarget = (int)(count * 0.02f);
    int hiTarget = (int)(count * 0.98f);
    int acc = 0, loBin = 0, hiBin = DEPTH_HIST_BINS - 1;
    for (int b = 0; b < DEPTH_HIST_BINS; b++) {
        acc += hist[b];
        if (acc >= loTarget) {
            loBin = b;
            break;
        }
    }
    acc = 0;
    for (int b = 0; b < DEPTH_HIST_BINS; b++) {
        acc += hist[b];
        if (acc >= hiTarget) {
            hiBin = b;
            break;
        }
    }

    *outLo = lo + loBin / scale;
    *outHi = lo + (hiBin + 1) / scale;
    if (*outHi <= *outLo) {
        *outHi = *outLo + 1e-6f;
    }
}

static void boxBlurH(const float* src, float* dst, int n, int r) {
    float inv = 1.0f / (float)(2 * r + 1);
    for (int y = 0; y < n; y++) {
        const float* s = src + (size_t)y * n;
        float* d = dst + (size_t)y * n;
        float sum = 0.0f;
        for (int i = -r; i <= r; i++) {
            int x = i < 0 ? 0 : (i >= n ? n - 1 : i);
            sum += s[x];
        }
        for (int x = 0; x < n; x++) {
            d[x] = sum * inv;
            int add = x + r + 1;
            int sub = x - r;
            sum += s[add >= n ? n - 1 : add] - s[sub < 0 ? 0 : sub];
        }
    }
}

// Column sums carried a row at a time. The obvious version, one column at a
// time, strides a whole row between reads and misses cache on every access,
// which cost 15 ms here rather than 1.
static void boxBlurV(const float* src, float* dst, int n, int r, float* colSums) {
    float inv = 1.0f / (float)(2 * r + 1);
    memset(colSums, 0, (size_t)n * sizeof(float));
    for (int i = -r; i <= r; i++) {
        int y = i < 0 ? 0 : (i >= n ? n - 1 : i);
        const float* s = src + (size_t)y * n;
        for (int x = 0; x < n; x++) {
            colSums[x] += s[x];
        }
    }
    for (int y = 0; y < n; y++) {
        float* d = dst + (size_t)y * n;
        for (int x = 0; x < n; x++) {
            d[x] = colSums[x] * inv;
        }
        int add = y + r + 1;
        int sub = y - r;
        const float* a = src + (size_t)(add >= n ? n - 1 : add) * n;
        const float* b = src + (size_t)(sub < 0 ? 0 : sub) * n;
        for (int x = 0; x < n; x++) {
            colSums[x] += a[x] - b[x];
        }
    }
}

// Three box passes is close enough to a gaussian
void lowPass(const float* src, float* dst, float* scratch, float* colSums, int n, int r) {
    boxBlurH(src, scratch, n, r);
    boxBlurV(scratch, dst, n, r, colSums);
    boxBlurH(dst, scratch, n, r);
    boxBlurV(scratch, dst, n, r, colSums);
    boxBlurH(dst, scratch, n, r);
    boxBlurV(scratch, dst, n, r, colSums);
}
