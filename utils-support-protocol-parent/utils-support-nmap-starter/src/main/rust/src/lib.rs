//! Rust Network Scanner (Nmap-like functionality)
//!
//! High-performance network scanning library with JNI bindings for Java.

use jni::objects::{JClass, JIntArray, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

/// Port scan result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PortScanResult {
    pub host: String,
    pub port: u16,
    pub state: String, // "open", "closed", "filtered"
    pub service: Option<String>,
}

// ==================== JNI Functions ====================

/// TCP端口扫描
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanTcpPorts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    ports: JIntArray<'local>,
    timeout: jint,
    _concurrency: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let ports_len = match env.get_array_length(&ports) {
        Ok(len) => len,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut ports_vec = Vec::with_capacity(ports_len as usize);
    for i in 0..ports_len {
        let mut buf = [0i32];
        if env.get_int_array_region(&ports, i, &mut buf).is_ok() {
            ports_vec.push(buf[0] as u16);
        } else {
            return std::ptr::null_mut();
        }
    }

    let results = scan_tcp_ports_internal(&host, &ports_vec, timeout as u64);
    
    match serde_json::to_string(&results) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// TCP端口范围扫描
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanTcpPortRange<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    start_port: jint,
    end_port: jint,
    timeout: jint,
    _concurrency: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let ports: Vec<u16> = (start_port as u16..=end_port as u16).collect();
    let results = scan_tcp_ports_internal(&host, &ports, timeout as u64);
    
    match serde_json::to_string(&results) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// UDP端口扫描
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanUdpPorts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    _ports: JIntArray<'local>,
    _timeout: jint,
    _concurrency: jint,
) -> jstring {
    let _host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    // UDP scan not fully implemented - return empty results
    let results: Vec<PortScanResult> = Vec::new();
    match serde_json::to_string(&results) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 扫描单个TCP端口
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanSingleTcpPort<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout: jint,
) -> jint {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    if tcp_connect(&host, port as u16, timeout as u64) {
        0 // open
    } else {
        1 // closed
    }
}

/// Ping主机
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_pingHost<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    timeout: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let is_up = is_host_up(&host, timeout as u64);
    let result = format!(r#"{{"host":"{}","is_up":{}}}"#, host, is_up);
    
    env.new_string(&result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// 扫描子网
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanSubnet<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subnet: JString<'local>,
    timeout: jint,
    _concurrency: jint,
) -> jstring {
    let subnet: String = match env.get_string(&subnet) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let hosts = expand_cidr(&subnet);
    let alive_hosts: Vec<String> = hosts
        .into_iter()
        .filter(|h| is_host_up(h, timeout as u64))
        .collect();
    
    match serde_json::to_string(&alive_hosts) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 扫描IP范围
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_scanIpRange<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    _start_ip: JString<'local>,
    _end_ip: JString<'local>,
    _timeout: jint,
    _concurrency: jint,
) -> jstring {
    // Not implemented - return empty array
    let results: Vec<String> = Vec::new();
    match serde_json::to_string(&results) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 检测服务版本
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_detectService<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let service = get_common_service(port as u16).unwrap_or_else(|| "unknown".to_string());
    let banner = grab_banner(&host, port as u16, timeout as u64).unwrap_or_default();
    
    let result = format!(
        r#"{{"port":{},"service":"{}","banner":"{}"}}"#,
        port, service, banner
    );
    
    env.new_string(&result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// 获取Banner
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_getBanner<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    match grab_banner(&host, port as u16, timeout as u64) {
        Some(banner) => env.new_string(&banner).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// 检测操作系统
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_detectOs<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    _host: JString<'local>,
    _timeout: jint,
) -> jstring {
    // Not implemented - return empty object
    let result = r#"{"os":"unknown"}"#;
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// 获取TTL
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_getTtl<'local>(
    mut _env: JNIEnv<'local>,
    _class: JClass<'local>,
    _host: JString<'local>,
    _timeout: jint,
) -> jint {
    -1 // Not implemented
}

/// 解析主机名
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_resolveHostname<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    hostname: JString<'local>,
) -> jstring {
    let hostname: String = match env.get_string(&hostname) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    use dns_lookup::lookup_host;
    
    match lookup_host(&hostname) {
        Ok(ips) => {
            if let Some(ip) = ips.into_iter().next() {
                return env.new_string(&ip.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut());
            }
        }
        Err(_) => {}
    }
    
    std::ptr::null_mut()
}

/// 反向DNS查询
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_reverseDns<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ip: JString<'local>,
) -> jstring {
    let ip_str: String = match env.get_string(&ip) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    use dns_lookup::lookup_addr;
    
    if let Ok(addr) = ip_str.parse::<IpAddr>() {
        if let Ok(hostname) = lookup_addr(&addr) {
            return env.new_string(&hostname).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut());
        }
    }
    
    std::ptr::null_mut()
}

/// 检查IP地址是否有效
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_isValidIp<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ip: JString<'local>,
) -> jboolean {
    let ip_str: String = match env.get_string(&ip) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    if ip_str.parse::<IpAddr>().is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 检查子网格式是否有效
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_isValidSubnet<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subnet: JString<'local>,
) -> jboolean {
    let subnet_str: String = match env.get_string(&subnet) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    if subnet_str.contains('/') {
        let parts: Vec<&str> = subnet_str.split('/').collect();
        if parts.len() == 2 {
            if parts[0].parse::<IpAddr>().is_ok() && parts[1].parse::<u8>().is_ok() {
                return JNI_TRUE;
            }
        }
    }
    
    JNI_FALSE
}

