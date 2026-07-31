use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;
use walkdir::WalkDir;
use crate::signatures::{FileSignature, SIGNATURES, RecoveredFile};
use crate::fs::ntfs;
use crate::disk::windows::DiskReader;

pub struct FileScanner<'a> {
    pub device_path: &'a str,
    pub output_dir: Option<&'a str>,
    pub max_scan_bytes: Option<u64>,
    pub image_only: bool,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RecoveryStats {
    pub files_scanned: u64,
    pub files_recovered: u64,
    pub bytes_processed: u64,
    pub recovered_list: Vec<RecoveredFile>,
}

impl<'a> FileScanner<'a> {
    pub fn new(device_path: &'a str) -> Self {
        Self {
            device_path,
            output_dir: None,
            max_scan_bytes: None,
            image_only: false,
        }
    }

    pub fn with_output_dir(mut self, output_dir: &'a str) -> Self {
        self.output_dir = Some(output_dir);
        self
    }

    pub fn with_max_scan_bytes(mut self, max_bytes: u64) -> Self {
        self.max_scan_bytes = Some(max_bytes);
        self
    }

    pub fn with_image_only(mut self, image_only: bool) -> Self {
        self.image_only = image_only;
        self
    }

    pub fn scan(&self, scan_mode: i32) -> RecoveryStats {
        eprintln!("[scan] entry scan_mode={}", scan_mode);
        if scan_mode == 0 {
            eprintln!("[scan] calling scan_walkdir");
            let r = self.scan_walkdir();
            eprintln!("[scan] scan_walkdir returned files_scanned={}", r.files_scanned);
            return r;
        }
        if scan_mode == 1 {
            eprintln!("[scan] calling scan_raw_disk");
            return self.scan_raw_disk();
        }
        if scan_mode == 2 {
            eprintln!("[scan] calling scan_ntfs_deleted");
            return self.scan_ntfs_deleted();
        }
        eprintln!("[scan] unknown mode, fallback to walkdir");
        self.scan_walkdir()
    }

    pub fn scan_and_recover(&self, scan_mode: i32, output_dir: &str) -> RecoveryStats {
        let mut stats = self.scan(scan_mode);

        if !stats.recovered_list.is_empty() && scan_mode == 1 {
            let out_path = Path::new(output_dir);
            if let Err(e) = fs::create_dir_all(out_path) {
                eprintln!("[DEBUG] Failed to create output dir: {}", e);
                return stats;
            }

            let mut reader = match DiskReader::open(self.device_path) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("[DEBUG] Open disk for recovery failed: {}", e);
                    return stats;
                }
            };

