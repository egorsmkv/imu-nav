//! Standard ZIP packaging; routing compression is independently measured, Tantivy stays at its original settings.
use std::{io::Write, path::Path};
/// Create a new archive without overwriting an existing one.
/// # Errors
/// Returns invalid level, traversal, input or output I/O errors.
pub fn write(directory: &Path, output: &Path, routing_level: i64) -> crate::Result<()> {
    if !matches!(routing_level, 6 | 9) {
        return Err("routing compression must be 6 or 9".into());
    }
    let parent = output.parent().filter(|path| !path.as_os_str().is_empty()).unwrap_or_else(|| Path::new("."));
    if parent.canonicalize()?.starts_with(directory.canonicalize()?) {
        return Err("archive output must be outside the pack directory".into());
    }
    let mut archive = zip::ZipWriter::new(std::fs::File::create_new(output)?);
    archive_directory(directory, directory, &mut archive, routing_level)?;
    archive.finish()?.flush()?;
    Ok(())
}
fn archive_directory(
    root: &Path,
    directory: &Path,
    archive: &mut zip::ZipWriter<std::fs::File>,
    routing_level: i64,
) -> crate::Result<()> {
    let mut entries = std::fs::read_dir(directory)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let path = entry.path();
        if path.is_dir() {
            archive_directory(root, &path, archive, routing_level)?;
        } else {
            archive.start_file(
                path.strip_prefix(root)?
                    .to_string_lossy()
                    .replace('\\', "/"),
                zip::write::SimpleFileOptions::default()
                    .compression_method(zip::CompressionMethod::Deflated)
                    .compression_level(
                        if directory == root
                            && path.extension().is_some_and(|value| value == "routing")
                        {
                            Some(routing_level)
                        } else {
                            None
                        },
                    ),
            )?;
            std::io::copy(&mut std::fs::File::open(path)?, archive)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn archive_cannot_include_its_own_output() {
        let temporary = tempfile::tempdir().unwrap();
        let output = temporary.path().join("self.zip");
        assert!(write(temporary.path(), &output, 6).is_err());
        assert!(!output.exists());
    }
    #[test]
    fn routing_levels_leave_search_payload_and_compression_unchanged() {
        let temporary = tempfile::tempdir().unwrap();
        let directory = temporary.path().join("pack");
        std::fs::create_dir_all(directory.join("search.tantivy")).unwrap();
        let search: Vec<u8> = (0u32..10_000).flat_map(u32::to_le_bytes).collect();
        std::fs::write(directory.join("search.tantivy/segment"), &search).unwrap();
        std::fs::write(directory.join("car.routing"), b"routing routing routing").unwrap();
        let mut records = vec![];
        for level in [6, 9] {
            let path = temporary.path().join(format!("{level}.zip"));
            write(&directory, &path, level).unwrap();
            let mut archive = zip::ZipArchive::new(std::fs::File::open(path).unwrap()).unwrap();
            let mut entry = archive.by_name("search.tantivy/segment").unwrap();
            let mut contents = vec![];
            std::io::Read::read_to_end(&mut entry, &mut contents).unwrap();
            assert_eq!(contents, search);
            records.push((entry.compressed_size(), entry.compression(), entry.crc32()));
        }
        assert_eq!(records[0], records[1]);
        assert_eq!(
            std::fs::read(directory.join("search.tantivy/segment")).unwrap(),
            search
        );
    }
}
