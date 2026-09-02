# -*- coding: utf-8 -*-
"""Update coverage section in ONNX HTML report from model-test-data.json"""
import io, json, os, sys
from collections import Counter, defaultdict
from datetime import date

BASE = os.path.dirname(os.path.abspath(__file__))
JSON_PATH = os.path.join(BASE, 'model-test-data.json')
HTML_PATH = os.path.join(BASE, 'ONNX模型库测试报告.html')

START_MARK = '<!-- coverage:start -->'
END_MARK = '<!-- coverage:end -->'

STATUS_LABEL = {
    'verified': '\u2705 \u5b9e\u6d4b\u901a\u8fc7',
    'smoke': '\U0001f9ea \u5192\u70df\u901a\u8fc7\uff08\u65e0\u6b63\u6837\u672c\uff09',
    'documented': '\U0001f4c4 \u5df2\u6709\u8bb0\u5f55',
    'issue': '\U0001f41e \u53d1\u73b0\u7f3a\u9677',
    'na': '\U0001f6ab \u4e0d\u518d\u8ffd\u8e2a',
    'pending': '\u2b1c \u672a\u6d4b\u8bd5',
}
STATUS_ORDER = ['verified', 'smoke', 'issue', 'documented', 'na', 'pending']

def load_data():
    with io.open(JSON_PATH, encoding='utf-8') as fp:
        return json.load(fp)

def render(data):
    models = data['models']
    by_cap = defaultdict(list)
    for m in models:
        by_cap[m['capability']].append(m)

    counts = Counter(m['status'] for m in models)
    total = len(models)
    today = date.today().isoformat()

    out = []
    out.append('<h2 id="model-coverage">\u9644\uff1a\u6a21\u578b\u6d4b\u8bd5\u8986\u76d6\u603b\u89c8\uff08%d \u4e2a\u6ce8\u518c\u6a21\u578b\uff0c\u66f4\u65b0\u4e8e %s\uff09</h2>'
               % (total, today))
    out.append('<p>\u6570\u636e\u6e90 <code>docs/model-test-data.json</code>\uff0c\u7531 <code>docs/generate-coverage.py</code> \u904d\u5386\u751f\u6210\u672c\u8282\uff1b\u7ef4\u62a4\u6d4b\u8bd5\u72b6\u6001\u53ea\u9700\u4fee\u6539 JSON \u540e\u91cd\u65b0\u8fd0\u884c\u811a\u672c\u3002</p>')
    out.append('<p>\u72b6\u6001\u7edf\u8ba1\uff1a%s</p>' % '\u3000'.join(
        '%s %d' % (STATUS_LABEL[s], counts.get(s, 0)) for s in STATUS_ORDER))
    pending_ids = [m['id'] for m in models if m['status'] == 'pending']
    out.append('<p>\u5f85\u6d4b\u8bd5\u6e05\u5355\uff08%d\uff09\uff1a%s</p>' % (
        counts.get('pending', 0),
        ','.join('`%s`' % mid for mid in pending_ids) or '\u65e0'))
    for cap in sorted(by_cap):
        group = sorted(by_cap[cap], key=lambda m: (STATUS_ORDER.index(m['status']), m['id']))
        out.append('<h3>%s\uff08%d\uff09</h3>' % (cap, len(group)))
        out.append('<table><thead><tr><th>\u6a21\u578bID</th><th>\u72b6\u6001</th>'
                   '<th>Translator</th><th>\u5b9e\u6d4b\u6570\u636e / \u8bf4\u660e</th></tr></thead><tbody>')
        for m in group:
            detail = ''
            if m.get('results'):
                parts = []
                for k, v in m['results'].items():
                    parts.append('%s\u2192%s' % (k, v))
                detail = '\uff1b'.join(parts)
                if m.get('threshold'):
                    th = m['threshold']
                    th_parts = []
                    if th.get('default') is not None:
                        th_parts.append('\u9ed8\u8ba4\u9608\u503c %s' % th['default'])
                    if th.get('tested'):
                        th_parts.append('\u9608\u503c\u9a8c\u8bc1 %s' % th['tested'])
                    if th_parts:
                        detail += '\uff08' + '\uff0c'.join(th_parts) + '\uff09'
            elif m['status'] == 'pending':
                detail = m.get('notes', '')
            translator = m.get('translator', 'null') or 'null'
            out.append('<tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>'
                       % (m['id'], STATUS_LABEL[m['status']], translator, detail))
        out.append('</tbody></table>')
    return '\n'.join(out)

def main():
    data = load_data()
    html = render(data)
    with io.open(HTML_PATH, encoding='utf-8') as fp:
        content = fp.read()
    if START_MARK in content and END_MARK in content:
        i = content.index(START_MARK)
        j = content.index(END_MARK) + len(END_MARK)
        new_content = content[:i] + START_MARK + '\n' + html + '\n' + END_MARK + content[j:]
    else:
        sep = '' if content.endswith('\n') else '\n'
        new_content = content + sep + '<hr>\n' + START_MARK + '\n' + html + '\n' + END_MARK + '\n'
    with io.open(HTML_PATH, 'w', encoding='utf-8') as fp:
        fp.write(new_content)
    counts = Counter(m['status'] for m in data['models'])
    print('coverage updated: total=%d verified=%d smoke=%d na=%d pending=%d' % (
        len(data['models']), counts.get('verified',0), counts.get('smoke',0),
        counts.get('na',0), counts.get('pending',0)))

if __name__ == '__main__':
    main()