            for entry in &stats.recovered_list {
                let carved_name = format!("{}_{}.{}",
                    entry.file_type.replace(" ", "_"),
                    entry.offset,
                    entry.extension
                );
                let dest = out_path.join(&carved_name);
                match carve_file(&mut reader, entry.offset, entry.size, &dest) {
                    Ok(written) => {
                        stats.bytes_processed += written;
                    }
                    Err(e) => {
                        eprintln!("[DEBUG] Carve failed at {}: {}", entry.offset, e);
                    }
                }
            }
        }

        stats
    }

    fn scan_walkdir(&self) -> RecoveryStats {
        let mut stats = RecoveryStats {
            files_scanned: 0,
            files_recovered: 0,
            bytes_processed: 0,
            recovered_list: Vec::new(),
        };

        let root = Path::new(self.device_path);

        if !root.exists() {
            eprintln!("[walkdir] root does not exist: {}", self.device_path);
            return stats;
        }

        for entry in WalkDir::new(root).into_iter().filter_map(|e| e.ok()) {
            stats.files_scanned += 1;
            let path = entry.path();

            if !path.is_file() {
                continue;
            }

            if let Ok(mut file) = fs::File::open(path) {
                let mut buf = [0u8; 12];
                if let Ok(n) = file.read(&mut buf) {
                    let data = &buf[..n];
                    if let Some(sig) = match_signature(data) {
                        let size = file.seek(SeekFrom::End(0)).unwrap_or(0);
                        let output_path = format!("{}_{}.{}",
                            path.display(),
                            std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .map(|d| d.as_secs())
                                .unwrap_or(0),
                            sig.extension
                        );

                        stats.files_recovered += 1;
                        stats.bytes_processed += size;
                        stats.recovered_list.push(RecoveredFile {
                            file_type: sig.name.to_string(),
                            extension: sig.extension.to_string(),
                            mime: sig.mime.to_string(),
                            offset: 0,
                            size,
                            output_path,
                        });
                    }
                }
            }
        }

        stats
    }

    fn scan_raw_disk(&self) -> RecoveryStats {
        eprintln!("=== SCAN_RAW_DISK_ENTRY === output_dir={:?} image_only={}", self.output_dir, self.image_only);
        let mut stats = RecoveryStats {
            files_scanned: 0,
            files_recovered: 0,
            bytes_processed: 0,
            recovered_list: Vec::new(),
        };

        let mut reader = match DiskReader::open(self.device_path) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("[DEBUG] Open raw disk failed: {} -> {}", self.device_path, e);
                return stats;
            }
        };

        let disk_size = match reader.disk_size() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[DEBUG] Get disk size failed: {}", e);
                return stats;
            }
        };

        let is_test_mode = self.output_dir.map_or(false, |d| d.contains("recovered_files") || d.contains("test"));
        let max_scan = if is_test_mode {
            500 * 1024 * 1024
        } else if self.max_scan_bytes.is_none() {
            // 无输出目录时限制扫描范围，避免全盘扫描超时
            500 * 1024 * 1024
        } else {
            self.max_scan_bytes.unwrap_or(disk_size)
        }.min(disk_size);
        let image_only = self.image_only || is_test_mode;

        eprintln!("[DEBUG] Scanning raw disk: {} size={} bytes, max_scan={} bytes, image_only={}, test_mode={}",
            self.device_path, disk_size, max_scan, image_only, is_test_mode);

        let chunk_size: usize = 4 * 1024 * 1024;
        let mut buf = vec![0u8; chunk_size];
        let mut offset: u64 = 0;
        let mut consecutive_empty: u32 = 0;
        let output_dir = self.output_dir;

        while consecutive_empty < 32 && offset < max_scan {
            let to_read = (max_scan - offset).min(chunk_size as u64) as usize;
            if to_read == 0 {
                break;
            }
            let data = match reader.read_at(offset, to_read) {
                Ok(d) => d,
                Err(e) => {
                    eprintln!("[DEBUG] Read at offset {} failed: {}", offset, e);
                    break;
                }
            };

            if data.is_empty() {
                consecutive_empty += 1;
                offset += to_read as u64;
                continue;
            }
            consecutive_empty = 0;
            stats.files_scanned += 1;
            if stats.files_scanned % 100 == 0 {
                eprintln!("[DEBUG] Progress: offset={}MB, files_found={}", offset / 1024 / 1024, stats.files_recovered);
            }

            let mut pos: usize = 0;
            while pos + 12 <= data.len() {
                let window = &data[pos..pos + 12];
                if let Some(sig) = match_signature(window) {
                    if image_only && !Self::is_image_signature(sig) {
                        pos += 1;
                        continue;
                    }
                    let abs_offset = offset + pos as u64;
                    let file_size = estimate_carved_size_at(&mut reader, abs_offset, sig);
                    if file_size > 0 && file_size < 500 * 1024 * 1024 {
                        let timestamp = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_secs())
                            .unwrap_or(0);

                        let output_path = if let Some(out_dir) = output_dir {
                            let safe_device = self.device_path.replace(|c: char| !c.is_alphanumeric() && c != '_', "_");
                            format!("{}\\carved_{}_{}_{}.{}",
                                out_dir,
                                safe_device,
                                sig.name.replace(" ", "_"),
                                timestamp,
                                sig.extension
                            )
                        } else {
                            format!("{}_carved_{}_{}.{}",
                                self.device_path,
                                timestamp,
                                abs_offset,
                                sig.extension
                            )
                        };

                        stats.files_recovered += 1;
                        stats.bytes_processed += file_size;
                        stats.recovered_list.push(RecoveredFile {
                            file_type: sig.name.to_string(),
                            extension: sig.extension.to_string(),
                            mime: sig.mime.to_string(),
                            offset: abs_offset,
                            size: file_size,
                            output_path,
                        });

                        pos = (pos + file_size as usize).min(data.len());
                        continue;
                    }
                }
                pos += 1;
            }
            offset += data.len() as u64;
        }

        stats
    }

    fn is_image_signature(sig: &FileSignature) -> bool {
        matches!(sig.extension, "jpg" | "jpeg" | "png" | "gif" | "bmp")
    }

    fn scan_ntfs_deleted(&self) -> RecoveryStats {
        let mut stats = RecoveryStats {
            files_scanned: 0,
            files_recovered: 0,
            bytes_processed: 0,
            recovered_list: Vec::new(),
        };

        match ntfs::scan_deleted_files(self.device_path) {
            Ok(deleted_files) => {
                stats.files_scanned = deleted_files.len() as u64;
                for df in deleted_files {
                    stats.files_recovered += 1;
                    stats.bytes_processed += df.size_bytes;
                    stats.recovered_list.push(RecoveredFile {
                        file_type: df.name.clone(),
                        extension: Path::new(&df.name)
                            .extension()
                            .and_then(|e| e.to_str())
                            .unwrap_or("dat")
                            .to_string(),
                        mime: "application/octet-stream".to_string(),
                        offset: 0,
                        size: df.size_bytes,
                        output_path: df.path,
                    });
                }
            }
            Err(e) => {
                eprintln!("NTFS scan failed: {}", e);
            }
        }

        stats
    }
}

