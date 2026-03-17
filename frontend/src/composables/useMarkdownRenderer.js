import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
});

const defaultFence =
  md.renderer.rules.fence ||
  function (tokens, idx, options, env, self) {
    return self.renderToken(tokens, idx, options);
  };

md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const rawCode = token.content;
  const encodedCode = rawCode
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
  const lang = token.info.trim().split(/\s+/)[0] || '';
  const langLabel = lang
    ? `<span class="code-block-lang">${md.utils.escapeHtml(lang)}</span>`
    : '';

  const codeHtml = defaultFence(tokens, idx, options, env, self);

  return (
    `<div class="code-block-wrapper">` +
    `<div class="code-block-header">` +
    langLabel +
    `<button type="button" class="copy-code-btn" data-code="${encodedCode}" title="複製程式碼">` +
    `<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>` +
    `</button>` +
    `</div>` +
    codeHtml +
    `</div>`
  );
};

const DEFAULT_MAX_RENDER_LENGTH = 20000;

export function useMarkdownRenderer(maxRenderLength = DEFAULT_MAX_RENDER_LENGTH) {
  const renderMarkdown = (text) => {
    if (!text) return '';
    if (text.length > maxRenderLength) {
      return '<p>[訊息過長，無法渲染，請使用複製功能查看原始內容]</p>';
    }
    try {
      return DOMPurify.sanitize(md.render(text), {
        ADD_TAGS: ['button'],
        ADD_ATTR: ['data-code'],
        FORBID_TAGS: ['style'],
        FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick', 'onmouseover'],
      });
    } catch (err) {
      console.error('Markdown rendering failed:', err);
      return '<p>[內容渲染失敗]</p>';
    }
  };

  return { renderMarkdown };
}
