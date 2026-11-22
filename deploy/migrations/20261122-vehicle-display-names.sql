BEGIN;
SET LOCAL ROLE fieldroutes_owner;
SET LOCAL search_path TO fieldroutes;
SELECT pg_advisory_xact_lock(1179799636, 1);

DO $upgrade$
BEGIN
    IF to_regclass('fieldroutes.vehicles_name') IS NULL THEN RETURN; END IF;
    DROP INDEX fieldroutes.vehicles_name;

    -- Reproduce only aliases previously generated from this exact vehicle's source profile.
    WITH profiles AS (
        SELECT vehicle_id, source_record_id, source_record::jsonb AS profile FROM vehicle_source_profiles
    ), fields AS (
        SELECT vehicle_id, source_record_id,
            CASE WHEN lower(trim(profile->>'company_code'))='n/a' THEN '' ELSE trim(profile->>'company_code') END AS code,
            CASE WHEN lower(trim(profile->>'brand'))='n/a' THEN '' ELSE trim(profile->>'brand') END AS brand,
            CASE WHEN lower(trim(profile->>'model'))='n/a' THEN '' ELSE trim(profile->>'model') END AS model
        FROM profiles
    ), names AS (
        SELECT *, coalesce(nullif(code,''),nullif(trim(brand || ' ' || model),''),'Vehicle') AS clean_name,
               count(*) OVER (PARTITION BY lower(code)) AS code_count FROM fields
    ), aliases AS (
        SELECT vehicle_id,clean_name,
               CASE WHEN code='' THEN clean_name || ' #' || source_record_id
                    ELSE code || ' (Record #' || source_record_id || ')' END AS old_name
        FROM names WHERE code='' OR code_count>1
    ), drops AS (
        UPDATE route_vehicles v SET name=a.clean_name FROM aliases a
        WHERE v.vehicle_id=a.vehicle_id AND v.name=a.old_name
    ), mainline AS (
        UPDATE mainline_daily_vehicles v SET name=a.clean_name FROM aliases a
        WHERE v.vehicle_id=a.vehicle_id AND v.name=a.old_name
    )
    UPDATE vehicles v SET name=a.clean_name FROM aliases a WHERE v.id=a.vehicle_id AND v.name=a.old_name;
END
$upgrade$;

COMMIT;
