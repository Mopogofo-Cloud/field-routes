package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import static fieldops.Views.h;

final class EntityRateCardView {
    private EntityRateCardView() {}

    static String render(ObjectNode card) {
        if (card == null) return "";
        var fields = EntityRateFields.fields(card);
        boolean roadOnly = EntityRateFields.roadOnlyPerFoot(card);
        String model = card.path("billingModel").asText("");
        var active = fields.stream().filter(f -> f.scope().active(model)).toList();
        var html = new StringBuilder();
        var details = new StringBuilder();
        metadata(details, "Company name", card.get("companyName"));
        metadata(details, "Billing model", card.get("billingModel"));
        metadata(details, "Location", card.get("location"));
        metadata(details, "Latitude", card.get("latitude"));
        metadata(details, "Longitude", card.get("longitude"));
        metadata(details, "Lunch provided", card.get("lunchProvided"));
        var entityRates = active.stream().filter(f -> f.group() == EntityRateFields.Group.ENTITY).toList();
        if (!details.isEmpty() || !entityRates.isEmpty()) {
            html.append("<section class='panel stack formula-reference' id='wg-entity-details' aria-label='Contracting entity'>");
            if (!details.isEmpty()) html.append("<dl>").append(details).append("</dl>");
            tables(html, roadOnly, entityRates);
            html.append("</section>");
        }
        html.append("<section class='panel stack formula-reference' id='wg-rate-card'><h2>Rate Card</h2>");
        tables(html, roadOnly, active.stream().filter(f -> !f.group().labor && f.group() != EntityRateFields.Group.ENTITY).toList());
        html.append("</section><section class='panel stack formula-reference' id='wg-labor-rates'><h2>Labor Rates</h2>");
        tables(html, roadOnly, active.stream().filter(f -> f.group().labor).toList());
        html.append("</section>");
        var inactive = fields.stream().filter(f -> !f.scope().active(model) && f.amount() != null).toList();
        if (!inactive.isEmpty()) {
            html.append("<details class='panel formula-reference'><summary>Saved rates for other billing models</summary>");
            tables(html, roadOnly, inactive);
            html.append("</details>");
        }
        return html.toString();
    }

    private static void metadata(StringBuilder out, String label, JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) return;
        String text = value.isBoolean() ? (value.booleanValue() ? "Yes" : "No") : value.asText();
        out.append("<dt><strong>").append(h(label)).append("</strong></dt><dd>").append(h(text)).append("</dd>");
    }

    private static void tables(StringBuilder out, boolean roadOnly, List<EntityRateFields.Field> fields) {
        for (var group : EntityRateFields.Group.values()) {
            var rows = fields.stream().filter(f -> f.group() == group).toList();
            if (rows.isEmpty()) continue;
            boolean showMeasure = roadOnly && rows.stream().anyMatch(row -> EntityRateFields.GENERIC_BORE_KEY.equals(row.key()));
            out.append("<table><caption>").append(h(group.label)).append("</caption>")
                .append("<thead><tr><th scope='col'>Rate</th><th scope='col'>Unit</th><th scope='col'>Amount ($)</th>")
                .append("<th scope='col'>Formula key</th>");
            if (showMeasure) out.append("<th scope='col'>Applies to</th>");
            out.append("</tr></thead><tbody>");
            for (var row : rows) {
                out.append("<tr><th scope='row'>").append(h(row.label())).append("</th><td>").append(h(row.unit())).append("</td>");
                if (row.amount() == null) out.append("<td></td><td></td>");
                else out.append("<td>").append(h(EntityRateFields.displayNumber(row.amount()))).append("</td><td><code>")
                    .append(h(row.key())).append("</code></td>");
                if (showMeasure) {
                    out.append("<td>");
                    if (EntityRateFields.GENERIC_BORE_KEY.equals(row.key())) out.append(h(EntityRateFields.ROAD_ONLY_MEASURE));
                    out.append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</tbody></table>");
        }
    }
}
