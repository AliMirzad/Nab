import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ShieldCheck,
  LayoutDashboard,
  MapPin,
  Route,
  Users,
  CalendarClock,
  ClipboardList,
  LogOut,
  Plus,
  ArrowUpLeft,
  RefreshCw,
  Search,
  Download,
  FileText,
  Check,
  Clock,
  AlertTriangle,
  X,
  QrCode,
  Printer,
  Building2,
  Radio,
  Activity,
  ChevronLeft,
  Eye,
  LockKeyhole,
} from "lucide-react";
import L from "leaflet";
import QRCode from "qrcode";
import "@fontsource/vazirmatn/400.css";
import "@fontsource/vazirmatn/500.css";
import "@fontsource/vazirmatn/600.css";
import "@fontsource/vazirmatn/700.css";
import "leaflet/dist/leaflet.css";
import "./style.css";
import { api, Row, Session, savedSession, fa, date } from "./api";
import { PlanFields, PlansList, planPayload } from "./Planning";
import { Reports } from "./Reports";

const titles: Record<string, string> = {
  reports: "گزارش‌گیری",
  plans: "برنامه شیفت و گشت",
  dashboard: "نمای کلی",
  tours: "گشت‌ها و گزارش‌ها",
  checkpoints: "نقاط کنترل",
  routes: "مسیرهای گشت",
  assignments: "شیفت و مأموریت",
  users: "نیروهای نگهبانی",
  sites: "محل‌های پروژه",
};
const nav = [
  ["plans", CalendarClock],
  ["dashboard", LayoutDashboard],
  ["tours", ClipboardList],
  ["reports", FileText],
  ["assignments", CalendarClock],
  ["checkpoints", MapPin],
  ["routes", Route],
  ["users", Users],
  ["sites", Building2],
] as const;
const eventNames: Record<string, string> = {
  ABORT: "توقف گشت ناقص",
  START: "شروع گشت",
  VISIT: "ثبت نقطه",
  LOCATION: "موقعیت دوره‌ای",
  INCIDENT: "گزارش حادثه",
  FINISH: "پایان گشت",
};
const statusLabel = (t: Row) =>
  t.status === "ACTIVE"
    ? "در حال گشت"
    : t.status === "ABORTED"
      ? "متوقف‌شده / ناموفق"
      : "پایان‌یافته";
const stopVisited = (t: Row, p: Row, index: number) =>
  t.events.some(
    (e: Row) =>
      e.event_type === "VISIT" &&
      (t.snapshot.routePolicy === "ORDERED_V1"
        ? e.payload.data.stopIndex === index
        : e.payload.data.checkpointId === p.id),
  );
const startDelay = (t: Row) =>
  Math.max(
    0,
    Math.floor((Date.parse(t.started_at) - Date.parse(t.starts_at)) / 60000),
  );
const duration = (t: Row) =>
  t.finished_at
    ? Math.floor((Date.parse(t.finished_at) - Date.parse(t.started_at)) / 60000)
    : null;
