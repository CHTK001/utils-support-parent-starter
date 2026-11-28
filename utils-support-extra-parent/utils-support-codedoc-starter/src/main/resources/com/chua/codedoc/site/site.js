/* Generated content is rendered only with textContent. No HTML from the model is executed. */
(() => {
  'use strict';
  const data = window.CODEDOC_DATA;
  const main = document.getElementById('main');
  if (!data || !Array.isArray(data.pages)) {
    main.textContent = '文档数据未能载入。请确认 index.html、data.js、site.js、site.css 在同一目录。';
    return;
  }
  document.title = data.title;
  document.getElementById('brand-title').textContent = data.title;
  document.getElementById('issues-link').textContent = `生成记录 · ${data.issues.length} 个问题`;
  const nav = document.getElementById('file-nav');
  const toc = document.getElementById('toc');
  const search = document.getElementById('search');
  const status = document.getElementById('status');
  const menu = document.getElementById('menu');
  const sidebar = document.getElementById('sidebar');

  /* MERGE-BEGIN */
  // 合并口径：只按「标题里的功能概念」把同功能族的多个实现（不同模型 / 方言 / 平台 / 后端）
  // 收敛为一篇阅读文档；不按分类、摘要或正文合并。组内每篇原样保留为「多种配置」中的一节，
  // 说明、示例、API 参考与源码快照都不删除，源码覆盖不减少。
  const MERGE_BLACKLIST = new Set([
    '完整示例', '示例', '使用说明', '快速开始', '概述', '模块说明', '工具类', '数据模型',
    // 领域/子系统名不是功能概念：一个页面塞进不同任务的翻译器、混合职责的处理器，
    // 读起来比按功能拆开更差，交由更具体的功能词归组。
    '深度学习', '图像处理', '数据同步', '文件处理', '常量定义'
  ]);
  const MERGE_CURATED = [
    { label: '进度条', test: title => /进度条|进度跟踪/.test(title) },
    // 同一功能的不同厂商/后端实现：标题写作「图像生成」或「图片生成」。
    // 「幻影坦克」是图像隐写、二维码是编码，属别的功能，不并入。
    { label: '图像生成', test: title => /图像生成|图片生成/.test(title) && !/幻影坦克|二维码/.test(title) },
    // 文件转换 SPI 的读、写、读写三套实现（含各文件格式的实现）。
    { label: '文件读写', test: title => /文件读写|文件读取|文件写入/.test(title) },
    // 同一批对称算法在 JDK 与 BouncyCastle 两套后端上的实现（「非对称加密」是另一族）。
    { label: '对称加解密', test: title => /^(?!.*非对称).*对称(加解密|加密)$/.test(mergeConceptOf(title)) },
    // APM 字节码插桩的中间件处理器：同一包下每个 Handler 是「调用拦截」的一种配置。
    // 按源码路径锁定（标题写法有「调用拦截 / 请求拦截 / JDBC 拦截 / 监控处理器」等多种），
    // 数据模型与工具类不并入。
    {
      label: 'APM 调用拦截处理器',
      test: (title, page) => {
        const path = String((page && page.path) || '');
        if (!/\/runtime\/apm\/handler\/[^/]+\.java$/.test(path)) return false;
        return !/(?:^|\/)(?:LogEntry|BoundedRecordList|SoftwareDetector)\.java$/.test(path);
      }
    }
  ];
  // 标题里带功能词、源码却不是这个功能：SPI 的 ServiceResolver 是「服务解析」，
  // 只是标题末尾写作「SPI服务发现」，不并入网络服务发现族。
  const MERGE_EXCLUDE_PATHS = [
    /\/com\/chua\/common\/support\/spi\/resolver\/ServiceResolver\.java$/
  ];
  const MERGE_MIN_TERM = 4;
  const MERGE_MIN_MEMBERS = 3;
  const MERGE_MAX_MEMBERS = 20;
  const MERGE_COHERENCE = 0.8;

  function mergeConceptOf(title) {
    const text = String(title || '').trim();
    const cut = text.search(/[-—:：]/);
    return cut > 0 ? text.slice(0, cut).trim() : text;
  }

  function isMergeExcluded(page) {
    const path = String((page && page.path) || '');
    return MERGE_EXCLUDE_PATHS.some(pattern => pattern.test(path));
  }

  function buildMergePlan(pages) {
    const assigned = new Set();
    const plan = [];
    function addGroup(label, indices) {
      if (indices.length < 2) return;
      plan.push({ label, members: indices.slice() });
      for (const index of indices) assigned.add(index);
    }
    for (const family of MERGE_CURATED) {
      const indices = [];
      pages.forEach((page, index) => {
        if (!assigned.has(index) && !isMergeExcluded(page) && family.test(String(page.title || ''), page)) indices.push(index);
      });
      addGroup(family.label, indices);
    }
    const termIndex = new Map();
    pages.forEach((page, index) => {
      if (assigned.has(index) || isMergeExcluded(page)) return;
      const terms = new Set(String(page.title || '').match(/[\u4e00-\u9fff]{2,}/g) || []);
      for (const term of terms) {
        if (term.length < MERGE_MIN_TERM || MERGE_BLACKLIST.has(term)) continue;
        if (!termIndex.has(term)) termIndex.set(term, []);
        termIndex.get(term).push(index);
      }
    });
    const candidates = [];
    for (const [term, indices] of termIndex) {
      if (indices.length < MERGE_MIN_MEMBERS || indices.length > MERGE_MAX_MEMBERS) continue;
      // 语义凝聚检查：绝大多数成员的标题「概念前缀」都必须带这个词，
      // 借此排除只靠一个宽泛词偶然同组的无关实现。
      const coherent = indices.filter(index => mergeConceptOf(pages[index].title).includes(term)).length;
      if (coherent / indices.length < MERGE_COHERENCE) continue;
      candidates.push({ term, indices });
    }
    candidates.sort((a, b) => b.term.length - a.term.length || b.indices.length - a.indices.length || (a.term < b.term ? -1 : 1));
    for (const candidate of candidates) {
      const remaining = candidate.indices.filter(index => !assigned.has(index));
      if (remaining.length >= MERGE_MIN_MEMBERS) addGroup(candidate.term, remaining);
    }
    return plan;
  }
  function buildDocModel(pages) {
    const plan = buildMergePlan(pages);
    const memberIndexToDoc = new Map();
    const groupDocs = plan.map((group, position) => {
      const members = group.members.map(index => pages[index]);
      const categories = new Map();
      for (const member of members) {
        const name = member.category || '未分类';
        categories.set(name, (categories.get(name) || 0) + 1);
      }
      let category = '未分类';
      let best = -1;
      for (const [name, count] of categories) {
        if (count > best) {
          best = count;
          category = name;
        }
      }
      const names = members.map(member => member.title || member.path.split('/').pop());
      return {
        id: `group-${position + 1}`,
        title: group.label,
        category,
        isGroup: true,
        members,
        mergedCount: members.length,
        path: `${members[0].path} 等 ${members.length} 个文件`,
        summary: `同一功能的 ${members.length} 个实现合并在本页：${names.join('、')}。右侧「多种配置」可直达每个实现，各自的说明、示例、API 参数参考与源码快照均保留。`
      };
    });
    plan.forEach((group, position) => {
      for (const index of group.members) memberIndexToDoc.set(index, groupDocs[position]);
    });
    const docs = [];
    const seen = new Set();
    pages.forEach((page, index) => {
      const doc = memberIndexToDoc.get(index) || page;
      if (seen.has(doc.id)) return;
      seen.add(doc.id);
      docs.push(doc);
    });
    // 搜索只匹配菜单项本身：标题、分类、源码路径。
    // 合并组收录组内每个实现的标题与真实源码路径；菜单上生成的「等 N 个文件」只是显示文案，不进索引。
    const searchIndex = new Map(docs.map(doc => {
      const parts = [doc.title, doc.category];
      if (doc.members) {
        for (const member of doc.members) parts.push(member.title, member.path);
      } else {
        parts.push(doc.path);
      }
      return [doc.id, parts.join(' ').toLocaleLowerCase()];
    }));
    return { plan, docs, groupDocs, memberIndexToDoc, searchIndex };
  }
  /* MERGE-END */

  const { plan: mergePlan, docs, searchIndex } = buildDocModel(data.pages);
  const docMap = new Map(docs.map(doc => [doc.id, doc]));
  let currentPage = null;
  let renderedRoute = null;
  let pendingAnchor = null;
  const sourceRegistry = new Map();

  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  }
  function link(text, target, className) {
    const node = element('a', text, className);
    node.href = target;
    return node;
  }
  function paragraph(text, className = 'description') {
    return element('p', text || '源码未明确', className);
  }
  function notice() {
    return paragraph('内容由 AI 根据源码生成，需人工复核。所有调用示例均未执行验证；请在自己的环境中确认依赖、配置与副作用。', 'notice');
  }
  function message(text) {
    status.textContent = text;
  }
  function addToc(label, id, route = currentPage.id) {
    toc.append(link(label, `#${route}/${id}`));
  }
  function codeBlock(code, label) {
    const box = element('div', null, 'code-box');
    const toolbar = element('div', null, 'code-toolbar');
    const copy = element('button', '复制代码');
    copy.type = 'button';
    copy.setAttribute('aria-label', `复制${label}`);
    const pre = element('pre');
    const content = element('code', code);
    pre.append(content);
    copy.addEventListener('click', async () => {
      try {
        if (!navigator.clipboard || !window.isSecureContext) throw new Error('clipboard unavailable');
        await navigator.clipboard.writeText(code);
        message('代码已复制');
      } catch {
        const selection = window.getSelection();
        if (selection) {
          const range = document.createRange();
          range.selectNodeContents(content);
          selection.removeAllRanges();
          selection.addRange(range);
          message('代码已选中，请按 Ctrl+C 或 ⌘C 复制');
        } else {
          message('请选中代码后复制');
        }
      }
    });
    toolbar.append(element('span', label), copy);
    box.append(toolbar, pre);
    return box;
  }
  function ensureActiveNavVisible() {
    const active = nav.querySelector('[aria-current="page"]');
    if (!active) return;
    const top = active.offsetTop;
    const bottom = top + active.offsetHeight;
    const viewTop = sidebar.scrollTop + 12;
    const viewBottom = sidebar.scrollTop + sidebar.clientHeight - 12;
    if (top < viewTop) sidebar.scrollTop = Math.max(0, top - 12);
    else if (bottom > viewBottom) sidebar.scrollTop = bottom - sidebar.clientHeight + 12;
  }
  function renderNav() {
    const query = search.value.trim().toLocaleLowerCase();
    const terms = query.split(/\s+/).filter(Boolean);
    const matches = docs.filter(doc => terms.every(term => searchIndex.get(doc.id).includes(term)));
    // 记住被点菜单项在视口中的位置，重建后原位还原，避免目录跳到别处。
    const anchorTop = pendingAnchor && pendingAnchor.isConnected ? pendingAnchor.getBoundingClientRect().top : null;
    nav.replaceChildren();
    nav.append(link('概览与快速导航', '#overview', 'file-link'));
    document.getElementById('search-count').textContent = `${matches.length} / ${docs.length} 个功能文档`;
    const groups = new Map();
    for (const doc of matches) {
      const group = doc.category || '未分类';
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(doc);
    }
    for (const [name, items] of groups) {
      const group = element('details');
      group.open = Boolean(query) || items.some(item => item.id === currentPage?.id) || groups.size <= 4;
      group.append(element('summary', `${name} · ${items.length}`));
      for (const doc of items) {
        const item = link(doc.title || doc.path.split('/').pop(), `#${doc.id}`, 'file-link');
        item.title = doc.path;
        item.append(element('small', doc.members
          ? `合并 ${doc.mergedCount} 篇同功能文档`
          : (doc.path.includes('/') ? doc.path.slice(0, doc.path.lastIndexOf('/')) : doc.language)));
        if (doc.id === currentPage?.id) item.setAttribute('aria-current', 'page');
        item.addEventListener('click', () => { pendingAnchor = item; });
        group.append(item);
      }
      nav.append(group);
    }
    if (!matches.length) nav.append(paragraph('没有匹配的菜单项，请尝试功能名或类名。', 'muted'));
    const current = nav.querySelector('[aria-current="page"]');
    if (current && anchorTop !== null) {
      sidebar.scrollTop += current.getBoundingClientRect().top - anchorTop;
    }
    ensureActiveNavVisible();
    pendingAnchor = null;
  }
  function stat(value, label) {
    const node = element('div', null, 'stat');
    node.append(element('strong', value), element('span', label));
    return node;
  }
  function renderOverview() {
    currentPage = null;
    main.append(element('div', '功能指南', 'breadcrumb'), element('h1', data.title),
      paragraph('从源码出发，查找功能、理解参数，带着示例开始使用。', 'lead'));
    const stats = element('div', null, 'stats');
    stats.append(stat(data.scannedFiles, '匹配源码文件'), stat(data.pages.length, '源码文档'),
      stat(mergePlan.length, '同功能合并组'), stat(docs.length, '阅读文档'),
      stat(data.pages.reduce((sum, page) => sum + page.apis.length, 0), '功能条目'));
    main.append(stats, notice());
    main.append(element('h2', '功能目录'), paragraph(`按功能分类选择指南。同一功能的多个实现已合并为一篇，页右侧「多种配置」列出每个实现的用途、示例、API 与源码快照，源码覆盖没有减少：${data.pages.length} 篇源码文档合并为 ${docs.length} 篇阅读文档。`));
    const list = element('ul', null, 'overview-list');
    const groups = new Map();
    for (const doc of docs) {
      const group = doc.category || '未分类';
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(doc);
    }
    for (const [name, items] of groups) {
      const item = element('li');
      item.append(element('h3', name));
      const entries = element('ul');
      for (const doc of items) {
        const entry = element('li');
        const label = doc.members ? `${doc.title}（合并 ${doc.mergedCount} 篇）` : doc.title || doc.path;
        entry.append(link(label, `#${doc.id}`), paragraph(doc.summary, 'muted'));
        entries.append(entry);
      }
      item.append(entries);
      list.append(item);
    }
    if (!docs.length) main.append(paragraph('尚无成功生成的文档。请查看生成记录中的问题和扫描统计。', 'notice'));
    main.append(list, element('h2', '本次生成'), paragraph(`生成时间：${data.generatedAt}；模型调用 ${data.modelCalls} 次。`, 'meta'),
      link(`查看生成记录（${data.issues.length} 个问题）`, '#issues'));
    toc.append(link('生成记录', '#issues'));
  }
  function renderIssues() {
    currentPage = null;
    main.append(element('div', 'CODE / GENERATION LOG', 'breadcrumb'), element('h1', '生成记录'),
      paragraph(`扫描匹配 ${data.scannedFiles} 个文件，成功 ${data.documentedFiles} 个，模型调用 ${data.modelCalls} 次。`, 'lead'));
    if (!data.issues.length) {
      main.append(paragraph(data.scannedFiles === 0 ? '没有匹配文件，请检查目录和 include/exclude 规则。' : '扫描和分析未报告问题。此状态不代表 AI 文档或示例经过执行验证。'));
      return;
    }
    main.append(table(['文件', '阶段', '说明'], data.issues.map(issue => [issue.path, issue.stage, issue.message])));
  }
  function table(headings, rows) {
    const wrapper = element('div', null, 'table-wrap');
    const node = element('table');
    const head = element('thead');
    const headerRow = element('tr');
    for (const heading of headings) {
      const cell = element('th', heading);
      cell.scope = 'col';
      headerRow.append(cell);
    }
    head.append(headerRow);
    const body = element('tbody');
    for (const values of rows) {
      const row = element('tr');
      for (const value of values) row.append(element('td', value));
      body.append(row);
    }
    node.append(head, body);
    wrapper.append(node);
    return wrapper;
  }
  function notes(items) {
    const list = element('ul');
    for (const item of items) list.append(element('li', item));
    return list;
  }
  function buildSourceDetails(detailsId, page) {
    const details = element('details', null, 'source-details');
    details.id = detailsId;
    details.append(element('summary', '查看源码快照'));
    let pre = null;
    const ensure = () => {
      if (pre) return pre;
      const text = String(page.source || '').split('\n').map((line, index) => `${String(index + 1).padStart(5)}  ${line}`).join('\n');
      const box = codeBlock(text, `${page.path} · 扫描时的源码快照`);
      pre = box.querySelector('pre');
      details.append(box);
      return pre;
    };
    details.addEventListener('toggle', () => { if (details.open) ensure(); });
    sourceRegistry.set(detailsId, { details, ensure });
    return details;
  }
  // 渲染一篇源码文档的功能正文：指南分节、示例、API 参考、注意事项与源码快照。
  // 独立文档直接挂在 main 上；功能族文档把每篇实现的正文挂到它自己的「配置」小节里。
  function renderMemberBody(page, options, container = main) {
    const { prefix = '', level = 2, tocRoute = page.id, withToc = true, sourceId = 'source', apiId = 'api-reference' } = options;
    const headingTag = `h${level}`;
    (page.sections || []).forEach((guide, index) => {
      const section = element('section', null, 'guide-section');
      section.id = `${prefix}guide-${index}`;
      const heading = element('div', null, 'api-head');
      heading.append(element(headingTag, guide.title),
        link(`源码 L${guide.startLine}–${guide.endLine}`, `#${tocRoute}/${sourceId}-L${guide.startLine}`, 'source-link'));
      section.append(heading);
      for (const text of guide.content.split(/\n\s*\n/)) section.append(paragraph(text));
      for (const example of guide.examples) {
        section.append(element('h3', example.title), codeBlock(example.code, '完整示例 · 未执行验证'), paragraph(example.explanation));
      }
      if (guide.notes.length) section.append(notes(guide.notes));
      container.append(section);
      if (withToc) addToc(guide.title, section.id, tocRoute);
    });
    const reference = element('details', null, 'api-reference');
    if (apiId) reference.id = apiId;
    reference.append(element('summary', `API 参数与返回值参考 · ${page.apis.length} 项`));
    page.apis.forEach((api, index) => {
      const section = element('section', null, 'api');
      section.id = `${prefix}api-${index}`;
      const heading = element('div', null, 'api-head');
      heading.append(element('h2', api.name), link(`源码 L${api.startLine}–${api.endLine}`, `#${tocRoute}/${sourceId}-L${api.startLine}`, 'source-link'));
      section.append(heading, paragraph(api.description), codeBlock(api.signature, '声明 / 签名'));
      section.append(element('h3', '参数'));
      if (api.parameters.length) section.append(table(['参数', '类型', '含义与约束'], api.parameters.map(p => [p.name, p.type, p.description])));
      else section.append(paragraph('本条目未提取到参数，请结合声明与源码确认。', 'muted'));
      section.append(element('h3', '返回值'), paragraph(api.returns));
      api.examples.forEach(example => {
        section.append(element('h3', example.title || '调用示例'), codeBlock(example.code, 'AI 生成示例 · 未执行验证'), paragraph(example.explanation));
      });
      if (api.notes.length) section.append(element('h3', '异常与注意事项'), notes(api.notes));
      reference.append(section);
    });
    if (page.apis.length) {
      container.append(reference);
      if (withToc) addToc('API 参数与返回值参考', apiId, tocRoute);
    }
    if (!page.apis.length) container.append(paragraph('该文件未提取到独立的公开功能条目，可查阅文件概述和源码。', 'muted'));
    if (page.notes.length) {
      const section = element('section');
      section.id = `${prefix}notes`;
      section.append(element('h2', '文件注意事项'), notes(page.notes));
      container.append(section);
      if (withToc) addToc('文件注意事项', section.id, tocRoute);
    }
    container.append(buildSourceDetails(sourceId, page));
    if (withToc) addToc('源码快照', sourceId, tocRoute);
  }
  function renderPage(page) {
    currentPage = page;
    main.append(element('div', `${page.category || '未分类'} / ${page.path}`, 'breadcrumb'),
      element('h1', page.title || page.path.split('/').pop()));
    const intro = paragraph(page.summary);
    intro.id = 'summary';
    main.append(intro, notice());
    addToc('功能概览', 'summary');
    renderMemberBody(page, { prefix: '', level: 2, tocRoute: page.id, withToc: true, sourceId: 'source', apiId: 'api-reference' });
  }
  function renderGroupPage(doc) {
    currentPage = doc;
    main.append(element('div', `${doc.category || '未分类'} / 同功能合并 · ${doc.mergedCount} 个实现`, 'breadcrumb'),
      element('h1', doc.title));
    const intro = paragraph(doc.summary, 'lead');
    intro.id = 'summary';
    main.append(intro, notice());
    addToc('功能概览', 'summary', doc.id);
    const variantsHead = element('h2', '多种配置');
    variantsHead.id = 'variants';
    main.append(variantsHead, paragraph(`下表列出该功能的 ${doc.mergedCount} 个实现，每一项在下方都有独立的说明、示例、API 参考与源码快照。`, 'meta'));
    main.append(table(['配置', '实现', '源码文件'], doc.members.map((member, index) => [
      `配置 ${index + 1}`, member.title || member.path.split('/').pop(), member.path
    ])));
    addToc(`多种配置 · ${doc.mergedCount} 项`, 'variants', doc.id);
    doc.members.forEach((member, index) => {
      const anchor = `v${index + 1}`;
      const prefix = `${anchor}-`;
      const section = element('section', null, 'variant');
      section.id = anchor;
      const name = member.title || member.path.split('/').pop();
      section.append(element('h2', `配置 ${index + 1} · ${name}`), element('p', member.path, 'meta'));
      const summary = paragraph(member.summary);
      summary.id = `${prefix}summary`;
      section.append(summary);
      renderMemberBody(member, { prefix, level: 3, tocRoute: doc.id, withToc: false, sourceId: `${prefix}source`, apiId: null }, section);
      main.append(section);
      addToc(`配置 ${index + 1} · ${name}`, anchor, doc.id);
    });
  }
  function route() {
    const [id = 'overview', section] = location.hash.slice(1).split('/');
    const doc = docMap.get(id);
    if (renderedRoute !== id) {
      main.replaceChildren();
      toc.replaceChildren();
      sourceRegistry.clear();
      renderedRoute = id;
      if (id === 'issues') renderIssues();
      else if (id === 'overview' || !id) renderOverview();
      else if (doc) {
        if (doc.members) renderGroupPage(doc);
        else renderPage(doc);
      } else {
        currentPage = null;
        main.append(element('h1', '文档不存在'), paragraph('该文件可能已移除或生成失败，请从目录重新选择。'), link('返回概览', '#overview'));
      }
      renderNav();
      window.scrollTo(0, 0);
    } else {
      // 点的是当前已打开的文档：目录不重建，清掉锚点，避免下次重建时按旧位置还原。
      pendingAnchor = null;
    }
    if (doc && section) {
      const source = section.match(/^(.*?)source(?:-L(\d+))?$/);
      if (source) {
        const entry = sourceRegistry.get(`${source[1]}source`);
        if (entry) {
          entry.details.open = true;
          const pre = entry.ensure();
          entry.details.scrollIntoView({ block: 'start' });
          const line = Number(source[2]);
          if (Number.isInteger(line) && line > 0) pre.scrollTop = (line - 1) * parseFloat(getComputedStyle(pre).lineHeight);
        }
      } else {
        document.getElementById(section)?.scrollIntoView({ block: 'start' });
      }
    }
    document.body.classList.remove('menu-open');
    menu.setAttribute('aria-expanded', 'false');
  }
  search.addEventListener('input', renderNav);
  menu.addEventListener('click', () => {
    const open = document.body.classList.toggle('menu-open');
    menu.setAttribute('aria-expanded', String(open));
    if (open) search.focus();
  });
  document.addEventListener('keydown', event => {
    if (event.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) {
      event.preventDefault();
      if (matchMedia('(max-width: 760px)').matches) {
        document.body.classList.add('menu-open');
        menu.setAttribute('aria-expanded', 'true');
      }
      search.focus();
    }
    if (event.key === 'Escape') {
      document.body.classList.remove('menu-open');
      menu.setAttribute('aria-expanded', 'false');
      status.textContent = '';
      if (document.activeElement === search) { search.value = ''; renderNav(); }
    }
  });
  document.getElementById('theme').addEventListener('click', () => {
    const root = document.documentElement;
    const isDark = root.dataset.theme === 'dark' || !root.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset.theme = isDark ? 'light' : 'dark';
  });
  window.addEventListener('hashchange', route);
  route();
})();
