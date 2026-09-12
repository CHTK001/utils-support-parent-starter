#!/bin/bash

# Rust 项目构建脚本
# 用法: 
#   ./build.sh [平台] [架构] [构建模式]
# 示例: 
#   ./build.sh windows x86_64 release    # Windows x86_64 发布版
#   ./build.sh linux x86_64 release      # Linux x86_64 发布版
#   ./build.sh darwin aarch64 release    # macOS ARM64 发布版
#   ./build.sh auto auto release         # 自动检测平台和架构
#   ./build.sh                            # 使用默认值（auto auto release）

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 脚本所在目录（Rust 项目根目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 解析参数
OS_TYPE="${1:-auto}"
ARCH="${2:-auto}"
BUILD_MODE="${3:-release}"

# 检测操作系统
detect_os() {
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
        echo "windows"
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "linux"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        echo "darwin"
    else
        echo "unknown"
    fi
}

# 检测架构
detect_arch() {
    local arch=$(uname -m)
    case "$arch" in
        x86_64|amd64)
            echo "x86_64"
            ;;
        aarch64|arm64)
            echo "aarch64"
            ;;
        *)
            echo "x86_64"  # 默认
            ;;
    esac
}

# 如果未指定，自动检测
if [[ "$OS_TYPE" == "auto" ]]; then
    OS_TYPE=$(detect_os)
    echo -e "${YELLOW}[INFO]${NC} 自动检测操作系统: $OS_TYPE"
fi

if [[ "$ARCH" == "auto" ]]; then
    ARCH=$(detect_arch)
    echo -e "${YELLOW}[INFO]${NC} 自动检测架构: $ARCH"
fi

# 验证构建模式
if [[ "$BUILD_MODE" != "release" && "$BUILD_MODE" != "debug" ]]; then
    echo -e "${RED}[ERROR]${NC} 无效的构建模式: $BUILD_MODE，必须是 release 或 debug"
    exit 1
fi

# 设置 Rust target triple
setup_target() {
    local target=""
    local lib_ext=""
    local platform_dir=""
    
    case "$OS_TYPE" in
        windows)
            case "$ARCH" in
                x86_64)
                    target="x86_64-pc-windows-msvc"
                    lib_ext="dll"
                    platform_dir="windows-x86_64"
                    ;;
                *)
                    echo -e "${RED}[ERROR]${NC} Windows 不支持架构: $ARCH"
                    exit 1
                    ;;
            esac
            ;;
        linux)
            case "$ARCH" in
                x86_64)
                    target="x86_64-unknown-linux-gnu"
                    lib_ext="so"
                    platform_dir="linux-x86_64"
                    ;;
                aarch64)
                    target="aarch64-unknown-linux-gnu"
                    lib_ext="so"
                    platform_dir="linux-aarch64"
                    ;;
                *)
                    echo -e "${RED}[ERROR]${NC} Linux 不支持架构: $ARCH"
                    exit 1
                    ;;
            esac
            ;;
        darwin)
            case "$ARCH" in
                x86_64)
                    target="x86_64-apple-darwin"
                    lib_ext="dylib"
                    platform_dir="darwin-x86_64"
                    ;;
                aarch64)
                    target="aarch64-apple-darwin"
                    lib_ext="dylib"
                    platform_dir="darwin-aarch64"
                    ;;
                *)
                    echo -e "${RED}[ERROR]${NC} macOS 不支持架构: $ARCH"
                    exit 1
                    ;;
            esac
            ;;
        *)
            echo -e "${RED}[ERROR]${NC} 不支持的操作系统: $OS_TYPE"
            exit 1
            ;;
    esac
    
    export TARGET="$target"
    export LIB_EXT="$lib_ext"
    export PLATFORM_DIR="$platform_dir"
    
    echo -e "${GREEN}[INFO]${NC} 目标平台: $TARGET"
    echo -e "${GREEN}[INFO]${NC} 库文件扩展名: $LIB_EXT"
    echo -e "${GREEN}[INFO]${NC} 平台目录: $PLATFORM_DIR"
}

