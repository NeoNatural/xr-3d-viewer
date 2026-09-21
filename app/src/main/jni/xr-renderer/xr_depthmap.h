
// CPU filtering of the depth model output: the robust range the map is
// normalised against and the low pass that splits it into overall shape
// and local detail. Plain arrays in and out, so it builds anywhere.

#ifndef XR_DEPTHMAP_H
#define XR_DEPTHMAP_H

// Bins for the percentile search over the model output
#define DEPTH_HIST_BINS 512

// Motion is estimated on a half-size luminance guide. A 32x32 field gives
// one vector per 8x8 depth pixels without turning stabilization into another
// neural model.
#define DEPTH_MOTION_GUIDE_SIZE 128
#define DEPTH_MOTION_GRID_SIZE 32
#define DEPTH_MOTION_SEARCH_RADIUS 8

void robustRange(const float* v, int count, float* outLo, float* outHi);
void lowPass(const float* src, float* dst, float* scratch, float* colSums, int n, int r);

// Keeps small depth noise temporally stable while accepting a moving depth
// boundary immediately enough that it does not leave a soft trail behind it.
float motionAdaptiveDepthAlpha(float baseAlpha, float delta);

// Estimates backward motion: each current guide position maps to
// (x + dx, y + dy) in the previous guide. Returns mean match confidence.
float estimateDepthMotionGrid(const float* current, const float* previous, int size,
                              float* outDx, float* outDy, float* outConfidence,
                              int gridSize, int searchRadius);

#endif
