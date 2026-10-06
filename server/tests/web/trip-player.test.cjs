const { test } = require("node:test");
const assert = require("node:assert/strict");
const { sampleAt, trackSegments } = require("../../static/trip-player.js");
const point = (time_ms, segment = 0) => ({
    time_ms,
    segment,
    lat: 50,
    lon: 30,
});
test("seeking respects samples, gaps and restart boundaries", () => {
    const positions = [
        point(0),
        point(1000),
        point(10000),
        point(10000, 1),
        point(11000, 1),
    ];
    assert.equal(sampleAt(positions, -1), null);
    assert.equal(sampleAt(positions, 500), positions[0]);
    assert.equal(sampleAt(positions, 1000), positions[1]);
    assert.equal(sampleAt(positions, 1001), null);
    assert.equal(sampleAt(positions, 9999), null);
    assert.equal(sampleAt(positions, 10000), positions[3]);
    assert.equal(sampleAt(positions, 11000), positions[4]);
    assert.equal(trackSegments(positions).length, 2);
});
test("a singleton is not drawn as a fabricated line", () => {
    assert.deepEqual(
        trackSegments([point(0), point(9000), point(10000, 1)]),
        [],
    );
    assert.equal(sampleAt([point(0), point(1000, 1)], 500), null);
});
