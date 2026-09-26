/*
 * Announcements — the portal's second CRUD module. A notice written here
 * appears as a banner in the Android app for the chosen audience:
 *
 *   Everyone   welcome screen, supplier home, officer and auditor home
 *   Public     welcome screen (anyone can read public notices)
 *   Suppliers  supplier home
 *   Staff      officer and auditor home
 *
 * The database decides who can read which notice (announcement_visible in
 * admin_portal.sql), and records who wrote or changed it.
 */

import { sb, messageOf } from '../client.js';
import { h, clear, icon, pageHead, badge, note, empty, dateTime, modal, field, select, busy, toast, confirmDialog } from '../ui.js';

const AUDIENCES = [['everyone', 'Everyone'], ['public', 'Public'], ['suppliers', 'Suppliers'], ['staff', 'Staff (officers and auditors)']];
const SEVERITIES = [['info', 'Information'], ['warning', 'Warning'], ['critical', 'Critical']];
const SEVERITY_TONE = { info: 'info', warning: 'warn', critical: 'danger' };
const AUDIENCE_LABEL = Object.fromEntries(AUDIENCES.map(([k, v]) => [k, v.split(' (')[0]]));

function statusOf(a, now = Date.now()) {
  if (!a.published) return ['Draft', 'neutral'];
  if (new Date(a.starts_at).getTime() > now) return ['Scheduled', 'info'];
  if (a.ends_at && new Date(a.ends_at).getTime() <= now) return ['Ended', 'neutral'];
  return ['Live in the app', 'success'];
}

