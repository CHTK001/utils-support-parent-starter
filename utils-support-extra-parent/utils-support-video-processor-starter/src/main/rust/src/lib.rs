use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use std::process::Command;

const VERSION: &str = "1.0.0";

#[no_mangle]
pub extern "system" fn Java_com_chua_video_processor_support_bridge_VideoProcessorBridge_transcodeToHls(
    mut env: JNIEnv,
    _class: JClass,
    input_path: JString,
    output_dir: JString,
) -> jboolean {
    let input: String = match env.get_string(&input_path) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("Failed to get input path: {}", e);
            return 0;
        }
    };

    let output: String = match env.get_string(&output_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("Failed to get output dir: {}", e);
            return 0;
        }
    };

    match transcode_to_hls(&input, &output) {
        Ok(_) => 1,
        Err(e) => {
            eprintln!("Transcode failed: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_chua_video_processor_support_bridge_VideoProcessorBridge_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            eprintln!("Failed to create version string: {}", e);
            std::ptr::null_mut()
        }
    }
}

fn transcode_to_hls(input: &str, output_dir: &str) -> anyhow::Result<()> {
    let ffmpeg = which::which("ffmpeg").map_err(|_| anyhow::anyhow!("ffmpeg not found in PATH"))?;

    let output_path = format!("{}/output.m3u8", output_dir);
    let segment_pattern = format!("{}/segment_%03d.ts", output_dir);

    let status = Command::new(ffmpeg)
        .arg("-i")
        .arg(input)
        .arg("-c:v")
        .arg("libx264")
        .arg("-preset")
        .arg("fast")
        .arg("-crf")
        .arg("23")
        .arg("-c:a")
        .arg("aac")
        .arg("-b:a")
        .arg("128k")
        .arg("-f")
        .arg("hls")
        .arg("-hls_time")
        .arg("6")
        .arg("-hls_list_size")
        .arg("0")
        .arg("-hls_segment_filename")
        .arg(&segment_pattern)
        .arg(&output_path)
        .status()?;

    if !status.success() {
        return Err(anyhow::anyhow!("ffmpeg exited with code: {:?}", status.code()));
    }

    Ok(())
}
