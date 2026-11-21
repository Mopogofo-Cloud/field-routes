package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityRateCardViewTest {
    private static ObjectNode card(String json) throws Exception {
        return (ObjectNode)App.JSON.readTree(json);
    }

    @Test void onlySavedAmountsBecomeKeysIncludingHiddenModelZeros() throws Exception {
        ObjectNode card = card("""
            {"companyName":"Fixture","billingModel":"BASE_PER_FOOT","lunchProvided":false,"driveRate":null,
             "boreRatePerFoot":"1.25000","conduitCeOverageRate":"0","googleLaborDropRate":0,
             "roleRates":"","items":[],"footageTiers":null,"googleLaborItemRates":[]}
            """);
        var amounts = EntityRateFields.amounts(card);
        assertEquals(Set.of("wg_bore_rate_per_foot","wg_conduit_ce_overage_rate","wg_google_labor_drop_rate"), amounts.keySet());
        assertEquals(new BigDecimal("1.25000"), amounts.get("wg_bore_rate_per_foot"));
        assertEquals(BigDecimal.ZERO,amounts.get("wg_google_labor_drop_rate"));
        String html = EntityRateCardView.render(card);
        assertTrue(html.contains("<dd>No</dd>"));
        assertTrue(html.contains("<h2>Labor Rates</h2></section>"));
        assertEquals(1,html.split("wg_google_labor_drop_rate",-1).length - 1);
        assertTrue(html.indexOf("Saved rates for other billing models") < html.indexOf("wg_google_labor_drop_rate"));
        assertFalse(html.contains("wg_drive_rate"));
        assertEquals("", EntityRateCardView.render(null));
        assertTrue(EntityRateFields.amounts(null).isEmpty());
    }

    @Test void retainOpenBoundsOrderAndDuplicateRoleOrItemRowsWithoutInventingMissingRates() throws Exception {
        ObjectNode card = card("""
            {"billingModel":"GOOGLE_BILLING","footageTiers":[{"fromFt":0,"toFt":12,"rate":"3.0010"},
                  {"fromFt":"12.10","toFt":null,"rate":0},{"fromFt":"12.1","rate":"1.2"}],
             "itemNames":{"7":"Vault <A>"},"roleNames":{"9":"Foreman"},
             "items":[{"dropItemId":7,"rate":null},{"dropItemId":"7","rate":"0.012345"}],
             "googleLaborItemRates":[{"dropItemId":"7","name":"Old & Name","rate":0}],
             "roleRates":[{"employeeRoleId":9,"employeeRoleName":"Past & Role","dropRate":null,"boreRate":0},
                          {"employeeRoleId":9,"dropRate":"1.75","boreRate":null}],"boreSidewalkOverageRate":5}
            """);
        var amounts = EntityRateFields.amounts(card);
        assertEquals(Set.of("wg_tier_0_12","wg_tier_12p1_open","wg_tier_12p1_open_2","wg_item_7_2","wg_labor_item_7",
            "wg_role_9_bore","wg_role_9_2_drop","wg_bore_sidewalk_overage_rate"), amounts.keySet());
        String html = EntityRateCardView.render(card);
        assertTrue(html.contains("From 12.10 ft · To ∞"));
        assertTrue(html.indexOf("wg_tier_0_12") < html.indexOf("wg_tier_12p1_open"));
        assertTrue(html.contains("Vault &lt;A&gt; (saved as Old &amp; Name)"));
        assertTrue(html.contains("Foreman (saved as Past &amp; Role) · Bore Rate"));
        assertTrue(html.contains("per ft over 6ft"));
        assertTrue(html.contains("0.012345"));
        for (String key : amounts.keySet()) {
            assertEquals(1,html.split("<code>" + key + "</code>",-1).length - 1);
            PayrollExpression.validate(key, amounts.keySet());
        }
    }

    @Test void driveRateBelongsToEntityDetailsWhenBothCardsAreEmpty() throws Exception {
        ObjectNode doc = card("""
            {"billingModel":"BUNDLE","driveRate":0.75,"footageTiers":[],"roleRates":[]}
            """);
        String html = EntityRateCardView.render(doc);
        assertTrue(html.contains("<h2>Rate Card</h2></section>"));
        assertTrue(html.contains("<h2>Labor Rates</h2></section>"));
        assertTrue(html.indexOf("id='wg-entity-details'") < html.indexOf("<code>wg_drive_rate</code>"));
        assertTrue(html.indexOf("<code>wg_drive_rate</code>") < html.indexOf("id='wg-rate-card'"));
    }

    @Test void olderItemTypeNamesGiveTheSameKeysAfterReordering() throws Exception {
        ObjectNode first = card("""
            {"items":[{"type":"Outlet <A>","rate":1},{"type":"Road bore","rate":"0.50"}]}
            """);
        ObjectNode reordered = card("""
            {"items":[{"type":"Road bore","rate":"0.50"},{"type":"Outlet <A>","rate":1}]}
            """);
        var values = EntityRateFields.amounts(first);
        assertEquals(2,values.size());
        assertEquals(values,EntityRateFields.amounts(reordered));
        assertTrue(EntityRateCardView.render(first).contains("Outlet &lt;A&gt;"));
        assertTrue(EntityRateCardView.render(first).contains("Road bore"));
        for (String key : values.keySet()) PayrollExpression.validate(key,values.keySet());
    }

    @Test void comcastUsesTheOriginalRateKeyOnlyForRoadFeet() throws Exception {
        ObjectNode comcast = card("""
            {"id":2,"companyName":"COMCAST - SUBCONTRACTOR 2026","billingModel":"TIERED_FLAT","boreRatePerFoot":"7.12340"}
            """);
        ObjectNode original = comcast.deepCopy();
        assertTrue(EntityRateFields.roadOnlyPerFoot(comcast));
        String field = "wg_bore_rate_per_foot";
        String onlyRoad = "Road bore feet only";
        assertEquals(new BigDecimal("7.12340"),EntityRateFields.amounts(comcast).get(field));
        String html = EntityRateCardView.render(comcast);
        assertTrue(html.contains("<th scope='row'>Rate per ft</th><td>per ft</td><td>7.12340</td><td><code>"
            + field + "</code></td><td>" + onlyRoad + "</td></tr>"));
        var draft = EntityRateCardEditor.initial(comcast);
        String editor = EntityRateCardEditor.form("/entities/99/rates",draft,"old",false,"",false,
            EntityRateFields.roadOnlyPerFoot(comcast),Set.of(),Set.of());
        assertTrue(editor.contains(onlyRoad + "<input type='text' name='boreRatePerFoot' value='7.12340'"));
        assertTrue(PayrollViews.reference(Map.of(),comcast).contains("<code>road_bore_feet * " + field + "</code>"));
        assertEquals(original,comcast);

        var other = comcast.deepCopy().put("id",21);
        var unrelated = comcast.deepCopy().put("companyName","Comcast Example");
        var local = comcast.deepCopy();
        local.remove("id");
        assertFalse(EntityRateFields.roadOnlyPerFoot(null));
        for (var card : java.util.List.of(other,unrelated,local)) {
            assertFalse(EntityRateFields.roadOnlyPerFoot(card));
            assertFalse(EntityRateCardView.render(card).contains(onlyRoad));
            assertFalse(PayrollViews.reference(Map.of(),card).contains(onlyRoad));
            assertTrue(EntityRateCardView.render(card).contains(field));
        }
        var google = comcast.deepCopy().put("id",19).put("companyName","Google fixture")
            .put("billingModel","GOOGLE_BILLING").put("boreRoadBoreRate",2).put("googleLaborBoreRoadBoreRate",3);
        String googleView = EntityRateCardView.render(google);
        assertTrue(googleView.contains("Road Bore</th><td>per ft</td>"));
        assertTrue(googleView.contains("Bore Road Bore</th><td>per Q</td>"));
        assertFalse(googleView.contains(onlyRoad));
        String googleGuide = PayrollViews.reference(Map.of(),google);
        assertTrue(googleGuide.contains("road_bore_feet * wg_bore_road_bore_rate</code>"));
        assertTrue(googleGuide.contains("road_bore_count * wg_google_labor_bore_road_bore_rate</code>"));
        assertFalse(googleGuide.contains(onlyRoad));
        assertFalse(PayrollViews.reference(Map.of(),comcast.deepCopy().putNull("boreRatePerFoot")).contains(onlyRoad));
    }

    @Test void unusuallyLongTierBoundsStillHaveValidStableKeys() throws Exception {
        ObjectNode card = card("""
            {"footageTiers":[{"fromFt":"1234567890123456789012345678901234567890123456789012345678901234567890",
                              "toFt":"1E+500","rate":"0.45"}]}
            """);
        var values = EntityRateFields.amounts(card);
        String key = values.keySet().iterator().next();
        assertTrue(key.length() <= 64);
        PayrollExpression.validate(key,values.keySet());
        assertTrue(EntityRateCardView.render(card).contains("1E+500"));
        assertEquals(values, EntityRateFields.amounts(card.deepCopy()));
    }
}
