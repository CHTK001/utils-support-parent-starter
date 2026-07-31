use std::fs;
use std::io::Seek;
use std::path::Path;
use rand::Rng;

pub struct FileEraser<'a> {
    pub device_path: &'a str,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DeleteResult {
    pub success: bool,
    pub bytes_overwritten: u64,
    pub passes_completed: u32,
    pub message: String,
}

impl<'a> FileEraser<'a> {
    pub fn new(device_path: &'a str) -> Self {
        Self { device_path }
    }

    pub fn permanent_delete(&self, file_path: &str, method: &str) -> DeleteResult {
        let path = Path::new(file_path);
        let mut result = DeleteResult {
            success: false,
            bytes_overwritten: 0,
            passes_completed: 0,
            message: String::new(),
        };

        if !path.exists() {
            result.message = "File not found".to_string();
            return result;
        }

        let metadata = match fs::metadata(path) {
            Ok(m) => m,
            Err(e) => {
                result.message = format!("Failed to get metadata: {}", e);
                return result;
            }
        };

        let file_size = metadata.len();
        let passes = match method {
            "dod" => 3,
            "gutmann" => 35,
            "simple" => 1,
            _ => 1,
        };

        if let Ok(mut file) = fs::OpenOptions::new().write(true).open(path) {
            use std::io::Write;
            let mut rng = rand::thread_rng();
            let mut buffer = vec![0u8; 4096];

            for _pass in 0..passes {
                if let Err(e) = file.seek(std::io::SeekFrom::Start(0)) {
                    result.message = format!("Failed to seek: {}", e);
                    return result;
                }

                let mut written_this_pass = 0u64;
                for _ in 0..(file_size / 4096 + 1) {
                    rng.fill(&mut buffer[..]);
                    let to_write = std::cmp::min(buffer.len() as u64, file_size - written_this_pass) as usize;
                    if let Err(e) = file.write_all(&buffer[..to_write]) {
                        result.message = format!("Failed to write: {}", e);
                        return result;
                    }
                    written_this_pass += to_write as u64;
                }

                result.bytes_overwritten += written_this_pass;
                result.passes_completed += 1;
            }

            drop(file);
            if let Err(e) = fs::remove_file(path) {
                result.message = format!("Failed to delete file: {}", e);
                return result;
            }

            result.success = true;
            result.message = format!("Secure delete completed with {} passes", result.passes_completed);
        } else {
            result.message = "Failed to open file for writing".to_string();
        }

        result
    }
}
