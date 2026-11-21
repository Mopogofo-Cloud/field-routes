BEGIN;
SET LOCAL ROLE fieldroutes_owner;
SET LOCAL search_path TO fieldroutes;

ALTER TABLE mainline_dailies ADD COLUMN IF NOT EXISTS crew_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE mainline_daily_members ADD COLUMN IF NOT EXISTS removed INTEGER NOT NULL DEFAULT 0 CHECK(removed IN (0,1));

COMMIT;
