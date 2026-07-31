use std::fs::File;
use std::path::Path;
use std::io::{Read, Seek, SeekFrom};

pub struct DiskReader {
    file: File,
    sector_size: u64,
}

impl DiskReader {
    pub fn open(path: &str) -> std::io::Result<Self> {
        let file = File::open(path)?;
        Ok(Self {
            file,
            sector_size: 512,
        })
    }

    pub fn read_sectors(&mut self, start_sector: u64, count: usize) -> std::io::Result<Vec<u8>> {
        let offset = start_sector * self.sector_size;
        self.file.seek(SeekFrom::Start(offset))?;
        let total_bytes = count * self.sector_size as usize;
        let mut buffer = vec![0u8; total_bytes];
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
        self.file.seek(SeekFrom::Start(offset))?;
        let mut buffer = vec![0u8; size];
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

    pub fn sector_size(&self) -> u64 {
        self.sector_size
    }

    pub fn disk_size(&mut self) -> std::io::Result<u64> {
        let metadata = self.file.metadata()?;
        Ok(metadata.len())
    }
}

pub fn detect_filesystem(path: &str) -> &'static str {
    if let Ok(mut reader) = DiskReader::open(path) {
        if let Ok(sector0) = reader.read_sectors(0, 1) {
            if sector0.len() >= 512 {
                let apfs_sig = &sector0[0x400..0x408];
                if apfs_sig == b"NXSB    " {
                    return "apfs";
                }
                let hfs_sig = &sector0[0x400..0x402];
                if hfs_sig == [0x48, 0x2B] {
                    return "hfs+";
                }
            }
        }
    }
    "unknown"
}