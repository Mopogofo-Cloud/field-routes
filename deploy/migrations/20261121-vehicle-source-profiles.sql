BEGIN;
SET LOCAL ROLE fieldroutes_owner;
SET LOCAL search_path TO fieldroutes;

CREATE TABLE IF NOT EXISTS vehicle_source_profiles (
    vehicle_id BIGINT PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
    source_record_id TEXT NOT NULL UNIQUE,
    source_record TEXT NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON vehicle_source_profiles TO fieldroutes_app;

COMMIT;
