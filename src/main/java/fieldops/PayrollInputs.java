package fieldops;

import java.sql.Connection;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

final class PayrollInputs {
    private PayrollInputs() {}

    static boolean isFootage(String key) {
        return switch (key) {
            case "feet_buried", "driveway_bore_feet", "sidewalk_bore_feet", "road_bore_feet",
                 "driveway_conduit_feet", "sidewalk_conduit_feet", "road_conduit_feet", "existing_conduit_feet" -> true;
            default -> false;
        };
    }

    static Map<String, BigDecimal> creditFootage(Map<String, BigDecimal> raw, String mode, BigDecimal crewSize) {
        var credited = new LinkedHashMap<>(raw);
        if ("full".equals(mode)) return credited;
        if (!"split".equals(mode)) throw new Problem(422, "Choose full or split footage.");
        for (var input : credited.entrySet()) {
            if (!isFootage(input.getKey())) continue;
            if (crewSize == null || crewSize.signum() <= 0 || crewSize.stripTrailingZeros().scale() > 0)
                throw new Problem(422, "Enter a positive whole crew size to split the footage.");
            input.setValue(input.getValue().divide(crewSize, MathContext.DECIMAL128));
        }
        return credited;
    }

    static Map<String, String> labels(Connection db) throws SQLException {
        var labels = new LinkedHashMap<String, String>();
        labels.put("days_worked", "Days worked");
        labels.put("profile_rate", "Contractor’s profile rate ($)");
        labels.put("crew_size", "Number of crew members");
        labels.put("completed_jobs", "Completed jobs");
        labels.put("drop_jobs", "Completed Drop jobs");
        labels.put("damage_jobs", "Completed Damage jobs");
        labels.put("survey_jobs", "Completed Survey jobs");
        labels.put("urgent_jobs", "Completed jobs marked Urgent");
        labels.put("tech_jobs", "Completed jobs marked Tech needed");
        labels.put("feet_buried", "Feet buried");
        labels.put("driveway_bore_feet", "Driveway bore feet");
        labels.put("sidewalk_bore_feet", "Sidewalk bore feet");
        labels.put("road_bore_feet", "Road bore feet");
        labels.put("driveway_bore_count", "Driveway bore entries");
        labels.put("sidewalk_bore_count", "Sidewalk bore entries");
        labels.put("road_bore_count", "Road bore entries");
        labels.put("driveway_conduit_feet", "Driveway conduit feet");
        labels.put("sidewalk_conduit_feet", "Sidewalk conduit feet");
        labels.put("road_conduit_feet", "Road conduit feet");
        labels.put("existing_conduit_feet", "Existing conduit feet");
        for (var item : ReportCatalog.itemRows(db)) {
            String unit = item.path("unit").asText();
            String name = item.path("name").asText();
            labels.put("item_" + item.path("id").asLong(), name + " quantity" + (unit.isEmpty() ? "" : " (" + unit + ")"));
        }
        return labels;
    }
}
