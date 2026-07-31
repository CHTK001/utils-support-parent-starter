use jni::JNIEnv;
use jni::objects::{JClass, JString, JObjectArray};
use jni::sys::{jboolean, jint, jstring};
use serde::Serialize;

mod signatures;
mod scanner;
mod recoverer;
mod eraser;
mod disk;
mod fs;
mod carver;

use signatures::FileSignature;
use scanner::FileScanner;
use recoverer::FileRecoverer;
use eraser::FileEraser;

const VERSION: &str = "1.0.0";

#[derive(Serialize)]
struct ScanResultJson {
    success: bool,
    files_scanned: i64,
    files_found: i64,
    entries: Vec<FileEntryJson>,
    message: String,
}

#[derive(Serialize)]
struct FileEntryJson {
    name: String,
    path: String,
    size_bytes: i64,
    modified_timestamp: i64,
    deleted_timestamp: i64,
    recovery_score: i32,
    carved_signature: String,
}

#[derive(Serialize)]
struct RecoverResultJson {
    success: bool,
    success_count: i32,
    failed_count: i32,
    failed_list: Vec<FailedItemJson>,
    total_bytes_written: i64,
    message: String,
}

#[derive(Serialize)]
struct FailedItemJson {
    path: String,
    reason: String,
}

#[derive(Serialize)]
struct DeleteResultJson {
    success: bool,
    bytes_overwritten: i64,
    passes_completed: i32,
    message: String,
}

#[no_mangle]
pub extern "system" fn Java_com_recovery_DataRecovery_nativeScan(
    mut env: JNIEnv,
    _class: JClass,
    device_path: JString,
    scan_mode: jint,
) -> jstring {
    let device_path_str: String = match env.get_string(&device_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = ScanResultJson {
                success: false,
                files_scanned: 0,
                files_found: 0,
                entries: Vec::new(),
                message: format!("Failed to get device path: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let scanner = FileScanner::new(&device_path_str);
    let scan_result = scanner.scan(scan_mode as i32);

    let result = ScanResultJson {
        success: true,
        files_scanned: scan_result.files_scanned as i64,
        files_found: scan_result.files_recovered as i64,
        entries: scan_result
            .recovered_list
            .into_iter()
            .map(|r| FileEntryJson {
                name: r.file_type,
                path: r.output_path.clone(),
                size_bytes: r.size as i64,
                modified_timestamp: 0,
                deleted_timestamp: 0,
                recovery_score: 100,
                carved_signature: r.extension,
            })
            .collect(),
        message: "Scan completed".to_string(),
    };

    to_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_recovery_DataRecovery_nativeScanAndRecover(
    mut env: JNIEnv,
    _class: JClass,
    device_path: JString,
    scan_mode: jint,
    output_dir: JString,
) -> jstring {
    let device_path_str: String = match env.get_string(&device_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = ScanResultJson {
                success: false,
                files_scanned: 0,
                files_found: 0,
                entries: Vec::new(),
                message: format!("Failed to get device path: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let output_dir_str: String = match env.get_string(&output_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = ScanResultJson {
                success: false,
                files_scanned: 0,
                files_found: 0,
                entries: Vec::new(),
                message: format!("Failed to get output dir: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let scanner = FileScanner::new(&device_path_str).with_output_dir(&output_dir_str);
    let scan_result = scanner.scan_and_recover(scan_mode as i32, &output_dir_str);

    let result = ScanResultJson {
        success: true,
        files_scanned: scan_result.files_scanned as i64,
        files_found: scan_result.files_recovered as i64,
        entries: scan_result
            .recovered_list
            .into_iter()
            .map(|r| FileEntryJson {
                name: r.file_type,
                path: r.output_path.clone(),
                size_bytes: r.size as i64,
                modified_timestamp: 0,
                deleted_timestamp: 0,
                recovery_score: if r.offset == 0 { 30 } else { 80 },
                carved_signature: r.extension,
            })
            .collect(),
        message: format!("Scan and recover completed. {} files carved to {}", scan_result.files_recovered, output_dir_str),
    };

    to_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_recovery_DataRecovery_nativeRecover(
    mut env: JNIEnv,
    _class: JClass,
    device_path: JString,
    file_paths: JObjectArray,
    output_dir: JString,
    preserve_structure: jboolean,
) -> jstring {
    let device_path_str: String = match env.get_string(&device_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = RecoverResultJson {
                success: false,
                success_count: 0,
                failed_count: 0,
                failed_list: Vec::new(),
                total_bytes_written: 0,
                message: format!("Failed to get device path: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let output_dir_str: String = match env.get_string(&output_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = RecoverResultJson {
                success: false,
                success_count: 0,
                failed_count: 0,
                failed_list: Vec::new(),
                total_bytes_written: 0,
                message: format!("Failed to get output dir: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let paths_vec = match env.get_array_length(&file_paths) {
        Ok(len) => {
            let mut paths = Vec::new();
            for i in 0..len {
                let Ok(obj) = env.get_object_array_element(&file_paths, i) else {
                    continue;
                };
                let jstr: JString = unsafe { JString::from_raw(obj.into_raw()) };
                let s: String = match env.get_string(&jstr) {
                    Ok(js) => js.into(),
                    Err(_) => continue,
                };
                paths.push(s);
            }
            paths
        }
        Err(_) => Vec::new(),
    };

    let recoverer = FileRecoverer::new(&device_path_str, &output_dir_str);
    let recover_result = recoverer.recover(&paths_vec, preserve_structure != 0);

    let result = RecoverResultJson {
        success: recover_result.success,
        success_count: recover_result.success_count as i32,
        failed_count: recover_result.failed_count as i32,
        failed_list: recover_result
            .failed_list
            .into_iter()
            .map(|f| FailedItemJson {
                path: f.path,
                reason: f.reason,
            })
            .collect(),
        total_bytes_written: recover_result.total_bytes_written as i64,
        message: recover_result.message,
    };

    to_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_recovery_DataRecovery_nativeDelete(
    mut env: JNIEnv,
    _class: JClass,
    device_path: JString,
    file_path: JString,
    method: JString,
) -> jstring {
    let device_path_str: String = match env.get_string(&device_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = DeleteResultJson {
                success: false,
                bytes_overwritten: 0,
                passes_completed: 0,
                message: format!("Failed to get device path: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let file_path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = DeleteResultJson {
                success: false,
                bytes_overwritten: 0,
                passes_completed: 0,
                message: format!("Failed to get file path: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let method_str: String = match env.get_string(&method) {
        Ok(s) => s.into(),
        Err(e) => {
            let result = DeleteResultJson {
                success: false,
                bytes_overwritten: 0,
                passes_completed: 0,
                message: format!("Failed to get method: {}", e),
            };
            return to_jstring(&mut env, &result);
        }
    };

    let eraser = FileEraser::new(&device_path_str);
    let delete_result = eraser.permanent_delete(&file_path_str, &method_str);

    let result = DeleteResultJson {
        success: delete_result.success,
        bytes_overwritten: delete_result.bytes_overwritten as i64,
        passes_completed: delete_result.passes_completed as i32,
        message: delete_result.message,
    };

    to_jstring(&mut env, &result)
}

fn to_jstring<T: serde::Serialize>(env: &mut JNIEnv, value: &T) -> jstring {
    match serde_json::to_string(value) {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                eprintln!("Failed to create jstring: {}", e);
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            eprintln!("Failed to serialize json: {}", e);
            std::ptr::null_mut()
        }
    }
}