// Emits reference outputs of the vendored AndroidX graphics-path 1.0.1 C++ (unchanged sources).
#include "PathIterator.h"
#include "Conic.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>

static uint32_t bits(float f) { uint32_t b; memcpy(&b, &f, 4); return b; }
static uint64_t state = 0x9E3779B97F4A7C15ull;
static uint32_t nextU() { state ^= state << 13; state ^= state >> 7; state ^= state << 17; return (uint32_t)(state >> 11); }
static float rnd(float lo, float hi) { return lo + (hi - lo) * (float)(nextU() % 1000003) / 1000003.0f; }

static void floats(const float* v, int n) {
    printf("[");
    for (int i = 0; i < n; i++) printf("%s%u", i ? "," : "", bits(v[i]));
    printf("]");
}

static void conicCase(Point p[3], float w, float tol) {
    Point out[1 + 2 * 64];
    for (auto& o : out) o = {123.0f, 456.0f};
    int n = conicToQuadratics(p, out, 1 + 2 * 64, w, tol);
    // Buffer smaller than needed: report the requested size. The C++ compares the quadratic count
    // with the float count, so it can still write 2n+1 points; give it room so nothing is corrupted.
    Point small[1 + 2 * 64];
    int needed = conicToQuadratics(p, small, 2, w, tol);
    printf("{\"kind\":\"conic\",\"points\":"); floats(&p[0].x, 6);
    printf(",\"weight\":%u,\"tolerance\":%u,\"count\":%d,\"small\":%d,\"out\":", bits(w), bits(tol), n, needed);
    floats(&out[0].x, n > 0 ? 2 * (2 * n + 1) : 0);
    printf("}\n");
}

static void pathCase(const std::vector<Verb>& verbs, const std::vector<Point>& pts, const std::vector<float>& weights, float tol) {
    for (int direction = 0; direction < 2; direction++) {
        for (int eval = 0; eval < 2; eval++) {
            std::vector<Verb> v = verbs;
            Verb* start = v.data();
            if (direction == 1) { // API < 30 stores verbs reversed and iterates backwards from the end.
                for (size_t i = 0; i < v.size(); i++) v[i] = verbs[verbs.size() - 1 - i];
                start = v.data() + v.size();
            }
            std::vector<Point> p = pts;
            std::vector<float> w = weights;
            PathIterator it(p.data(), start, w.data(), (int)verbs.size(),
                            direction == 0 ? PathIterator::VerbDirection::Forward : PathIterator::VerbDirection::Backward,
                            PathIterator::ConicEvaluation(eval), tol);
            printf("{\"kind\":\"path\",\"direction\":%d,\"evaluation\":%d,\"tolerance\":%u,\"verbs\":[", direction, eval, bits(tol));
            for (size_t i = 0; i < verbs.size(); i++) printf("%s%d", i ? "," : "", (int)verbs[i]);
            printf("],\"points\":"); floats(&pts[0].x, 2 * (int)pts.size());
            printf(",\"weights\":"); floats(weights.data(), (int)weights.size());
            printf(",\"raw\":%d,\"count\":%d,\"steps\":[", it.rawCount(), it.count());
            bool first = true;
            while (true) {
                int peek = (int)it.peek();
                bool has = it.hasNext();
                Point out[4];
                for (auto& o : out) o = {0.0f, 0.0f};
                Verb verb = it.next(out);
                printf("%s{\"peek\":%d,\"has\":%d,\"verb\":%d,\"out\":", first ? "" : ",", peek, has ? 1 : 0, (int)verb);
                floats(&out[0].x, 8);
                printf("}");
                first = false;
                if (verb == Verb::Done) break;
            }
            printf("]}\n");
        }
    }
}

int main() {
    const float specials[] = {1.0f, 0.0f, 0.5f, 0.70710677f, 2.0f, 1e6f, 1e-6f, INFINITY, NAN, -1.0f};
    const float tolerances[] = {0.25f, 0.0f, -1.0f, 1.0f, 0.01f, 1e-4f, INFINITY, NAN};
    for (float w : specials) for (float tol : tolerances) {
        Point p[3] = {{0, 0}, {100, 0}, {100, 100}};
        conicCase(p, w, tol);
    }
    Point degenerate[3] = {{5, 5}, {5, 5}, {5, 5}};
    conicCase(degenerate, 0.7f, 0.25f);
    Point huge[3] = {{-3e38f, 0}, {3e38f, 3e38f}, {3e38f, -3e38f}};
    conicCase(huge, 2.0f, 0.25f);
    Point nonFinite[3] = {{0, 0}, {INFINITY, 1}, {2, 2}};
    conicCase(nonFinite, 0.7f, 0.25f);
    for (int i = 0; i < 400; i++) {
        Point p[3] = {{rnd(-500, 500), rnd(-500, 500)}, {rnd(-500, 500), rnd(-500, 500)}, {rnd(-500, 500), rnd(-500, 500)}};
        conicCase(p, rnd(0.01f, 8.0f), rnd(0.001f, 2.0f));
    }

    // Paths: Move Line Quadratic Conic Cubic Close (Done is never stored).
    pathCase({Verb::Move, Verb::Line, Verb::Close}, {{1, 2}, {3, 4}}, {}, 0.25f);
    pathCase({Verb::Move, Verb::Quadratic, Verb::Cubic, Verb::Line, Verb::Close},
             {{0, 0}, {10, 0}, {10, 10}, {20, 20}, {30, 0}, {40, 40}, {0, 50}}, {}, 0.25f);
    pathCase({Verb::Move, Verb::Conic, Verb::Conic, Verb::Close},
             {{0, 0}, {100, 0}, {100, 100}, {0, 100}, {0, 0}}, {0.70710677f, 3.0f}, 0.25f);
    pathCase({Verb::Move, Verb::Conic, Verb::Move, Verb::Conic, Verb::Line},
             {{0, 0}, {1000, 0}, {1000, 1000}, {5, 5}, {6, 9}, {7, 5}, {1, 1}}, {50.0f, 0.1f}, 0.01f);
    pathCase({}, {}, {}, 0.25f);
    for (int i = 0; i < 60; i++) {
        std::vector<Verb> verbs = {Verb::Move};
        std::vector<Point> pts = {{rnd(-200, 200), rnd(-200, 200)}};
        std::vector<float> weights;
        int n = 1 + (int)(nextU() % 8);
        for (int k = 0; k < n; k++) {
            Verb v = (Verb)(nextU() % 6);
            verbs.push_back(v);
            int add = v == Verb::Move || v == Verb::Line ? 1 : v == Verb::Quadratic || v == Verb::Conic ? 2 : v == Verb::Cubic ? 3 : 0;
            for (int j = 0; j < add; j++) pts.push_back({rnd(-200, 200), rnd(-200, 200)});
            if (v == Verb::Conic) weights.push_back(rnd(0.05f, 6.0f));
        }
        pathCase(verbs, pts, weights, rnd(0.01f, 1.0f));
    }
    return 0;
}
