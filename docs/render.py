#!/usr/bin/env python3
"""Render docs/architecture.md -> docs/architecture.html.

- Markdown via python-markdown (tables, toc, fenced code, codehilite/pygments).
- Mermaid ```mermaid fences are preserved as <pre class="mermaid"> and rendered
  client-side by Mermaid (loaded as an ES module from jsDdelivr). Diagram source
  is HTML-escaped so it survives intact into the element's textContent.
- TOC is lifted out of the body into a sticky sidebar.
"""
import html
import re
from pathlib import Path

import markdown
from pygments.formatters import HtmlFormatter

DOCS = Path(__file__).resolve().parent
SRC = DOCS / "architecture.md"
OUT = DOCS / "architecture.html"

raw = SRC.read_text(encoding="utf-8")

# The [TOC] marker would render an inline TOC; we want it only in the sidebar.
raw = raw.replace("[TOC]\n", "").replace("[TOC]", "")

# Stash mermaid fences before markdown processing so neither fenced_code nor
# codehilite mangles them.
mermaid_blocks: list[str] = []


def _stash(m: re.Match) -> str:
    mermaid_blocks.append(m.group(1).strip())
    return f"\n\nXMERMAIDX{len(mermaid_blocks) - 1}XENDX\n\n"


raw = re.sub(r"```mermaid\s*\n(.*?)```", _stash, raw, flags=re.DOTALL)

md = markdown.Markdown(
    extensions=["fenced_code", "tables", "toc", "codehilite", "attr_list", "sane_lists"],
    extension_configs={"codehilite": {"guess_lang": False}},
)
body = md.convert(raw)
toc_html = md.toc  # populated by the `toc` extension

# Re-insert mermaid diagrams. Escaping keeps <br/> etc. in textContent for Mermaid.
for i, src in enumerate(mermaid_blocks):
    pre = '<pre class="mermaid">' + html.escape(src) + "</pre>"
    token = f"XMERMAIDX{i}XENDX"
    body = body.replace(f"<p>{token}</p>", pre).replace(token, pre)

# Make wide tables scroll horizontally on narrow screens instead of overflowing.
body = body.replace("<table>", '<div class="table-wrap"><table>').replace(
    "</table>", "</table></div>"
)

pygments_css = HtmlFormatter(style="friendly").get_style_defs(".codehilite")

CSS = """
:root{
  --bg:#ffffff; --fg:#1f2328; --muted:#636c76; --accent:#2f6feb;
  --accent-weak:#e8f0ff; --border:#d0d7de; --code-bg:#f6f8fa;
  --stripe:#f8fafc; --sidebar-bg:#fbfcfd;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;color:var(--fg);background:var(--bg);font-size:16px;line-height:1.65;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
.layout{display:flex;align-items:flex-start;max-width:1180px;margin:0 auto}
.sidebar{position:sticky;top:0;height:100vh;overflow:auto;width:290px;flex:0 0 290px;
  padding:24px 18px;border-right:1px solid var(--border);background:var(--sidebar-bg);font-size:13.5px}
.sidebar .brand{font-weight:700;font-size:15px;margin:0 0 4px}
.sidebar .brand small{display:block;font-weight:400;color:var(--muted);font-size:11.5px;margin-top:2px}
.sidebar h2{font-size:11px;text-transform:uppercase;letter-spacing:.07em;color:var(--muted);margin:18px 0 8px}
.content{flex:1 1 auto;min-width:0;padding:40px 52px 140px}
.content > h1:first-child{font-size:2.1rem;margin:.1em 0 .15em}
.content h2{font-size:1.5rem;margin-top:2.3em;padding-bottom:.3em;border-bottom:1px solid var(--border)}
.content h3{font-size:1.2rem;margin-top:1.9em}
.content h4{font-size:1.02rem;margin-top:1.5em}
h1,h2,h3,h4{scroll-margin-top:14px;line-height:1.3}
hr{border:none;border-top:1px solid var(--border);margin:2.6em 0}
p,li{overflow-wrap:anywhere}
.toc ul{list-style:none;margin:0;padding-left:0}
.toc > ul > li{margin:1px 0}
.toc ul ul{padding-left:13px;border-left:1px solid var(--border);margin:1px 0 1px 4px}
.toc a{color:var(--muted);display:block;padding:3px 7px;border-radius:6px;line-height:1.35}
.toc a:hover{color:var(--accent);background:var(--accent-weak);text-decoration:none}
code{font-family:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
  font-size:.86em;background:var(--code-bg);padding:.14em .36em;border-radius:5px}
pre{background:var(--code-bg);padding:16px;border-radius:10px;overflow:auto;border:1px solid var(--border)}
pre code{background:none;padding:0;font-size:.85em;line-height:1.55}
.codehilite{background:var(--code-bg);border:1px solid var(--border);border-radius:10px;margin:1.2em 0}
.codehilite pre{border:none;margin:0;background:none}
.table-wrap{overflow-x:auto;margin:1.3em 0;border:1px solid var(--border);border-radius:10px}
table{border-collapse:collapse;width:100%;font-size:.92em}
th,td{border-bottom:1px solid var(--border);border-right:1px solid var(--border);padding:8px 12px;text-align:left;vertical-align:top}
tr td:last-child,tr th:last-child{border-right:none}
tbody tr:last-child td{border-bottom:none}
thead th{background:var(--code-bg);border-bottom:2px solid var(--border)}
tbody tr:nth-child(even){background:var(--stripe)}
blockquote{margin:1.3em 0;padding:.5em 1.1em;border-left:4px solid var(--accent);
  background:var(--accent-weak);border-radius:0 8px 8px 0}
blockquote p{margin:.4em 0}
pre.mermaid{background:transparent;border:none;text-align:center;padding:8px;color:var(--muted);font-size:12px}
pre.mermaid[data-processed]{font-size:0}
.mermaid svg{max-width:100%;height:auto}
@media (max-width:920px){
  .layout{flex-direction:column}
  .sidebar{position:static;height:auto;width:100%;flex:none;border-right:none;border-bottom:1px solid var(--border)}
  .content{padding:26px 20px 90px}
}
"""

HTML = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>akouo — Architecture Plan</title>
<style>{CSS}{pygments_css}</style>
</head>
<body>
<div class="layout">
  <nav class="sidebar">
    <p class="brand">akouo<small>Architecture Plan</small></p>
    <h2>Contents</h2>
    {toc_html}
  </nav>
  <main class="content">
    {body}
  </main>
</div>
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/+esm';
  mermaid.initialize({{
    startOnLoad: false,
    theme: 'neutral',
    securityLevel: 'loose',
    flowchart: {{ useMaxWidth: true, htmlLabels: true, curve: 'basis' }},
    er: {{ useMaxWidth: true }}
  }});
  try {{
    await mermaid.run({{ querySelector: 'pre.mermaid' }});
  }} catch (e) {{
    console.error('Mermaid render error:', e);
  }}
</script>
</body>
</html>
"""

OUT.write_text(HTML, encoding="utf-8")
print(f"wrote {OUT} ({len(HTML):,} bytes, {len(mermaid_blocks)} diagrams)")
