use std::io::{Read, Seek, SeekFrom};
use std::fs::File;
use crate::disk::windows::DiskReader;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DeletedFile {
    pub name: String,
    pub path: String,
    pub size_bytes: u64,
    pub created_timestamp: i64,
    pub modified_timestamp: i64,
    pub deleted_timestamp: i64,
    pub recovery_score: i32,
    pub data_runs: Vec<DataRun>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DataRun {
    pub start_cluster: u64,
    pub cluster_count: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct NtfsInfo {
    pub bytes_per_sector: u16,
    pub sectors_per_cluster: u8,
    pub mft_cluster: u64,
    pub mft_record_size: u32,
    pub volume_size: u64,
}

pub struct NtfsParser {
    reader: DiskReader,
    info: NtfsInfo,
}

impl NtfsParser {
    pub fn open(path: &str) -> std::io::Result<Self> {
        let mut reader = DiskReader::open(path)?;
        let boot_sector = reader.read_at(0, 512)?;
        if boot_sector.len() < 512 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Boot sector too small"));
        }
        let bytes_per_sector = u16::from_le_bytes([boot_sector[0x0B], boot_sector[0x0C]]);
        let sectors_per_cluster = boot_sector[0x0D];
        let mft_cluster = u64::from_le_bytes([
            boot_sector[0x30], boot_sector[0x31], boot_sector[0x32], boot_sector[0x33],
            boot_sector[0x34], boot_sector[0x35], boot_sector[0x36], boot_sector[0x37],
        ]);
        let cluster_size = sectors_per_cluster as u32 * bytes_per_sector as u32;
        let mft_record_size_raw = boot_sector[0x40] as i8;
        let mft_record_size = if mft_record_size_raw > 0 {
            mft_record_size_raw as u32 * cluster_size
        } else {
            1u32 << ((-mft_record_size_raw) as u32)
        };
        let total_sectors = u64::from_le_bytes([
            boot_sector[0x28], boot_sector[0x29], boot_sector[0x2A], boot_sector[0x2B],
            boot_sector[0x2C], boot_sector[0x2D], boot_sector[0x2E], boot_sector[0x2F],
        ]);
        let volume_size = total_sectors * bytes_per_sector as u64;
        Ok(Self {
            reader,
            info: NtfsInfo {
                bytes_per_sector,
                sectors_per_cluster,
                mft_cluster,
                mft_record_size,
                volume_size,
            },
        })
    }

    pub fn scan_deleted(&mut self) -> std::io::Result<Vec<DeletedFile>> {
        let mft_offset = self.info.mft_cluster * self.info.sectors_per_cluster as u64 * self.info.bytes_per_sector as u64;
        let record_size = self.info.mft_record_size as usize;
        let mut deleted_files = Vec::new();
        let chunk_size = 64 * 1024 * 1024;
        let total_scan = self.info.volume_size.min(4 * 1024 * 1024 * 1024);
        let mut total_records = 0u64;
        let mut found_deleted = 0;
        let mut offset = mft_offset;
        while offset < mft_offset + total_scan {
            let remaining = (mft_offset + total_scan - offset) as usize;
            let to_read = remaining.min(chunk_size);
            let mft_data = self.reader.read_at(offset, to_read)?;
            let num_records = mft_data.len() / record_size;
            total_records += num_records as u64;
            for i in 0..num_records {
                let record = &mft_data[i * record_size..(i + 1) * record_size];
                if record.len() < 48 {
                    continue;
                }
                let magic = &record[0..4];
                if magic != b"FILE" {
                    continue;
                }
                let flags = u16::from_le_bytes([record[0x16], record[0x17]]);
                let is_deleted = (flags & 0x0001) == 0;
                if !is_deleted {
                    continue;
                }
                found_deleted += 1;
                if let Some(deleted) = self.parse_mft_record(record, i as u64) {
                    deleted_files.push(deleted);
                }
            }
            offset += to_read as u64;
        }
        let debug = format!("MFT offset: {}\nRecord size: {}\nTotal scan: {}\nTotal records: {}\nFound {} deleted records, parsed: {}\n",
            mft_offset, record_size, total_scan, total_records, found_deleted, deleted_files.len());
        let _ = std::fs::write("G:\\work\\ntfs_debug.txt", debug);
        Ok(deleted_files)
    }

    fn parse_mft_record(&mut self, record: &[u8], record_number: u64) -> Option<DeletedFile> {
        let mut name = format!("file_{}.dat", record_number);
        let mut size = 0u64;
        let mut created = 0i64;
        let mut modified = 0i64;
        let mut deleted_ts = 0i64;
        let mut data_runs = Vec::new();
        let mut offset = 0x38usize;
        while offset + 8 < record.len() {
            let attr_type = u32::from_le_bytes([
                record[offset], record[offset+1], record[offset+2], record[offset+3]
            ]);
            if attr_type == 0xFFFFFFFF {
                break;
            }
            if attr_type == 0x30 {
                if offset + 0x60 < record.len() {
                    let name_len = record[offset + 0x40] as usize;
                    let name_space = record[offset + 0x41];
                    if name_len > 0 && name_space == 0 {
                        let name_start = offset + 0x42;
                        if name_start + name_len * 2 <= record.len() {
                            let name_bytes = &record[name_start..name_start + name_len * 2];
                            name = String::from_utf16_lossy(
                                &name_bytes.chunks(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect::<Vec<u16>>()
                            );
                        }
                    }
                }
            }
            if attr_type == 0x10 {
                if offset + 0x40 < record.len() {
                    created = i64::from_le_bytes([
                        record[offset+0x00], record[offset+0x01], record[offset+0x02], record[offset+0x03],
                        record[offset+0x04], record[offset+0x05], record[offset+0x06], record[offset+0x07],
                    ]);
                    modified = i64::from_le_bytes([
                        record[offset+0x08], record[offset+0x09], record[offset+0x0A], record[offset+0x0B],
                        record[offset+0x0C], record[offset+0x0D], record[offset+0x0E], record[offset+0x0F],
                    ]);
                    deleted_ts = i64::from_le_bytes([
                        record[offset+0x10], record[offset+0x11], record[offset+0x12], record[offset+0x13],
                        record[offset+0x14], record[offset+0x15], record[offset+0x16], record[offset+0x17],
                    ]);
                }
            }
            if attr_type == 0x80 {
                if offset + 0x20 < record.len() {
                    size = u64::from_le_bytes([
                        record[offset+0x30], record[offset+0x31], record[offset+0x32], record[offset+0x33],
                        record[offset+0x34], record[offset+0x35], record[offset+0x36], record[offset+0x37],
                    ]);
                }
                let data_run_offset = offset + 0x20;
                if data_run_offset < record.len() {
                    let dr_offset = record[data_run_offset] as usize;
                    if dr_offset > 0 && dr_offset + 24 < record.len() {
                        let dr_data = &record[dr_offset..];
                        let mut dr_pos = 0usize;
                        while dr_pos + 1 < dr_data.len() && dr_data[dr_pos] != 0 {
                            let header = dr_data[dr_pos];
                            let len_size = header & 0x0F;
                            let off_size = (header >> 4) & 0x0F;
                            dr_pos += 1;
                            if dr_pos + len_size as usize + off_size as usize >= dr_data.len() {
                                break;
                            }
                            let cluster_count = self.read_vlq(&dr_data[dr_pos..], len_size as usize);
                            dr_pos += len_size as usize;
                            let start_offset = self.read_vlq_signed(&dr_data[dr_pos..], off_size as usize);
                            dr_pos += off_size as usize;
                            if cluster_count > 0 {
                                data_runs.push(DataRun {
                                    start_cluster: if start_offset < 0 { 0 } else { start_offset as u64 },
                                    cluster_count,
                                });
                            }
                            if cluster_count == 0 || start_offset == 0 {
                                break;
                            }
                        }
                    }
                }
            }
            let attr_len = u16::from_le_bytes([
                record[offset+4], record[offset+5]
            ]) as usize;
            if attr_len == 0 {
                break;
            }
            offset += attr_len;
        }
        if data_runs.is_empty() && size == 0 {
            let _ = std::fs::write("G:\\work\\ntfs_debug2.txt", format!("Record {}: size={}, data_runs empty\n", record_number, size));
        }
        Some(DeletedFile {
            name,
            path: format!("\\$MFT\\{}", record_number),
            size_bytes: if size == 0 { 0 } else { size },
            created_timestamp: windows_time_to_unix(created),
            modified_timestamp: windows_time_to_unix(modified),
            deleted_timestamp: windows_time_to_unix(deleted_ts),
            recovery_score: if data_runs.is_empty() { 10 } else { 80 },
            data_runs,
        })
    }

    fn read_vlq(&self, data: &[u8], size: usize) -> u64 {
        let mut val: u64 = 0;
        for i in 0..size {
            if i < data.len() {
                val = (val << 8) | data[i] as u64;
            }
        }
        val
    }

    fn read_vlq_signed(&self, data: &[u8], size: usize) -> i64 {
        let mut val: i64 = 0;
        for i in 0..size {
            if i < data.len() {
                val = (val << 8) | (data[i] as i8 as i64);
            }
        }
        val
    }
}

fn windows_time_to_unix(windows_time: i64) -> i64 {
    if windows_time == 0 {
        return 0;
    }
    (windows_time / 10_000_000) - 11644473600
}

pub fn scan_deleted_files(disk_path: &str) -> std::io::Result<Vec<DeletedFile>> {
    let mut parser = NtfsParser::open(disk_path)?;
    parser.scan_deleted()
}