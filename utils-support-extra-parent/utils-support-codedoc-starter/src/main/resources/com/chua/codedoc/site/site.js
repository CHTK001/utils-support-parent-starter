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
  const pageMap = new Map(data.pages.map(page => [page.id, page]));
  const searchIndex = new Map(data.pages.map(page => [page.id, JSON.stringify({
    path: page.path, summary: page.summary, apis: page.apis, notes: page.notes
  }).toLocaleLowerCase()]));
  let currentPage = null;
  let sourceDetails = null;
  let sourcePre = null;

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
  function renderNav() {
    const query = search.value.trim().toLocaleLowerCase();
    const terms = query.split(/\s+/).filter(Boolean);
    const matches = data.pages.filter(page => terms.every(term => searchIndex.get(page.id).includes(term)));
    nav.replaceChildren();
    nav.append(link('概览与快速导航', '#overview', 'file-link'));
    document.getElementById('search-count').textContent = `${matches.length} / ${data.pages.length} 个文档文件`;
    const groups = new Map();
    for (const page of matches) {
      const group = page.path.includes('/') ? page.path.split('/')[0] : '根目录';
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(page);
    }
    for (const [name, pages] of groups) {
      const group = element('details');
      group.open = Boolean(query) || pages.some(page => page.id === currentPage?.id) || groups.size <= 4;
      group.append(element('summary', `${name} · ${pages.length}`));
      for (const page of pages) {
        const item = link(page.path.split('/').pop(), `#${page.id}`, 'file-link');
        item.title = page.path;
        item.append(element('small', page.path.includes('/') ? page.path.slice(0, page.path.lastIndexOf('/')) : page.language));
        if (page.id === currentPage?.id) item.setAttribute('aria-current', 'page');
        group.append(item);
      }
      nav.append(group);
    }
    if (matches.length === 0) nav.append(paragraph('没有匹配的文档，请尝试方法名或参数名。', 'muted'));
  }
  function stat(value, label) {
    const node = element('div', null, 'stat');
    node.append(element('strong', value), element('span', label));
    return node;
  }
  function renderOverview() {
    currentPage = null;
    main.append(element('div', 'CODE / REFERENCE / OVERVIEW', 'breadcrumb'), element('h1', data.title),
      paragraph('从源码出发，查找功能、理解参数，带着示例开始使用。', 'lead'));
    const stats = element('div', null, 'stats');
    stats.append(stat(data.scannedFiles, '匹配文件'), stat(data.documentedFiles, '已生成文档'),
      stat(data.pages.reduce((sum, page) => sum + page.apis.length, 0), '功能条目'), stat(data.cachedFiles, '命中缓存'));
    main.append(stats, notice());
    main.append(element('h2', '开始查阅'), paragraph('通过左侧目录按文件浏览，或搜索方法名、用途、参数。每个功能保留源码行号，可展开生成时的源码快照。'));
    const list = element('ul', null, 'overview-list');
    const groups = new Map();
    for (const page of data.pages) {
      const group = page.path.includes('/') ? page.path.split('/')[0] : '根目录';
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(page);
    }
    for (const [name, pages] of groups) {
      const item = element('li');
      item.append(link(name, `#${pages[0].id}`), element('span', ` · ${pages.length} 个文件`, 'meta'));
      list.append(item);
    }
    if (!data.pages.length) main.append(paragraph('尚无成功生成的文档。请查看生成记录中的问题和扫描统计。', 'notice'));
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
  function renderPage(page) {
    currentPage = page;
    main.append(element('div', page.path, 'breadcrumb'), element('h1', page.path.split('/').pop()));
    const intro = paragraph(page.summary);
    intro.id = 'summary';
    main.append(intro, notice());
    addToc('功能概览', 'summary');
    page.apis.forEach((api, index) => {
      const section = element('section', null, 'api');
      section.id = `api-${index}`;
      const heading = element('div', null, 'api-head');
      heading.append(element('h2', api.name), link(`源码 L${api.startLine}–${api.endLine}`, `#${page.id}/source-L${api.startLine}`, 'source-link'));
      section.append(heading, paragraph(api.description), codeBlock(api.signature, '声明 / 签名'));
      section.append(element('h3', '参数'));
      if (api.parameters.length) section.append(table(['参数', '类型', '含义与约束'], api.parameters.map(p => [p.name, p.type, p.description])));
      else section.append(paragraph('本条目未提取到参数，请结合声明与源码确认。', 'muted'));
      section.append(element('h3', '返回值'), paragraph(api.returns));
      api.examples.forEach(example => {
        section.append(element('h3', example.title || '调用示例'), codeBlock(example.code, 'AI 生成示例 · 未执行验证'), paragraph(example.explanation));
      });
      if (api.notes.length) section.append(element('h3', '异常与注意事项'), notes(api.notes));
      main.append(section);
      addToc(api.name, section.id);
    });
    if (!page.apis.length) main.append(paragraph('该文件未提取到独立的公开功能条目，可查阅文件概述和源码。', 'muted'));
    if (page.notes.length) {
      const section = element('section');
      section.id = 'notes';
      section.append(element('h2', '文件注意事项'), notes(page.notes));
      main.append(section);
      addToc('文件注意事项', 'notes');
    }
    sourceDetails = element('details', null, 'source-details');
    sourceDetails.id = 'source';
    sourceDetails.append(element('summary', '查看源码快照'));
    sourcePre = null;
    sourceDetails.addEventListener('toggle', () => { if (sourceDetails.open) ensureSource(page); });
    main.append(sourceDetails);
    addToc('源码快照', 'source');
  }
  function ensureSource(page) {
    if (sourcePre) return;
    const text = page.source.split('\n').map((line, index) => `${String(index + 1).padStart(5)}  ${line}`).join('\n');
    const box = codeBlock(text, `${page.path} · 扫描时的源码快照`);
    sourcePre = box.querySelector('pre');
    sourceDetails.append(box);
  }
  function route() {
    const [id = 'overview', section] = location.hash.slice(1).split('/');
    const page = pageMap.get(id);
    if (!page || currentPage?.id !== page.id) {
      main.replaceChildren();
      toc.replaceChildren();
      sourceDetails = null;
      sourcePre = null;
      if (id === 'issues') renderIssues();
      else if (id === 'overview' || !id) renderOverview();
      else if (page) renderPage(page);
      else {
        currentPage = null;
        main.append(element('h1', '文档不存在'), paragraph('该文件可能已移除或生成失败，请从目录重新选择。'), link('返回概览', '#overview'));
      }
      renderNav();
      window.scrollTo(0, 0);
    }
    if (page && section) {
      if (section.startsWith('source')) {
        sourceDetails.open = true;
        ensureSource(page);
        sourceDetails.scrollIntoView({ block: 'start' });
        const line = Number(section.replace('source-L', ''));
        if (Number.isInteger(line) && line > 0) sourcePre.scrollTop = (line - 1) * parseFloat(getComputedStyle(sourcePre).lineHeight);
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
