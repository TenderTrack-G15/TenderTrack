/*
 * PDF downloads: a small PDF writer for the portal's documents (tender
 * documents, and the procurement officer's "all documents" file).
 *
 * Text on A4 pages — headings, paragraphs, lists and label/value rows — in
 * Helvetica, the PDF reader's own font, so nothing is embedded and no library
 * is needed. The file is built in the browser as plain ASCII text.
 */

// Helvetica and Helvetica-Bold character widths (1/1000 em) for codes 32-255
// in WinAnsiEncoding, from the standard Adobe font metrics.
const REGULAR = [
  278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
  1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
  667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
  333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
  556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 761,
  556, 0, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
  0, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 0, 500, 667,
  278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
  400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,
  667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
  722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
  556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
  556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,
];
const BOLD = [
  278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
  556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
  975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
  667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
  333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
  611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 761,
  556, 0, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
  0, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 0, 500, 667,
  278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
  400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,
  722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
  722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
  556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,
  611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556,
];

// Characters outside Latin-1 that WinAnsiEncoding has, and a few it does not
// have, written as something close.
const WINANSI = {
  '€': 0x80, '‚': 0x82, 'ƒ': 0x83, '„': 0x84, '…': 0x85, '†': 0x86, '‡': 0x87, 'ˆ': 0x88, '‰': 0x89,
  'Š': 0x8a, '‹': 0x8b, 'Œ': 0x8c, 'Ž': 0x8e, '‘': 0x91, '’': 0x92, '“': 0x93, '”': 0x94, '•': 0x95,
  '–': 0x96, '—': 0x97, '˜': 0x98, '™': 0x99, 'š': 0x9a, '›': 0x9b, 'œ': 0x9c, 'ž': 0x9e, 'Ÿ': 0x9f,
};
const SIMILAR = {
  '→': '->', '←': '<-', '✓': 'Yes', '✔': 'Yes', '✗': 'No', '≥': '>=', '≤': '<=', '−': '-', '‑': '-',
  ' ': ' ', ' ': ' ', ' ': ' ', '​': '', '\t': '    ',
};

const PAGE_W = 595.28;   // A4, in points
const PAGE_H = 841.89;
const LEFT = 56;
const RIGHT = PAGE_W - 56;
const TOP = PAGE_H - 62;
const BOTTOM = 70;
const INK = '0.11 0.13 0.16';
const MUTED = '0.40 0.43 0.47';
const RULE = '0.84 0.86 0.88';

/** Text as WinAnsi codes (32-255). */
function codesOf(text) {
  const out = [];
  for (const ch of String(text ?? '')) {
    const swap = SIMILAR[ch];
    if (swap !== undefined) { for (const c of swap) out.push(c.charCodeAt(0)); continue; }
    const cp = ch.codePointAt(0);
    if (cp >= 32 && cp <= 126) out.push(cp);
    else if (cp >= 160 && cp <= 255) out.push(cp);
    else if (WINANSI[ch]) out.push(WINANSI[ch]);
    else if (cp === 10 || cp === 13) out.push(32);
    else out.push(63); // ?
  }
  return out;
}

/** A PDF string: ( ) \ escaped, everything outside printable ASCII as \ooo, so the file is plain ASCII. */
function pdfString(codes) {
  let s = '(';
  for (const c of codes) {
    if (c === 40 || c === 41 || c === 92) s += `\\${String.fromCharCode(c)}`;
    else if (c < 32 || c > 126) s += `\\${c.toString(8).padStart(3, '0')}`;
    else s += String.fromCharCode(c);
  }
  return `${s})`;
}

function widthOf(codes, size, bold) {
  const table = bold ? BOLD : REGULAR;
  let w = 0;
  for (const c of codes) w += table[c - 32] || 556;
  return (w * size) / 1000;
}

