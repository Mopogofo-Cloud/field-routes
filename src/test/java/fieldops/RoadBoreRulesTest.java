package fieldops;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RoadBoreRulesTest {
    private static final String GENERIC = "wg_bore_rate_per_foot";
    private static final String ROAD_UNIT = "wg_google_labor_bore_road_bore_rate";

    @Test void derivesBindingsOnlyFromPresentScopedRateFields() {
        var comcast = App.JSON.createObjectNode().put("id",2).put("companyName","COMCAST - SUBCONTRACTOR 2026")
            .put("boreRatePerFoot",6).put("boreRoadBoreRate",0).put("googleLaborBoreRoadBoreRate","0.00");
        assertEquals(Map.of(GENERIC,"road_bore_feet","wg_bore_road_bore_rate","road_bore_feet",ROAD_UNIT,"road_bore_count"),
            RoadBoreRules.forCard(comcast));
        var other = comcast.deepCopy().put("id",3).put("boreRoadBoreRate","").putNull("googleLaborBoreRoadBoreRate");
        assertTrue(RoadBoreRules.forCard(other).isEmpty());
        other.put("boreRoadBoreRate",11);
        assertEquals(Map.of("wg_bore_road_bore_rate","road_bore_feet"),RoadBoreRules.forCard(other));
        comcast.remove("id");
        assertFalse(RoadBoreRules.forCard(comcast).containsKey(GENERIC));
        assertTrue(RoadBoreRules.forCard(null).isEmpty());
    }

    @Test void acceptsIndependentTermsFactoringCrewSplitsAndBothOperandOrders() {
        var bindings = Map.of(GENERIC,"road_bore_feet",ROAD_UNIT,"road_bore_count");
        for (String source : List.of(
            GENERIC + " * road_bore_feet",
            "-road_bore_feet * (" + GENERIC + " / crew_size)",
            "(12 * " + GENERIC + ") * (road_bore_feet / (crew_size + 1))",
            "(road_bore_feet / crew_size) * " + GENERIC + " * .75 + sidewalk_bore_feet * sidewalk_rate",
            "("+ GENERIC +" * road_bore_feet + driveway_bore_feet * driveway_rate + profile_rate) / crew_size",
            "road_bore_feet * (" + GENERIC + " + manual_road_bonus_per_foot)",
            GENERIC + " * (road_bore_feet + 0.05 * road_bore_feet)",
            "road_bore_feet * " + GENERIC + " + " + ROAD_UNIT + " * road_bore_count",
            "driveway_bore_feet * driveway_rate + sidewalk_bore_feet * sidewalk_rate",
            "wg_role_13_bore * driveway_bore_feet",
            "(driveway_bore_feet * driveway_rate) / sidewalk_rate")) {
            assertDoesNotThrow(()->PayrollExpression.validate(source,PayrollExpression.variables(source),bindings),source);
        }
    }

    @Test void rejectsWrongSurfaceUnitsUnrelatedFactorsAndUnmatchedRatesEvenWhenFactored() {
        var bindings = Map.of(GENERIC,"road_bore_feet",ROAD_UNIT,"road_bore_count");
        for (String source : List.of(
            GENERIC, "10 * " + GENERIC, GENERIC + " + road_bore_feet",
            GENERIC + " / road_bore_feet", "road_bore_feet / " + GENERIC,
            GENERIC + " * feet_buried", GENERIC + " * driveway_bore_feet", GENERIC + " * sidewalk_bore_feet",
            "road_bore_count * " + GENERIC, ROAD_UNIT + " * road_bore_feet", ROAD_UNIT + " * item_5",
            GENERIC + " * (road_bore_feet + sidewalk_bore_feet)",
            GENERIC + " * (road_bore_feet + 5)", GENERIC + " * (road_bore_feet / driveway_bore_feet)",
            "(" + GENERIC + " * road_bore_feet + 15) * driveway_bore_feet",
            "("+ GENERIC + " * road_bore_feet) / (crew_size + feet_buried)",
            GENERIC + " * road_bore_feet * " + ROAD_UNIT + " * road_bore_count",
            "(days_worked + " + GENERIC + " * road_bore_feet) / (" + ROAD_UNIT + " * road_bore_count)",
            GENERIC + " * road_bore_feet + " + GENERIC,
            "("+ GENERIC + " + sidewalk_rate) * sidewalk_bore_feet")) {
            Problem error = assertThrows(Problem.class,()->PayrollExpression.validateRateQuantities(source,bindings),source);
            assertEquals(422,error.status);
            assertTrue(error.getMessage().contains("road_bore_"),source);
        }
        assertDoesNotThrow(()->PayrollExpression.validate(GENERIC+"*driveway_bore_feet",
            Set.of(GENERIC,"driveway_bore_feet"), Map.of()));
        assertThrows(Problem.class,()->PayrollExpression.validate("missing",Set.of(),bindings));
    }
}