/// 获取本机IP地址列表
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_getLocalIps<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // Simplified implementation - return empty array
    let ips: Vec<String> = Vec::new();
    
    match serde_json::to_string(&ips) {
        Ok(json) => env.new_string(&json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 获取本机Mac地址
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_getLocalMac<'local>(
    mut _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // Not implemented - return null
    std::ptr::null_mut()
}

/// 获取Rust Nmap库版本
#[no_mangle]
pub extern "system" fn Java_com_chua_nmap_support_bridge_RustNmapBridge_getVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    env.new_string("0.1.0").map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// ==================== Internal Helper Functions ====================

fn scan_tcp_ports_internal(host: &str, ports: &[u16], timeout_ms: u64) -> Vec<PortScanResult> {
    let mut results = Vec::new();
    let timeout = Duration::from_millis(timeout_ms);
    
    for &port in ports {
        let addr = format!("{}:{}", host, port);
        let state = if let Ok(sock_addr) = addr.parse::<SocketAddr>() {
            if TcpStream::connect_timeout(&sock_addr, timeout).is_ok() {
                "open"
            } else {
                "closed"
            }
        } else {
            "error"
        };
        
        if state == "open" {
            results.push(PortScanResult {
                host: host.to_string(),
                port,
                state: state.to_string(),
                service: get_common_service(port),
            });
        }
    }
    
    results
}

fn is_host_up(host: &str, timeout_ms: u64) -> bool {
    let common_ports = [80, 443, 22, 21, 25, 445, 3389];
    let timeout = Duration::from_millis(timeout_ms / common_ports.len() as u64);
    
    for port in common_ports {
        let addr = format!("{}:{}", host, port);
        if let Ok(sock_addr) = addr.parse::<SocketAddr>() {
            if TcpStream::connect_timeout(&sock_addr, timeout).is_ok() {
                return true;
            }
        }
    }
    
    false
}

fn tcp_connect(host: &str, port: u16, timeout_ms: u64) -> bool {
    let addr = format!("{}:{}", host, port);
    let timeout = Duration::from_millis(timeout_ms);
    
    if let Ok(sock_addr) = addr.parse::<SocketAddr>() {
        TcpStream::connect_timeout(&sock_addr, timeout).is_ok()
    } else {
        false
    }
}

fn grab_banner(host: &str, port: u16, timeout_ms: u64) -> Option<String> {
    use std::io::{Read, Write};
    
    let addr = format!("{}:{}", host, port);
    let sock_addr = addr.parse::<SocketAddr>().ok()?;
    let mut stream = TcpStream::connect_timeout(&sock_addr, Duration::from_millis(timeout_ms)).ok()?;
    
    stream.set_read_timeout(Some(Duration::from_millis(timeout_ms))).ok()?;
    
    // Send probe based on port
    let probe = match port {
        80 | 8080 => b"GET / HTTP/1.0\r\n\r\n".to_vec(),
        _ => vec![],
    };
    
    if !probe.is_empty() {
        stream.write_all(&probe).ok()?;
    }
    
    let mut buf = [0u8; 1024];
    let n = stream.read(&mut buf).ok()?;
    
    if n > 0 {
        Some(String::from_utf8_lossy(&buf[..n]).to_string())
    } else {
        None
    }
}

fn expand_cidr(cidr: &str) -> Vec<String> {
    let mut hosts = Vec::new();
    
    if cidr.contains('/') {
        let parts: Vec<&str> = cidr.split('/').collect();
        if parts.len() == 2 {
            if let (Some(base_ip), Ok(prefix)) = (parse_ip(parts[0]), parts[1].parse::<u8>()) {
                let base = ip_to_u32(&base_ip);
                let count = (1u32 << (32 - prefix)).min(256); // Limit to 256 hosts for safety
                
                for i in 1..count.saturating_sub(1) {
                    let ip = u32_to_ip(base + i);
                    hosts.push(ip);
                }
            }
        }
    } else {
        hosts.push(cidr.to_string());
    }
    
    hosts
}

fn parse_ip(s: &str) -> Option<[u8; 4]> {
    let parts: Vec<&str> = s.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    
    let mut ip = [0u8; 4];
    for (i, part) in parts.iter().enumerate() {
        ip[i] = part.parse().ok()?;
    }
    Some(ip)
}

fn ip_to_u32(ip: &[u8; 4]) -> u32 {
    ((ip[0] as u32) << 24) | ((ip[1] as u32) << 16) | ((ip[2] as u32) << 8) | (ip[3] as u32)
}

fn u32_to_ip(n: u32) -> String {
    format!("{}.{}.{}.{}", (n >> 24) & 0xFF, (n >> 16) & 0xFF, (n >> 8) & 0xFF, n & 0xFF)
}

fn get_common_service(port: u16) -> Option<String> {
    let services: HashMap<u16, &str> = [
        (21, "ftp"), (22, "ssh"), (23, "telnet"), (25, "smtp"),
        (53, "dns"), (80, "http"), (110, "pop3"), (143, "imap"),
        (443, "https"), (445, "smb"), (993, "imaps"), (995, "pop3s"),
        (1433, "mssql"), (3306, "mysql"), (3389, "rdp"), (5432, "postgresql"),
        (6379, "redis"), (8080, "http-proxy"), (8443, "https-alt"),
    ].iter().cloned().collect();
    
    services.get(&port).map(|s| s.to_string())
}
