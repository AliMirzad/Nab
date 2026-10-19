CREATE TABLE patrol_plan (
 id VARCHAR(36) PRIMARY KEY,
 guard_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
 route_id VARCHAR(36) NOT NULL REFERENCES patrol_route(id),
 label VARCHAR(160) NOT NULL,
 starts_at VARCHAR(40) NOT NULL, ends_at VARCHAR(40) NOT NULL,
 work_hours INTEGER NOT NULL, rest_hours INTEGER NOT NULL,
 interval_minutes INTEGER NOT NULL, window_minutes INTEGER NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
ALTER TABLE assignment ADD COLUMN plan_id VARCHAR(36) REFERENCES patrol_plan(id);
CREATE INDEX assignments_plan_idx ON assignment(plan_id);