/** Splits text into lines no wider than [width] points; "\n" starts a new line. */
function wrap(text, width, size, bold) {
  const result = [];
  for (const paragraph of String(text ?? '').replace(/\r\n?/g, '\n').split('\n')) {
    const words = paragraph.split(/ +/);
    let line = '';
    for (let word of words) {
      const candidate = line ? `${line} ${word}` : word;
      if (widthOf(codesOf(candidate), size, bold) <= width) { line = candidate; continue; }
      if (line) result.push(line);
      // A word longer than the line (a link, say) is broken where it must be.
      while (widthOf(codesOf(word), size, bold) > width) {
        let cut = word.length - 1;
        while (cut > 1 && widthOf(codesOf(word.slice(0, cut)), size, bold) > width) cut -= 1;
        result.push(word.slice(0, cut));
        word = word.slice(cut);
      }
      line = word;
    }
    result.push(line);
  }
  return result;
}

/** A string for the file's properties (title): UTF-16, as hex, so any character survives. */
function infoString(text) {
  let hex = '<FEFF';
  for (const ch of String(text ?? '')) {
    const cp = ch.codePointAt(0);
    if (cp > 0xffff) {
      const v = cp - 0x10000;
      hex += (0xd800 + (v >> 10)).toString(16).padStart(4, '0') + (0xdc00 + (v & 0x3ff)).toString(16).padStart(4, '0');
    } else hex += cp.toString(16).padStart(4, '0');
  }
  return `${hex.toUpperCase()}>`;
}

const n2 = (x) => (Math.round(x * 100) / 100).toString();

/**
 * A text document on A4 pages. Add content in order, then output() for the
 * file (a string of plain ASCII) or download() to save it.
 */
export class PdfDocument {
  /**
   * @param title    shown at the top of the first page and in the file's properties
   * @param footer   small print at the bottom of every page
   */
  constructor({ title, subtitle = '', footer = '' }) {
    this.title = title;
    this.footer = footer;
    this.pages = [];
    this.newPage();
    this.text(title, { size: 18, bold: true, lead: 23 });
    if (subtitle) this.text(subtitle, { size: 10.5, color: MUTED, lead: 15 });
    this.space(10);
  }

  newPage() {
    this.ops = [];
    this.pages.push(this.ops);
    this.y = TOP;
    return this;
  }

  /** Starts a new page unless [needed] points still fit on this one. */
  ensure(needed) {
    if (this.y - needed < BOTTOM) this.newPage();
    return this;
  }

  space(points = 8) {
    this.y -= points;
    return this;
  }

  /** Wrapped text. Options: size, bold, color, indent, lead (line height), gap (space after). */
  text(value, { size = 10, bold = false, color = INK, indent = 0, lead = size * 1.45, gap = 0 } = {}) {
    const width = RIGHT - LEFT - indent;
    for (const line of wrap(value, width, size, bold)) {
      this.ensure(lead);
      this.y -= lead;
      if (line) this.ops.push(`BT /${bold ? 'F2' : 'F1'} ${size} Tf ${color} rg ${n2(LEFT + indent)} ${n2(this.y + lead * 0.28)} Td ${pdfString(codesOf(line))} Tj ET`);
    }
    this.y -= gap;
    return this;
  }

  /** A section heading; [level] 1 is larger. Never left alone at the bottom of a page. */
  heading(value, level = 1) {
    const size = level === 1 ? 13.5 : 11.5;
    this.ensure(size * 1.5 + 40);
    this.space(level === 1 ? 12 : 8);
    this.text(value, { size, bold: true, lead: size * 1.4 });
    if (level === 1) this.rule();
    this.space(4);
    return this;
  }

  paragraph(value, options = {}) {
    return this.text(value, { gap: 5, ...options });
  }

  /** Muted small print. */
  note(value) {
    return this.text(value, { size: 8.5, color: MUTED, lead: 12, gap: 4 });
  }

  /** A list; each item is a string, or [mark, text] for a different mark (e.g. "[x]"). */
  bullets(items, { size = 10 } = {}) {
    for (const item of items) {
      const [mark, value] = Array.isArray(item) ? item : ['•', item];
      const lead = size * 1.45;
      const lines = wrap(value, RIGHT - LEFT - 22, size, false);
      lines.forEach((line, i) => {
        this.ensure(lead);
        this.y -= lead;
        const base = n2(this.y + lead * 0.28);
        if (i === 0) this.ops.push(`BT /F1 ${size} Tf ${INK} rg ${n2(LEFT + 6)} ${base} Td ${pdfString(codesOf(mark))} Tj ET`);
        this.ops.push(`BT /F1 ${size} Tf ${INK} rg ${n2(LEFT + 22)} ${base} Td ${pdfString(codesOf(line))} Tj ET`);
      });
    }
    this.space(4);
    return this;
  }

