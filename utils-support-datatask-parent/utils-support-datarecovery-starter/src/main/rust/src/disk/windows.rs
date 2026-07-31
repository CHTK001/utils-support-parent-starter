use std::io::{Read, Seek, SeekFrom};
use std::fs::File;
use std::path::Path;

pub struct DiskReader {
    file: File,
    sector_size: u64,
    pos: u64,
}

impl DiskReader {
    pub fn open(path: &str) -> std::io::Result<Self> {
        let file = File::open(path)?;
        Ok(Self {
            file,
            sector_size: 512,
            pos: 0,
        })
    }

    pub fn read_sectors(&mut self, start_sector: u64, count: usize) -> std::io::Result<Vec<u8>> {
        let offset = start_sector * self.sector_size;
        let total_bytes = count * self.sector_size as usize;
        let mut buffer = vec![0u8; total_bytes];
        self.file.seek(SeekFrom::Start(offset))?;
        let mut read_bytes = 0;
        while read_bytes < total_bytes {
            match self.file.read(&mut buffer[read_bytes..]) {
                Ok(0) => break,
                Ok(n) => read_bytes += n,
                Err(e) => return Err(e),
            }
        }
        buffer.truncate(read_bytes);
        Ok(buffer)
    }

    pub fn read_at(&mut self, offset: u64, size: usize) -> std::io::Result<Vec<u8>> {
        let mut buffer = vec![0u8; size];
        self.file.seek(SeekFrom::Start(offset))?;
        let mut read_bytes = 0;
        while read_bytes < size {
            match self.file.read(&mut buffer[read_bytes..]) {
                Ok(0) => break,
                Ok(n) => read_bytes += n,
                Err(e) => return Err(e),
            }
        }
        buffer.truncate(read_bytes);
        Ok(buffer)
    }

    pub fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.file.read(buf)?;
        self.pos += n as u64;
        Ok(n)
    }

    pub fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        let new_pos = match pos {
            SeekFrom::Start(p) => p,
            SeekFrom::End(p) => (self.file.metadata()?.len() as i64 + p) as u64,
            SeekFrom::Current(p) => (self.pos as i64 + p) as u64,
        };
        self.file.seek(SeekFrom::Start(new_pos))?;
        self.pos = new_pos;
        Ok(new_pos)
    }

    pub fn sector_size(&self) -> u64 {
        self.sector_size
    }

    pub fn disk_size(&mut self) -> std::io::Result<u64> {
        match self.file.metadata() {
            Ok(m) => Ok(m.len()),
            Err(_) => {
                let boot = self.read_at(0, 512)?;
                if boot.len() < 512 {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Boot sector too small"));
                }
                let bps = u16::from_le_bytes([boot[0x0B], boot[0x0C]]) as u64;
                if bps == 0 {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Invalid BPS"));
                }
                if &boot[0x28..0x30] != b"" {
                    let total_sectors = u64::from_le_bytes([
                        boot[0x28], boot[0x29], boot[0x2A], boot[0x2B],
                        boot[0x2C], boot[0x2D], boot[0x2E], boot[0x2F],
                    ]);
                    if total_sectors > 0 {
                        return Ok(total_sectors * bps);
                    }
                }
                let total_sectors_16 = u16::from_le_bytes([boot[0x13], boot[0x14]]) as u64;
                let total_sectors_32 = u32::from_le_bytes([
                    boot[0x20], boot[0x21], boot[0x22], boot[0x23],
                ]) as u64;
                let total_sectors = if total_sectors_16 == 0 { total_sectors_32 } else { total_sectors_16 };
                Ok(total_sectors * bps)
            }
        }
    }
}

pub fn detect_filesystem(path: &str) -> &'static str {
    if let Ok(mut reader) = DiskReader::open(path) {
        if let Ok(sector0) = reader.read_sectors(0, 1) {
            if sector0.len() >= 512 {
                let sig = u16::from_le_bytes([sector0[510], sector0[511]]);
                if sig == 0xAA55 {
                    if sector0[0x01C2] == 0x07 {
                        return "ntfs";
                    }
                    if sector0[0x01C2] == 0x0B || sector0[0x01C2] == 0x0C {
                        return "fat32";
                    }
                    if sector0[0x01C2] == 0x83 {
                        return "ext4";
                    }
                    if sector0[0x01C2] == 0xEE || sector0[0x01C2] == 0xEF {
                        return "gpt";
                    }
                }
                let ntfs_sig = &sector0[3..11];
                if ntfs_sig == b"NTFS    " {
                    return "ntfs";
                }
            }
        }
    }
    "unknown"
}