# 设置 Windows 环境（如果需要）
setup_windows_env() {
    if [[ "$OS_TYPE" == "windows" ]]; then
        # 查找 Visual Studio 的 link.exe，确保它在 PATH 前面（避免 Git Bash 的 link 命令干扰）
        local vs_paths=(
            "/c/Program Files/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC"
            "/c/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC"
            "/c/Program Files/Microsoft Visual Studio/2022/Professional/VC/Tools/MSVC"
            "/c/Program Files/Microsoft Visual Studio/2022/Enterprise/VC/Tools/MSVC"
            "/c/Program Files (x86)/Microsoft Visual Studio/2019/BuildTools/VC/Tools/MSVC"
            "/c/Program Files (x86)/Microsoft Visual Studio/2019/Community/VC/Tools/MSVC"
        )
        
        local link_exe_path=""
        for vs_base in "${vs_paths[@]}"; do
            if [[ -d "$vs_base" ]]; then
                # 查找最新的 MSVC 版本
                local msvc_versions=()
                for version_dir in "$vs_base"/*; do
                    if [[ -d "$version_dir" && -d "$version_dir/bin/Hostx64/x64" ]]; then
                        msvc_versions+=("$version_dir")
                    fi
                done
                
                # 按版本号排序，取最新的
                if [[ ${#msvc_versions[@]} -gt 0 ]]; then
                    IFS=$'\n' sorted=($(printf '%s\n' "${msvc_versions[@]}" | sort -V -r))
                    local latest_msvc="${sorted[0]}"
                    local link_path="$latest_msvc/bin/Hostx64/x64/link.exe"
                    if [[ -f "$link_path" ]]; then
                        link_exe_path="$link_path"
                        break
                    fi
                fi
            fi
        done
        
        if [[ -n "$link_exe_path" ]]; then
            # 将 Visual Studio 工具链路径添加到 PATH 前面（Git Bash 使用 Unix 风格路径）
            local vs_bin_dir=$(dirname "$link_exe_path")
            export PATH="$vs_bin_dir:$PATH"
            echo -e "${GREEN}[INFO]${NC} 找到 Visual Studio 链接器: $link_exe_path"
            echo -e "${GREEN}[INFO]${NC} 已设置 PATH 优先级，确保使用正确的 link.exe"
            
            # 设置 MSVC 库路径（包含 msvcrt.lib 等）
            local msvc_base=$(dirname "$(dirname "$(dirname "$vs_bin_dir")")")
            local msvc_lib="$msvc_base/lib/x64"
            if [[ -d "$msvc_lib" ]]; then
                local win_msvc_lib=$(echo "$msvc_lib" | sed 's|^/c/|C:/|' | sed 's|/|\\|g')
                if [[ -z "$LIB" ]]; then
                    export LIB="$win_msvc_lib"
                else
                    export LIB="$win_msvc_lib;$LIB"
                fi
                echo -e "${GREEN}[INFO]${NC} 已添加 MSVC 库路径: $msvc_lib"
                
                # 检查 msvcrt.lib
                if [[ -f "$msvc_lib/msvcrt.lib" ]]; then
                    echo -e "${GREEN}[INFO]${NC} 找到 msvcrt.lib: $msvc_lib/msvcrt.lib"
                else
                    echo -e "${YELLOW}[WARN]${NC} 未找到 msvcrt.lib，可能影响编译"
                fi
            fi
            
            # 验证 link.exe 路径
            local which_link=$(which link.exe 2>/dev/null || which link 2>/dev/null || echo "")
            if [[ -n "$which_link" ]]; then
                echo -e "${GREEN}[INFO]${NC} 当前使用的 link: $which_link"
            fi
        else
            echo -e "${YELLOW}[WARN]${NC} 未找到 Visual Studio link.exe"
            echo -e "${YELLOW}[INFO]${NC} 如果编译失败，请在 cmd.exe 中运行: vcvarsall.bat x64"
        fi
        
        # 检查 Visual Studio 环境变量
        if [[ -z "$VCINSTALLDIR" ]]; then
            echo -e "${YELLOW}[INFO]${NC} 未检测到 Visual Studio 环境变量 VCINSTALLDIR"
        else
            echo -e "${GREEN}[INFO]${NC} Visual Studio 环境已存在: $VCINSTALLDIR"
        fi
        
        # 自动检测并设置 Windows SDK 路径（确保 kernel32.lib 等可找到）
        local sdk_base="/c/Program Files (x86)/Windows Kits/10"
        if [[ -d "$sdk_base/Lib" ]]; then
            # 查找最新的 SDK 版本
            local sdk_version=""
            local latest_version=""
            for version_dir in "$sdk_base/Lib"/10.0.*; do
                if [[ -d "$version_dir" ]]; then
                    local ver=$(basename "$version_dir")
                    if [[ -z "$latest_version" || "$ver" > "$latest_version" ]]; then
                        latest_version="$ver"
                    fi
                fi
            done
            sdk_version="$latest_version"
            
            if [[ -n "$sdk_version" ]]; then
                echo -e "${GREEN}[INFO]${NC} 检测到 Windows SDK 版本: $sdk_version"
                local sdk_lib_um="$sdk_base/Lib/$sdk_version/um/x64"
                local sdk_lib_ucrt="$sdk_base/Lib/$sdk_version/ucrt/x64"
                
                # 检查 kernel32.lib 是否存在
                if [[ -f "$sdk_lib_um/kernel32.lib" ]]; then
                    echo -e "${GREEN}[INFO]${NC} 找到 kernel32.lib: $sdk_lib_um/kernel32.lib"
                else
                    echo -e "${YELLOW}[WARN]${NC} 未找到 kernel32.lib，可能影响编译"
                fi
                
                # 设置 LIB 环境变量（使用 Windows 路径格式）
                if [[ -d "$sdk_lib_um" ]]; then
                    local win_sdk_um=$(echo "$sdk_lib_um" | sed 's|^/c/|C:/|' | sed 's|/|\\|g')
                    if [[ -z "$LIB" ]]; then
                        export LIB="$win_sdk_um"
                    else
                        export LIB="${LIB};${win_sdk_um}"
                    fi
                fi
                if [[ -d "$sdk_lib_ucrt" ]]; then
                    local win_sdk_ucrt=$(echo "$sdk_lib_ucrt" | sed 's|^/c/|C:/|' | sed 's|/|\\|g')
                    export LIB="${LIB:+$LIB;}${win_sdk_ucrt}"
                fi
            else
                echo -e "${YELLOW}[WARN]${NC} 未检测到 Windows SDK 版本"
            fi
        fi
    fi
}

# 检查 Cargo.toml
check_cargo_toml() {
    if [[ ! -f "Cargo.toml" ]]; then
        echo -e "${RED}[ERROR]${NC} 未找到 Cargo.toml，请确保在 Rust 项目根目录运行此脚本"
        exit 1
    fi
    
    # 读取项目名称
    PROJECT_NAME=$(grep -E '^name\s*=' Cargo.toml | head -1 | sed -E 's/^name\s*=\s*"([^"]+)".*/\1/')
    if [[ -z "$PROJECT_NAME" ]]; then
        echo -e "${RED}[ERROR]${NC} 无法从 Cargo.toml 读取项目名称"
        exit 1
    fi
    
    echo -e "${GREEN}[INFO]${NC} 项目名称: $PROJECT_NAME"
}

