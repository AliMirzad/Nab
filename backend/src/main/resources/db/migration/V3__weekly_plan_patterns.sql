ALTER TABLE patrol_plan ADD COLUMN schedule_mode VARCHAR(20) NOT NULL DEFAULT 'INTERVAL';
ALTER TABLE patrol_plan ADD COLUMN weekly_windows TEXT;
ALTER TABLE patrol_plan ADD COLUMN cycle_anchor VARCHAR(40);
ALTER TABLE patrol_plan ADD COLUMN template_root_id VARCHAR(36) REFERENCES patrol_plan(id);
CREATE UNIQUE INDEX plan_repeat_range_idx ON patrol_plan(template_root_id, starts_at, ends_at);
