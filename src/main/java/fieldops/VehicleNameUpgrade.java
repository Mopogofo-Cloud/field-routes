package fieldops;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import org.sqlite.Function;
import static fieldops.StoreSql.*;

/** Upgrades the SQLite fleet schema and translates the same values in read-only backup imports. */
final class VehicleNameUpgrade {
    private VehicleNameUpgrade() {}
    private record Alias(String original,String clean) {}

    static boolean hasOldUniqueName(Connection db) throws SQLException {
        return one(db,"""
            SELECT 1 FROM pragma_index_list('vehicles') i JOIN pragma_index_info(i.name) c
            WHERE i.origin='u' AND c.name='name'
            """) != null;
    }

    private static boolean exists(Connection db,String table) throws SQLException {
        return one(db,"SELECT 1 FROM sqlite_schema WHERE name=? AND type='table'",table)!=null;
    }

    static void registerNameTranslation(Connection db) throws SQLException {
        var profiles = rows(db,"""
            SELECT vehicle_id,source_record_id,json_extract(source_record,'$.company_code') AS company_code,
                   json_extract(source_record,'$.brand') AS brand,json_extract(source_record,'$.model') AS model
            FROM vehicle_source_profiles
            """);
        var counts = new HashMap<String,Integer>();
        for (var p:profiles)
            counts.merge(FleetImport.operational(p,"company_code").toLowerCase(Locale.ROOT),1,Integer::sum);
        var aliases = new HashMap<Long,Alias>();
        for (var p:profiles) {
            String code=FleetImport.operational(p,"company_code"), sourceId=p.get("source_record_id").asText();
            String clean=FleetImport.displayName(p);
            String previous;
            if (code.isEmpty()) previous=clean+" #"+sourceId;
            else if (counts.get(code.toLowerCase(Locale.ROOT))>1) previous=code+" (Record #"+sourceId+")";
            else continue;
            aliases.put(p.get("vehicle_id").asLong(),new Alias(previous,clean));
        }
        Function.create(db,"fleet_clean_name",new Function() {
            protected void xFunc() throws SQLException {
                var alias=aliases.get(value_long(0));
                String stored=value_text(1);
                result(alias!=null && alias.original().equals(stored) ? alias.clean() : stored);
            }
        },2,Function.FLAG_DETERMINISTIC);
    }

    static void upgradeSqlite(Connection db) throws SQLException {
        if (!hasOldUniqueName(db)) return;
        // SQLite cannot drop an inline UNIQUE constraint. Preserve child rows (including
        // ON DELETE CASCADE profiles) while replacing the parent under one writer lock.
        try (var sql=db.createStatement()) {
            boolean foreignKeys=one(db,"PRAGMA foreign_keys").get("foreign_keys").asBoolean();
            sql.execute("PRAGMA foreign_keys=OFF");
            try {
                transaction(db,true,()->{
                    if (!hasOldUniqueName(db)) return null;
                    var objects=rows(db,"SELECT sql FROM sqlite_schema WHERE tbl_name='vehicles' AND type IN ('index','trigger') AND sql IS NOT NULL");
                    boolean profiles=exists(db,"vehicle_source_profiles");
                    if (profiles) registerNameTranslation(db);
                    sql.execute("CREATE TABLE vehicles_name_upgrade " + Vehicles.SQLITE_COLUMNS);
                    String name=profiles ? "fleet_clean_name(id,name)" : "name";
                    sql.execute("INSERT INTO vehicles_name_upgrade(id,name,brand,plate) SELECT id,"+name+",brand,plate FROM vehicles");
                    sql.execute("DROP TABLE vehicles");
                    sql.execute("ALTER TABLE vehicles_name_upgrade RENAME TO vehicles");
                    if (profiles) {
                        for(String table:new String[]{"route_vehicles","mainline_daily_vehicles"}) if (exists(db,table))
                            sql.execute("UPDATE "+table+" SET name=fleet_clean_name(vehicle_id,name) WHERE name COLLATE BINARY<>fleet_clean_name(vehicle_id,name)");
                    }
                    for (var object:objects) sql.execute(object.get("sql").asText());
                    if (one(db,"PRAGMA foreign_key_check")!=null) throw new SQLException("Vehicle name migration failed its foreign key check.");
                    return null;
                });
            } finally {
                if (foreignKeys) sql.execute("PRAGMA foreign_keys=ON");
            }
        }
    }
}