const mapLabel = (text: string) => {
  const node = document.createElement("span");
  node.textContent = text;
  return node;
};
function Badge({
  children,
  tone = "neutral",
}: {
  children: React.ReactNode;
  tone?: string;
}) {
  return <span className={"badge " + tone}>{children}</span>;
}
function Empty({ text = "هنوز موردی ثبت نشده است" }: { text?: string }) {
  return (
    <div className="empty">
      <ShieldCheck size={36} />
      <p>{text}</p>
    </div>
  );
}
function Modal({
  title,
  children,
  close,
  wide = false,
}: {
  title: string;
  children: React.ReactNode;
  close: () => void;
  wide?: boolean;
}) {
  return (
    <div className="overlay" onClick={close}>
      <section
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={"modal " + (wide ? "wide" : "")}
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <h2>{title}</h2>
          <button aria-label="بستن" className="iconbtn" onClick={close}>
            <X size={20} />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
function PatrolMap({
  tours,
  checkpoints = [],
}: {
  tours: Row[];
  checkpoints?: Row[];
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    const map = L.map(ref.current, { zoomControl: false }).setView(
      [35.724, 51.337],
      15,
    );
    L.control.zoom({ position: "bottomleft" }).addTo(map);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "© OpenStreetMap",
      maxZoom: 19,
    }).addTo(map);
    const bounds: L.LatLngTuple[] = [];
    for (const p of checkpoints) {
      if (p.latitude == null || p.longitude == null) continue;
      const xy: L.LatLngTuple = [p.latitude, p.longitude];
      bounds.push(xy);
      L.circleMarker(xy, {
        radius: 6,
        color: "#617575",
        fillColor: "#fff",
        fillOpacity: 1,
        weight: 2,
      })
        .addTo(map)
        .bindTooltip(mapLabel(p.name));
    }
    for (const [index, t] of tours.entries()) {
      const points: L.LatLngTuple[] = [];
      const color = ["#168772", "#cb9642", "#5873aa"][index % 3];
      for (const e of t.events) {
        const g = e.payload?.data?.location;
        if (g && Number.isFinite(g.latitude) && Number.isFinite(g.longitude))
          points.push([g.latitude, g.longitude]);
      }
      if (points.length) {
        bounds.push(...points);
        L.polyline(points, {
          color,
          weight: 3,
          dashArray: t.status === "ACTIVE" ? undefined : "6,6",
        }).addTo(map);
        L.circleMarker(points[points.length - 1], {
          radius: 9,
          color: "#fff",
          weight: 3,
          fillColor: color,
          fillOpacity: 1,
        })
          .addTo(map)
          .bindTooltip(mapLabel(t.guard_name));
      }
    }
    if (bounds.length)
      map.fitBounds(bounds, { padding: [45, 45], maxZoom: 17 });
    return () => {
      map.remove();
    };
  }, [tours, checkpoints]);
  return (
    <div
      className="patrol-map"
      ref={ref}
      aria-label="نقشه موقعیت‌های دریافت‌شده"
    />
  );
}
function QRCard({ point }: { point: Row }) {
  const [image, setImage] = useState("");
  useEffect(() => {
    QRCode.toDataURL(point.tag_code, {
      width: 240,
      margin: 2,
      errorCorrectionLevel: "M",
    }).then(setImage);
  }, [point.tag_code]);
  return (
    <div className="qr-card">
      <ShieldCheck size={22} />
      <h3>{point.name}</h3>
      <p>{point.site_name}</p>
      {image && (
        <img width="220" height="220" src={image} alt={"QR " + point.name} />
      )}
      <small dir="ltr">{point.tag_code}</small>
      <p>ناب · نقطه کنترل گشت</p>
    </div>
  );
}
function Photo({ id, token }: { id: string; token: string }) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    let objectUrl = "";
    let alive = true;
    fetch("/api/photos/" + id, {
      headers: { Authorization: `Bearer ${token}` },
    }).then(async (r) => {
      if (!r.ok) return;
      objectUrl = URL.createObjectURL(await r.blob());
      if (alive) setUrl(objectUrl);
      else URL.revokeObjectURL(objectUrl);
    });
    return () => {
      alive = false;
      URL.revokeObjectURL(objectUrl);
    };
  }, [id, token]);
  return url ? (
    <a href={url} target="_blank" rel="noreferrer">
      <img className="incident-photo" src={url} alt="تصویر گزارش" />
    </a>
  ) : (
    <small>در حال دریافت تصویر…</small>
  );
}
function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <main className="login-page">
      <div className="login-story">
        <div className="brand">
          <ShieldCheck size={34} />
          <span>
            ناب<small>NAB</small>
          </span>
        </div>
        <div>
          <span className="eyebrow">GUARD TOUR MANAGEMENT</span>
          <h1>
            هر گشت،
            <br />
            یک قدم به امنیت.
          </h1>
          <p>
            نیروها، مسیرها و گزارش‌های نگهبانی؛
            <br />
            همه در یک نگاه روشن.
          </p>
          <div className="story-badges">
            <span>
              <Check size={16} /> ثبت آفلاین
            </span>
            <span>
              <MapPin size={16} /> موقعیت گشت
            </span>
            <span>
              <QrCode size={16} /> کنترل نقاط
            </span>
          </div>
        </div>
        <small>سامانه مدیریت گشت نگهبانی</small>
      </div>
      <section className="login-form">
        <span className="eyebrow">خوش آمدید</span>
        <h2>ورود به مرکز کنترل</h2>
        <p className="muted">برای مدیریت گشت‌ها وارد حساب خود شوید.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            const f = new FormData(e.currentTarget);
            try {
              const s = await api(
                "/auth/login",
                undefined,
                Object.fromEntries(f),
              );
              if (s.user.role === "GUARD")
                throw new Error("برای نگهبانی از اپلیکیشن موبایل استفاده کنید");
              onLogin(s);
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            نام کاربری
            <input name="username" autoComplete="username" dir="ltr" required />
          </label>
          <label>
            رمز عبور
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              dir="ltr"
              required
            />
          </label>
          {error && <p className="error">{error}</p>}
          <button className="primary" disabled={busy}>
            {busy ? "در حال ورود…" : "ورود به پنل"}
            <ArrowUpLeft size={18} />
          </button>
        </form>
        <div className="secure-note">
          <LockKeyhole size={15} /> دسترسی ویژه مدیر و سرپرست
        </div>
      </section>
    </main>
  );
}

