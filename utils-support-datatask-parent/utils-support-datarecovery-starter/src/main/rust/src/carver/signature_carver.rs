use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

use crate::signatures::{SIGNATURES, FileSignature};

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct CarvedEntry {
    pub name: String,
    pub path: String,
    pub size_bytes: u64,
    pub modified_timestamp: i64,
    pub deleted_timestamp: i64,
    pub recovery_score: i32,
    pub carved_signature: String,
}

pub struct SignatureCarver<'a> {
    disk_path: &'a str,
    output_dir: &'a str,
    max_file_size: u64,
}

impl<'a> SignatureCarver<'a> {
    pub fn new(disk_path: &'a str, output_dir: &'a str) -> Self {
        Self {
            disk_path,
            output_dir,
            max_file_size: 500 * 1024 * 1024,
        }
    }

    pub fn carve(&self) -> Vec<CarvedEntry> {
        let mut results = Vec::new();
        if let Ok(mut file) = File::open(self.disk_path) {
            let disk_size = file.metadata().map(|m| m.len()).unwrap_or(0);
            let mut buffer = vec![0u8; 64 * 1024];
            let mut offset = 0u64;
            while offset + 4096 < disk_size {
                let _ = file.seek(SeekFrom::Start(offset));
                if let Ok(_) = file.read_exact(&mut buffer[..4096]) {
                    if let Some(sig) = match_signature(&buffer[..4096]) {
                        let file_size = self.estimate_file_size(&mut file, sig);
                        if file_size > 0 && file_size <= self.max_file_size {
                            let carved = self.carve_file(&mut file, offset, file_size, sig);
                            if let Some(entry) = carved {
                                results.push(entry);
                                offset += file_size;
                                continue;
                            }
                        }
                    }
                }
                offset += 4096;
            }
        }
        results
    }

    fn estimate_file_size(&self, file: &mut File, sig: &FileSignature) -> u64 {
        let mut buf = [0u8; 4096];
        if file.read_exact(&mut buf).is_err() {
            return 0;
        }
        let mut size: u64 = 4096;
        let max_size = self.max_file_size.min(50 * 1024 * 1024);
        while size < max_size {
            if let Some(ref footer) = sig.footer {
                let window = &buf[buf.len().saturating_sub(footer.len())..];
                if window == footer.as_ref() {
                    return size;
                }
            } else if sig.extension == "png" || sig.extension == "jpg" {
                let marker_start = &buf[2..4];
                let len = u16::from_be_bytes([buf[4], buf[5]]) as usize;
                if marker_start == &[0xFF, 0xC0] || marker_start == &[0xFF, 0xC2] {
                    if len >= buf.len() {
                        return size + len as u64 - 2;
                    }
                }
            }
            if file.read_exact(&mut buf).is_err() {
                break;
            }
            size += buf.len() as u64;
        }
        let ext = &sig.extension;
        if *ext == "zip" || *ext == "pdf" {
            size.min(20 * 1024 * 1024)
        } else if *ext == "mp4" || *ext == "mp3" {
            size.min(50 * 1024 * 1024)
        } else {
            size.min(10 * 1024 * 1024)
        }
    }

    fn carve_file(&self, file: &mut File, start_offset: u64, size: u64, sig: &FileSignature) -> Option<CarvedEntry> {
        let mut data = vec![0u8; size as usize];
        if file.seek(SeekFrom::Start(start_offset)).is_err() {
            return None;
        }
        if file.read_exact(&mut data).is_err() {
            return None;
        }
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let filename = format!("carved_{}_{:04}.{}", timestamp, start_offset as usize, sig.extension);
        let output_path = Path::new(self.output_dir).join(&filename);
        if let Some(parent) = output_path.parent() {
            if fs::create_dir_all(parent).is_err() {
                return None;
            }
        }
        if let Ok(mut out) = File::create(&output_path) {
            if out.write_all(&data).is_err() {
                return None;
            }
        } else {
            return None;
        }
        Some(CarvedEntry {
            name: sig.name.to_string(),
            path: output_path.to_string_lossy().into_owned(),
            size_bytes: size,
            modified_timestamp: timestamp as i64,
            deleted_timestamp: 0,
            recovery_score: 60,
            carved_signature: sig.extension.to_string(),
        })
    }
}

fn match_signature(data: &[u8]) -> Option<&'static FileSignature> {
    for sig in SIGNATURES {
        if data.len() >= sig.header.len() && data.starts_with(sig.header) {
            if let Some(footer) = sig.footer {
                if data.windows(footer.len()).any(|w| w == footer) {
                    return Some(sig);
                }
            } else {
                return Some(sig);
            }
        }
    }
    None
}

pub fn carve_disk(disk_path: &str, output_dir: &str) -> Vec<CarvedEntry> {
    let carver = SignatureCarver::new(disk_path, output_dir);
    carver.carve()
}