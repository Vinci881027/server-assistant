function normalizeFilenamePart(rawValue, fallback) {
  const raw = typeof rawValue === 'string' ? rawValue.trim() : '';
  const cleaned = raw
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '-')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
  return cleaned || fallback;
}

export function sanitizeFilenamePart(value, fallback = 'export') {
  return normalizeFilenamePart(value, normalizeFilenamePart(fallback, 'export'));
}

export function buildExportTimestamp(date = new Date()) {
  return date.toISOString().replace(/[:.]/g, '-');
}

export function downloadTextFile(filename, content, mimeType = 'text/plain;charset=utf-8') {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  const blob = new Blob([content], { type: mimeType });
  const objectUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(objectUrl);
}

export function escapeCsvCell(value) {
  const normalized = value == null ? '' : String(value);
  if (/[",\n\r]/.test(normalized)) {
    return `"${normalized.replace(/"/g, '""')}"`;
  }
  return normalized;
}

export function buildCsvText(headers, rows) {
  const headerLine = headers.map((header) => escapeCsvCell(header)).join(',');
  const rowLines = rows.map((row) => row.map((cell) => escapeCsvCell(cell)).join(','));
  return [headerLine, ...rowLines].join('\n');
}
