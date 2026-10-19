export type Row = Record<string, any>;
export type Session = {
  token: string;
  user: { id: string; name: string; username: string; role: string };
};
export function savedSession(): Session | null {
  try {
    return JSON.parse(sessionStorage.getItem("nab.session") || "null");
  } catch {
    return null;
  }
}
export async function api(
  path: string,
  token?: string,
  data?: unknown,
): Promise<any> {
  const response = await fetch("/api" + path, {
    method: data === undefined ? "GET" : "POST",
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(data !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: "ارتباط با سرور برقرار نشد" }));
    throw new Error(error.message || "انجام درخواست ممکن نشد");
  }
  return response.status === 204 ||
    response.headers.get("content-length") === "0"
    ? null
    : response.text().then((t) => (t ? JSON.parse(t) : null));
}
export const fa = (n: number) => n.toLocaleString("fa-IR");
export const date = (value: string | undefined) =>
  value
    ? new Date(value).toLocaleString("fa-IR", {
        dateStyle: "short",
        timeStyle: "short",
      })
    : "—";
export function csv(rows: unknown[][], name: string) {
  const escape = (v: unknown) => {
    let s = String(v ?? "");
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
    return '"' + s.replaceAll('"', '""') + '"';
  };
  const blob = new Blob(
    ["\uFEFF" + rows.map((r) => r.map(escape).join(",")).join("\r\n")],
    { type: "text/csv;charset=utf-8" },
  );
  const url = URL.createObjectURL(blob),
    link = document.createElement("a");
  link.href = url;
  link.download = name + ".csv";
  link.click();
  URL.revokeObjectURL(url);
}
