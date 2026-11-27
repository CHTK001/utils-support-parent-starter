"""容器健康检查：模型真的加载完成才算健康。

``/status`` 在加载期间也回 200（``loading=true`` / ``model_loaded=false``），
所以不能只看 HTTP 状态码。加载失败（``error`` 非空）与正在加载都判为不健康，
让编排层保持「未就绪」，避免流量打到还不能推理的实例上。
"""

import json
import os
import sys
import urllib.request

PORT = os.environ.get("WEVISONE_PORT", "8765")
URL = f"http://127.0.0.1:{PORT}/status"


def main() -> int:
    try:
        with urllib.request.urlopen(URL, timeout=8) as response:
            body = json.load(response)
    except Exception as exc:  # noqa: BLE001 - 健康检查只回状态码
        print(f"unhealthy: {type(exc).__name__}: {exc}")
        return 1
    if body.get("model_loaded") is True:
        return 0
    print(f"not ready: loading={body.get('loading')} error={body.get('error')}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
