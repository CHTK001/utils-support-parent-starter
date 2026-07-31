use std::fs;
use std::path::Path;

pub struct FileRecoverer<'a> {
    pub device_path: &'a str,
    pub output_dir: &'a str,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RecoverResult {
    pub success: bool,
    pub success_count: usize,
    pub failed_count: usize,
    pub failed_list: Vec<FailedItem>,
    pub total_bytes_written: u64,
    pub message: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FailedItem {
    pub path: String,
    pub reason: String,
}

impl<'a> FileRecoverer<'a> {
    pub fn new(device_path: &'a str, output_dir: &'a str) -> Self {
        Self { device_path, output_dir }
    }

    pub fn recover(&self, file_paths: &[String], preserve_structure: bool) -> RecoverResult {
        let mut result = RecoverResult {
            success: true,
            success_count: 0,
            failed_count: 0,
            failed_list: Vec::new(),
            total_bytes_written: 0,
            message: String::new(),
        };

        let out_root = Path::new(self.output_dir);
        if let Err(e) = fs::create_dir_all(out_root) {
            result.success = false;
            result.message = format!("Failed to create output dir: {}", e);
            return result;
        }

        for file_path in file_paths {
            let src = Path::new(file_path);
            let dest = if preserve_structure {
                let rel = src.strip_prefix(Path::new(self.device_path)).unwrap_or(src);
                out_root.join(rel)
            } else {
                out_root.join(src.file_name().unwrap_or_default())
            };

            if let Some(parent) = dest.parent() {
                if let Err(e) = fs::create_dir_all(parent) {
                    result.failed_count += 1;
                    result.failed_list.push(FailedItem {
                        path: file_path.clone(),
                        reason: format!("Failed to create parent dir: {}", e),
                    });
                    continue;
                }
            }

            match fs::copy(src, &dest) {
                Ok(written) => {
                    result.success_count += 1;
                    result.total_bytes_written += written;
                }
                Err(e) => {
                    result.failed_count += 1;
                    result.failed_list.push(FailedItem {
                        path: file_path.clone(),
                        reason: e.to_string(),
                    });
                }
            }
        }

        result.message = format!("Recovered {}/{} files", result.success_count, file_paths.len());
        result
    }
}