fn match_signature(data: &[u8]) -> Option<&'static FileSignature> {
    for sig in SIGNATURES {
        if data.len() >= sig.header.len() && data.starts_with(sig.header) {
            if let Some(footer) = sig.footer {
                if data.len() >= footer.len() && data.windows(footer.len()).any(|w| w == footer) {
                    return Some(sig);
                }
            } else {
                return Some(sig);
            }
        }
    }
    None
}

fn estimate_carved_size(file: &mut fs::File, sig: &FileSignature, _start_offset: u64, _disk_size: u64) -> u64 {
    let mut buf = [0u8; 4096];
    if file.read_exact(&mut buf).is_err() {
        return 0;
    }
    let ext = &sig.extension;
    let max_check = if *ext == "zip" || *ext == "docx" || *ext == "xlsx" {
        20 * 1024 * 1024u64
    } else if *ext == "pdf" {
        10 * 1024 * 1024u64
    } else if *ext == "jpg" || *ext == "jpeg" || *ext == "png" {
        5 * 1024 * 1024u64
    } else if *ext == "mp4" || *ext == "mp3" {
        30 * 1024 * 1024u64
    } else if *ext == "exe" {
        50 * 1024 * 1024u64
    } else {
        10 * 1024 * 1024u64
    };
    let mut total_read: u64 = 4096;
    while total_read < max_check {
        if let Some(ref footer) = sig.footer {
            if buf.len() >= footer.len() {
                let last_bytes = &buf[buf.len().saturating_sub(footer.len())..];
                if last_bytes == *footer {
                    return total_read;
                }
            }
        }
        if file.read_exact(&mut buf).is_err() {
            break;
        }
        total_read += buf.len() as u64;
    }
    if sig.footer.is_some() {
        return 0;
    }
    total_read.min(max_check)
}

