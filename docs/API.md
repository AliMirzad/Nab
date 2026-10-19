# Nab API v1

Base path: `/api`. JSON, UTF-8. All routes except health/login require `Authorization: Bearer <token>`. IDs are UUIDs.

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | Public | Process health |
| POST | `/auth/login` | Public | `{username,password}` -> `{token,expiresAt,user}` |
| GET | `/auth/me` | Any | Current actor |
| POST | `/auth/logout` | Any | Revoke current session |
| GET/POST | `/admin/users` | Admin creates; supervisor reads | Users, no password hashes returned |
| POST | `/admin/users/{id}/active` | Admin | `{active:boolean}`, revokes target sessions |
| GET/POST | `/admin/sites` | Admin/supervisor | `{name,address}` |
| GET/POST | `/admin/checkpoints` | Admin/supervisor | `{siteId,name,latitude?,longitude?}`, generates QR |
| GET/POST | `/admin/routes` | Admin/supervisor | `{siteId,name,checkpointIds,expectedMinutes}` |
| GET/POST | `/admin/assignments` | Admin/supervisor | `{guardId,routeId,label,startsAt,endsAt}` |
| GET/POST | `/admin/plans` | Admin/supervisor | Interval or fixed weekly windows; see scheduling below; returns `{id,roundCount,shiftCount}` |
| POST | `/admin/plans/preview` | Admin/supervisor | Same spec as creation; read-only authoritative calendar preview |
| POST | `/admin/plans/{id}/repeat` | Admin/supervisor | `{label,startsAt,endsAt}`; reuse pattern with preserved cycle phase |
| GET | `/admin/tours` | Admin/supervisor | Snapshots, ordered events, photo references |
| POST | `/admin/reports` | Admin/supervisor | Filtered immutable report preview and export ID; see below |
| GET | `/admin/reports/{id}/xlsx` | Creating admin/supervisor | Actual OOXML workbook, authenticated attachment |
| GET | `/admin/reports/{id}/pdf` | Creating admin/supervisor | Paginated Persian PDF, authenticated attachment |
| GET | `/admin/audit` | Admin | Latest 200 administrative actions |
| GET | `/guard/bootstrap` | Guard | Assigned snapshots and existing server-tour state |
| POST | `/guard/sync` | Guard | Transactional event receipts |
| POST | `/photos/{eventId}` | Owning guard | Multipart `file`, PNG/JPEG max 6 MiB, idempotent per incident |
| GET | `/photos/{photoId}` | Admin/supervisor/owning guard | Authenticated image bytes |

Management list fields use snake_case; mobile bootstrap/sync uses camelCase. Each assignment is one immutable one-time execution. Reports keep the original route snapshot.

## Weekly scheduling

Example `POST /api/admin/plans` or `/api/admin/plans/preview`:

```json
{
  "guardId": "guard-uuid", "routeId": "route-uuid", "label": "Weekly workshop",
  "startsAt": "2026-11-02T04:30:00Z", "endsAt": "2026-11-09T04:30:00Z",
  "workHours": 24, "restHours": 24,
  "intervalMinutes": 120, "windowMinutes": 45,
  "scheduleMode": "WEEKLY", "cycleAnchor": null,
  "weeklyWindows": [
    { "days": [1,2,3,4,5,6,7], "startTime": "02:00", "endTime": "04:00" },
    { "days": [1,2,3,4,5,6,7], "startTime": "06:00", "endTime": "08:00" }
  ]
}
```

`scheduleMode` defaults to `INTERVAL` for existing clients. INTERVAL uses the original interval/window policy. WEEKLY uses up to 32 window rules, each with ISO calendar weekdays (Monday=1, Sunday=7) and minute-precision `HH:mm` clock times in `Asia/Tehran`. `intervalMinutes`/`windowMinutes` retain their valid numeric values for compatibility but do not control WEEKLY windows. Each window represents one ordered execution of the whole selected route, not a different checkpoint per hour. End earlier than start means next-day end; equal times are invalid. A weekday is the local **start day** of the window. Overlap/duplicates are rejected, including Sunday-to-Monday and overnight overlaps. Window duration must be at least route expected duration and no longer than a work shift.

Work periods repeat from `cycleAnchor` (default `startsAt`) with `workHours + restHours`. The anchor cannot be after the requested range. Only complete windows inside one work shift and inside the requested range become assignments. Rest-day windows and windows crossing shift/range boundaries are skipped, never shortened or moved. All generated assignments carry the normal immutable ordered route snapshot and become available in guard bootstrap for offline execution.

Preview returns `{timeZone,scheduleMode,roundCount,shiftCount,skippedWindows,shifts,days}`. Each day contains local ISO `date`, `workMinutes`, `restMinutes`, clipped `work`/`rest` spans and `rounds` starting on that local day. Spans have `startsAt`/`endsAt` instants. A partial first/last day contains only the requested range. Preview is read-only and can show zero rounds; creation rejects zero-round plans. Preview/creation/repeat share the same deterministic scheduler. Limits remain 31 days and 1,000 generated rounds.

Repeat copies the source plan's guard, route, pattern and original cycle anchor, not its assignment IDs. It generates fresh assignments/snapshots without changing existing offline work. Repeating a seven-day 24/24 pattern must not restart the cycle: the example above creates 4 shifts/8 rounds; the contiguous next week creates 3 shifts/6 rounds. The initial plan and all its copies form one template lineage. A root-row database lock serializes repeat submissions; duplicate or overlapping ranges within that lineage return 409 without partial writes, including concurrent requests. Non-overlapping copies preserve the original anchor even when repeating a copy. Existing legacy plans can be repeated with their original interval mode. No unbounded background expansion is scheduled; only an explicitly selected range is materialized.

