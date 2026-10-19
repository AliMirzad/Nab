import { useEffect, useMemo, useState } from "react";
import { CalendarClock, ArrowUpLeft, Plus, X, Repeat2 } from "lucide-react";
import { api, Row, fa } from "./api";
import {
  localPlanDate as localDate,
  planInstant,
  nextPlanWeek as nextWeek,
} from "./planTime";

const zone = "Asia/Tehran";
const dayNames = [
  [6, "شنبه"],
  [7, "یکشنبه"],
  [1, "دوشنبه"],
  [2, "سه‌شنبه"],
  [3, "چهارشنبه"],
  [4, "پنجشنبه"],
  [5, "جمعه"],
] as const;
type Window = { days: number[]; startTime: string; endTime: string };
const allDays = [1, 2, 3, 4, 5, 6, 7];
const tehran = (value: string) =>
  new Date(value).toLocaleString("fa-IR", {
    timeZone: zone,
    dateStyle: "short",
    timeStyle: "short",
  });
const clock = (value: string) =>
  new Date(value).toLocaleTimeString("fa-IR", {
    timeZone: zone,
    hour: "2-digit",
    minute: "2-digit",
  });
export function planPayload(values: Row): Row {
  return {
    ...values,
    startsAt: planInstant(values.startsAt),
    endsAt: planInstant(values.endsAt),
    workHours: Number(values.workHours),
    restHours: Number(values.restHours),
    intervalMinutes: Number(values.intervalMinutes || 120),
    windowMinutes: Number(values.windowMinutes || 45),
    weeklyWindows:
      typeof values.weeklyWindows === "string"
        ? JSON.parse(values.weeklyWindows)
        : values.weeklyWindows,
    cycleAnchor: values.cycleAnchor || null,
  };
}