/** ISO time -> the value a datetime-local input expects, in local time. */
function toLocalInput(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export async function render(view) {
  let items = [];
  const listHost = h('div');

  async function load() {
    const { data, error } = await sb.from('announcements').select('*').order('created_at', { ascending: false });
    if (error) throw new Error(messageOf(error));
    items = data;
  }

  function draw() {
    clear(listHost);
    if (!items.length) {
      listHost.appendChild(h('div', { class: 'table-wrap' }, empty('No announcements yet', 'Write one to show a banner in the app.', 'campaign')));
      return;
    }
    listHost.appendChild(h('div', { class: 'table-wrap' },
      h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Announcement'), h('th', {}, 'Audience'), h('th', {}, 'Status'), h('th', {}, 'Showing'), h('th', {}, 'Last change'), h('th', {}, 'Actions'))),
        h('tbody', {}, items.map((a) => {
          const [label, tone] = statusOf(a);
          return h('tr', {},
            h('td', {},
              h('div', { class: 'inline' }, badge(SEVERITIES.find(([k]) => k === a.severity)[1], SEVERITY_TONE[a.severity], false), h('span', { class: 'primary' }, a.title)),
              a.body ? h('div', { class: 'secondary detail-text' }, a.body.length > 140 ? `${a.body.slice(0, 140)}…` : a.body) : null),
            h('td', {}, AUDIENCE_LABEL[a.audience] || a.audience),
            h('td', {}, badge(label, tone)),
            h('td', {}, h('div', {}, `From ${dateTime(a.starts_at)}`), h('div', { class: 'secondary' }, a.ends_at ? `Until ${dateTime(a.ends_at)}` : 'No end date')),
            h('td', {}, h('div', {}, a.updated_by || a.created_by), h('div', { class: 'secondary' }, dateTime(a.updated_at))),
            h('td', {}, h('div', { class: 'row-actions' },
              h('button', { class: 'btn btn-secondary btn-xs', type: 'button', onclick: () => openEditor(a) }, 'Edit'),
              h('button', { class: 'btn btn-secondary btn-xs', type: 'button', onclick: () => togglePublished(a) }, a.published ? 'Unpublish' : 'Publish'),
              h('button', { class: 'btn btn-danger btn-xs', type: 'button', onclick: () => remove(a) }, 'Delete'))));
        })))));
  }

  async function refresh() {
    await load();
    draw();
  }

  // CREATE and UPDATE share one form.
  function openEditor(existing) {
    const title = field({ label: 'Title', input: h('input', { class: 'input', maxlength: '120', value: existing ? existing.title : '', placeholder: 'e.g. Scheduled maintenance on Saturday' }) });
    const body = field({ label: 'Message', input: h('textarea', { class: 'textarea', maxlength: '1000', value: existing ? existing.body : '', placeholder: 'What should people know?' }), hint: ' ' });
    const counter = body.wrap.querySelector('.hint');
    const audience = field({ label: 'Who sees it', input: select(AUDIENCES, existing ? existing.audience : 'everyone', null, 'Audience') });
    const severity = field({ label: 'Type', input: select(SEVERITIES, existing ? existing.severity : 'info', null, 'Type') });
    const starts = field({ label: 'Show from', input: h('input', { class: 'input', type: 'datetime-local', value: toLocalInput(existing ? existing.starts_at : new Date().toISOString()) }) });
    const ends = field({ label: 'Show until (optional)', input: h('input', { class: 'input', type: 'datetime-local', value: toLocalInput(existing && existing.ends_at) }) });
    const published = h('input', { type: 'checkbox', checked: existing ? existing.published : true });
    const preview = h('div', { class: 'app-preview' });
    const errorBox = h('div', { class: 'mb-lg', hidden: true });

    function updatePreview() {
      const sev = severity.input.value;
      counter.textContent = `${body.input.value.length} / 1000 characters`;
      clear(preview).appendChild(h('div', { class: `app-note ${sev}` },
        h('div', {},
          h('p', { class: 'note-title' }, title.input.value.trim() || 'Title'),
          body.input.value.trim() ? h('p', {}, body.input.value.trim()) : null)));
    }
    for (const el of [title.input, body.input, severity.input]) el.addEventListener('input', updatePreview);
    severity.input.addEventListener('change', updatePreview);
    updatePreview();

    modal(existing ? 'Edit announcement' : 'New announcement', 'Shown as a banner in the TenderTrack app.', (close) => {
      const submit = h('button', { class: 'btn btn-primary btn-sm', type: 'submit' }, existing ? 'Save changes' : 'Save announcement');
      return h('form', {
        novalidate: true,
        onsubmit: async (event) => {
          event.preventDefault();
          errorBox.hidden = true;
          let ok = true;
          const t = title.input.value.trim();
          if (t.length < 3) { title.setError('At least 3 characters.'); ok = false; } else title.setError('');
          if (!starts.input.value) { starts.setError('Choose when it starts showing.'); ok = false; } else starts.setError('');
          const startIso = starts.input.value ? new Date(starts.input.value).toISOString() : null;
          const endIso = ends.input.value ? new Date(ends.input.value).toISOString() : null;
          if (startIso && endIso && endIso <= startIso) { ends.setError('Must be after the start.'); ok = false; } else ends.setError('');
          if (!ok) return;

          const row = {
            title: t,
            body: body.input.value.trim(),
            audience: audience.input.value,
            severity: severity.input.value,
            published: published.checked,
            starts_at: startIso,
            ends_at: endIso,
          };
          await busy(submit, async () => {
            const { error } = existing
              ? await sb.from('announcements').update(row).eq('id', existing.id)
              : await sb.from('announcements').insert(row);
            if (error) {
              clear(errorBox).appendChild(note('danger', null, messageOf(error)));
              errorBox.hidden = false;
              return;
            }
            close();
            toast(published.checked ? 'Saved. The app shows it from the start time.' : 'Saved as a draft. It is not shown in the app.');
            await refresh();
          });
        },
      },
      errorBox, title.wrap, body.wrap,
      h('div', { class: 'form-grid' }, audience.wrap, severity.wrap, starts.wrap, ends.wrap),
      h('label', { class: 'check' }, published, h('span', {}, 'Published (untick to keep it as a draft)')),
      h('p', { class: 'label mb-lg' }, 'Preview in the app'),
      preview,
      h('div', { class: 'modal-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: close }, 'Cancel'),
        submit));
    });
  }

  async function togglePublished(a) {
    const { error } = await sb.from('announcements').update({ published: !a.published }).eq('id', a.id);
    if (error) { toast(messageOf(error), 'error'); return; }
    toast(a.published ? 'Unpublished. The app stops showing it.' : 'Published.');
    await refresh();
  }

  async function remove(a) {
    const yes = await confirmDialog({
      title: 'Delete announcement?',
      message: `"${a.title}" is removed from the app. The audit trail keeps a record that it existed.`,
      confirmText: 'Delete', danger: true,
    });
    if (!yes) return;
    const { error } = await sb.from('announcements').delete().eq('id', a.id);
    if (error) { toast(messageOf(error), 'error'); return; }
    toast('Announcement deleted.');
    await refresh();
  }

  await load();
  clear(view).append(
    pageHead('Communication', 'Announcements', 'Notices that appear as a banner in the app, for everyone or for one group.',
      h('button', { class: 'btn btn-primary btn-sm', type: 'button', onclick: () => openEditor(null) }, icon('add'), 'New announcement')),
    h('div', { class: 'mb-lg' }, note('info', 'Where they appear in the app',
      'Everyone and Public: the welcome screen. Suppliers: the supplier home screen. Staff: the procurement officer and auditor home screens. Everyone notices appear on all of them.')),
    listHost,
  );
  draw();
}
