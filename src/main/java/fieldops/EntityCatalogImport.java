package fieldops;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static fieldops.StoreSql.*;

/** Import the two WG iDig contracting entity lists supplied by the dispatcher. */
public final class EntityCatalogImport {
    private EntityCatalogImport() {}

    private record Source(int id, String name) {}
    private static final List<Source> CATALOG = List.of(
        new Source(21, "COMCAST 2026 - INHOUSE"),
        new Source(2, "COMCAST - SUBCONTRACTOR 2026"),
        new Source(25, "CONSOLIDATE"),
        new Source(18, "Fidium ITG - Subcontractor 2026"),
        new Source(20, "FRONTIER IA - SUBCONTRACTOR 2026"),
        new Source(7, "FRONTIER IL- INHOUSE 2026"),
        new Source(11, "FRONTIER IL - SUBCONTRACTORS 2026"),
        new Source(6, "FRONTIER OH - INHOUSE 2026"),
        new Source(10, "FRONTIER OH - SUBCONTRACTORS 2026"),
        new Source(5, "FRONTIER WV - INHOUSE 2026"),
        new Source(9, "FRONTIER WV - SUBCONTRACTORS 2026"),
        new Source(19, "GOOGLE HUNTSVILLE INHOUSE 2026"),
        new Source(12, "GOOGLE - HUNTSVILLE SUBCONTRACTOR 2026"),
        new Source(1, "Google Nashville - INHOUSE CREWS 2026"),
        new Source(22, "GOOGLE NASHVILLE SUBCONTRACTOR 2026"),
        new Source(3, "METRONET - INHOUSE 2026"),
        new Source(24, "Metronet In House Antonio"),
        new Source(23, "METRONET _LUCAS"),
        new Source(14, "METRONET-SUBCONTRACTOR 2026"),
        new Source(4, "Mtronet EJEMPLO DO NOT USE"),
        new Source(15, "SPECTRUM - MO INHOUSE 2026"),
        new Source(17, "Spectrum- MO subcontractor 2026"),
        new Source(16, "TRIX - INHOUSE 2026")
    );

    record Result(int created, int matched, int alreadyLinked, List<Integer> conflictingIds) {}

    static Result run(Connection db) throws SQLException {
        return transaction(db, true, () -> {
            int created = 0, matched = 0, alreadyLinked = 0;
            List<Integer> conflicts = new ArrayList<>();
            for (Source src : CATALOG) {
                var linked = one(db, "SELECT e.name FROM wgidig_entities w JOIN entities e ON e.id=w.entity_id WHERE w.wgidig_id=?", src.id());
                if (linked != null) {
                    if (src.name().equals(linked.get("name").textValue())) alreadyLinked++;
                    else conflicts.add(src.id());
                    continue;
                }
                var local = one(db, """
                    SELECT e.id,e.name,w.wgidig_id FROM entities e LEFT JOIN wgidig_entities w ON w.entity_id=e.id
                    """ + " WHERE " + nameEquals(db, "e.name"), src.name());
                long id;
                if (local == null) {
                    id = insertId(db, "INSERT INTO entities(name) VALUES(?)", src.name());
                    created++;
                } else {
                    // Link an exact local match only. Leave ambiguous names and existing mappings untouched.
                    if (local.hasNonNull("wgidig_id") || !src.name().equals(local.get("name").textValue())) {
                        conflicts.add(src.id());
                        continue;
                    }
                    id = local.get("id").longValue();
                    matched++;
                }
                change(db, "INSERT INTO wgidig_entities(entity_id,wgidig_id) VALUES(?,?)", id, src.id());
            }
            return new Result(created, matched, alreadyLinked, List.copyOf(conflicts));
        });
    }

    public static void main(String[] args) throws SQLException {
        if (args.length != 0) throw new IllegalArgumentException("Use FIELDOPS_DATA_DIR to select the database directory.");
        Result result;
        try (var db = Db.open()) {
            Db.initialize(db);
            result = run(db);
        }
        System.out.printf("Created %d entities, matched %d local entities, found %d already linked. Conflicting WG iDig IDs %s.%n",
            result.created(), result.matched(), result.alreadyLinked(), result.conflictingIds());
        if (!result.conflictingIds().isEmpty()) System.exit(2);
    }
}
