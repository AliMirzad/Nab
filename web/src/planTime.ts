const zone = "Asia/Tehran";
const parts = (value: Date) =>
  Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: zone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(value)
      .map((p) => [p.type, p.value]),
  );
export function localPlanDate(value: Date) {
  const p = parts(value);
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}
// Interpret calendar input in Tehran, independently of the manager's device timezone.
export function planInstant(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error("تاریخ برنامه معتبر نیست");
  const [, y, m, d, h, min] = match.map(Number);
  const wall = Date.UTC(y, m - 1, d, h, min);
  let guess = wall;
  for (let i = 0; i < 3; i++) {
    const p = parts(new Date(guess));
    guess += wall - Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute);
  }
  if (localPlanDate(new Date(guess)) !== value)
    throw new Error("تاریخ برنامه معتبر نیست");
  return new Date(guess).toISOString();
}
export const nextPlanWeek = (start: string) =>
  localPlanDate(new Date(Date.parse(planInstant(start)) + 7 * 86400000));
