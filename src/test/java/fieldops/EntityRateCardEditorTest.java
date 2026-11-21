package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityRateCardEditorTest {
    private static ObjectNode card(String json) throws Exception {
        return (ObjectNode)EntityRateCards.JSON.readTree(json);
    }
    private static String render(ObjectNode draft, boolean stale, String action, boolean error) {
        return EntityRateCardEditor.form("/entities/1/rates",draft,"test-digest",stale,action,error,false,
            Set.of("A & B"),Set.of("Test role"));
    }

    @Test void untouchedSourceSectionsAndNewBlankRowsDoNotCreateRates() throws Exception {
        var source = card("""
            {"billingModel":"BASE_PER_FOOT","footageTiers":null,"items":"","googleLaborItemRates":[],
             "driveRate":"1.25000","roleRates":[{"employeeRoleId":7,"employeeRoleName":"Crew lead","dropRate":0}]}
            """);
        var baseline = EntityRateCardEditor.initial(source);
        var draft = baseline.deepCopy();
        EntityRateCardEditor.changeDraft(draft,"add-items");
        EntityRateCardEditor.changeDraft(draft,"add-footageTiers");
        assertTrue(EntityRateCardEditor.patch(baseline,draft).isEmpty());
        var line = draft.withArray("roleRates").get(0);
        assertEquals("Crew lead",line.path("label").textValue());
        assertEquals("",line.path("boreRate").textValue());
        assertEquals("0",line.path("dropRate").textValue());
        String html = render(draft,false,"add-items",false);
        assertTrue(html.contains("name='driveRate' value='1.25000'"));
        assertTrue(html.contains("name='roleRates.0.dropRate' value='0'"));
        assertTrue(html.contains("name='roleRates.0.boreRate' value=''"));
        draft.put("driveRate","");
        assertEquals(card("{\"driveRate\":\"\"}"),EntityRateCardEditor.patch(baseline,draft));
    }

    @Test void draftKeepsOldRowIdentitiesOnRemovalAndSupportsAddingMultipleRows() throws Exception {
        var baseline = EntityRateCardEditor.initial(card("""
            {"items":[{"type":"Historic & outlet","rate":"0.50"},{"dropItemId":7,"rate":0}],"itemNames":{"7":"Vault"}}
            """));
        var draft = baseline.deepCopy();
        assertEquals("Historic & outlet",draft.path("items").path(0).path("label").textValue());
        EntityRateCardEditor.changeDraft(draft,"remove-items-0");
        EntityRateCardEditor.changeDraft(draft,"add-items");
        EntityRateCardEditor.changeDraft(draft,"add-items");
        var rows = draft.withArray("items");
        ((ObjectNode)rows.get(1)).put("label","Road").put("rate","2.1001");
        var changed = EntityRateCardEditor.patch(baseline,draft);
        assertEquals(2,changed.path("items").size());
        assertEquals(1,changed.path("items").get(0).path("_index").intValue());
        assertFalse(changed.path("items").get(1).has("_index"));
        assertEquals("2.1001",changed.path("items").get(1).path("rate").textValue());
        EntityRateCardEditor.changeDraft(draft,"remove-items-1");
        EntityRateCardEditor.changeDraft(draft,"remove-items-0");
        assertEquals(card("{\"items\":[]}"),EntityRateCardEditor.patch(baseline,draft));
        assertThrows(Problem.class,()->EntityRateCardEditor.changeDraft(draft,"remove-items-400"));
    }

    @Test void switchingModelMovesGroupsAndPreservesTypedValuesAndRowLabels() throws Exception {
        var baseline = EntityRateCardEditor.initial(null);
        assertTrue(EntityRateCardEditor.patch(baseline,baseline).isEmpty());
        var draft = baseline.deepCopy();
        draft.put("billingModel","GOOGLE_BILLING").put("googleLaborDropRate","12.501").put("boreRatePerFoot","0.1");
        EntityRateCardEditor.changeDraft(draft,"add-googleLaborItemRates");
        EntityRateCardEditor.changeDraft(draft,"add-roleRates");
        ((ObjectNode)draft.path("roleRates").get(0)).put("label","<Crew & lead>").put("boreRate","pending' fix");
        EntityRateCardEditor.changeDraft(draft,"model");
        String html = render(draft,true,"add-roleRates",true);
        assertTrue(html.contains("<option value='GOOGLE_BILLING' selected>"));
        int other = html.indexOf("<summary>Rates for other billing models");
        assertTrue(html.indexOf("name='googleLaborDropRate'") < other);
        assertTrue(html.indexOf("name='boreRatePerFoot'") > other);
        assertTrue(html.indexOf("name='roleRates.0.label'") > other);
        assertTrue(html.contains("&lt;Crew &amp; lead&gt;"));
        assertTrue(html.contains("pending&#39; fix"));
        assertTrue(html.contains("<details class='panel' open>"));
        assertTrue(html.contains("value='save' disabled"));
        assertTrue(html.contains("<option value='A &amp; B'>"));
        assertTrue(html.contains("name='digest' value='test-digest'"));
        assertFalse(html.contains("<Crew & lead>"));
        draft.put("billingModel","BUNDLE");
        String switched = render(draft,false,"model",false);
        int hidden = switched.indexOf("<summary>Rates for other billing models");
        assertTrue(switched.indexOf("name='baseFeeWithBore'") < hidden);
        assertTrue(switched.indexOf("name='googleLaborDropRate' value='12.501'") > hidden);
        assertTrue(switched.indexOf("name='roleRates.0.label'") < hidden);
    }
}
