"""Per frame, the bubble's outer and inner edge on one pixel row: edges.py <rows.raw> <right|left>.

rows.raw holds two 1080-px RGB rows per frame (ffmpeg crop=1080:2, rawvideo rgb24); the first is used.
"""
import sys
data = open(sys.argv[1], 'rb').read(); W = 1080; side = sys.argv[2]; n = len(data) // (W * 3 * 2)
def lum(i, x): o = (i * 2 * W + x) * 3; return 0.3 * data[o] + 0.59 * data[o + 1] + 0.11 * data[o + 2]
for i in range(n):
    xs = range(W - 1, -1, -1) if side == 'right' else range(W)
    edge = next((x for x in xs if lum(i, x) > 120), None)
    if edge is None: print(i, '-', '-'); continue
    step = -1 if side == 'right' else 1; x = edge
    while 0 <= x + step < W and lum(i, x + step) > 120: x += step
    print(i, edge, x)
