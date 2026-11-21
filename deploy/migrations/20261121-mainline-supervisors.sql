BEGIN;
SET LOCAL ROLE fieldroutes_owner;
SET LOCAL search_path TO fieldroutes;

ALTER TABLE mainline_projects ADD COLUMN IF NOT EXISTS supervisor_person_id bigint REFERENCES people(id);
ALTER TABLE mainline_daily_members ADD COLUMN IF NOT EXISTS supervisor_only INTEGER NOT NULL DEFAULT 0 CHECK(supervisor_only IN (0,1));
CREATE INDEX IF NOT EXISTS mainline_projects_by_supervisor ON mainline_projects(supervisor_person_id,id);

COMMIT;