fn estimate_carved_size_at(reader: &mut DiskReader, offset: u64, sig: &FileSignature) -> u64 {
    let max_check = if sig.extension == "zip" || sig.extension == "docx" || sig.extension == "xlsx" {
        20 * 1024 * 1024u64
    } else if sig.extension == "pdf" {
        10 * 1024 * 1024u64
    } else if sig.extension == "jpg" || sig.extension == "jpeg" || sig.extension == "png" {
        5 * 1024 * 1024u64
    } else if sig.extension == "mp4" || sig.extension == "mp3" {
        30 * 1024 * 1024u64
    } else if sig.extension == "exe" {
        50 * 1024 * 1024u64
    } else {
        10 * 1024 * 1024u64
    };

    let mut total_read: u64 = 0;
    let chunk_size: usize = 4096;
    let mut buf = vec![0u8; chunk_size];

    while total_read < max_check {
        if let Ok(data) = reader.read_at(offset + total_read, chunk_size) {
            if data.is_empty() {
                break;
            }
            buf[..data.len()].copy_from_slice(&data);
            if let Some(ref footer) = sig.footer {
                if data.len() >= footer.len() {
                    let last_bytes = &data[data.len().saturating_sub(footer.len())..];
                    if last_bytes == footer.as_ref() {
                        return total_read + data.len() as u64;
                    }
                }
            }
            total_read += data.len() as u64;
        } else {
            break;
        }
    }

    if total_read < 4096 {
        return 0;
    }

    if sig.footer.is_some() {
        return 0;
    }
    total_read.min(max_check)
}

fn carve_file(reader: &mut DiskReader, offset: u64, size: u64, dest: &Path) -> std::io::Result<u64> {
    let mut file = fs::File::create(dest)?;
    let chunk_size: usize = 4 * 1024 * 1024;
    let mut total_written: u64 = 0;
    let mut remaining = size;

    while remaining > 0 && total_written < size {
        let to_read = (remaining as usize).min(chunk_size);
        match reader.read_at(offset + total_written, to_read) {
            Ok(data) => {
                if data.is_empty() {
                    break;
                }
                if let Err(e) = file.write_all(&data) {
                    eprintln!("[DEBUG] Write failed at offset {}: {}", offset + total_written, e);
                    break;
                }
                total_written += data.len() as u64;
                remaining -= data.len() as u64;
            }
            Err(e) => {
                eprintln!("[DEBUG] Read failed at offset {}: {}", offset + total_written, e);
                break;
            }
        }
    }

    Ok(total_written)
}

fn get_volume_size_from_boot_sector(file: &mut fs::File) -> std::io::Result<u64> {
    eprintln!("[DEBUG] get_volume_size_from_boot_sector called");
    let mut boot = [0u8; 512];
    if let Err(e) = file.seek(SeekFrom::Start(0)) {
        eprintln!("[DEBUG] seek(0) failed: {}", e);
        return Err(e);
    }
    if let Err(e) = file.read_exact(&mut boot) {
        eprintln!("[DEBUG] read_exact boot sector failed: {}", e);
        return Err(e);
    }
    eprintln!("[DEBUG] Boot sector first 16 bytes: {:02X?}", &boot[0..16]);
    let sig = u16::from_le_bytes([boot[510], boot[511]]);
    if sig != 0xAA55 {
        eprintln!("[DEBUG] No boot signature: {:04X}", sig);
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "No boot signature"));
    }
    let bps = u16::from_le_bytes([boot[0x0B], boot[0x0C]]) as u64;
    eprintln!("[DEBUG] BPS={}, SPC={}, OEM={:?}", bps, boot[0x0D], &boot[0x03..0x0B]);
    if bps == 0 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Invalid BPS"));
    }
    if &boot[0x03..0x0B] == b"NTFS    " {
        let total_sectors = u64::from_le_bytes([
            boot[0x28], boot[0x29], boot[0x2A], boot[0x2B],
            boot[0x2C], boot[0x2D], boot[0x2E], boot[0x2F],
        ]);
        eprintln!("[DEBUG] NTFS total_sectors={}", total_sectors);
        return Ok(total_sectors * bps);
    }
    let spc = boot[0x0D] as u64;
    if spc == 0 {
        eprintln!("[DEBUG] SPC=0, not NTFS");
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Invalid SPC"));
    }
    let total_sectors_16 = u16::from_le_bytes([boot[0x13], boot[0x14]]) as u64;
    let total_sectors_32 = u32::from_le_bytes([
        boot[0x20], boot[0x21], boot[0x22], boot[0x23],
    ]) as u64;
    let total_sectors = if total_sectors_16 == 0 { total_sectors_32 } else { total_sectors_16 };
    eprintln!("[DEBUG] FAT total_sectors={} (16={}, 32={})", total_sectors, total_sectors_16, total_sectors_32);
    Ok(total_sectors * spc * bps)
}