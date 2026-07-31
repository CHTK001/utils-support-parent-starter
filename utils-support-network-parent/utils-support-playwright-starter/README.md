# utils-support-playwright-starter

Playwright 浏览器自动化：URL 截图、长截图、页面加载等待 + 爬虫浏览器渲染抓取/解析

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-playwright-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `PlaywrightScreenshot` | Playwright 截图工具 基于 Playwright 实现 URL 转图片、长截图、页面加载等待等功能。 |
| `PlaywrightFetcher` | Playwright 浏览器渲染抓取器。 (SPI: `playwright`) |
| `PlaywrightParser` | Playwright 浏览器渲染解析器。 (SPI: `playwright`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-playwright-starter
├── utils-support-common-starter
├── utils-support-spider-starter
```