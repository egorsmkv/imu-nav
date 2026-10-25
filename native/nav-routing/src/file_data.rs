//! Read-only mapping ownership. 32-bit Android uses bounded windows instead of mapping an entire country.
use crate::Result;
use memmap2::{Mmap, MmapOptions};
#[cfg(any(test, target_pointer_width = "32"))]
use std::{collections::VecDeque, sync::Mutex};
use std::{fs::File, path::Path};
#[cfg(any(test, target_pointer_width = "32"))]
const WINDOW_BYTES: usize = 16 * 1024 * 1024;
#[cfg(any(test, target_pointer_width = "32"))]
const MAX_WINDOWS: usize = 4;
/// 64-bit hosts/phones map the whole file. On 32-bit devices each profile retains at most 64 MiB.
pub struct FileData {
    length: usize,
    backend: Backend,
}
enum Backend {
    #[cfg(target_pointer_width = "64")]
    Whole(Mmap),
    #[cfg(any(test, target_pointer_width = "32"))]
    Windowed {
        file: File,
        windows: Mutex<VecDeque<(usize, Mmap)>>,
    },
}
impl FileData {
    pub fn open(path: &Path) -> Result<Self> {
        let file = File::open(path)?;
        let length = usize::try_from(file.metadata()?.len())?;
        #[cfg(target_pointer_width = "64")]
        // Installed files cannot change while a routing reader owns them.
        let backend = Backend::Whole(unsafe { MmapOptions::new().map(&file)? });
        #[cfg(target_pointer_width = "32")]
        let backend = Backend::Windowed {
            file,
            windows: Mutex::new(VecDeque::new()),
        };
        Ok(Self { length, backend })
    }
    pub fn len(&self) -> usize {
        self.length
    }
    pub fn bytes<const N: usize>(&self, offset: usize) -> [u8; N] {
        self.with_slice(offset, N, |slice| {
            let mut result = [0; N];
            result.copy_from_slice(slice);
            result
        })
    }
    pub fn with_slice<T>(&self, offset: usize, length: usize, work: impl FnOnce(&[u8]) -> T) -> T {
        assert!(
            offset <= self.length && length <= self.length - offset,
            "routing range outside validated file"
        );
        match &self.backend {
            #[cfg(target_pointer_width = "64")]
            Backend::Whole(map) => work(&map[offset..offset + length]),
            #[cfg(any(test, target_pointer_width = "32"))]
            Backend::Windowed { file, windows } => {
                let base = offset / WINDOW_BYTES * WINDOW_BYTES;
                let within = offset - base;
                if length > WINDOW_BYTES - within {
                    // Headers/records may straddle a window; bounded names are the only variable records.
                    let mut buffer = vec![0; length];
                    read_at(file, offset, &mut buffer);
                    return work(&buffer);
                }
                let mut cache = windows.lock().expect("routing window cache poisoned");
                if let Some(index) = cache.iter().position(|(start, _)| *start == base) {
                    let entry = cache.remove(index).expect("existing routing window");
                    cache.push_front(entry);
                } else {
                    if cache.len() == MAX_WINDOWS {
                        cache.pop_back();
                    }
                    // Base is a multiple of 16 MiB (also 16 KiB); only the mapped region is retained.
                    let map = unsafe {
                        MmapOptions::new()
                            .offset(base as u64)
                            .len(WINDOW_BYTES.min(self.length - base))
                            .map(file)
                    }
                    .expect("cannot map routing window");
                    cache.push_front((base, map));
                }
                work(&cache.front().expect("cached routing window").1[within..within + length])
            }
        }
    }
}
#[cfg(any(test, target_pointer_width = "32"))]
fn read_at(file: &File, offset: usize, buffer: &mut [u8]) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::FileExt;
        file.read_exact_at(buffer, offset as u64)
            .expect("cannot read routing record");
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::FileExt;
        let mut done = 0;
        while done < buffer.len() {
            let read = file
                .seek_read(&mut buffer[done..], (offset + done) as u64)
                .expect("cannot read routing record");
            assert!(read > 0, "truncated routing record");
            done += read;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Seek, SeekFrom, Write};
    #[test]
    fn bounded_windows_read_crossing_records_and_evict_old_mappings() {
        let mut file = tempfile::tempfile().unwrap();
        file.set_len((WINDOW_BYTES * 6) as u64).unwrap();
        for index in 1..6 {
            file.seek(SeekFrom::Start((WINDOW_BYTES * index - 4) as u64))
                .unwrap();
            file.write_all(&[1, 2, 3, 4, 5, 6, 7, 8]).unwrap();
        }
        let data = FileData {
            length: WINDOW_BYTES * 6,
            backend: Backend::Windowed {
                file,
                windows: Mutex::new(VecDeque::new()),
            },
        };
        for index in 1..6 {
            assert_eq!(data.bytes::<4>(WINDOW_BYTES * index), [5, 6, 7, 8]);
            assert_eq!(
                data.bytes::<8>(WINDOW_BYTES * index - 4),
                [1, 2, 3, 4, 5, 6, 7, 8]
            );
        }
        if let Backend::Windowed { windows, .. } = &data.backend {
            assert_eq!(windows.lock().unwrap().len(), MAX_WINDOWS);
        }
    }
}