Plan list adds `site_name`, `schedule_mode`, `weeklyWindows` (decoded JSON), `cycle_anchor`, `template_root_id`; existing fields remain. Client calendar inputs are interpreted as Tehran wall-clock time independently of browser timezone.

## Reporting

Example `POST /api/admin/reports`:

```json
{
  "guardId": null,
  "siteId": null,
  "routeId": null,
  "assignmentId": null,
  "period": "MONTH",
  "year": 1405,
  "month": 7,
  "fromDate": null,
  "toDate": null,
  "status": "ALL",
  "includeEvents": true
}
```

ID filters are ANDed. Period: `ALL`, `MONTH` (Persian calendar year 1300–1500, month 1–12), or `RANGE` (`fromDate`/`toDate` as ISO Gregorian dates). The date basis is the **assignment's scheduled start in Asia/Tehran**, not upload time. Bounds are inclusive local midnight through exclusive next-day/next-month midnight. Timestamps are parsed as instants, never compared as varying-precision strings.

Status: `ALL|ACTIVE|FINISHED|ABORTED|PENDING|OVERDUE`. PENDING means no tour received and the scheduled window has not ended; OVERDUE means no tour received after its scheduled window. Neither proves the guard failed to perform while offline. Partial active/aborted tours stay distinct from finished tours. Ordered-route visit totals use stop indices so a circular start/end checkpoint counts twice when both stops were scanned.

Response: `id`, `generatedAt`, `periodLabel`, `timeZone`, `criteria`, `summary`, `guards`, `rows`, `includeEvents`. Rows include original assignment route/site names, scheduled and actual timestamps, visit/incident totals, delay/duration minutes and optional events. Missing duration/delay is null, not zero; an end before start yields null duration and `clockInvalid: true`. Device time is evidence, not certified time. Event records contain both captured and received time; missing GPS does not invalidate the report.

Download the two authenticated attachment endpoints using the returned ID. Both serialize that exact report snapshot even if sync changes the database afterward. Snapshots are private to the creating actor, expire after 15 minutes, and are bounded to 30 cached reports per server process. Expiry/eviction/restart returns `410 REPORT_EXPIRED`; generate a fresh preview. Another administrator's ID is `403`; guards cannot generate/download administrative reports. Downloads send `Cache-Control: no-store`; do not place bearer tokens in URLs.

Limit: 10,000 matching assignments and, if selected, 50,000 detail events. Exceeding limits returns `413 REPORT_TOO_LARGE` without truncation. Excel sheets: summary, assignments, optionally events; counts/date cells are typed and user text is never interpreted as a formula. PDF embeds the OFL-licensed Persian font and repeats table headers across pages. PDF event notes continue across pages instead of being truncated.

## Sync request

```json
{
  "deviceId": "stable-installation-uuid",
  "events": [{
    "id": "event-uuid",
    "tourId": "tour-uuid",
    "assignmentId": "assignment-uuid",
    "sequence": 1,
    "type": "START",
    "capturedAt": "2026-10-19T06:00:00.000Z",
    "data": {"location": null, "locationStatus": "UNAVAILABLE", "checkpointId": "first-checkpoint-uuid", "tagCode": "NAB:tag-uuid", "method": "QR", "stopIndex": 0}
  }]
}
```

Types: START, VISIT, LOCATION, INCIDENT, FINISH, ABORT. Sequence starts at 1 and increases by one per tour. START must be first. FINISH/ABORT close the tour; identical duplicate events still get receipts after closing. A batch is at most 100 events, all committed or all rolled back.

New assignments carry `routePolicy: ORDERED_V1`. START must contain the first checkpoint QR and `stopIndex: 0`. Mobile commits START and VISIT(0) atomically after scanning the start QR. Each VISIT must match the next snapshot stop and contain its zero-based `stopIndex`, `checkpointId`, `tagCode`, `method: "QR"`, optional `location` and display-only `checkpointName`. FINISH is only valid after every stop, including the end. A circular route can repeat the first physical checkpoint as the last stop; the last visit must have the final index, not zero. Intermediate duplicate points are not permitted. New routes need at least two stops (three for a loop).

ABORT requires a non-empty `note` (max 4000 chars), produces status ABORTED, never FINISHED. It remains visible as unsuccessful. Already-issued legacy snapshots keep their original behavior to preserve offline data; clients cannot downgrade server snapshots.

Plans generate immutable assignments within each work period and none during rest. Horizon: max 31 days / 1000 slots. A slot must fit completely inside work hours and the plan end. Window must be at least route expected duration and no greater than the interval or shift length. Seven days of 24h work / 24h rest, interval 120min and window 45min yields 48 rounds. Assignments are generated eagerly, not on a background scheduler, so the full plan can be downloaded for offline use.

INCIDENT data: `note` (1–4000 chars), `severity` (`LOW|MEDIUM|HIGH`), `hasPhoto`, optional `location`. Upload photo after event acknowledgement.

Location: `latitude`, `longitude`, `accuracy` (meters), `fixAt`, `source`, `isMocked`. Missing position is null. Staleness, low accuracy and device timestamps outside the scheduled window do not reject event evidence. Incomplete ordered routes cannot FINISH; they can ABORT with a reason. Malformed coordinates are invalid input, not a geofence policy.

Success: `{ "acceptedIds": ["..."], "serverTime": "..." }`. Mark only confirmed IDs after a successful response. Duplicate IDs must contain identical data and belong to the same actor/device. Differences, gaps, double starts, second-device continuation and new events after FINISH are rejected. Keep the outbox on all errors. 401 requires reauthentication; 409 requires visible reconciliation, never silent deletion.

Single organization. Supervisors manage all sites in this installation. Sessions expire after 30 days. Offline operations do not depend on token freshness. Existing-assignment transfer between phones is not supported.
