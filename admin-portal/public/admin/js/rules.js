/*
 * Simple checks that point the administrator at tenders worth a closer look.
 * They only highlight; the administrator decides whether to raise a flag,
 * and the procurement officer handles it in the app.
 */

const SEVERITY_ORDER = { high: 0, medium: 1, low: 2 };

/** Returns a list of { severity, text } for one tender. */
export function tenderConcerns(t, now = Date.now()) {
  const concerns = [];
  const estimate = Number(t.estimated_budget || 0);
  const awarded = t.awarded_value === null || t.awarded_value === undefined ? null : Number(t.awarded_value);
  const paid = Number(t.paid_to_date || 0);

  // FR6 uses the same 10% threshold when the officer records the award.
  if (awarded !== null && estimate > 0) {
    const difference = (awarded - estimate) / estimate;
    if (Math.abs(difference) > 0.10) {
      const direction = difference > 0 ? 'above' : 'below';
      concerns.push({ severity: 'high', text: `Awarded ${Math.round(Math.abs(difference) * 100)}% ${direction} the estimated budget` });
    }
  }

  if (awarded !== null && paid > awarded) {
    concerns.push({ severity: 'high', text: 'Paid more than the awarded value' });
  }

  if (t.status === 'published' && t.closing_date && new Date(t.closing_date).getTime() < now) {
    concerns.push({ severity: 'medium', text: 'Still open for bids after the closing date' });
  }

  if (['awarded', 'in_progress', 'completed'].includes(t.status) && !t.awarded_supplier_name) {
    concerns.push({ severity: 'high', text: 'Awarded, but no supplier is recorded' });
  }

  if (t.status === 'completed' && awarded !== null && paid < awarded * 0.5) {
    concerns.push({ severity: 'low', text: 'Completed with less than half of the award paid' });
  }

  if (Number(t.open_flag_count || 0) > 0) {
    const n = Number(t.open_flag_count);
    concerns.push({ severity: 'medium', text: `${n} open compliance ${n === 1 ? 'flag' : 'flags'}` });
  }

  return concerns.sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]);
}

export const SEVERITY_TONE = { high: 'danger', medium: 'warn', low: 'neutral' };
