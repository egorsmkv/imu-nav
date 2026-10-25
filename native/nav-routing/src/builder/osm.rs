//! Streaming PBF/XML reader; the two-pass importer retains only road nodes and useful search data.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::Result;
use std::{collections::BTreeMap, fs::File, io::BufReader, path::Path};
pub type Tags = BTreeMap<String, String>;
#[derive(Clone, Debug)]
pub enum Object {
    Node {
        id: i64,
        lat: f64,
        lon: f64,
        tags: Tags,
    },
    Way {
        id: i64,
        nodes: Vec<i64>,
        tags: Tags,
    },
    Relation {
        members: Vec<(String, i64, String)>,
        tags: Tags,
    },
}
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn read(path: &Path, mut consume: impl FnMut(Object) -> Result<()>) -> Result<()> {
    if path.extension().is_some_and(|e| e == "pbf") {
        for object in osmpbfreader::OsmPbfReader::new(File::open(path)?).par_iter() {
            let object = object?;
            let tags = object
                .tags()
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect();
            consume(match object {
                osmpbfreader::OsmObj::Node(node) => Object::Node {
                    id: node.id.0,
                    lat: node.lat(),
                    lon: node.lon(),
                    tags,
                },
                osmpbfreader::OsmObj::Way(way) => Object::Way {
                    id: way.id.0,
                    nodes: way.nodes.into_iter().map(|n| n.0).collect(),
                    tags,
                },
                osmpbfreader::OsmObj::Relation(relation) => Object::Relation {
                    members: relation
                        .refs
                        .into_iter()
                        .map(|m| {
                            (
                                match m.member {
                                    osmpbfreader::OsmId::Node(_) => "node",
                                    osmpbfreader::OsmId::Way(_) => "way",
                                    osmpbfreader::OsmId::Relation(_) => "relation",
                                }
                                .into(),
                                m.member.inner_id(),
                                m.role.to_string(),
                            )
                        })
                        .collect(),
                    tags,
                },
            })?;
        }
        return Ok(());
    }
    read_xml(path, consume)
}

fn read_xml(path: &Path, mut consume: impl FnMut(Object) -> Result<()>) -> Result<()> {
    use quick_xml::events::Event;
    let mut reader = quick_xml::Reader::from_reader(BufReader::new(File::open(path)?));
    let mut buffer = Vec::new();
    let mut current = None;
    loop {
        let event = reader.read_event_into(&mut buffer)?;
        let empty = matches!(event, Event::Empty(_));
        match event {
            Event::Start(ref e) | Event::Empty(ref e) => {
                let attrs: Tags = e
                    .attributes()
                    .map(|a| {
                        let a = a?;
                        Ok((
                            String::from_utf8(a.key.as_ref().to_vec())?,
                            a.decode_and_unescape_value(reader.decoder())?.into_owned(),
                        ))
                    })
                    .collect::<Result<_>>()?;
                let field = |key: &str| {
                    attrs
                        .get(key)
                        .ok_or_else(|| format!("missing XML attribute {key}"))
                };
                match e.name().as_ref() {
                    b"node" => {
                        current = Some(Object::Node {
                            id: field("id")?.parse()?,
                            lat: field("lat")?.parse()?,
                            lon: field("lon")?.parse()?,
                            tags: Tags::new(),
                        });
                    }
                    b"way" => {
                        current = Some(Object::Way {
                            id: field("id")?.parse()?,
                            nodes: vec![],
                            tags: Tags::new(),
                        });
                    }
                    b"relation" => {
                        current = Some(Object::Relation {
                            members: vec![],
                            tags: Tags::new(),
                        });
                    }
                    b"nd" => {
                        if let Some(Object::Way { nodes, .. }) = &mut current {
                            nodes.push(field("ref")?.parse()?);
                        }
                    }
                    b"member" => {
                        if let Some(Object::Relation { members, .. }) = &mut current {
                            members.push((
                                field("type")?.clone(),
                                field("ref")?.parse()?,
                                field("role")?.clone(),
                            ));
                        }
                    }
                    b"tag" => {
                        if let Some(object) = &mut current {
                            let tags = match object {
                                Object::Node { tags, .. }
                                | Object::Way { tags, .. }
                                | Object::Relation { tags, .. } => tags,
                            };
                            tags.insert(field("k")?.clone(), field("v")?.clone());
                        }
                    }
                    _ => {}
                }
                if empty
                    && matches!(e.name().as_ref(), b"node" | b"way" | b"relation")
                    && let Some(object) = current.take()
                {
                    consume(object)?;
                }
            }
            Event::End(ref e) => {
                if matches!(e.name().as_ref(), b"node" | b"way" | b"relation")
                    && let Some(object) = current.take()
                {
                    consume(object)?;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buffer.clear();
    }
    Ok(())
}
