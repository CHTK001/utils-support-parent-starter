# -*- coding: utf-8 -*-
"""模型测试覆盖总览生成器。

用法:
  python generate-coverage.py            # 生成/更新报告中的「模型测试覆盖总览」章节
  python generate-coverage.py --check    # 仅输出 pending 清单，不写报告

数据源: docs/model-test-data.json（唯一维护入口）
目标:   docs/模型测试报告.html 中 <!-- coverage:start --> ... <!-- coverage:end --> 区块
"""
import io
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import date

BASE = os.path.dirname(os.path.abspath(__file__))
JSON_PATH = os.path.join(BASE, 'model-test-data.json')
REPORT_PATH = os.path.join(BASE, '模型测试报告.html')

START_MARK = '<!-- coverage:start -->'
END_MARK = '<!-- coverage:end -->'

STATUS_LABEL = {
    'verified': '✅ 实测通过',
    'smoke': '🧪 冒烟通过（无正样本）',
    'documented': '📄 已有记录',
    'issue': '🐞 发现缺陷',
    'pending': '⬜ 未测试',
}
STATUS_ORDER = ['verified', 'smoke', 'issue', 'documented', 'pending']


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
    out.append('<h2 id="model-coverage">附：模型测试覆盖总览（%d 个注册模型，更新于 %s）</h2>'
               % (total, today))
    out.append('<p>数据源 <code>docs/model-test-data.json</code>，由 '
               '<code>docs/generate-coverage.py</code> 遍历生成本章节；'
               '维护测试状态只需修改 JSON 后重新运行脚本。</p>')
    out.append('<p>状态统计：%s</p>' % '　'.join(
        '%s %d' % (STATUS_LABEL[s], counts.get(s, 0)) for s in STATUS_ORDER))
    out.append('<p>待测试清单（%d）：%s</p>' % (
        counts.get('pending', 0),
        '、'.join('`%s`' % m['id'] for m in models if m['status'] == 'pending')
        or '无'))
    for cap in sorted(by_cap):
        group = sorted(by_cap[cap], key=lambda m: (STATUS_ORDER.index(m['status']), m['id']))
        out.append('<h3>%s（%d）</h3>' % (cap, len(group)))
        out.append('<table><thead><tr><th>模型ID</th><th>状态</th>'
                   '<th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>')
        for m in group:
            detail = ''
            if m.get('results'):
                detail = '；'.join('%s→%s' % (k, v) for k, v in m['results'].items())
                if m.get('threshold'):
                    th = m['threshold']
                    parts = []
                    if th.get('default') is not None:
                        parts.append('默认阈值 %s' % th['default'])
                    if th.get('tested'):
                        parts.append('阈值验证 %s' % th['tested'])
                    detail += '（%s）' % '，'.join(parts)
            elif m['status'] == 'pending':
                detail = ''
            out.append('<tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>'
                       % (m['id'], STATUS_LABEL[m['status']], m['translator'], detail))
        out.append('</tbody></table>')
    return '\n'.join(out)


def main():
    data = load_data()
    html = render(data)
    if '--check' in sys.argv:
        print(html)
        return
    block = START_MARK + '\n' + html + '\n' + END_MARK
    with io.open(REPORT_PATH, encoding='utf-8') as fp:
        content = fp.read()
    if START_MARK in content and END_MARK in content:
        i = content.index(START_MARK)
        j = content.index(END_MARK) + len(END_MARK)
        new_content = content[:i] + block + content[j:]
    else:
        # 首次插入：附加到文末
        sep = '' if content.endswith('\n') else '\n'
        new_content = content + sep + '<hr>\n' + block + '\n'
    with io.open(REPORT_PATH, 'w', encoding='utf-8') as fp:
        fp.write(new_content)
    pending = sum(1 for m in data['models'] if m['status'] == 'pending')
    print('coverage section updated: total=%d pending=%d' % (len(data['models']), pending))


if __name__ == '__main__':
    main()
