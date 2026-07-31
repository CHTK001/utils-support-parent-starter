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
    pub start_cluster: u64,
}

#[derive(Debug, Clone)]
pub struct FatInfo {
    pub bytes_per_sector: u16,
    pub sectors_per_cluster: u8,
    pub reserved_sectors: u16,
    pub num_fats: u8,
    pub fat_size_sectors: u32,
    pub root_cluster: u32,
    pub volume_size: u64,
}

pub struct FatParser {
    reader: DiskReader,
    info: FatInfo,
}

impl FatParser {
    pub fn open(path: &str) -> std::io::Result<Self> {
        let mut reader = DiskReader::open(path)?;
        let boot_sector = reader.read_at(0, 512)?;
        if boot_sector.len() < 36 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Boot sector too small"));
        }
        let bytes_per_sector = u16::from_le_bytes([boot_sector[0x0B], boot_sector[0x0C]]);
        let sectors_per_cluster = boot_sector[0x0D];
        let reserved_sectors = u16::from_le_bytes([boot_sector[0x0E], boot_sector[0x0F]]);
        let num_fats = boot_sector[0x10];
        let fat_size_sectors = if boot_sector[0x16] != 0 || boot_sector[0x17] != 0 {
            u32::from_le_bytes([boot_sector[0x16], boot_sector[0x17], boot_sector[0x18], boot_sector[0x19]])
        } else {
            u32::from_le_bytes([boot_sector[0x24], boot_sector[0x25], boot_sector[0x26], boot_sector[0x27]])
        };
        let root_cluster = u32::from_le_bytes([boot_sector[0x2C], boot_sector[0x2D], boot_sector[0x2E], boot_sector[0x2F]]);
        let volume_size = reader.disk_size()?;
        Ok(Self {
            reader,
            info: FatInfo {
                bytes_per_sector,
                sectors_per_cluster,
                reserved_sectors,
                num_fats,
                fat_size_sectors,
                root_cluster,
                volume_size,
            },
        })
    }

    pub fn scan_deleted(&mut self) -> std::io::Result<Vec<DeletedFile>> {
        let bpfs = self.info.bytes_per_sector as u64;
        let fat_offset = self.info.reserved_sectors as u64 * bpfs;
        let fat_entries_per_sector = (bpfs / 4) as usize;
        let fat_sectors = self.info.fat_size_sectors as usize;
        let root_dir_offset = (self.info.reserved_sectors as u64 + fat_sectors as u64 * self.info.num_fats as u64) * bpfs;
        let data_offset = root_dir_offset;
        let mut deleted_files = Vec::new();
        for sector in 0..1024 {
            let dir_sector = root_dir_offset + sector as u64 * bpfs;
            if dir_sector >= self.info.volume_size {
                break;
            }
            if let Ok(dir_data) = self.reader.read_at(dir_sector, bpfs as usize) {
                for i in (0..dir_data.len()).step_by(32) {
                    if i + 32 > dir_data.len() {
                        break;
                    }
                    let entry = &dir_data[i..i+32];
                    if entry[0] == 0xE5 || (entry[0] != 0x00 && entry[0] != 0x2E) {
                        let is_deleted = entry[0] == 0xE5;
                        if !is_deleted {
                            continue;
                        }
                        let attrs = entry[0x0B];
                        let is_volume = (attrs & 0x08) != 0;
                        let is_dir = (attrs & 0x10) != 0;
                        if is_volume || is_dir {
                            continue;
                        }
                        let short_name = self.parse_short_name(&entry[0..8]);
                        let short_ext = self.parse_short_ext(&entry[8..11]);
                        let name = if short_ext.is_empty() {
                            short_name.trim().to_string()
                        } else {
                            format!("{}.{}", short_name.trim(), short_ext.trim())
                        };
                        if name.is_empty() || name == "." || name == ".." {
                            continue;
                        }
                        let start_cluster = u16::from_le_bytes([entry[0x1A], entry[0x1B]]) as u64;
                        let file_size = u32::from_le_bytes([entry[0x1C], entry[0x1D], entry[0x1E], entry[0x1F]]) as u64;
                        if file_size == 0 && start_cluster == 0 {
                            continue;
                        }
                        let modified_date = u16::from_le_bytes([entry[0x16], entry[0x17]]);
                        let modified_time = u16::from_le_bytes([entry[0x14], entry[0x15]]);
                        deleted_files.push(DeletedFile {
                            name: name.clone(),
                            path: format!("\\{}", name),
                            size_bytes: file_size,
                            created_timestamp: 0,
                            modified_timestamp: fat_date_time_to_unix(modified_date, modified_time),
                            deleted_timestamp: 0,
                            recovery_score: 50,
                            start_cluster,
                        });
                    }
                }
            }
        }
        Ok(deleted_files)
    }

    fn parse_short_name(&self, data: &[u8]) -> String {
        let mut name = String::new();
        for &b in data {
            if b == 0x20 || b == 0x00 {
                break;
            }
            if let Some(c) = char::from_u32(b as u32) {
                if c.is_ascii_graphic() || c == ' ' {
                    name.push(c);
                }
            }
        }
        name
    }

    fn parse_short_ext(&self, data: &[u8]) -> String {
        self.parse_short_name(data)
    }
}

fn fat_date_time_to_unix(date: u16, time: u16) -> i64 {
    let year = ((date >> 9) & 0x7F) as i64 + 1980;
    let month = ((date >> 5) & 0x0F) as i64;
    let day = (date & 0x1F) as i64;
    let hour = ((time >> 11) & 0x1F) as i64;
    let minute = ((time >> 5) & 0x3F) as i64;
    let second = ((time & 0x1F) * 2) as i64;
    let days = chrono_days_since_epoch(year, month, day);
    (days * 86400) + (hour * 3600) + (minute * 60) + second
}

fn chrono_days_since_epoch(year: i64, month: i64, day: i64) -> i64 {
    let a = (14 - month) / 12;
    let y = year + 4800 - a;
    let m = month + 12 * a - 3;
    let jd = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    jd - 2440588
}

pub fn scan_deleted_files(disk_path: &str) -> std::io::Result<Vec<DeletedFile>> {
    let mut parser = FatParser::open(disk_path)?;
    parser.scan_deleted()
}