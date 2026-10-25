//! Shared trusted graph-export reader for isolated routing experiments.
use cch::{INF_WEIGHT, graph::Graph};
use std::{
    error::Error,
    fs::File,
    io::{BufReader, Read},
    path::Path,
};
pub type Result<T> = std::result::Result<T, Box<dyn Error>>;
/// Shared directed road states and legal turn transitions emitted by CchGraphExport.
pub struct Input {
    pub graph: Graph,
    pub from: Vec<u32>,
    pub to: Vec<u32>,
    pub initial: Vec<u32>,
    pub lat: Vec<f32>,
    pub lon: Vec<f32>,
}
fn number(reader: &mut impl Read) -> Result<u32> {
    let mut bytes = [0; 4];
    reader.read_exact(&mut bytes)?;
    Ok(u32::from_be_bytes(bytes))
}
/// Consume only our bounded, local export format, validating CSR endpoints before calling CCH.
pub fn read_input(path: &Path) -> Result<Input> {
    let mut reader = BufReader::new(File::open(path)?);
    if number(&mut reader)? != 0x43434831 {
        return Err("Invalid graph magic".into());
    }
    let count = number(&mut reader)? as usize;
    if count == 0 || count > 30_000_000 {
        return Err("Unsupported state count".into());
    }
    let mut input = Input {
        graph: Graph {
            first_out: vec![0],
            head: vec![],
            weight: vec![],
        },
        from: Vec::with_capacity(count),
        to: Vec::with_capacity(count),
        initial: Vec::with_capacity(count),
        lat: Vec::with_capacity(count),
        lon: Vec::with_capacity(count),
    };
    for _ in 0..count {
        input.from.push(number(&mut reader)?);
        input.to.push(number(&mut reader)?);
        let _key = number(&mut reader)?;
        input.initial.push(number(&mut reader)?);
        input.lat.push(f32::from_bits(number(&mut reader)?));
        input.lon.push(f32::from_bits(number(&mut reader)?));
        let degree = number(&mut reader)?;
        if degree > 10000 {
            return Err("Excessive degree".into());
        }
        for _ in 0..degree {
            let head = number(&mut reader)?;
            let weight = number(&mut reader)?;
            if head as usize >= count || weight >= INF_WEIGHT {
                return Err("Invalid arc".into());
            }
            input.graph.head.push(head);
            input.graph.weight.push(weight);
        }
        input
            .graph
            .first_out
            .push(u32::try_from(input.graph.head.len())?);
    }
    let mut tail = [0];
    if reader.read(&mut tail)? != 0 {
        return Err("Trailing bytes".into());
    }
    Ok(input)
}