export function PlanFields({
  users,
  routes,
  token,
  source,
}: {
  users: Row[];
  routes: Row[];
  token: string;
  source?: Row | null;
}) {
  const initial = source
    ? localDate(new Date(source.ends_at))
    : localDate(new Date()).slice(0, 11) + "08:00";
  const [label, setLabel] = useState(source?.label || ""),
    [guard, setGuard] = useState(source?.guard_id || ""),
    [route, setRoute] = useState(source?.route_id || "");
  const [start, setStart] = useState(initial),
    [end, setEnd] = useState(nextWeek(initial));
  const [work, setWork] = useState(source?.work_hours || 24),
    [rest, setRest] = useState(source?.rest_hours ?? 24);
  const [interval, setInterval] = useState(source?.interval_minutes || 120),
    [window, setWindow] = useState(source?.window_minutes || 45);
  const [mode, setMode] = useState(source?.schedule_mode || "WEEKLY");
  const [windows, setWindows] = useState<Window[]>(
    source?.weeklyWindows || [
      { days: allDays, startTime: "02:00", endTime: "04:00" },
      { days: allDays, startTime: "06:00", endTime: "08:00" },
    ],
  );
  const [preview, setPreview] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const anchor = source ? source.cycle_anchor || source.starts_at : "";
  const payload = useMemo(() => {
    try {
      return planPayload({
        label: label.trim() || "پیش‌نمایش هفته",
        guardId: guard,
        routeId: route,
        startsAt: start,
        endsAt: end,
        workHours: work,
        restHours: rest,
        intervalMinutes: interval,
        windowMinutes: window,
        scheduleMode: mode,
        weeklyWindows: windows,
        cycleAnchor: anchor,
      });
    } catch {
      return null;
    }
  }, [
    label,
    guard,
    route,
    start,
    end,
    work,
    rest,
    interval,
    window,
    mode,
    windows,
    anchor,
  ]);
  useEffect(() => {
    setPreview(null);
    setError("");
    if (!payload || !guard || !route) {
      setBusy(false);
      return;
    }
    let alive = true;
    setBusy(true);
    const timer = setTimeout(() => {
      api("/admin/plans/preview", token, payload)
        .then((result) => {
          if (alive) setPreview(result);
        })
        .catch((e) => {
          if (alive) setError(e.message);
        })
        .finally(() => {
          if (alive) setBusy(false);
        });
    }, 450);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [payload, guard, route, token]);
  const updateWindow = (
    i: number,
    key: keyof Window,
    value: string | number[],
  ) =>
    setWindows((old) =>
      old.map((w, n) => (n === i ? { ...w, [key]: value } : w)),
    );
  return (
    <div className="weekly-plan-form">
      {source && (
        <>
          <input type="hidden" name="sourcePlanId" value={source.id} />
          <div className="info-note">
            استفاده مجدد از الگوی «{source.label}» برای {source.guard_name}. فقط
            بازه جدید را انتخاب کن؛ مسیر، ساعت‌ها و چرخه شیفت حفظ می‌شوند و هفته
            از نو با روز کاری شروع نمی‌شود.
          </div>
        </>
      )}
      <label>
        عنوان برنامه / الگو
        <input
          name="label"
          required
          maxLength={110}
          value={label}
          placeholder="مثلاً برنامه هفتگی کارگاه"
          onChange={(e) => setLabel(e.target.value)}
        />
      </label>
      {source ? (
        <>
          <input type="hidden" name="guardId" value={guard} />
          <input type="hidden" name="routeId" value={route} />
          <div className="pattern-summary">
            <strong>{source.guard_name}</strong>
            <span>
              {source.site_name} · {source.route_name}
            </span>
          </div>
        </>
      ) : (
        <div className="form-row">
          <label>
            نگهبان
            <select
              name="guardId"
              required
              value={guard}
              onChange={(e) => setGuard(e.target.value)}
            >
              <option value="" disabled>
                انتخاب نگهبان
              </option>
              {users
                .filter((u) => u.role === "GUARD" && u.active)
                .map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            مسیر و محل گشت
            <select
              name="routeId"
              required
              value={route}
              onChange={(e) => {
                setRoute(e.target.value);
                const r = routes.find((r) => r.id === e.target.value);
                if (r) setWindow(Math.min(1440, r.expected_minutes));
              }}
            >
              <option value="" disabled>
                انتخاب مسیر
              </option>
              {routes.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name} — {r.site_name}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      <div className="form-row">
        <label>
          {source
            ? "شروع بازه جدید (تهران)"
            : "شروع برنامه و اولین شیفت (تهران)"}
          <input
            type="datetime-local"
            name="startsAt"
            required
            value={start}
            onChange={(e) => {
              setStart(e.target.value);
              try {
                setEnd(nextWeek(e.target.value));
              } catch {
                /* Wait for a complete date. */
              }
            }}
          />
        </label>
        <label>
          پایان برنامه (تهران)
          <input
            type="datetime-local"
            name="endsAt"
            required
            value={end}
            onChange={(e) => setEnd(e.target.value)}
          />
        </label>
      </div>
      <small className="muted">
        ورودی تاریخ میلادی است؛ پیش‌نمایش شمسی و همه ساعت‌ها به وقت تهران هستند.
        پایان به‌صورت پیش‌فرض یک هفته بعد است.
      </small>
      <div className="form-row">
        <label>
          ساعت کار در هر شیفت
          <input
            name="workHours"
            type="number"
            required
            min={1}
            max={72}
            readOnly={!!source}
            value={work}
            onChange={(e) => setWork(Number(e.target.value))}
          />
        </label>
        <label>
          ساعت استراحت بعد از شیفت
          <input
            name="restHours"
            type="number"
            required
            min={0}
            max={168}
            readOnly={!!source}
            value={rest}
            onChange={(e) => setRest(Number(e.target.value))}
          />
        </label>
      </div>
      <input type="hidden" name="scheduleMode" value={mode} />
      <input
        type="hidden"
        name="weeklyWindows"
        value={JSON.stringify(windows)}
      />
      <input type="hidden" name="cycleAnchor" value={anchor} />
      {!source && (
        <div className="schedule-mode" role="group" aria-label="روش زمان‌بندی">
          <button
            type="button"
            className={mode === "WEEKLY" ? "chosen" : ""}
            onClick={() => setMode("WEEKLY")}
          >
            ساعت‌های ثابت هفته
          </button>
          <button
            type="button"
            className={mode === "INTERVAL" ? "chosen" : ""}
            onClick={() => setMode("INTERVAL")}
          >
            تکرار با فاصله زمانی
          </button>
        </div>
      )}
      {mode === "WEEKLY" ? (
        <section className="weekly-pattern">
          <div className="pattern-title">
            <div>
              <h3>الگوی ساعت‌های گشت</h3>
              <small>یک‌بار تعریف کن؛ فقط در شیفت‌های کاری تکرار می‌شود.</small>
            </div>
            {!source && (
              <button
                className="secondary"
                type="button"
                disabled={windows.length >= 32}
                onClick={() =>
                  setWindows([
                    ...windows,
                    { days: allDays, startTime: "10:00", endTime: "12:00" },
                  ])
                }
              >
                <Plus size={15} />
                بازه جدید
              </button>
            )}
          </div>
          {windows.map((w, i) => (
            <div className="weekly-window" key={i}>
              <div className="window-times">
                <strong>بازه {fa(i + 1)}</strong>
                <label>
                  از ساعت
                  <input
                    aria-label={`شروع بازه ${i + 1}`}
                    type="time"
                    step={60}
                    required
                    readOnly={!!source}
                    value={w.startTime}
                    onChange={(e) =>
                      updateWindow(i, "startTime", e.target.value)
                    }
                  />
                </label>
                <label>
                  تا ساعت
                  <input
                    aria-label={`پایان بازه ${i + 1}`}
                    type="time"
                    step={60}
                    required
                    readOnly={!!source}
                    value={w.endTime}
                    onChange={(e) => updateWindow(i, "endTime", e.target.value)}
                  />
                </label>
                {!source && (
                  <button
                    className="icon-btn"
                    type="button"
                    disabled={windows.length === 1}
                    aria-label={`حذف بازه ${i + 1}`}
                    onClick={() =>
                      setWindows(windows.filter((_, n) => n !== i))
                    }
                  >
                    <X size={16} />
                  </button>
                )}
              </div>
              <div className="weekdays">
                {dayNames.map(([day, name]) => (
                  <button
                    type="button"
                    key={day}
                    disabled={!!source}
                    aria-pressed={w.days.includes(day)}
                    className={w.days.includes(day) ? "selected" : ""}
                    onClick={() =>
                      updateWindow(
                        i,
                        "days",
                        w.days.includes(day)
                          ? w.days.filter((d) => d !== day)
                          : [...w.days, day],
                      )
                    }
                  >
                    {name}
                  </button>
                ))}
              </div>
              {w.endTime < w.startTime && (
                <small>پایان این بازه در روز بعد است.</small>
              )}
            </div>
          ))}
          <input type="hidden" name="intervalMinutes" value={interval} />
          <input type="hidden" name="windowMinutes" value={window} />
          <p className="muted">
            هر بازه یک نوبت بازدید از کل مسیر است. بازه‌ای که کامل داخل شیفت
            کاری جا نگیرد ساخته نمی‌شود؛ ساعت پایان کوچک‌تر از شروع یعنی عبور از
            نیمه‌شب.
          </p>
        </section>
      ) : (
        <div className="form-row">
          <label>
            فاصله شروع نوبت‌ها (دقیقه)
            <input
              name="intervalMinutes"
              type="number"
              min={1}
              max={1440}
              required
              readOnly={!!source}
              value={interval}
              onChange={(e) => setInterval(Number(e.target.value))}
            />
          </label>
          <label>
            مهلت هر نوبت (دقیقه)
            <input
              name="windowMinutes"
              type="number"
              min={1}
              max={1440}
              required
              readOnly={!!source}
              value={window}
              onChange={(e) => setWindow(Number(e.target.value))}
            />
          </label>
        </div>
      )}
      <div className="info-note">
        الگوی شیفت: {fa(work)} ساعت کار، سپس {fa(rest)} ساعت استراحت. تمام
        نوبت‌های این بازه با یک ثبت ساخته می‌شوند؛ نگهبان پس از دریافت برنامه
        می‌تواند گشت‌ها را آفلاین انجام دهد.
      </div>
      <div aria-live="polite">
        {busy ? (
          <p className="muted">در حال محاسبه برنامه هفته…</p>
        ) : error ? (
          <p className="error">{error}</p>
        ) : preview ? (
          <SchedulePreview preview={preview} />
        ) : (
          <p className="muted">
            برای دیدن برنامه خودکار هفته، نگهبان و مسیر را انتخاب کن.
          </p>
        )}
      </div>
    </div>
  );
}

function SchedulePreview({ preview }: { preview: Row }) {
  return (
    <section className="plan-preview weekly-preview">
      <h3>
        {fa(preview.shiftCount)} شیفت کاری · {fa(preview.roundCount)} نوبت گشت
      </h3>
      <p>پیش‌نمایش دقیق برنامه، قبل از ثبت نهایی</p>
      {preview.roundCount === 0 && (
        <p className="error">
          هیچ بازه گشتی در ساعت‌های کار این هفته قرار نمی‌گیرد.
        </p>
      )}
      {preview.skippedWindows > 0 && (
        <p className="skip-note">
          {fa(preview.skippedWindows)} بازه به‌دلیل استراحت یا خارج‌بودن از
          محدوده شیفت/برنامه ساخته نمی‌شود.
        </p>
      )}
      <div className="week-calendar">
        {preview.days.map((d: Row) => (
          <article
            className={d.workMinutes === 0 ? "rest-day" : "work-day"}
            key={d.date}
          >
            <header>
              <strong>
                {new Date(d.date + "T12:00:00Z").toLocaleDateString("fa-IR", {
                  timeZone: zone,
                  weekday: "long",
                  month: "short",
                  day: "numeric",
                })}
              </strong>
              <span>
                {d.workMinutes === 0
                  ? "استراحت"
                  : `${fa(d.workMinutes / 60)} ساعت کار`}
              </span>
            </header>
            {d.work.map((w: Row, i: number) => (
              <p key={i} className="duty-time">
                شیفت: {clock(w.startsAt)} تا {clock(w.endsAt)}
              </p>
            ))}
            {d.rest?.map((r: Row, i: number) => (
              <p className="rest-time" key={i}>
                استراحت: {clock(r.startsAt)} تا {clock(r.endsAt)}
              </p>
            ))}
            {d.rounds.length > 0 ? (
              <ul>
                {d.rounds.map((r: Row) => (
                  <li key={r.startsAt}>
                    <span>گشت</span>
                    <time>
                      {clock(r.startsAt)} تا {clock(r.endsAt)}
                      {new Date(r.startsAt).toLocaleDateString("en-CA", {
                        timeZone: zone,
                      }) !==
                      new Date(r.endsAt).toLocaleDateString("en-CA", {
                        timeZone: zone,
                      })
                        ? " (روز بعد)"
                        : ""}
                    </time>
                  </li>
                ))}
              </ul>
            ) : (
              <small>
                {d.workMinutes === 0
                  ? "گشتی برنامه‌ریزی نمی‌شود"
                  : "بدون نوبت گشت در این روز"}
              </small>
            )}
          </article>
        ))}
      </div>
      <details>
        <summary>ساعت شروع و پایان شیفت‌ها</summary>
        {preview.shifts.map((s: Row, i: number) => (
          <p key={i}>
            شیفت {fa(i + 1)}: {tehran(s.startsAt)} تا {tehran(s.endsAt)}
          </p>
        ))}
      </details>
    </section>
  );
}

export function PlansList({
  plans,
  inspect,
  repeat,
}: {
  plans: Row[];
  inspect: (p: Row) => void;
  repeat: (p: Row) => void;
}) {
  return (
    <>
      <div className="info-note">
        یک الگو برای ساعت‌های گشت و چرخه کار/استراحت تعریف کن؛ همه نوبت‌های هفته
        خودکار ساخته می‌شوند. با «استفاده برای هفته بعد» همان الگو را بدون ورود
        دوباره ساعت‌ها اجرا کن.
      </div>
      <div className="point-grid">
        {plans.map((p) => (
          <article className="point-card" key={p.id}>
            <span className="point-icon">
              <CalendarClock size={23} />
            </span>
            <h3>{p.label}</h3>
            <p>
              {p.guard_name} · {p.route_name}
            </p>
            <small>{p.site_name}</small>
            <p>
              {tehran(p.starts_at)} تا {tehran(p.ends_at)}
            </p>
            <p>
              {fa(p.work_hours)} ساعت کار / {fa(p.rest_hours)} ساعت استراحت
            </p>
            {p.schedule_mode === "WEEKLY" ? (
              <div className="saved-windows">
                {p.weeklyWindows.map((w: Window, i: number) => (
                  <span key={i}>
                    {w.startTime}–{w.endTime} ·{" "}
                    {w.days.length === 7
                      ? "همه روزهای کاری"
                      : w.days
                          .map((d) => dayNames.find((x) => x[0] === d)?.[1])
                          .join("، ")}
                  </span>
                ))}
              </div>
            ) : (
              <p>
                هر {fa(p.interval_minutes)} دقیقه · مهلت {fa(p.window_minutes)}{" "}
                دقیقه
              </p>
            )}
            <strong>{fa(p.round_count)} نوبت گشت</strong>
            <button className="secondary" onClick={() => inspect(p)}>
              مشاهده نوبت‌ها <ArrowUpLeft size={16} />
            </button>
            <button className="secondary reuse-plan" onClick={() => repeat(p)}>
              <Repeat2 size={16} />
              استفاده برای هفته بعد
            </button>
          </article>
        ))}
        {!plans.length && (
          <div className="empty">
            هنوز الگویی تعریف نشده؛ با افزودن برنامه، هفته را یک‌جا بساز.
          </div>
        )}
      </div>
    </>
  );
}
