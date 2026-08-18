import glob

candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
c = open(p, encoding="utf-8").read()
lines = c.split("\n")

# 找到所有误插的 def _clean_ocr_output 块并移除（它们在错误的类内）
stray = []
i = 0
while i < len(lines):
    if lines[i].strip() == "def _clean_ocr_output(self, text: str) -> str:":
        # 收集缩进 4 空格的块直到下一个同级（非 4空格缩进代码块）
        j = i + 1
        while j < len(lines):
            ln = lines[j]
            if ln.startswith("        ") or ln.strip() == "" or ln.startswith("    "):
                # 空行或注释算作块内
                if ln.strip() == "":
                    j += 1
                    continue
                if ln.startswith("    ") and not ln.startswith("        "):
                    break
                j += 1
            else:
                break
        stray.append((i, j))
        i = j
    else:
        i += 1

# 从后往前删除误插块
for lo, hi in reversed(stray):
    # 检查该块是否就在 OvisOcrRunner.run 前面（合理位置不应删）—— 简化：都删，因为 OvisOcrRunner.run 前会重新插入
    del lines[lo:hi]
    print(f"removed stray def block lines {lo+1}-{hi}")

# 现在找含 apply_chat_template 的 run，在其前插入 def
run_anchor = "    def run(self, inputs: dict, params: dict) -> str:"
at = None
for i, ln in enumerate(lines):
    if "apply_chat_template" in ln:
        # 向上找最近的 def run
        for k in range(i - 1, -1, -1):
            if lines[k] == run_anchor:
                at = k
                break
        break

if at is None:
    print("OvisOcrRunner.run NOT found")
    open(p, "w", encoding="utf-8").write("\n".join(lines))
else:
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
        "        return s.strip()\n"
        ""
    )
    # 检查是否已存在（at 前一行是 return s.strip())
    prev = lines[at - 1].strip() if at > 0 else ""
    if prev == "return s.strip()":
        print("already inserted correctly")
    else:
        lines = lines[:at] + def_block.split("\n") + lines[at:]
        print(f"inserted def before run at line {at+1}")
    open(p, "w", encoding="utf-8").write("\n".join(lines))