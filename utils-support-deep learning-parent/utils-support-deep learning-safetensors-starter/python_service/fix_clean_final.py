import glob, re

candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
print("PATH:", p)
c = open(p, encoding="utf-8").read()
lines = c.split("\n")

# 1) 找到 OvisOcrRunner 类区间（class OvisOcrRunner 到下一个 class 前）
cls_start = None
cls_end = None
for i, ln in enumerate(lines):
    if ln.startswith("class OvisOcrRunner"):
        cls_start = i
    elif cls_start is not None and ln.startswith("class ") and i > cls_start:
        cls_end = i
        break
if cls_start is None:
    print("class OvisOcrRunner NOT found")
    raise SystemExit(1)
if cls_end is None:
    cls_end = len(lines)
print(f"OvisOcrRunner class lines {cls_start+1}-{cls_end}")

# 2) 类内已有 def _clean_ocr_output?
inside = lines[cls_start:cls_end]
has_def = any("def _clean_ocr_output" in l for l in inside)
print("has def in OvisOcrRunner:", has_def)

# 3) 移除文件其它位置的 def _clean_ocr_output（不在 OvisOcrRunner 内的）
def_start = None
def_end = None
for i, ln in enumerate(lines):
    if "def _clean_ocr_output" in ln:
        if cls_start <= i < cls_end:
            continue  # 类内，保留
        # 记录区间
        j = i + 1
        while j < len(lines):
            lj = lines[j]
            if lj.startswith("    def ") or lj.startswith("class ") or (lj.startswith("def ") and not lj.startswith("    def ")):
                break
            j += 1
        # 从后往前删
        print(f"remove stray def at lines {i+1}-{j}")
        del lines[i:j]
        break  # 逐个处理，重新扫描

# 重新读取
c2 = "\n".join(lines)
lines = c2.split("\n")

# 4) 若 OvisOcrRunner 内无 def，则在其 run 前插入（run 含 apply_chat_template）
cls_start2 = None
for i, ln in enumerate(lines):
    if ln.startswith("class OvisOcrRunner"):
        cls_start2 = i
        break
if cls_start2 is None:
    print("class OvisOcrRunner lost!")
    raise SystemExit(1)
inside2 = lines[cls_start2:]
if not any("def _clean_ocr_output" in l for l in inside2):
    run_idx = None
    for i, ln in enumerate(inside2):
        if ln == "    def run(self, inputs: dict, params: dict) -> str:":
            # 确认这是含 apply_chat_template 的 run
            j = i + 1
            body_has = False
            while j < len(inside2):
                if inside2[j].startswith("    def ") or inside2[j].startswith("class "):
                    break
                if "apply_chat_template" in inside2[j]:
                    body_has = True
                j += 1
            if body_has:
                run_idx = cls_start2 + i
                break
    if run_idx is None:
        print("OvisOcrRunner.run with apply_chat_template NOT found")
        raise SystemExit(1)
    def_block = [
        "    def _clean_ocr_output(self, text: str) -> str:",
        "        import re",
        "        if not text:",
        "            return text",
        "        s = text",
        "        sidx = s.rfind('assistant')",
        "        if sidx >= 0:",
        "            s = s[sidx + len('assistant'):].strip()",
        "        s = re.sub(r'<[^>]+>', '', s)",
        "        return s.strip()",
        "",
    ]
    lines = lines[:run_idx] + def_block + lines[run_idx:]
    print(f"inserted def before OvisOcrRunner.run at line {run_idx+1}")

open(p, "w", encoding="utf-8").write("\n".join(lines))
print("written OK")