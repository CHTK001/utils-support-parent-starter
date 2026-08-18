import glob

candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
c = open(p, encoding="utf-8").read()

# 1) 移除 VlmRunner 内误插的 _clean_ocr_output（位于 _build_prompt 之后、VlmRunner.run 之前）
def_block = (
    "    def _clean_ocr_output(self, text: str) -> str:\n"
    "        import re\n"
    "        if not text:\n"
    "            return text\n"
    "        s = text\n"
    "        sidx = s.rfind('assistant')\n"
    "        if sidx >= 0:\n"
    "            s = s[sidx + len('assistant'):].strip()\n"
    "        s = re.sub(r'<[^>]+>', '', s)\n"
    "        return s.strip()\n\n"
)
if def_block in c:
    c = c.replace(def_block, "", 1)
    print("removed wrong def")
else:
    print("def_block not found for removal")

# 2) 在 OvisOcrRunner 的 run 前插入（其 run 特征：紧随 import base64, io 在 OvisOCR 加载日志之后）
# OvisOcrRunner.run 的特征：前面有 _load_modelscope_pipeline 与 query 默认中文
needle = (
    "        query = prompt if prompt.strip() else \u8bf7\u5c06\u8fd9\u5f20\u6587\u6863\u56fe\u7247\u8f6c\u6362\u4e3a Markdown \u683c\u5f0f\u3002\n"
    "\n"
    "        import torch\n"
)
idx = c.find(needle)
print("ovis needle idx:", idx)
if idx < 0:
    # 备选：找 OvisOcrRunner.run 的 "image = Image.open"
    anchor = "    def run(self, inputs: dict, params: dict) -> str:"
    # 找最后一个 run（OvisOcrRunner/Unlimited 之外需要 OvisOCR 调用点），用 apply_chat_template 定位
    at_idx = c.find("apply_chat_template")
    if at_idx < 0:
        print("apply_chat_template not found")
    else:
        # 找该行之前最近的 "    def run"
        line_start = c.rfind("\n", 0, at_idx)
        run_idx = c.rfind(anchor, 0, line_start)
        print("fallback run idx:", run_idx)
        if run_idx >= 0:
            new_c = c[:run_idx] + def_block + c[run_idx:]
            open(p, "w", encoding="utf-8").write(new_c)
            print("inserted def near apply_chat_template")
        else:
            print("run anchor not found")
else:
    # needle 匹配到 OvisOcrRunner 的 query 赋值，run 在其后
    run_idx = c.find("    def run(self, inputs: dict, params: dict) -> str:", idx)
    new_c = c[:run_idx] + def_block + c[run_idx:]
    open(p, "w", encoding="utf-8").write(new_c)
    print("inserted def before OvisOcrRunner.run")