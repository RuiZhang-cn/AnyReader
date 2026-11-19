# AnyReader

An IntelliJ IDEA reader plugin for **EPUB, PDF, Markdown, TXT, and image folders**, built for comfortable reading inside the IDE with theme adaptation, status-bar micro reading, and instant boss-key hiding.

> 支持 **EPUB、PDF、Markdown、TXT 及图片目录** 的 IntelliJ IDEA 内置阅读插件，支持跟随 IDE 主题配色、状态栏微阅读以及一键隐藏（老板键）。

---

## Features / 核心功能

- **Multi-format support / 多格式支持**:
  - **EPUB**: Chapter navigation, table of contents, styling & IDE theme adaptation via JCEF.
  - **PDF**: Page rendering and navigation inside the Docs tool window.
  - **Markdown & TXT**: Clean distraction-free reading with search and progress tracking.
  - **Image Folders**: Browse sequential images / comics directly from a local folder.
- **IDE Theme Synchronization / 主题适配**: Automatically follows IntelliJ light/dark themes and lets you adjust reading palette settings.
- **Status-Bar Micro Reading / 状态栏微阅读**: Discreet reading progress and snippet display in the IDE status bar (`Document Status Bar`).
- **Quick Hide (Boss Key) / 快速隐藏（老板键）**: Instantly hide or restore the reader view and status bar widget.

## Keyboard Shortcuts / 快捷键

| Shortcut | Action | 说明 |
|---|---|---|
| `Alt+Q` | Toggle Boss Key (hide / restore) | 切换老板键（快速隐藏 / 恢复） |
| `Double Esc` | Quick hide reader | 双击 Esc 快速隐藏 |
| `Ctrl+F` | Search within document | 文档内搜索 |
| `[` / `]` | Previous / Next chapter or section | 上一章 / 下一章 |
| `j` / `k` | Scroll down / up | 向下 / 向上滚动 |

## Requirements / 运行要求

- IntelliJ IDEA **2024.3+** (`since-build="243"`) with **JCEF** support enabled
- **JDK 21** (for building from source)

## Build from Source / 本地构建

Point Gradle to your local IntelliJ IDEA installation via `-PideaHome` or the `IDEA_HOME` environment variable:

```bash
# Using Gradle property
gradle test buildPluginZip -PideaHome="/path/to/IntelliJ IDEA"

# Or using IDEA_HOME environment variable
export IDEA_HOME="/path/to/IntelliJ IDEA"
gradle test buildPluginZip
```

The installable plugin archive will be generated at:

```text
build/distributions/anyreader-1.0.0.zip
```

Install in IntelliJ IDEA via **Settings → Plugins → ⚙️ → Install Plugin from Disk...**.

## License / 开源协议

This project is licensed under the [MIT License](LICENSE).
