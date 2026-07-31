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
    pub inode: u64,
}

#[derive(Debug, Clone)]
pub struct Ext4Info {
    pub block_size: u64,
    pub inodes_per_group: u32,
    pub blocks_per_group: u32,
    pub inode_size: u32,
    pub first_data_block: u32,
    pub volume_size: u64,
}

pub struct Ext4Parser {
    reader: DiskReader,
    info: Ext4Info,
}

impl Ext4Parser {
    pub fn open(path: &str) -> std::io::Result<Self> {
        let mut reader = DiskReader::open(path)?;
        let superblock = reader.read_at(1024, 1024)?;
        if superblock.len() < 256 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Superblock too small"));
        }
        let s_block_size = i32::from_le_bytes([
            superblock[0x18], superblock[0x19], superblock[0x1A], superblock[0x1B]
        ]);
        let block_size: u64 = if s_block_size < 0 {
            1024
        } else {
            1024u64 << s_block_size
        };
        let inodes_per_group = u32::from_le_bytes([
            superblock[0x28], superblock[0x29], superblock[0x2A], superblock[0x2B]
        ]);
        let blocks_per_group = u32::from_le_bytes([
            superblock[0x20], superblock[0x21], superblock[0x22], superblock[0x23]
        ]);
        let inode_size = u16::from_le_bytes([superblock[0x58], superblock[0x59]]);
        let first_data_block = u32::from_le_bytes([
            superblock[0x14], superblock[0x15], superblock[0x16], superblock[0x17]
        ]);
        let volume_size = reader.disk_size()?;
        Ok(Self {
            reader,
            info: Ext4Info {
                block_size,
                inodes_per_group,
                blocks_per_group,
                inode_size: if inode_size == 0 { 128 } else { inode_size as u32 },
                first_data_block,
                volume_size,
            },
        })
    }

    pub fn scan_deleted(&mut self) -> std::io::Result<Vec<DeletedFile>> {
        let block_size = self.info.block_size;
        let inode_size = self.info.inode_size as usize;
        let inodes_per_group = self.info.inodes_per_group;
        let group_count = ((self.info.volume_size / block_size) / self.info.blocks_per_group as u64).max(1) as u32;
        let mut deleted_files = Vec::new();
        for group_idx in 0..group_count.min(256) {
            let group_desc_offset = 1024u64 + group_idx as u64 * 64;
            if group_desc_offset + 64 > self.info.volume_size {
                break;
            }
            if let Ok(group_desc) = self.reader.read_at(group_desc_offset, 64) {
                let inode_table_block = u32::from_le_bytes([
                    group_desc[0x08], group_desc[0x09], group_desc[0x0A], group_desc[0x0B]
                ]);
                if inode_table_block == 0 {
                    continue;
                }
                let inode_table_offset = inode_table_block as u64 * block_size;
                let inodes_in_group = std::cmp::min(inodes_per_group, 1024);
                for inode_idx in 0..inodes_in_group {
                    let inode_offset = inode_table_offset + inode_idx as u64 * inode_size as u64;
                    if inode_offset + inode_size as u64 > self.info.volume_size {
                        break;
                    }
                    if let Ok(inode_data) = self.reader.read_at(inode_offset, inode_size) {
                        if inode_data.len() < 128 {
                            continue;
                        }
                        let mode = u16::from_le_bytes([inode_data[0], inode_data[1]]);
                        let link_count = u16::from_le_bytes([inode_data[0x1A], inode_data[0x1B]]);
                        let is_deleted = link_count == 0 && mode != 0;
                        if !is_deleted {
                            continue;
                        }
                        let is_regular = (mode & 0x8000) != 0;
                        let is_dir = (mode & 0x4000) != 0;
                        if is_dir || !is_regular {
                            continue;
                        }
                        let file_size = u32::from_le_bytes([
                            inode_data[0x04], inode_data[0x05], inode_data[0x06], inode_data[0x07]
                        ]);
                        let inode_num = group_idx * inodes_per_group + inode_idx + 1;
                        let modified = u32::from_le_bytes([
                            inode_data[0x20], inode_data[0x21], inode_data[0x22], inode_data[0x23]
                        ]);
                        let ctime = u32::from_le_bytes([
                            inode_data[0x08], inode_data[0x09], inode_data[0x0A], inode_data[0x0B]
                        ]);
                        let name = format!("inode_{}_{}.dat", inode_num, inode_idx);
                        deleted_files.push(DeletedFile {
                            name: name.clone(),
                            path: format!("/{}", name),
                            size_bytes: file_size as u64,
                            created_timestamp: ctime as i64,
                            modified_timestamp: modified as i64,
                            deleted_timestamp: 0,
                            recovery_score: 40,
                            inode: inode_num as u64,
                        });
                    }
                }
            }
        }
        Ok(deleted_files)
    }
}

pub fn scan_deleted_files(disk_path: &str) -> std::io::Result<Vec<DeletedFile>> {
    let mut parser = Ext4Parser::open(disk_path)?;
    parser.scan_deleted()
}