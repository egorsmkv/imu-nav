//! Bit-for-bit comparison with the original AndroidX 1.0.1 C++ implementation.
//!
//! `fixtures/cpp-reference.jsonl` was produced by `fixtures/reference.cpp` linked with the
//! unchanged upstream `Conic.cpp` and `PathIterator.cpp` (see README.md). Floats are stored as
//! IEEE-754 bit patterns, so every comparison is exact, including NaN and infinity cases.

use androidx_graphics_path::conic::conic_to_quadratics;
use androidx_graphics_path::iterator::{ConicEvaluation, PathIterator, VerbDirection};
use androidx_graphics_path::{Point, Verb};
use serde_json::Value;

fn cases() -> Vec<Value> {
    include_str!("fixtures/cpp-reference.jsonl").lines().map(|line| serde_json::from_str(line).unwrap()).collect()
}

fn floats(value: &Value) -> Vec<f32> {
    value.as_array().unwrap().iter().map(|bits| f32::from_bits(u32::try_from(bits.as_u64().unwrap()).unwrap())).collect()
}

fn bits(values: &[f32]) -> Vec<u32> {
    values.iter().map(|v| v.to_bits()).collect()
}

fn points(values: &[f32]) -> Vec<Point> {
    values.chunks_exact(2).map(|p| Point { x: p[0], y: p[1] }).collect()
}

fn number(case: &Value, key: &str) -> i64 {
    case[key].as_i64().unwrap()
}

fn float(case: &Value, key: &str) -> f32 {
    f32::from_bits(u32::try_from(case[key].as_u64().unwrap()).unwrap())
}

#[test]
fn conic_conversion_matches_cpp_bit_for_bit() {
    let mut checked = 0;
    for case in cases().iter().filter(|c| c["kind"] == "conic") {
        let input = points(&floats(&case["points"]));
        let conic = [input[0], input[1], input[2]];
        let (weight, tolerance) = (float(case, "weight"), float(case, "tolerance"));
        let mut out = vec![0f32; 2 * (1 + 2 * 64)];
        let count = conic_to_quadratics(conic, &mut out, weight, tolerance);
        assert_eq!(count as i64, number(case, "count"), "{case}");
        let expected = floats(&case["out"]);
        assert_eq!(bits(&out[..expected.len()]), bits(&expected), "{case}");
        // A buffer of two floats: the C++ only reports the size it needs.
        let mut small = [0f32; 2];
        let needed = conic_to_quadratics(conic, &mut small, weight, tolerance);
        assert_eq!(needed as i64, number(case, "small"), "{case}");
        checked += 1;
    }
    assert!(checked > 400, "fixture has {checked} conic cases");
}

#[test]
fn path_iteration_matches_cpp_in_both_skia_layouts() {
    let mut checked = 0;
    for case in cases().iter().filter(|c| c["kind"] == "path") {
        let logical: Vec<u8> = case["verbs"].as_array().unwrap().iter().map(|v| u8::try_from(v.as_u64().unwrap()).unwrap()).collect();
        let direction = if number(case, "direction") == 0 { VerbDirection::Forward } else { VerbDirection::Backward };
        // Before Android 11 Skia stores verbs in reverse memory order.
        let verbs: Vec<u8> = if direction == VerbDirection::Forward { logical.clone() } else { logical.iter().rev().copied().collect() };
        let path_points = points(&floats(&case["points"]));
        let weights = floats(&case["weights"]);
        let evaluation = ConicEvaluation::from_raw(i32::try_from(number(case, "evaluation")).unwrap());
        let mut it = PathIterator::new(&path_points, &verbs, &weights, direction, evaluation, float(case, "tolerance"));
        assert_eq!(it.raw_count() as i64, number(case, "raw"), "{case}");
        // Called before iterating, exactly as the reference harness does.
        assert_eq!(it.count() as i64, number(case, "count"), "{case}");
        for step in case["steps"].as_array().unwrap() {
            assert_eq!(it.peek() as i64, number(step, "peek"), "{case}");
            assert_eq!(i64::from(it.has_next()), number(step, "has"), "{case}");
            let mut out = [Point::default(); 4];
            let verb = it.next(&mut out);
            assert_eq!(verb as i64, number(step, "verb"), "{case}");
            let flat: Vec<f32> = out.iter().flat_map(|p| [p.x, p.y]).collect();
            assert_eq!(bits(&flat), bits(&floats(&step["out"])), "{case}");
        }
        assert_eq!(it.next(&mut [Point::default(); 4]), Verb::Done);
        checked += 1;
    }
    assert!(checked > 250, "fixture has {checked} path cases");
}

#[test]
fn malformed_paths_end_instead_of_reading_outside_the_arrays() {
    let points = [Point { x: 1.0, y: 2.0 }];
    // A line without a previous point, a cubic without its points and a conic without a weight.
    for verbs in [[1u8, 0], [0, 4], [0, 3]] {
        for direction in [VerbDirection::Forward, VerbDirection::Backward] {
            for evaluation in [ConicEvaluation::AsConic, ConicEvaluation::AsQuadratics] {
                let mut it = PathIterator::new(&points, &verbs, &[], direction, evaluation, 0.25);
                let _ = it.count();
                let mut steps = 0;
                while it.next(&mut [Point::default(); 4]) != Verb::Done {
                    steps += 1;
                    assert!(steps <= verbs.len());
                }
            }
        }
    }
    let mut it = PathIterator::new(&points, &[9, 0], &[], VerbDirection::Forward, ConicEvaluation::AsConic, 0.25);
    assert_eq!(it.next(&mut [Point::default(); 4]), Verb::Done, "unknown verbs stop the walk");
}
