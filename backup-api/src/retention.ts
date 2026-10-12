import type { SnapshotRecord } from './contracts.js';

const dayKey = (iso: string) => iso.slice(0, 10);

function isoWeekKey(iso: string): string {
  const date = new Date(`${dayKey(iso)}T00:00:00.000Z`);
  const day = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - day + 3);
  const firstThursday = new Date(Date.UTC(date.getUTCFullYear(), 0, 4));
  const firstDay = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDay + 3);
  const week = 1 + Math.round((date.getTime() - firstThursday.getTime()) / 604_800_000);
  return `${date.getUTCFullYear()}-W${String(week).padStart(2, '0')}`;
}

/** Keep the latest snapshot for each of the 7 newest UTC dates, then 4 older UTC weeks. */
export function snapshotsToDelete(snapshots: SnapshotRecord[]): string[] {
  const sorted = [...snapshots].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt));
  const dailyDates = new Set<string>();
  for (const item of sorted) {
    dailyDates.add(dayKey(item.uploadedAt));
    if (dailyDates.size === 7) break;
  }

  const keep = new Set<string>();
  const dailyKept = new Set<string>();
  const weekly = new Set<string>();
  for (const item of sorted) {
    const day = dayKey(item.uploadedAt);
    if (dailyDates.has(day)) {
      if (!dailyKept.has(day)) {
        keep.add(item.id);
        dailyKept.add(day);
      }
      continue;
    }
    const week = isoWeekKey(item.uploadedAt);
    if (!weekly.has(week)) {
      keep.add(item.id);
      weekly.add(week);
      if (weekly.size === 4) break;
    }
  }
  return sorted.filter((item) => !keep.has(item.id)).map((item) => item.id);
}
