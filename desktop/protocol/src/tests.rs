use super::*;

#[test]
fn both_wire_directions_validate_frames_and_preserve_phone_payloads() {
    let fixture = include_bytes!("../fixtures/state.ndjson");
    let frame = decode(fixture).unwrap();
    let encoded = encode(&frame).unwrap();
    assert_eq!(decode(&encoded).unwrap(), frame);
    let original: serde_json::Value = serde_json::from_slice(fixture).unwrap();
    let written: serde_json::Value = serde_json::from_slice(&encoded).unwrap();
    for key in ["sequence", "elapsed_ms", "utc_ms", "age_ms"] {
        assert_eq!(original["state"][key], written["state"][key]);
    }
    let Frame::State { mut state, version } = frame else {
        panic!("state fixture");
    };
    state.guidance.as_mut().unwrap().remaining_m = f64::NAN;
    assert!(encode(&Frame::State { state, version }).is_err());
    let mut raw: serde_json::Value = serde_json::from_slice(fixture).unwrap();
    for invalid in [serde_json::json!(0), serde_json::json!(-1)] {
        raw["state"]["sequence"] = invalid;
        assert!(decode(&serde_json::to_vec(&raw).unwrap()).is_err());
    }
    raw = serde_json::from_slice(fixture).unwrap();
    raw["state"]["guidance"]["source"] = serde_json::json!("UNKNOWN");
    assert!(decode(&serde_json::to_vec(&raw).unwrap()).is_err());
    raw = serde_json::from_slice(fixture).unwrap();
    raw["state"]["elapsed_ms"] = serde_json::json!(u64::MAX);
    assert!(decode(&serde_json::to_vec(&raw).unwrap()).is_err());
    assert!(matches!(
        decode(br#"{"type":"hello","version":255}"#),
        Err(ProtocolError::UnsupportedVersion)
    ));
}

#[test]
fn shared_phone_fixture_preserves_estimated_rmc_units_and_geography() {
    let Frame::State { version, state } =
        decode(include_bytes!("../fixtures/state.ndjson")).unwrap()
    else {
        panic!("state fixture");
    };
    assert_eq!(version, VERSION);
    assert!(state.valid());
    assert_eq!(state.nmea, include_str!("../fixtures/position.nmea"));
    let fix = parse_rmc(&state.nmea).unwrap();
    assert_eq!(fix.point, [-50.5, -30.25]);
    assert!((fix.speed_mps - 10.0).abs() < 0.001);
    assert_eq!(fix.bearing_deg, 90.0);
    let guidance: Guidance =
        serde_json::from_str(include_str!("../fixtures/guidance.json")).unwrap();
    assert_eq!(state.guidance, Some(guidance));
}

#[test]
fn fragmented_lines_are_bounded_and_bad_rmc_is_rejected() {
    let mut lines = Lines::default();
    let input = include_bytes!("../fixtures/state.ndjson");
    let mut complete = Vec::new();
    for byte in input {
        complete.extend(lines.push(&[*byte]).unwrap());
    }
    assert_eq!(complete, [input.to_vec()]);
    assert!(lines.push(&vec![b'a'; MAX_FRAME_BYTES + 1]).is_err());
    assert!(parse_rmc("$GPRMC,invalid*00\r\n").is_err());
    assert!(decode(&vec![b'a'; MAX_FRAME_BYTES + 1]).is_err());
}

fn rmc(
    latitude: &str,
    lat_hemi: &str,
    longitude: &str,
    lon_hemi: &str,
    status: &str,
    date: &str,
) -> String {
    let body = format!(
        "GPRMC,235959.999,{status},{latitude},{lat_hemi},{longitude},{lon_hemi},0.000,360.000,{date},,,E"
    );
    let checksum = body.bytes().fold(0, |sum, byte| sum ^ byte);
    format!("${body}*{checksum:02X}\r\n")
}

#[test]
fn coordinate_boundaries_zero_and_rounded_course_are_validated() {
    assert_eq!(
        parse_rmc(&rmc("0000.000000", "N", "00000.000000", "E", "A", "010126"))
            .unwrap()
            .point,
        [0.0, 0.0]
    );
    let pole = parse_rmc(&rmc("9000.000000", "N", "18000.000000", "E", "A", "311225")).unwrap();
    assert_eq!(pole.point, [90.0, 180.0]);
    assert_eq!(pole.bearing_deg, 0.0);
    for sentence in [
        rmc("9060.000000", "N", "18000.000000", "E", "A", "010126"),
        rmc("0000.000000", "N", "00000.000000", "E", "V", "010126"),
        rmc("0000.000000", "Q", "00000.000000", "E", "A", "010126"),
        rmc("0000.000000", "N", "00000.000000", "E", "A", "310226"),
    ] {
        assert!(parse_rmc(&sentence).is_err());
    }
}

#[test]
fn rmc_required_fields_optional_mode_and_trailing_fields_keep_their_contract() {
    let sentence = |body: &str| {
        let checksum = body.bytes().fold(0, |sum, byte| sum ^ byte);
        format!("${body}*{checksum:02X}\r\n")
    };
    let required = "GPRMC,000000,A,0000.0,N,00000.0,E,0,0,010126,,";
    for suffix in ["", ",E", ",A", ",E,extra"] {
        assert!(parse_rmc(&sentence(&format!("{required}{suffix}"))).is_ok());
    }
    assert!(parse_rmc(&sentence(&format!("{required},N"))).is_err());
    assert!(parse_rmc(&sentence(required.strip_suffix(',').unwrap())).is_err());
}

#[test]
fn routes_replace_only_on_complete_matching_transfers() {
    let route = Route {
        revision: Revision::new(1).unwrap(),
        geometry: vec![[0.0, 0.0]; 400],
        summary: "Synthetic".into(),
        destination: [0.0, 0.0],
    };
    let frames = route.frames().unwrap();
    assert!(frames.iter().all(|frame| frame.len() <= MAX_FRAME_BYTES));
    let mut assembly = RouteAssembly::default();
    for bytes in &frames[..frames.len() - 1] {
        assert!(assembly.accept(decode(bytes).unwrap()).unwrap().is_none());
    }
    assert_eq!(
        assembly
            .accept(decode(frames.last().unwrap()).unwrap())
            .unwrap(),
        Some(route)
    );
    assert!(
        assembly
            .accept(Frame::RouteEnd {
                revision: Revision::new(2).unwrap()
            })
            .is_err()
    );
    assembly
        .accept(Frame::RouteStart {
            revision: Revision::new(2).unwrap(),
            points: 2,
            summary: String::new(),
            destination: [0.0, 0.0],
        })
        .unwrap();
    assert!(
        assembly
            .accept(Frame::RouteChunk {
                revision: Revision::new(1).unwrap(),
                offset: 0,
                geometry: vec![[0.0, 0.0]]
            })
            .is_err()
    );
    assert!(
        assembly
            .accept(Frame::RouteStart {
                revision: Revision::new(1).unwrap(),
                points: MAX_ROUTE_POINTS + 1,
                summary: String::new(),
                destination: [0.0, 0.0]
            })
            .is_err()
    );
}