# 检查 Rust 工具链
check_rust() {
    if ! command -v cargo &> /dev/null; then
        echo -e "${RED}[ERROR]${NC} 未找到 cargo 命令，请先安装 Rust"
        echo -e "${YELLOW}[INFO]${NC} 安装方法: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
        exit 1
    fi
    
    echo -e "${GREEN}[INFO]${NC} Rust 版本: $(cargo --version)"
    echo -e "${GREEN}[INFO]${NC} Rust 工具链: $(rustup show | grep 'Default host' | awk '{print $3}')"
}

# 安装目标平台（如果需要）
install_target() {
    if ! rustup target list --installed | grep -q "^$TARGET$"; then
        echo -e "${YELLOW}[INFO]${NC} 安装目标平台: $TARGET"
        rustup target add "$TARGET"
    else
        echo -e "${GREEN}[INFO]${NC} 目标平台已安装: $TARGET"
    fi
}

# 编译项目
build_project() {
    echo -e "\n${GREEN}========================================${NC}"
    echo -e "${GREEN}[BUILD]${NC} 开始编译: $PROJECT_NAME"
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}[INFO]${NC} 目标: $TARGET"
    echo -e "${GREEN}[INFO]${NC} 模式: $BUILD_MODE"
    
    # 构建命令
    local build_cmd="cargo build"
    if [[ "$BUILD_MODE" == "release" ]]; then
        build_cmd="$build_cmd --release"
    fi
    build_cmd="$build_cmd --target $TARGET"
    
    echo -e "${GREEN}[INFO]${NC} 执行: $build_cmd"
    
    if eval "$build_cmd"; then
        echo -e "${GREEN}[SUCCESS]${NC} 编译成功: $PROJECT_NAME"
        return 0
    else
        echo -e "${RED}[ERROR]${NC} 编译失败: $PROJECT_NAME"
        return 1
    fi
}

