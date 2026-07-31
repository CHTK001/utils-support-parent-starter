
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FileSignature {
    pub name: &'static str,
    pub extension: &'static str,
    pub header: &'static [u8],
    pub footer: Option<&'static [u8]>,
    pub mime: &'static str,
}

pub const SIGNATURES: &[FileSignature] = &[
    FileSignature {
        name: "JPEG Image",
        extension: "jpg",
        header: &[0xFF, 0xD8, 0xFF],
        footer: Some(&[0xFF, 0xD9]),
        mime: "image/jpeg",
    },
    FileSignature {
        name: "PNG Image",
        extension: "png",
        header: &[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A],
        footer: None,
        mime: "image/png",
    },
    FileSignature {
        name: "PDF Document",
        extension: "pdf",
        header: b"%PDF-",
        footer: Some(b"%%EOF"),
        mime: "application/pdf",
    },
    FileSignature {
        name: "GIF Image",
        extension: "gif",
        header: b"GIF87a",
        footer: None,
        mime: "image/gif",
    },
    FileSignature {
        name: "GIF89a Image",
        extension: "gif",
        header: b"GIF89a",
        footer: None,
        mime: "image/gif",
    },
    FileSignature {
        name: "ZIP Archive",
        extension: "zip",
        header: &[0x50, 0x4B, 0x03, 0x04],
        footer: None,
        mime: "application/zip",
    },
    FileSignature {
        name: "RAR Archive",
        extension: "rar",
        header: b"Rar!\x1A\x07\x00",
        footer: None,
        mime: "application/x-rar-compressed",
    },
    FileSignature {
        name: "7-Zip Archive",
        extension: "7z",
        header: &[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C],
        footer: None,
        mime: "application/x-7z-compressed",
    },
    FileSignature {
        name: "MP4 Video",
        extension: "mp4",
        header: b"ftyp",
        footer: None,
        mime: "video/mp4",
    },
    FileSignature {
        name: "MP3 Audio",
        extension: "mp3",
        header: &[0xFF, 0xFB],
        footer: None,
        mime: "audio/mpeg",
    },
    FileSignature {
        name: "MP3 Audio ID3",
        extension: "mp3",
        header: b"ID3",
        footer: None,
        mime: "audio/mpeg",
    },
    FileSignature {
        name: "Executable",
        extension: "exe",
        header: b"MZ",
        footer: None,
        mime: "application/x-msdownload",
    },
    FileSignature {
        name: "ELF Binary",
        extension: "elf",
        header: &[0x7F, 0x45, 0x4C, 0x46],
        footer: None,
        mime: "application/x-executable",
    },
    FileSignature {
        name: "Windows BMP",
        extension: "bmp",
        header: b"BM",
        footer: None,
        mime: "image/bmp",
    },
    FileSignature {
        name: "DOCX Document",
        extension: "docx",
        header: &[0x50, 0x4B, 0x03, 0x04],
        footer: None,
        mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    },
    FileSignature {
        name: "XLSX Spreadsheet",
        extension: "xlsx",
        header: &[0x50, 0x4B, 0x03, 0x04],
        footer: None,
        mime: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    },
];

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RecoveryStats {
    pub files_scanned: u64,
    pub files_recovered: u64,
    pub bytes_processed: u64,
    pub recovered_list: Vec<RecoveredFile>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RecoveredFile {
    pub file_type: String,
    pub extension: String,
    pub mime: String,
    pub offset: u64,
    pub size: u64,
    pub output_path: String,
}

impl RecoveryStats {
    pub fn new() -> Self {
        Self {
            files_scanned: 0,
            files_recovered: 0,
            bytes_processed: 0,
            recovered_list: Vec::new(),
        }
    }
}