function App() {
  const [session, setSession] = useState<Session | null>(savedSession),
    [page, setPage] = useState("dashboard"),
    [data, setData] = useState<Record<string, Row[]>>({
      users: [],
      sites: [],
      checkpoints: [],
      routes: [],
      assignments: [],
      tours: [],
      plans: [],
    }),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(false),
    [updated, setUpdated] = useState(""),
    [modal, setModal] = useState<string | null>(null),
    [selected, setSelected] = useState<Row | null>(null),
    [assignmentPlan, setAssignmentPlan] = useState<Row | null>(null),
    [query, setQuery] = useState(""),
    [saving, setSaving] = useState(false),
    [formError, setFormError] = useState(""),
    [routeSite, setRouteSite] = useState(""),
    [routePoints, setRoutePoints] = useState<string[]>([]),
    [startPoint, setStartPoint] = useState(""),
    [endPoint, setEndPoint] = useState("");
  const load = async () => {
    if (!session) return;
    setLoading(true);
    try {
      const names = [
        "users",
        "sites",
        "checkpoints",
        "routes",
        "assignments",
        "tours",
        "plans",
      ];
      const results = await Promise.all(
        names.map((n) => api("/admin/" + n, session.token)),
      );
      setData(Object.fromEntries(names.map((n, i) => [n, results[i]])));
      setUpdated(new Date().toISOString());
      setError("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    if (!session) return;
    load();
    const timer = setInterval(load, 30000);
    return () => clearInterval(timer);
  }, [session]);
  if (!session)
    return (
      <Login
        onLogin={(s) => {
          sessionStorage.setItem("nab.session", JSON.stringify(s));
          setSession(s);
        }}
      />
    );
  const { tours, assignments, users, checkpoints, routes, sites, plans } = data;
  const active = tours.filter((t) => t.status === "ACTIVE"),
    finished = tours.filter((t) => t.status === "FINISHED"),
    incidents = tours.flatMap((t) =>
      t.events
        .filter((e: Row) => e.event_type === "INCIDENT")
        .map((e: Row) => ({ ...e, guard_name: t.guard_name, tour: t })),
    ),
    pending = assignments.filter(
      (a) => !tours.some((t) => t.assignment_id === a.id),
    ),
    overdue = pending.filter((a) => new Date(a.ends_at) < new Date());
  const visited = (t: Row) =>
    t.snapshot.routePolicy === "ORDERED_V1"
      ? t.events.filter((e: Row) => e.event_type === "VISIT").length
      : new Set(
          t.events
            .filter((e: Row) => e.event_type === "VISIT")
            .map((e: Row) => e.payload.data.checkpointId),
        ).size;
  const openCreate = () => {
    setModal(page);
    setFormError("");
    setRouteSite(sites[0]?.id || "");
    setRoutePoints([]);
    setStartPoint("");
    setEndPoint("");
  };
  const close = () => {
    setModal(null);
    setSelected(null);
    setFormError("");
  };
  const save = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setSaving(true);
    setFormError("");
    let values: Row = Object.fromEntries(new FormData(e.currentTarget));
    let endpoint = "/admin/" + modal;
    try {
      if (modal === "routes") {
        if (!startPoint || !endPoint)
          throw new Error("نقطه شروع و پایان را انتخاب کنید");
        values = {
          ...values,
          checkpointIds: [
            startPoint,
            ...routePoints.filter((id) => id !== startPoint && id !== endPoint),
            endPoint,
          ],
          expectedMinutes: Number(values.expectedMinutes),
        };
      }
      if (modal === "plans") {
        values = planPayload(values);
        if (values.sourcePlanId) {
          endpoint = "/admin/plans/" + values.sourcePlanId + "/repeat";
          values = {
            label: values.label,
            startsAt: values.startsAt,
            endsAt: values.endsAt,
          };
        }
      }
      if (modal === "checkpoints")
        values = {
          ...values,
          latitude: values.latitude === "" ? null : Number(values.latitude),
          longitude: values.longitude === "" ? null : Number(values.longitude),
        };
      if (modal === "assignments")
        values = {
          ...values,
          startsAt: new Date(values.startsAt).toISOString(),
          endsAt: new Date(values.endsAt).toISOString(),
        };
      await api(endpoint, session.token, values);
      close();
      await load();
    } catch (e) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };
  const field = (
    label: string,
    name: string,
    type = "text",
    required = true,
  ) => (
    <label>
      {label}
      <input
        name={name}
        type={type}
        required={required}
        step={type === "number" ? "any" : undefined}
      />
    </label>
  );
  const choice = (label: string, name: string, rows: Row[], key = "name") => (
    <label>
      {label}
      <select name={name} required defaultValue="">
        <option value="" disabled>
          انتخاب کنید
        </option>
        {rows.map((r) => (
          <option key={r.id} value={r.id}>
            {r[key]}
          </option>
        ))}
      </select>
    </label>
  );
  const filtered = (rows: Row[]) =>
    rows.filter((r) =>
      JSON.stringify(r).toLowerCase().includes(query.toLowerCase()),
    );
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <ShieldCheck size={32} />
          <span>
            ناب<small>نظم آفرینان بیدار</small>
          </span>
        </div>
        <div className="workspace">
          <span className="workspace-icon">
            <Building2 size={19} />
          </span>
          <div>
            مرکز کنترل عملیات<small>فضای مدیریت</small>
          </div>
          <ChevronLeft size={16} />
        </div>
        <small className="nav-label">میز کار</small>
        <nav>
          {nav.map(([id, Icon]) => (
            <button
              className={page === id ? "active" : ""}
              key={id}
              onClick={() => {
                setPage(id);
                setAssignmentPlan(null);
                setQuery("");
              }}
            >
              <Icon size={19} />
              <span>{titles[id]}</span>
              {id === "tours" && active.length > 0 && (
                <b>{fa(active.length)}</b>
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="offline-note">
            <Radio size={19} />
            <p>
              همراه گشت، حتی آفلاین
              <small>اطلاعات پس از اتصال دریافت می‌شوند.</small>
            </p>
          </div>
          <div className="profile">
            <span className="avatar">{session.user.name.slice(0, 1)}</span>
            <div>
              {session.user.name}
              <small>
                {session.user.role === "ADMIN" ? "مدیر سامانه" : "سرپرست"}
              </small>
            </div>
            <button
              aria-label="خروج"
              onClick={() => {
                api("/auth/logout", session.token, {}).catch(() => {});
                sessionStorage.removeItem("nab.session");
                setSession(null);
              }}
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div className="breadcrumb">
            مرکز کنترل <ChevronLeft size={14} />
            <strong>{titles[page]}</strong>
          </div>
          <div className="top-meta">
            <span className="live-dot" /> اتصال به مرکز{" "}
            <span className="divider" />
            {new Date().toLocaleDateString("fa-IR", {
              weekday: "long",
              month: "long",
              day: "numeric",
            })}
          </div>
        </header>
        <div className="content">
          <div className="page-heading">
            <div>
              <span className="eyebrow">NAB / OPERATIONS</span>
              <h1>{titles[page]}</h1>
              <p className="muted">
                {page === "dashboard"
                  ? "نبض عملیات نگهبانی را از اینجا دنبال کنید."
                  : "مدیریت، بررسی و پیگیری اطلاعات پروژه‌های شما"}
              </p>
            </div>
            <div className="actions">
              <button className="secondary" onClick={load} disabled={loading}>
                <RefreshCw size={16} className={loading ? "spin" : ""} />
                <span>{loading ? "دریافت…" : "به‌روزرسانی"}</span>
              </button>
              {page === "dashboard" || page === "tours" ? (
                <button className="primary" onClick={() => setPage("reports")}>
                  <Download size={17} /> دریافت گزارش
                </button>
              ) : page === "reports" ||
                (page === "users" && session.user.role !== "ADMIN") ? null : (
                <button className="primary" onClick={openCreate}>
                  <Plus size={18} /> افزودن{" "}
                  {page === "checkpoints"
                    ? "نقطه"
                    : page === "plans"
                      ? "برنامه"
                      : page === "assignments"
                        ? "مأموریت"
                        : page === "users"
                          ? "کاربر"
                          : page === "routes"
                            ? "مسیر"
                            : "محل"}
                </button>
              )}
            </div>
          </div>
          {error && <div className="error banner">{error}</div>}
          {page === "dashboard" && (
            <>
              <div className="stats">
                {[
                  {
                    title: "گشت‌های در حال اجرا",
                    n: active.length,
                    icon: Activity,
                    note: "بر اساس آخرین اطلاعات دریافت‌شده",
                    tone: "green",
                  },
                  {
                    title: "گشت‌های تکمیل‌شده",
                    n: finished.length,
                    icon: ShieldCheck,
                    note: "پایان گشت ثبت شده است",
                    tone: "blue",
                  },
                  {
                    title: "مأموریت‌های انجام‌نشده",
                    n: overdue.length,
                    icon: Clock,
                    note: "ممکن است دستگاه آفلاین باشد",
                    tone: "amber",
                  },
                  {
                    title: "گزارش‌های حادثه",
                    n: incidents.length,
                    icon: AlertTriangle,
                    note: "گزارش‌های ارسالی نگهبان‌ها",
                    tone: "red",
                  },
                ].map((c) => (
                  <section className="stat" key={c.title}>
                    <div>
                      <span>{c.title}</span>
                      <span className={"stat-icon " + c.tone}>
                        <c.icon size={19} />
                      </span>
                    </div>
                    <strong>{fa(c.n)}</strong>
                    <small>{c.note}</small>
                  </section>
                ))}
              </div>
              <div className="dashboard-grid">
                <section className="panel map-panel">
                  <div className="panel-heading">
                    <div>
                      <h2>موقعیت گشت‌ها</h2>
                      <small>
                        آخرین موقعیت دریافت‌شده؛ نمایش زنده نیازمند اتصال نگهبان
                        است.
                      </small>
                    </div>
                    <Badge tone="green">
                      <span className="live-dot" /> {fa(active.length)} گشت فعال
                    </Badge>
                  </div>
                  <PatrolMap tours={active} checkpoints={checkpoints} />
                  <div className="map-footer">
                    <span>
                      <i /> مسیر ثبت‌شده
                    </span>
                    <span>آخرین دریافت: {updated ? date(updated) : "—"}</span>
                  </div>
                </section>
                <section className="panel activity-panel">
                  <div className="panel-heading">
                    <h2>آخرین رویدادها</h2>
                    <Activity size={18} />
                  </div>
                  <div className="activity-list">
                    {tours
                      .flatMap((t) =>
                        t.events
                          .filter((e: Row) => e.event_type !== "LOCATION")
                          .map((e: Row) => ({ ...e, tour: t })),
                      )
                      .sort((a, b) =>
                        b.received_at.localeCompare(a.received_at),
                      )
                      .slice(0, 6)
                      .map((e) => (
                        <button
                          className="activity-item"
                          key={e.id}
                          onClick={() => {
                            setSelected(e.tour);
                            setModal("detail");
                          }}
                        >
                          <span
                            className={
                              "activity-icon " +
                              (e.event_type === "INCIDENT" ? "red" : "green")
                            }
                          >
                            {e.event_type === "INCIDENT" ? (
                              <AlertTriangle size={16} />
                            ) : (
                              <Check size={16} />
                            )}
                          </span>
                          <div>
                            <strong>{eventNames[e.event_type]}</strong>
                            <p>
                              {e.tour.guard_name} ·{" "}
                              {e.payload.data.checkpointName || e.tour.label}
                            </p>
                            <small>{date(e.captured_at)}</small>
                          </div>
                        </button>
                      ))}
                    {!tours.length && (
                      <Empty text="منتظر اولین گشت شما هستیم" />
                    )}
                  </div>
                  <button
                    className="panel-link"
                    onClick={() => setPage("tours")}
                  >
                    مشاهده همه گزارش‌ها <ArrowUpLeft size={16} />
                  </button>
                </section>
              </div>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>برنامه گشت‌ها</h2>
                    <small>مأموریت‌ها و میزان پیشرفت ثبت‌شده</small>
                  </div>
                  <button
                    className="textbtn"
                    onClick={() => setPage("assignments")}
                  >
                    مدیریت مأموریت‌ها <ArrowUpLeft size={16} />
                  </button>
                </div>
                {renderAssignments(assignments.slice(0, 5))}
              </section>
            </>
          )}
          {page === "reports" && (
            <Reports
              token={session.token}
              users={users}
              sites={sites}
              routes={routes}
              assignments={assignments}
            />
          )}
          {page !== "dashboard" && page !== "reports" && (
            <>
              <div className="list-toolbar">
                <div className="search">
                  <Search size={18} />
                  <input
                    placeholder="جست‌وجو در این بخش…"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                  />
                </div>
                <span className="muted">
                  {fa(
                    filtered(
                      page === "assignments"
                        ? assignments.filter(
                            (a) =>
                              !assignmentPlan ||
                              a.plan_id === assignmentPlan.id,
                          )
                        : data[page] || [],
                    ).length,
                  )}{" "}
                  مورد
                </span>
                {page === "checkpoints" && (
                  <button
                    className="secondary"
                    onClick={() => setModal("qr-all")}
                  >
                    <Printer size={16} /> چاپ همه QRها
                  </button>
                )}
              </div>
              {page === "assignments" && assignmentPlan && (
                <div className="info-note">
                  نوبت‌های برنامه «{assignmentPlan.label}» ·{" "}
                  {date(assignmentPlan.starts_at)} تا{" "}
                  {date(assignmentPlan.ends_at)}{" "}
                  <button
                    className="textbtn"
                    onClick={() => setAssignmentPlan(null)}
                  >
                    نمایش همه برنامه‌ها
                  </button>
                </div>
              )}
              <section className="panel list-panel">
                {page === "plans" && (
                  <PlansList
                    plans={filtered(plans)}
                    repeat={(p) => {
                      setSelected(p);
                      setFormError("");
                      setModal("plans");
                    }}
                    inspect={(p) => {
                      setPage("assignments");
                      setAssignmentPlan(p);
                      setQuery("");
                    }}
                  />
                )}
                {page === "assignments" &&
                  renderAssignments(
                    filtered(
                      assignments.filter(
                        (a) =>
                          !assignmentPlan || a.plan_id === assignmentPlan.id,
                      ),
                    ),
                  )}
                {page === "tours" && (
                  <>
                    <div className="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th>نگهبان / مأموریت</th>
                            <th>شروع / پایان</th>
                            <th>پیشرفت</th>
                            <th>وضعیت</th>
                            <th />
                          </tr>
                        </thead>
                        <tbody>
                          {filtered(tours).map((t) => (
                            <tr key={t.id}>
                              <td>
                                <strong>{t.guard_name}</strong>
                                <small>{t.label}</small>
                              </td>
                              <td>
                                {date(t.started_at)}
                                <small>{date(t.finished_at)}</small>
                              </td>
                              <td>{progress(t)}</td>
                              <td>
                                <Badge
                                  tone={
                                    t.status === "ACTIVE"
                                      ? "green"
                                      : t.status === "ABORTED"
                                        ? "red"
                                        : "blue"
                                  }
                                >
                                  {statusLabel(t)}
                                </Badge>
                              </td>
                              <td>
                                <button
                                  className="textbtn"
                                  onClick={() => {
                                    setSelected(t);
                                    setModal("detail");
                                  }}
                                >
                                  <Eye size={17} /> جزئیات
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {!tours.length && <Empty />}
                    <div className="info-note">
                      گشت آفلاین پس از همگام‌سازی در این فهرست دیده می‌شود. نقاط
                      ثبت‌نشده در گزارش هر گشت مشخص‌اند.
                    </div>
                  </>
                )}
                {page === "checkpoints" && (
                  <div className="point-grid">
                    {filtered(checkpoints).map((p) => (
                      <article className="point-card" key={p.id}>
                        <span className="point-icon">
                          <MapPin size={23} />
                        </span>
                        <h3>{p.name}</h3>
                        <p className="muted">{p.site_name}</p>
                        <small>
                          {p.latitude == null
                            ? "بدون مختصات مرجع"
                            : `${p.latitude.toFixed(5)} , ${p.longitude.toFixed(5)}`}
                        </small>
                        <button
                          className="secondary"
                          onClick={() => {
                            setSelected(p);
                            setModal("qr");
                          }}
                        >
                          <QrCode size={17} /> نمایش و چاپ QR
                        </button>
                      </article>
                    ))}
                    {!checkpoints.length && <Empty />}
                  </div>
                )}
                {page === "routes" && (
                  <div className="point-grid">
                    {filtered(routes).map((r) => (
                      <article className="point-card route-card" key={r.id}>
                        <span className="point-icon">
                          <Route size={24} />
                        </span>
                        <h3>{r.name}</h3>
                        <p className="muted">
                          {r.site_name} · {fa(r.expected_minutes)} دقیقه
                        </p>
                        <ol>
                          {r.checkpointIds.map((id: string, index: number) => (
                            <li key={index}>
                              {index === 0
                                ? "شروع: "
                                : index === r.checkpointIds.length - 1
                                  ? "پایان: "
                                  : ""}
                              {checkpoints.find((p) => p.id === id)?.name || id}
                            </li>
                          ))}
                        </ol>
                        <Badge>{fa(r.checkpointIds.length)} نقطه کنترل</Badge>
                      </article>
                    ))}
                    {!routes.length && <Empty />}
                  </div>
                )}
                {page === "users" && (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>نام</th>
                          <th>نام کاربری</th>
                          <th>نقش</th>
                          <th>وضعیت</th>
                          <th />
                        </tr>
                      </thead>
                      <tbody>
                        {filtered(users).map((u) => (
                          <tr key={u.id}>
                            <td>
                              <span className="table-person">
                                <span className="avatar pale">
                                  {u.name.slice(0, 1)}
                                </span>
                                {u.name}
                              </span>
                            </td>
                            <td dir="ltr">{u.username}</td>
                            <td>
                              {
                                (
                                  {
                                    ADMIN: "مدیر",
                                    SUPERVISOR: "سرپرست",
                                    GUARD: "نگهبان",
                                  } as Row
                                )[u.role]
                              }
                            </td>
                            <td>
                              <Badge tone={u.active ? "green" : "neutral"}>
                                {u.active ? "فعال" : "غیرفعال"}
                              </Badge>
                            </td>
                            <td>
                              {session.user.role === "ADMIN" &&
                                u.id !== session.user.id && (
                                  <button
                                    className="textbtn"
                                    onClick={async () => {
                                      try {
                                        await api(
                                          "/admin/users/" + u.id + "/active",
                                          session.token,
                                          { active: !u.active },
                                        );
                                        await load();
                                      } catch (e) {
                                        setError((e as Error).message);
                                      }
                                    }}
                                  >
                                    {u.active ? "غیرفعال‌کردن" : "فعال‌کردن"}
                                  </button>
                                )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {page === "sites" && (
                  <div className="point-grid">
                    {filtered(sites).map((s) => (
                      <article className="point-card" key={s.id}>
                        <Building2 size={28} />
                        <h3>{s.name}</h3>
                        <p>{s.address || "آدرس ثبت نشده"}</p>
                        <Badge>
                          {fa(
                            checkpoints.filter((p) => p.site_id === s.id)
                              .length,
                          )}{" "}
                          نقطه کنترل
                        </Badge>
                      </article>
                    ))}
                    {!sites.length && <Empty />}
                  </div>
                )}
              </section>
            </>
          )}
          <footer className="main-footer">
            <span>ناب · مدیریت گشت نگهبانی</span>
            <span>ثبت روی گوشی، تأیید در مرکز کنترل</span>
          </footer>
        </div>
      </main>
      {modal &&
        [
          "users",
          "sites",
          "checkpoints",
          "routes",
          "assignments",
          "plans",
        ].includes(modal) && (
          <Modal
            title={
              modal === "plans" && selected
                ? "استفاده از الگو برای هفته بعد"
                : "افزودن " + titles[modal]
            }
            close={close}
            wide={modal === "plans"}
          >
            <form className="create-form" onSubmit={save}>
              {modal === "plans" && (
                <PlanFields
                  users={users}
                  routes={routes}
                  token={session.token}
                  source={selected}
                />
              )}
              {modal === "users" && (
                <>
                  {field("نام و نام خانوادگی", "name")}
                  {field("نام کاربری", "username")}
                  {field("رمز عبور (حداقل ۸ نویسه)", "password", "password")}
                  <label>
                    نقش
                    <select name="role">
                      <option value="GUARD">نگهبان</option>
                      <option value="SUPERVISOR">سرپرست</option>
                      <option value="ADMIN">مدیر</option>
                    </select>
                  </label>
                </>
              )}
              {modal === "sites" && (
                <>
                  {field("نام محل پروژه", "name")}
                  {field("آدرس", "address", "text", false)}
                </>
              )}
              {modal === "checkpoints" && (
                <>
                  {choice("محل پروژه", "siteId", sites)}
                  {field("نام نقطه", "name")}
                  <p className="info-note">
                    مختصات مرجع اختیاری است و محدودیتی برای ثبت نگهبان ایجاد
                    نمی‌کند.
                  </p>
                  <div className="form-row">
                    {field("عرض جغرافیایی", "latitude", "number", false)}
                    {field("طول جغرافیایی", "longitude", "number", false)}
                  </div>
                </>
              )}
              {modal === "routes" && (
                <>
                  {field("نام مسیر", "name")}
                  <label>
                    محل پروژه
                    <select
                      name="siteId"
                      value={routeSite}
                      onChange={(e) => {
                        setRouteSite(e.target.value);
                        setRoutePoints([]);
                        setStartPoint("");
                        setEndPoint("");
                      }}
                      required
                    >
                      {sites.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  {field(
                    "مدت مورد انتظار (دقیقه)",
                    "expectedMinutes",
                    "number",
                  )}
                  <div className="form-row">
                    {[
                      ["نقطه شروع", startPoint, setStartPoint],
                      ["نقطه پایان", endPoint, setEndPoint],
                    ].map(([label, value, setter]) => (
                      <label key={label as string}>
                        {label as string}
                        <select
                          required
                          value={value as string}
                          onChange={(e) => {
                            (setter as (v: string) => void)(e.target.value);
                            setRoutePoints((prev) =>
                              prev.filter((id) => id !== e.target.value),
                            );
                          }}
                        >
                          <option value="" disabled>
                            انتخاب کنید
                          </option>
                          {checkpoints
                            .filter((p) => p.site_id === routeSite)
                            .map((p) => (
                              <option key={p.id} value={p.id}>
                                {p.name}
                              </option>
                            ))}
                        </select>
                      </label>
                    ))}
                  </div>
                  <label>نقاط میانی به ترتیب انتخاب (بین شروع و پایان)</label>
                  <div className="point-picker">
                    {checkpoints
                      .filter(
                        (p) =>
                          p.site_id === routeSite &&
                          p.id !== startPoint &&
                          p.id !== endPoint,
                      )
                      .map((p) => (
                        <button
                          type="button"
                          key={p.id}
                          className={routePoints.includes(p.id) ? "chosen" : ""}
                          onClick={() =>
                            setRoutePoints((prev) =>
                              prev.includes(p.id)
                                ? prev.filter((id) => id !== p.id)
                                : [...prev, p.id],
                            )
                          }
                        >
                          <span>
                            {routePoints.includes(p.id)
                              ? fa(routePoints.indexOf(p.id) + 1)
                              : "+"}
                          </span>
                          {p.name}
                        </button>
                      ))}
                  </div>
                  <small className="muted">
                    ترتیب اجباری است: ابتدا نقطه شروع، سپس نقاط میانی و در پایان
                    نقطه پایان. شروع و پایان می‌توانند یک محل باشند، به شرط
                    داشتن نقطه میانی.
                  </small>
                </>
              )}
              {modal === "assignments" && (
                <>
                  {field("عنوان مأموریت", "label")}
                  {choice(
                    "نگهبان",
                    "guardId",
                    users.filter((u) => u.role === "GUARD" && u.active),
                  )}
                  {choice("مسیر", "routeId", routes)}
                  {field("شروع شیفت", "startsAt", "datetime-local")}
                  {field("پایان شیفت", "endsAt", "datetime-local")}
                  <p className="info-note">
                    هر مأموریت برای یک گشت است. برای چند نوبت، چند مأموریت تعریف
                    کنید. برنامه پس از دریافت در اپ، آفلاین قابل اجراست.
                  </p>
                </>
              )}
              {formError && <p className="error">{formError}</p>}
              <div className="modal-actions">
                <button className="primary" disabled={saving}>
                  {saving ? "در حال ذخیره…" : "ثبت اطلاعات"}
                  <Check size={17} />
                </button>
                <button className="secondary" type="button" onClick={close}>
                  انصراف
                </button>
              </div>
            </form>
          </Modal>
        )}
      {modal?.startsWith("qr") && (
        <Modal title="برچسب نقاط کنترل" close={close} wide={modal === "qr-all"}>
          <div className="print-area">
            {(modal === "qr" ? (selected ? [selected] : []) : checkpoints).map(
              (p) => (
                <QRCard key={p.id} point={p} />
              ),
            )}
          </div>
          <div className="modal-actions no-print">
            <button className="primary" onClick={() => window.print()}>
              <Printer size={17} /> چاپ برچسب
            </button>
          </div>
        </Modal>
      )}
      {modal === "detail" && selected && (
        <Modal title={"گزارش " + selected.label} close={close} wide>
          <div className="detail-meta">
            <span>{selected.guard_name}</span>
            <Badge
              tone={
                selected.status === "ACTIVE"
                  ? "green"
                  : selected.status === "ABORTED"
                    ? "red"
                    : "blue"
              }
            >
              {statusLabel(selected)}
            </Badge>
            <span>{date(selected.started_at)}</span>
          </div>
          <div className="detail-points">
            <Badge tone={startDelay(selected) > 0 ? "amber" : "green"}>
              تأخیر شروع: {fa(startDelay(selected))} دقیقه
            </Badge>
            <Badge>
              مدت مورد انتظار: {fa(selected.snapshot.expectedMinutes)} دقیقه
            </Badge>
            {duration(selected) !== null && (
              <Badge>
                مدت طبق ساعت گوشی:{" "}
                {duration(selected)! < 0
                  ? "اختلاف ساعت دستگاه"
                  : fa(duration(selected)!) + " دقیقه"}
              </Badge>
            )}
          </div>
          <PatrolMap
            tours={[selected]}
            checkpoints={selected.snapshot.checkpoints.map((p: Row) => ({
              ...p,
              site_name: selected.snapshot.siteName,
            }))}
          />
          <div className="detail-points">
            {selected.snapshot.checkpoints.map((p: Row, index: number) => (
              <Badge
                key={index}
                tone={stopVisited(selected, p, index) ? "green" : "amber"}
              >
                {fa(index + 1)}. {p.name}
                {index === 0
                  ? " (شروع)"
                  : index === selected.snapshot.checkpoints.length - 1
                    ? " (پایان)"
                    : ""}{" "}
                · {stopVisited(selected, p, index) ? "ثبت شده" : "ثبت نشده"}
              </Badge>
            ))}
          </div>
          <div className="event-timeline">
            {selected.events.map((e: Row) => (
              <article key={e.id}>
                <span className="timeline-number">{fa(e.sequence_no)}</span>
                <div>
                  <strong>{eventNames[e.event_type]}</strong>
                  <small>
                    ثبت روی دستگاه: {date(e.captured_at)} · دریافت مرکز:{" "}
                    {date(e.received_at)}
                  </small>
                  {e.payload.data.checkpointName && (
                    <p>
                      {selected.snapshot.checkpoints.find(
                        (p: Row) => p.id === e.payload.data.checkpointId,
                      )?.name ?? e.payload.data.checkpointName}
                    </p>
                  )}
                  {e.payload.data.note && (
                    <p className="incident-note">{e.payload.data.note}</p>
                  )}
                  {e.payload.data.severity && (
                    <Badge
                      tone={
                        e.payload.data.severity === "HIGH" ? "red" : "amber"
                      }
                    >
                      اهمیت:{" "}
                      {
                        ({ LOW: "کم", MEDIUM: "متوسط", HIGH: "زیاد" } as Row)[
                          e.payload.data.severity
                        ]
                      }
                    </Badge>
                  )}
                  {e.payload.data.location ? (
                    <small dir="ltr">
                      GPS: {e.payload.data.location.latitude},{" "}
                      {e.payload.data.location.longitude} · ±
                      {Math.round(e.payload.data.location.accuracy || 0)}m ·{" "}
                      {date(e.payload.data.location.fixAt)}
                    </small>
                  ) : (
                    <small>موقعیت در دسترس نبوده؛ ثبت پذیرفته شده است.</small>
                  )}
                  {e.photo_id && (
                    <Photo id={e.photo_id} token={session.token} />
                  )}
                  {e.payload.data.hasPhoto && !e.photo_id && (
                    <small>تصویر هنوز از گوشی دریافت نشده است.</small>
                  )}
                </div>
              </article>
            ))}
          </div>
          <button className="secondary no-print" onClick={() => window.print()}>
            <Printer size={16} /> چاپ / ذخیره PDF
          </button>
        </Modal>
      )}
    </div>
  );
  function progress(t: Row) {
    const count = visited(t),
      total = t.snapshot.checkpoints.length;
    return (
      <div className="progress-cell">
        <span>
          {fa(count)} از {fa(total)} نقطه
        </span>
        <div className="progress-track">
          <i style={{ width: `${total ? (count / total) * 100 : 0}%` }} />
        </div>
      </div>
    );
  }
  function renderAssignments(rows: Row[]) {
    return (
      <>
        {rows.length ? (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>مأموریت</th>
                  <th>نگهبان</th>
                  <th>زمان شیفت</th>
                  <th>پیشرفت</th>
                  <th>وضعیت</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((a) => {
                  const t = tours.find((t) => t.assignment_id === a.id);
                  return (
                    <tr
                      key={a.id}
                      onClick={() => {
                        if (t) {
                          setSelected(t);
                          setModal("detail");
                        }
                      }}
                      className={t ? "clickable" : ""}
                    >
                      <td>
                        <strong>{a.label}</strong>
                        <small>{a.route_name}</small>
                      </td>
                      <td>{a.guard_name}</td>
                      <td>
                        {date(a.starts_at)}
                        <small>تا {date(a.ends_at)}</small>
                      </td>
                      <td>
                        {t ? (
                          progress(t)
                        ) : (
                          <span className="muted">منتظر شروع گشت</span>
                        )}
                      </td>
                      <td>
                        <Badge
                          tone={
                            t
                              ? t.status === "ACTIVE"
                                ? "green"
                                : t.status === "ABORTED"
                                  ? "red"
                                  : "blue"
                              : new Date(a.ends_at) < new Date()
                                ? "amber"
                                : "neutral"
                          }
                        >
                          {t
                            ? statusLabel(t)
                            : new Date(a.ends_at) < new Date()
                              ? "دریافت نشده"
                              : "برنامه‌ریزی‌شده"}
                        </Badge>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty />
        )}
      </>
    );
  }
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