# 查找生成的动态库
find_library() {
    local lib_file=""
    local target_dir="target/$TARGET/$BUILD_MODE"
    
    case "$OS_TYPE" in
        windows)
            lib_file="$target_dir/${PROJECT_NAME}.dll"
            ;;
        linux)
            lib_file="$target_dir/lib${PROJECT_NAME}.so"
            ;;
        darwin)
            lib_file="$target_dir/lib${PROJECT_NAME}.dylib"
            ;;
    esac
    
    if [[ -f "$lib_file" ]]; then
        echo "$lib_file"
        return 0
    else
        echo ""
        return 1
    fi
}

# 复制动态库到 resources/native 目录
copy_to_resources() {
    local lib_file="$1"
    
    # 向上查找包含 src/main/java 的目录（Java 模块根目录）
    local java_module_path=""
    local current_path="$SCRIPT_DIR"
    
    while [[ "$current_path" != "/" ]]; do
        if [[ -d "$current_path/src/main/java" ]]; then
            java_module_path="$current_path"
            break
        fi
        current_path=$(dirname "$current_path")
    done
    
    if [[ -z "$java_module_path" ]]; then
        echo -e "${YELLOW}[WARN]${NC} 未找到 Java 模块路径（包含 src/main/java 的目录），跳过复制"
        echo -e "${YELLOW}[INFO]${NC} 动态库位置: $lib_file"
        return 1
    fi
    
    # 复制到当前模块的 resources/native 目录
    local native_dir="$java_module_path/src/main/resources/native/$PLATFORM_DIR"
    mkdir -p "$native_dir"
    local dest_file="$native_dir/$(basename "$lib_file")"
    cp "$lib_file" "$dest_file"
    echo -e "${GREEN}[SUCCESS]${NC} 复制到: $dest_file"
    
    return 0
}

# 主函数
main() {
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Rust 项目构建脚本${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}[INFO]${NC} 操作系统: $OS_TYPE"
    echo -e "${GREEN}[INFO]${NC} 架构: $ARCH"
    echo -e "${GREEN}[INFO]${NC} 构建模式: $BUILD_MODE"
    
    # 检查 Cargo.toml
    check_cargo_toml
    
    # 设置目标平台
    setup_target
    
    # 设置 Windows 环境
    setup_windows_env
    
    # 检查 Rust 工具链
    check_rust
    
    # 安装目标平台
    install_target
    
    # 编译项目
    if ! build_project; then
        exit 1
    fi
    
    # 查找生成的动态库
    local lib_file=$(find_library)
    if [[ -z "$lib_file" ]]; then
        echo -e "${RED}[ERROR]${NC} 未找到生成的动态库文件"
        echo -e "${YELLOW}[INFO]${NC} 请检查 target/$TARGET/$BUILD_MODE 目录"
        exit 1
    fi
    
    echo -e "${GREEN}[INFO]${NC} 找到动态库: $lib_file"
    
    # 复制到 resources/native 目录
    copy_to_resources "$lib_file"
    
    echo -e "\n${GREEN}========================================${NC}"
    echo -e "${GREEN}[SUCCESS]${NC} 构建完成！"
    echo -e "${GREEN}========================================${NC}"
}

# 运行主函数
main "$@"

