BEGIN;
SET LOCAL ROLE fieldroutes_owner;
SET LOCAL search_path TO fieldroutes;

ALTER TABLE mainline_daily_members ADD COLUMN IF NOT EXISTS crew_role TEXT NOT NULL DEFAULT ''
    CHECK (crew_role IN ('','Machine Operator','Locator','Trencher','Missile Man'));

COMMIT;
