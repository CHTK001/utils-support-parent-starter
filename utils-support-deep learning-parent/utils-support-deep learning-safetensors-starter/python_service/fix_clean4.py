import glob, re

candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
c = open(p, encoding="utf-8").read()

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

# 移除所有误插的 def_block（可能多处）
while def_block in c:
    c = c.replace(def_block, "", 1)
print("removed stray defs")

# 找到 OvisOcrRunner.run：方法体内含 apply_chat_template 的 def run
lines = c.split("\n")
run_anchor = "    def run(self, inputs: dict, params: dict) -> str:"
# 扫描每个 run 的定义位置
positions = [i for i, ln in enumerate(lines) if ln == run_anchor]
target = None
for pos in positions:
    # 向后收集到下一个同缩进 def/class 为止，检查是否含 apply_chat_template
    j = pos + 1
    body = []
    while j < len(lines):
        ln = lines[j]
        if ln.startswith("    def ") or ln.startswith("class "):
            break
        body.append(ln)
        j += 1
    if any("apply_chat_template" in b for b in body):
        target = pos
        break

if target is None:
    print("OvisOcrRunner.run NOT found")
else:
    print("OvisOcrRunner.run at line", target + 1)
    # 检查该位置是否已插 def（前一行是否为 def_block 末尾）
    prev = lines[target - 1].strip() if target > 0 else ""
    if prev == "return s.strip()":
        print("already inserted")
    else:
        # 在 target 前插入 def_block
        new_lines = lines[:target] + def_block.rstrip("\n").split("\n") + lines[target:]
        open(p, "w", encoding="utf-8").write("\n".join(new_lines))
        print("inserted before OvisOcrRunner.run")