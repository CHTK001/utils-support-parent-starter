# WeVisOne-VL 推理服务镜像（CPU）
#
# 构建上下文 = 本 python/ 目录，且目录内需要有官方源码检出 `WeVisOne-VL/`
# （见 README.md 的「Docker」一节：把 https://github.com/tencent/WeVisOne-VL 的
# `WeVisOne-VL-main` 目录改名/复制成 `python/WeVisOne-VL`，该目录不入库）。
#
#   docker build -t wevisone-vl-2b:latest .
#   docker run -d --name wevisone-vl -p 8765:8765 \
#       -v /host/WeVisOne-VL-2B:/models/WeVisOne-VL-2B:ro \
#       --memory 12g wevisone-vl-2b:latest
#
# 内存：2B 权重 bf16 约 5GB，float32 约 10GB；容器 --memory 低于 8g 必然 OOM。
# CPU 上单图推理是分钟级，生产环境请改用 GPU 机器（`WEVISONE_DEVICE=cuda`）。

FROM python:3.11-slim

# torch 运行时依赖 libgomp（官方 slim 镜像不带），缺了 import torch 直接报
# OSError: libgomp.so.1: cannot open shared object file
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    WEVISONE_MODEL=/models/WeVisOne-VL-2B \
    WEVISONE_DEVICE=cpu \
    WEVISONE_DTYPE=bfloat16 \
    WEVISONE_BACKEND=transformers

WORKDIR /opt/wevisone

# CPU 版 torch/torchvision 走 PyTorch 官方 CPU 索引，避免拉 CUDA 轮子（省约 2GB）
RUN pip install --index-url https://download.pytorch.org/whl/cpu \
        torch==2.11.0 torchvision==0.26.0

COPY requirements.txt /opt/wevisone/requirements.txt
RUN pip install -r /opt/wevisone/requirements.txt

# 官方推理包（wevisone_vl）随源码一起安装；`pip install <dir>` 会把包和它的依赖元数据注册进环境
COPY WeVisOne-VL /opt/WeVisOne-VL
RUN pip install /opt/WeVisOne-VL

COPY server.py /opt/wevisone/server.py
COPY healthcheck.py /opt/wevisone/healthcheck.py

EXPOSE 8765

# 健康检查要求模型真的加载完成：加载期间 /status 仍回 200（loading=true），
# 只看 HTTP 码会把「还在加载」误判成健康，所以交给 healthcheck.py 解析 model_loaded。
HEALTHCHECK --interval=30s --timeout=10s --start-period=900s --retries=3 \
    CMD ["python", "/opt/wevisone/healthcheck.py"]

# 单 worker：模型是进程内单例，多 worker 会各自加载一份权重（内存翻倍）
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765", "--workers", "1"]
