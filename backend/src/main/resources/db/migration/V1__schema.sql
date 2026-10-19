CREATE TABLE app_user (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE,
 name VARCHAR(160) NOT NULL, password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE app_session (
 token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
 expires_at VARCHAR(40) NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE TABLE site (id VARCHAR(36) PRIMARY KEY, name VARCHAR(160) NOT NULL, address VARCHAR(500) NOT NULL);
CREATE TABLE checkpoint (
 id VARCHAR(36) PRIMARY KEY, site_id VARCHAR(36) NOT NULL REFERENCES site(id), name VARCHAR(160) NOT NULL,
 tag_code VARCHAR(160) NOT NULL UNIQUE, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION
);
CREATE TABLE patrol_route (
 id VARCHAR(36) PRIMARY KEY, site_id VARCHAR(36) NOT NULL REFERENCES site(id), name VARCHAR(160) NOT NULL,
 checkpoint_ids TEXT NOT NULL, expected_minutes INTEGER NOT NULL
);
CREATE TABLE assignment (
 id VARCHAR(36) PRIMARY KEY, guard_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
 route_id VARCHAR(36) NOT NULL REFERENCES patrol_route(id), label VARCHAR(160) NOT NULL,
 starts_at VARCHAR(40) NOT NULL, ends_at VARCHAR(40) NOT NULL, snapshot TEXT NOT NULL
);
CREATE TABLE patrol_tour (
 id VARCHAR(36) PRIMARY KEY, assignment_id VARCHAR(36) NOT NULL UNIQUE REFERENCES assignment(id),
 guard_id VARCHAR(36) NOT NULL REFERENCES app_user(id), device_id VARCHAR(80) NOT NULL,
 status VARCHAR(20) NOT NULL, started_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40),
 last_sequence INTEGER NOT NULL, received_at VARCHAR(40) NOT NULL
);
CREATE TABLE patrol_event (
 id VARCHAR(36) PRIMARY KEY, tour_id VARCHAR(36) NOT NULL REFERENCES patrol_tour(id),
 sequence_no INTEGER NOT NULL, event_type VARCHAR(30) NOT NULL, captured_at VARCHAR(40) NOT NULL,
 received_at VARCHAR(40) NOT NULL, payload TEXT NOT NULL,
 CONSTRAINT event_tour_sequence UNIQUE(tour_id, sequence_no)
);
CREATE TABLE photo (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE REFERENCES patrol_event(id),
 guard_id VARCHAR(36) NOT NULL REFERENCES app_user(id), file_name VARCHAR(80) NOT NULL, content_type VARCHAR(30) NOT NULL
);
CREATE TABLE audit_log (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36), action VARCHAR(60) NOT NULL,
 entity_id VARCHAR(80), created_at VARCHAR(40) NOT NULL
);
CREATE INDEX events_tour_idx ON patrol_event(tour_id, sequence_no);
CREATE INDEX assignments_guard_idx ON assignment(guard_id);
