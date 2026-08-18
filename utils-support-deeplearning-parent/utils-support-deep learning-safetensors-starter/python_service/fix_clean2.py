import glob

candidates = glob.glob(
    "D:\\ch\\project\\utils-support-parent-starter\\**\\safetensor_models.py", recursive=True
)
p = [c for c in candidates if "python_service" in c][0]
c = open(p, encoding="utf-8").read()
print("has def:", "def _clean_ocr_output" in c)

if "def _clean_ocr_output" in c:
    print("no-op")
else:
    needle = (
        "    def run(self, inputs: dict, params: dict) -> str:\n"
        "        if not self._loaded:\n"
        "            self._apply_gpu(params)\n"
        "            self._load()\n\n"
        "        import base64, io\n"
    )
    idx = c.find(needle)
    if idx < 0:
        print("OvisOcrRunner.run needle NOT found")
    else:
        method_def = (
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
        new_c = c[:idx] + method_def + c[idx:]
        open(p, "w", encoding="utf-8").write(new_c)
        print("inserted def")