  /** Label and value rows, like the portal's detail lists. */
  keyValues(pairs, { keyWidth = 150, size = 10 } = {}) {
    const lead = size * 1.45;
    for (const [key, value] of pairs) {
      const keys = wrap(key, keyWidth - 10, size, false);
      const values = wrap(value === null || value === undefined || value === '' ? '—' : String(value), RIGHT - LEFT - keyWidth, size, true);
      const rows = Math.max(keys.length, values.length);
      this.ensure(lead * rows + 4);
      for (let i = 0; i < rows; i += 1) {
        this.y -= lead;
        const base = n2(this.y + lead * 0.28);
        if (keys[i]) this.ops.push(`BT /F1 ${size} Tf ${MUTED} rg ${n2(LEFT)} ${base} Td ${pdfString(codesOf(keys[i]))} Tj ET`);
        if (values[i]) this.ops.push(`BT /F2 ${size} Tf ${INK} rg ${n2(LEFT + keyWidth)} ${base} Td ${pdfString(codesOf(values[i]))} Tj ET`);
      }
      this.y -= 3;
      this.ops.push(`${RULE} RG 0.5 w ${n2(LEFT)} ${n2(this.y)} m ${n2(RIGHT)} ${n2(this.y)} l S`);
    }
    this.space(6);
    return this;
  }

  rule() {
    this.y -= 4;
    this.ops.push(`${RULE} RG 0.8 w ${n2(LEFT)} ${n2(this.y)} m ${n2(RIGHT)} ${n2(this.y)} l S`);
    this.y -= 2;
    return this;
  }

  /** The finished file, as a string of plain ASCII. */
  output() {
    const total = this.pages.length;
    const objects = [];
    const add = (body) => { objects.push(body); return objects.length; };
    const catalog = add(null);
    const pagesObj = add(null);
    const f1 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');
    const f2 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>');
    const kids = [];
    this.pages.forEach((ops, i) => {
      const foot = [
        `${RULE} RG 0.5 w ${n2(LEFT)} ${n2(BOTTOM - 22)} m ${n2(RIGHT)} ${n2(BOTTOM - 22)} l S`,
        `BT /F1 7.5 Tf ${MUTED} rg ${n2(LEFT)} ${n2(BOTTOM - 34)} Td ${pdfString(codesOf(this.footer))} Tj ET`,
      ];
      const pageLabel = codesOf(`Page ${i + 1} of ${total}`);
      foot.push(`BT /F1 7.5 Tf ${MUTED} rg ${n2(RIGHT - widthOf(pageLabel, 7.5, false))} ${n2(BOTTOM - 34)} Td ${pdfString(pageLabel)} Tj ET`);
      const stream = [...ops, ...foot].join('\n');
      const content = add(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
      kids.push(add(`<< /Type /Page /Parent ${pagesObj} 0 R /MediaBox [0 0 ${PAGE_W} ${PAGE_H}] `
        + `/Resources << /Font << /F1 ${f1} 0 R /F2 ${f2} 0 R >> >> /Contents ${content} 0 R >>`));
    });
    objects[catalog - 1] = `<< /Type /Catalog /Pages ${pagesObj} 0 R >>`;
    objects[pagesObj - 1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(' ')}] /Count ${total} >>`;
    const info = add(`<< /Title ${infoString(this.title)} /Producer ${infoString('eTender Demo Portal (TenderTrack student project)')} >>`);

    let file = '%PDF-1.4\n';
    const offsets = [];
    objects.forEach((body, i) => {
      offsets.push(file.length);
      file += `${i + 1} 0 obj\n${body}\nendobj\n`;
    });
    const xref = file.length;
    file += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
    for (const o of offsets) file += `${String(o).padStart(10, '0')} 00000 n \n`;
    file += `trailer\n<< /Size ${objects.length + 1} /Root ${catalog} 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
    return file;
  }
}

/** A safe file name: "WC-SF-1042-Pricing-Schedule.pdf". */
export function pdfFileName(...parts) {
  return `${parts.map((p) => String(p).replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '')).filter(Boolean).join('-')}.pdf`;
}
