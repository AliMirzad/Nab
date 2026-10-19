import { useState } from "react";
import {
  Download,
  FileSpreadsheet,
  FileText,
  Filter,
  ClipboardList,
} from "lucide-react";
import { api, Row, fa } from "./api";

const months = [
  "فروردین",
  "اردیبهشت",
  "خرداد",
  "تیر",
  "مرداد",
  "شهریور",
  "مهر",
  "آبان",
  "آذر",
  "دی",
  "بهمن",
  "اسفند",
];
const statuses: Record<string, string> = {
  ALL: "همه وضعیت‌ها",
  FINISHED: "تکمیل‌شده",
  ACTIVE: "در حال گشت",
  ABORTED: "متوقف‌شده / ناموفق",
  OVERDUE: "دریافت‌نشده",
  PENDING: "در انتظار شروع",
};
const stamp = (value?: string) =>
  value
    ? new Date(value).toLocaleString("fa-IR", {
        timeZone: "Asia/Tehran",
        dateStyle: "short",
        timeStyle: "short",
      })
    : "—";
const current = new Intl.DateTimeFormat("en-US-u-ca-persian", {
  year: "numeric",
  month: "numeric",
  timeZone: "Asia/Tehran",
}).formatToParts(new Date());

export function Reports({
  token,
  users,
  sites,
  routes,
  assignments,
}: {
  token: string;
  users: Row[];
  sites: Row[];
  routes: Row[];
  assignments: Row[];
}) {
  const [filter, setFilter] = useState<Row>({
    period: "MONTH",
    year: Number(current.find((x) => x.type === "year")!.value),
    month: Number(current.find((x) => x.type === "month")!.value),
    guardId: "",
    siteId: "",
    routeId: "",
    assignmentId: "",
    status: "ALL",
    fromDate: "",
    toDate: "",
    includeEvents: false,
  });
  const [report, setReport] = useState<Row | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const change = (key: string, value: unknown) => {
    setFilter((old) => ({
      ...old,
      [key]: value,
      ...(["guardId", "siteId", "routeId"].includes(key)
        ? { assignmentId: "" }
        : {}),
      ...(key === "siteId" ? { routeId: "" } : {}),
    }));
    setReport(null);
    setError("");
    setPage(0);
  };
  const generate = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy("preview");
    setError("");
    setReport(null);
    try {
      const payload = Object.fromEntries(
        Object.entries(filter).map(([key, value]) => [
          key,
          value === "" ? null : value,
        ]),
      );
      setReport(await api("/admin/reports", token, payload));
      setPage(0);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy("");
    }
  };
  const download = async (format: "pdf" | "xlsx") => {
    if (!report) return;
    setBusy(format);
    setError("");
    try {
      const response = await fetch(
        `/api/admin/reports/${report.id}/${format}`,
        { headers: { Authorization: `Bearer ${token}` } },
      );
      if (!response.ok) {
        const data = await response.json();
        if (response.status === 410) setReport(null);
        throw new Error(data.message || "دریافت فایل انجام نشد");
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = `nab-${report.id.slice(0, 8)}.${format}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy("");
    }
  };
  const routeOptions = routes.filter(
    (r) => !filter.siteId || r.site_id === filter.siteId,
  );
  const assignmentOptions = assignments.filter(
    (a) =>
      (!filter.guardId || a.guard_id === filter.guardId) &&
      (!filter.routeId || a.route_id === filter.routeId) &&
      (!filter.siteId || routeOptions.some((r) => r.id === a.route_id)),
  );
  return (
    <div className="reports-page">
      <section className="panel report-filters">
        <div className="section-heading">
          <div>
            <h2>
              <Filter size={18} /> محدوده گزارش
            </h2>
            <small>
              گزارش بر اساس شروع برنامه‌ریزی‌شده هر نوبت، به وقت تهران تهیه
              می‌شود.
            </small>
          </div>
          <span className="report-chip">تقویم شمسی</span>
        </div>
        <form onSubmit={generate}>
          <fieldset disabled={!!busy} className="report-fieldset">
            <div className="report-grid">
              <label>
                نگهبان
                <select
                  value={filter.guardId}
                  onChange={(e) => change("guardId", e.target.value)}
                >
                  <option value="">همه نگهبانان</option>
                  {users
                    .filter((u) => u.role === "GUARD")
                    .map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                محل
                <select
                  value={filter.siteId}
                  onChange={(e) => change("siteId", e.target.value)}
                >
                  <option value="">همه محل‌ها</option>
                  {sites.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                مسیر گشت
                <select
                  value={filter.routeId}
                  onChange={(e) => change("routeId", e.target.value)}
                >
                  <option value="">همه مسیرها</option>
                  {routeOptions.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                نوبت گشت
                <select
                  value={filter.assignmentId}
                  onChange={(e) => change("assignmentId", e.target.value)}
                >
                  <option value="">همه نوبت‌ها</option>
                  {assignmentOptions.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.label} — {stamp(a.starts_at)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                بازه گزارش
                <select
                  value={filter.period}
                  onChange={(e) => change("period", e.target.value)}
                >
                  <option value="MONTH">ماه شمسی</option>
                  <option value="RANGE">بازه دلخواه</option>
                  <option value="ALL">همه زمان‌ها</option>
                </select>
              </label>
              {filter.period === "MONTH" && (
                <>
                  <label>
                    سال شمسی
                    <input
                      type="number"
                      min="1300"
                      max="1500"
                      required
                      value={filter.year}
                      onChange={(e) => change("year", Number(e.target.value))}
                    />
                  </label>
                  <label>
                    ماه
                    <select
                      value={filter.month}
                      onChange={(e) => change("month", Number(e.target.value))}
                    >
                      {months.map((m, i) => (
                        <option key={m} value={i + 1}>
                          {m}
                        </option>
                      ))}
                    </select>
                  </label>
                </>
              )}
              {filter.period === "RANGE" && (
                <>
                  <label>
                    از تاریخ (ورودی میلادی)
                    <input
                      type="date"
                      required
                      min="1900-01-01"
                      max="2100-12-31"
                      value={filter.fromDate}
                      onChange={(e) => change("fromDate", e.target.value)}
                    />
                  </label>
                  <label>
                    تا تاریخ، شامل کل روز
                    <input
                      type="date"
                      required
                      min={filter.fromDate || "1900-01-01"}
                      max="2100-12-31"
                      value={filter.toDate}
                      onChange={(e) => change("toDate", e.target.value)}
                    />
                  </label>
                </>
              )}
              <label>
                وضعیت
                <select
                  value={filter.status}
                  onChange={(e) => change("status", e.target.value)}
                >
                  {Object.entries(statuses).map(([v, label]) => (
                    <option key={v} value={v}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="report-form-footer">
              <label className="report-check">
                <input
                  type="checkbox"
                  checked={filter.includeEvents}
                  onChange={(e) => change("includeEvents", e.target.checked)}
                />
                افزودن جزئیات اسکن‌ها، موقعیت و رخدادها به فایل‌ها
              </label>
              <button className="primary" type="submit">
                <ClipboardList size={17} />
                {busy === "preview" ? "در حال تهیه گزارش…" : "نمایش گزارش"}
              </button>
            </div>
          </fieldset>
        </form>
      </section>
      {error && (
        <div role="alert" className="error banner">
          {error}
        </div>
      )}
      {!report ? (
        <section className="panel report-empty">
          <FileText size={36} />
          <h2>گزارش متناسب با نیاز شما</h2>
          <p>نگهبان، مسیر یا ماه را انتخاب کن و «نمایش گزارش» را بزن.</p>
          <small>
            پس از نمایش، PDF و Excel دقیقاً از همین نتیجه دریافت می‌شوند.
          </small>
        </section>
      ) : (
        <>
          <div className="report-result-heading">
            <div>
              <h2>نتیجه گزارش · {report.periodLabel}</h2>
              <small>
                تهیه‌شده: {stamp(report.generatedAt)} ·{" "}
                {fa(report.summary.total)} نوبت گشت
              </small>
            </div>
            <div className="actions">
              <button
                className="secondary"
                disabled={!!busy}
                onClick={() => download("xlsx")}
              >
                <FileSpreadsheet size={17} />
                {busy === "xlsx" ? "در حال دریافت…" : "دریافت Excel"}
              </button>
              <button
                className="primary"
                disabled={!!busy}
                onClick={() => download("pdf")}
              >
                <Download size={17} />
                {busy === "pdf" ? "در حال دریافت…" : "دریافت PDF"}
              </button>
            </div>
          </div>
          <div className="report-metrics">
            {[
              ["کل نوبت‌ها", "total"],
              ["تکمیل‌شده", "finished"],
              ["در حال گشت", "active"],
              ["متوقف‌شده", "aborted"],
              ["دریافت‌نشده", "overdue"],
              ["در انتظار شروع", "pending"],
            ].map(([label, key]) => (
              <div className={`panel report-metric metric-${key}`} key={key}>
                <small>{label}</small>
                <strong>{fa(report.summary[key])}</strong>
              </div>
            ))}
          </div>
          <p className="report-notice">
            «دریافت‌نشده» یعنی زمان نوبت گذشته اما داده‌ای به سرور نرسیده؛ ممکن
            است نگهبان آفلاین باشد. بازدید ثبت‌شده:{" "}
            {fa(report.summary.visitedStops)} از{" "}
            {fa(report.summary.expectedStops)} نقطه · رخدادها:{" "}
            {fa(report.summary.incidents)}
          </p>
          {report.guards.length > 0 && (
            <section className="panel">
              <div className="section-heading">
                <h2>جمع‌بندی نگهبانان</h2>
                <small>بر اساس همین فیلترها</small>
              </div>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      {[
                        "نگهبان",
                        "کل نوبت‌ها",
                        "تکمیل‌شده",
                        "در حال گشت",
                        "متوقف‌شده",
                        "دریافت‌نشده",
                        "در انتظار شروع",
                        "بازدید",
                        "رخداد",
                      ].map((h) => (
                        <th key={h}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {report.guards.map((g: Row) => (
                      <tr key={g.guardId}>
                        <td>
                          <strong>{g.guardName}</strong>
                        </td>
                        {[
                          "total",
                          "finished",
                          "active",
                          "aborted",
                          "overdue",
                          "pending",
                          "visitedStops",
                          "incidents",
                        ].map((k) => (
                          <td key={k}>{fa(g.totals[k])}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          <section className="panel">
            <div className="section-heading">
              <h2>جزئیات نوبت‌های گشت</h2>
              <small>
                فایل خروجی شامل همه {fa(report.rows.length)} ردیف است.
              </small>
            </div>
            {report.rows.length === 0 ? (
              <p className="report-notice">
                نوبت گشتی با این فیلترها یافت نشد.
              </p>
            ) : (
              <>
                <div className="table-scroll">
                  <table className="report-table">
                    <thead>
                      <tr>
                        {[
                          "نگهبان / محل",
                          "نوبت / مسیر",
                          "شروع برنامه",
                          "شروع واقعی",
                          "وضعیت",
                          "بازدید / کل",
                          "تأخیر / مدت (دقیقه)",
                        ].map((h) => (
                          <th key={h}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {report.rows
                        .slice(page * 25, (page + 1) * 25)
                        .map((r: Row) => (
                          <tr key={r.assignmentId}>
                            <td>
                              <strong>{r.guardName}</strong>
                              <small>{r.siteName}</small>
                            </td>
                            <td>
                              <strong>{r.label}</strong>
                              <small>{r.routeName}</small>
                            </td>
                            <td>{stamp(r.scheduledStart)}</td>
                            <td>{stamp(r.actualStart)}</td>
                            <td>
                              <span
                                className={`report-status status-${r.status.toLowerCase()}`}
                              >
                                {statuses[r.status]}
                              </span>
                            </td>
                            <td>
                              {fa(r.visitedStops)} / {fa(r.expectedStops)}
                            </td>
                            <td>
                              {r.delayMinutes == null
                                ? "—"
                                : fa(r.delayMinutes)}{" "}
                              /{" "}
                              {r.clockInvalid
                                ? "ساعت نامعتبر"
                                : r.durationMinutes == null
                                  ? "—"
                                  : fa(r.durationMinutes)}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
                <div className="report-pagination">
                  <span>
                    صفحه {fa(page + 1)} از{" "}
                    {fa(Math.ceil(report.rows.length / 25))}
                  </span>
                  <div className="actions">
                    <button
                      className="secondary"
                      disabled={page === 0}
                      onClick={() => setPage(page - 1)}
                    >
                      قبلی
                    </button>
                    <button
                      className="secondary"
                      disabled={(page + 1) * 25 >= report.rows.length}
                      onClick={() => setPage(page + 1)}
                    >
                      بعدی
                    </button>
                  </div>
                </div>
              </>
            )}
          </section>
          <small className="muted">
            فایل‌ها تا ۱۵ دقیقه از همین نسخه گزارش دریافت می‌شوند. برای دریافت
            تغییرات جدید، دوباره «نمایش گزارش» را بزن.
          </small>
        </>
      )}
    </div>
  );
}
