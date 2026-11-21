package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class ReportCatalog {
    static final List<String> FIBER_TYPES = List.of(
        "Fiber", "Fiber C", "Fiber F", "Fiber FF", "2PR (Copper)", "5PR (Copper)", "RG 6 (Copper)");

    record Item(String name, String unit) {}

    private ReportCatalog() {}

    static ArrayNode itemRows(Connection db) throws SQLException {
        return StoreSql.rows(db, "SELECT id,name,unit FROM report_items ORDER BY id");
    }

    static List<Item> items(Connection db) throws SQLException {
        List<Item> items = new ArrayList<>();
        for (var row : itemRows(db)) items.add(new Item(row.get("name").asText(), row.get("unit").asText()));
        return items;
    }

    static void addItem(Connection db, ObjectNode data) throws SQLException {
        String name = StoreInput.text(data, "name", 1, 250);
        String unit = data.has("unit") ? StoreInput.text(data, "unit", 0, 80) : "";
        StoreSql.uniqueChange(db, "An item with that name and unit already exists.",
            "INSERT INTO report_items(name,unit) VALUES(?,?)", name, unit);
    }
}
