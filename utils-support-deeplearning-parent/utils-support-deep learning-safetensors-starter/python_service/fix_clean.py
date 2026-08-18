import sys, glob, os

# 查找真实文件
candidates = glob.glob(r"D:\ch\project\utils-support-parent-starter\**\safetensor_models.py", recursive=True)
p = None
for c in candidates:
    if "python_service" in c:
        p = c
        break
if not p:
    print("NOT FOUND")
    sys.exit(1)
print("PATH:", p)
c = open(p, encoding="utf-8").read()
if "_clean_ocr_output" in c:
    print("already present")
    sys.exit(0)

old_run_start = (
    "    def run(self, inputs: dict, params: dict) -> str:\n"
    "        if not self._loaded:\n"
    "            self._apply_gpu(params)\n"
    "            self._load()\n\n"
    "        import base64, io\n"
    "        from PIL import Image\n"
)
insert = (
    "    def _clean_ocr_output(self, text: str) -> str:\n"
    '        """清理 OvisOCR2 输出：去除 chat 回显与 think/response 标签，仅保留最终生成内容。"""\n'
    "        import re\n"
    "        if not text:\n"
    "            return text\n"
    "        s = text\n"
    '        idx = s.rfind("assistant")\n'
    "        if idx >= 0:\n"
    '            s = s[idx + len("assistant"):].strip()\n'
    '        s = re.sub(r"<[^>]+>", "", s)\n'
    "        return s.strip()\n\n"
    + old_run_start
)
if old_run_start in c:
    c = c.replace(old_run_start, insert, 1)
    open(p, "w", encoding="utf-8").write(c)
    print("inserted _clean_ocr_output")
else:
    print("run marker NOT found")