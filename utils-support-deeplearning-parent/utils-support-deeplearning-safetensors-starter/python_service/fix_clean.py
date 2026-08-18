import glob
candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
print("PATH:", p)
c = open(p, encoding="utf-8").read()
print("has def:", "def _clean_ocr_output" in c)
print("has call:", "self._clean_ocr_output(raw)" in c)

if "def _clean_ocr_output" in c:
    print("no-op")
else:
    method_def = (
        "    def _clean_ocr_output(self, text: str) -> str:\n"
        "        \u6e05\u7406 OvisOCR2 \u8f93\u51fa\uff1a\u53bb\u9664 chat \u56de\u663e\u4e0e think/response \u6807\u7b7e\uff0c\u4ec5\u4fdd\u7559\u6700\u7ec8\u751f\u6210\u5185\u5bb9\u3002\n"
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
    anchor = "    def run(self, inputs: dict, params: dict) -> str:"
    idx = c.find(anchor)
    if idx < 0:
        print("run NOT found")
    else:
        before = c[:idx]
        load_marker = 'log.info("[OvisOCR2] \u52a0\u8f7d\u5b8c\u6210")'
        li = before.rfind(load_marker)
        if li >= 0:
            insert_pos = li + len(load_marker) + 1
        else:
            insert_pos = idx
        new_c = c[:insert_pos] + "\n" + method_def.rstrip("\n") + "\n" + c[insert_pos:]
        open(p, "w", encoding="utf-8").write(new_c)
        print("inserted def")