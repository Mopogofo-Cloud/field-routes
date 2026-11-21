package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import static fieldops.StoreSql.rows;

/** Read work-date assignment and job snapshots inside the caller's payroll-run transaction. */
final class WeeklyPayroll {
    private WeeklyPayroll() {}
    private record Member(long id, String name, String basis, Long rateCents) {}
    private record CrewDay(String date, long entityId, String entityName, long teamId, String teamName, List<Member> crew) {}
    private record Item(String name, String unit) {
        static Item key(String name, String unit) {
            return new Item(name.strip().toLowerCase(Locale.ROOT), unit.strip().toLowerCase(Locale.ROOT));
        }
    }

    private static final class Work {
        final Map<String,BigDecimal> quantities = new HashMap<>();
        final Map<String,String> itemUnits = new HashMap<>();
        final Map<String,String> missing = new HashMap<>();
        final Map<Item,BigDecimal> reportedItems = new LinkedHashMap<>();
        final List<Long> jobIds = new ArrayList<>();
        final Set<Long> routeIds = new LinkedHashSet<>();
        final ArrayNode reportOutcomes = App.JSON.createArrayNode();
        String reviewKey;
        int pendingJobs, canceledJobs;

        void add(String key, BigDecimal value) { quantities.merge(key, value, BigDecimal::add); }
        BigDecimal get(String key) { return quantities.getOrDefault(key, BigDecimal.ZERO); }

        void addJob(JsonNode job, Map<Item,String> catalog) throws Exception {
            jobIds.add(job.get("id").longValue());
            routeIds.add(job.get("route_id").longValue());
            String outcome = job.get("outcome").textValue();
            if (job.hasNonNull("feet_buried")) add("feet_buried", job.get("feet_buried").decimalValue());
            else if (outcome.equals("done")) missing.put("feet_buried", "A completed report is missing its buried footage.");
            if (outcome.equals("done")) {
                add("completed_jobs", BigDecimal.ONE);
                if (job.get("urgent").asBoolean()) add("urgent_jobs", BigDecimal.ONE);
                if (job.path("tech_needed").asBoolean()) add("tech_jobs", BigDecimal.ONE);
                String label = job.path("stop_label").asText();
                if (!label.isBlank()) add(label + "_jobs", BigDecimal.ONE);
            } else {
                if (outcome.equals("pending")) pendingJobs++;
                else canceledJobs++;
                reportOutcomes.addObject().put("job_id", job.get("id").longValue()).put("outcome", outcome)
                    .set("reason", job.get("outcome_reason"));
            }
            for (String group : List.of("bore","conduit")) {
                for (var run : App.JSON.readTree(job.get(group + "s").textValue())) {
                    String surface = run.get("type").textValue();
                    add(surface + "_" + group + "_feet", run.get("feet").decimalValue());
                    if (group.equals("bore")) add(surface + "_bore_count", BigDecimal.ONE);
                }
            }
            for (var line : App.JSON.readTree(job.get("items").textValue())) {
                var reported = new Item(line.path("item").asText(), line.path("unit").asText());
                reportedItems.merge(reported, line.get("quantity").decimalValue(), BigDecimal::add);
                Item item = Item.key(line.path("item").asText(), line.path("unit").asText());
                String key = catalog.get(item);
                if (key == null) key = catalog.get(new Item(item.name(), ""));
                if (key == null) continue;
                String priorUnit = itemUnits.putIfAbsent(key, item.unit());
                if (priorUnit != null && !priorUnit.equals(item.unit()))
                    missing.put(key, "Reports use different units for " + line.path("item").asText() + ".");
                add(key, line.get("quantity").decimalValue());
            }
        }
    }

    private static Map<CrewDay,Work> work(Connection db, String start, String end) throws Exception {
        Map<Item,String> catalog = new HashMap<>();
        for (var row : ReportCatalog.itemRows(db)) {
            catalog.put(Item.key(row.get("name").textValue(), row.get("unit").textValue()), "item_" + row.get("id").asLong());
        }
        Map<Long,List<Member>> crews = new HashMap<>();
        for (var a : rows(db, """
            SELECT a.route_id,a.person_id,a.name,a.pay_basis,a.rate_cents FROM assignments a
            JOIN routes r ON r.id=a.route_id WHERE r.date BETWEEN ? AND ? ORDER BY a.route_id,a.person_id
            """, start, end)) {
            Long cents = a.hasNonNull("rate_cents") ? a.get("rate_cents").longValue() : null;
            crews.computeIfAbsent(a.get("route_id").longValue(), k -> new ArrayList<>()).add(
                new Member(a.get("person_id").longValue(), a.get("name").textValue(), a.get("pay_basis").textValue(), cents));
        }
        Map<CrewDay,List<JsonNode>> reports = new LinkedHashMap<>();
        var jobs = rows(db, """
            SELECT r.id AS route_id,r.date,r.team_id,r.team_name,r.entity_id,r.entity_name,
                   j.id,j.revision,coalesce(j.outcome,'done') AS outcome,j.outcome_reason,
                   j.feet_buried,j.stop_label,j.urgent,j.tech_needed,j.bores,j.conduits,j.items
            FROM routes r JOIN active_route_jobs j ON j.route_id=r.id
            WHERE r.date BETWEEN ? AND ? AND j.submitted_at IS NOT NULL
            ORDER BY r.date,r.id,j.position
            """, start, end);
        for (var job : jobs) {
            var crew = crews.get(job.get("route_id").longValue());
            if (crew == null) continue;
            var day = new CrewDay(job.get("date").textValue(), job.get("entity_id").longValue(),
                job.get("entity_name").textValue(), job.get("team_id").longValue(), job.get("team_name").textValue(), crew);
            reports.computeIfAbsent(day, k -> new ArrayList<>()).add(job);
        }
        Map<CrewDay,Work> grouped = new LinkedHashMap<>();
        for (var group : reports.entrySet()) {
            var completed = group.getValue().stream().filter(job -> job.get("outcome").textValue().equals("done")).toList();
            var work = new Work();
            var jobsToCount = completed.isEmpty() ? group.getValue() : completed;
            for (var job : jobsToCount) work.addJob(job, catalog);
            if (completed.isEmpty()) {
                work.reviewKey = reviewKey(group.getKey(), jobsToCount);
                if (!work.quantities.containsKey("feet_buried")) work.missing.put("feet_buried", "These reports have no recorded buried footage.");
            }
            grouped.put(group.getKey(), work);
        }
        return grouped;
    }

    private static String reviewKey(CrewDay day, List<JsonNode> reports) throws Exception {
        var fingerprint = App.JSON.createObjectNode().put("date", day.date()).put("entity_id", day.entityId()).put("team_id", day.teamId());
        var members = fingerprint.putArray("crew");
        for (var member : day.crew()) members.addArray().add(member.id()).add(member.basis()).add(member.rateCents());
        var jobs = fingerprint.putArray("jobs");
        reports.stream().sorted(Comparator.comparingLong(job -> job.get("id").longValue())).forEach(job ->
            jobs.addArray().add(job.get("route_id").longValue()).add(job.get("id").longValue())
                .add(job.get("revision").longValue()).add(job.get("outcome").textValue()));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(App.JSON.writeValueAsBytes(fingerprint)));
    }

    private record Review(long personId, String key) {}

    private record WorkerDate(long id, String date) {}

    private static final class WorkerWeek {
        final long id;
        String name;
        final Set<String> dates = new HashSet<>();
        final ArrayNode details = App.JSON.createArrayNode();
        BigDecimal feet = BigDecimal.ZERO, gross = BigDecimal.ZERO;
        boolean ready = true;

        WorkerWeek(long id) { this.id = id; }

        void addDetail(ObjectNode line, Work work, String decision) {
            if (work.reviewKey != null) {
                line.put("review_key", work.reviewKey).put("review_decision", decision)
                    .put("pending_jobs", work.pendingJobs).put("canceled_jobs", work.canceledJobs)
                    .set("report_outcomes", work.reportOutcomes);
            }
            details.add(line);
            if (line.hasNonNull("amount")) gross = gross.add(line.get("amount").decimalValue());
            else ready = false;
        }

        ObjectNode result() {
            var worker = App.JSON.createObjectNode().put("person_id", id).put("name", name)
                .put("days_worked", dates.size()).put("feet_buried", feet);
            if (ready && gross.signum() < 0) {
                ready = false;
                worker.put("reason", "The calculated weekly gross is negative.");
            }
            if (ready) worker.put("gross", gross);
            else worker.putNull("gross");
            worker.put("status", ready ? "ready" : "needs_review").set("details", details);
            return worker;
        }
    }

    static ObjectNode calculate(Connection db, LocalDate friday) throws Exception {
        if (friday == null || friday.getDayOfWeek() != DayOfWeek.FRIDAY)
            throw new Problem(422, "Choose a Friday pay date.");
        String start = friday.minusDays(11).toString(), end = friday.minusDays(5).toString();
        var week = App.JSON.createObjectNode().put("pay_date", friday.toString()).put("start_date", start).put("end_date", end);
        var result = week.putArray("workers");
        var grouped = work(db, start, end);
        Map<Review,String> reviews = new HashMap<>();
        for (var review : rows(db, "SELECT person_id,review_key,decision FROM payroll_day_reviews WHERE pay_date=?", friday.toString())) {
            reviews.put(new Review(review.get("person_id").longValue(), review.get("review_key").textValue()), review.get("decision").textValue());
        }
        Map<WorkerDate,Integer> scopesPerDay = new HashMap<>();
        for (var group : grouped.entrySet()) {
            var day = group.getKey();
            String review = group.getValue().reviewKey;
            for (var member : day.crew()) {
                if (review == null || "include".equals(reviews.get(new Review(member.id(), review))))
                    scopesPerDay.merge(new WorkerDate(member.id(), day.date()), 1, Integer::sum);
            }
        }
        Map<Long,WorkerWeek> people = new TreeMap<>();
        Map<Long,ObjectNode> configs = new HashMap<>();
        Set<String> reportInputs = PayrollInputs.labels(db).keySet();
        for (var group : grouped.entrySet()) {
            var day = group.getKey();
            var work = group.getValue();
            if (!configs.containsKey(day.entityId())) configs.put(day.entityId(), EntityPayroll.read(db, day.entityId()));
            var config = configs.get(day.entityId());
            Map<String,BigDecimal> base = new HashMap<>();
            for (String key : reportInputs) base.put(key, work.get(key));
            base.put("days_worked", BigDecimal.ONE);
            base.put("crew_size", BigDecimal.valueOf(day.crew().size()));
            var card = (ObjectNode) config.get("rate_cards");
            base.putAll(EntityRateFields.amounts(card));
            var roadBoreRules = RoadBoreRules.forCard(card);
            for (var rate : config.get("rates")) base.put(rate.get("key").textValue(), rate.get("amount").decimalValue());
            for (var member : day.crew()) {
                var worker = people.computeIfAbsent(member.id(), WorkerWeek::new);
                worker.name = member.name();
                String decision = reviews.getOrDefault(new Review(member.id(), work.reviewKey), "none");
                if (work.reviewKey != null && !decision.equals("include")) {
                    var line = detail(day, work, member);
                    line.putNull("formula").putNull("expression").putNull("footage_credit").putNull("credited_feet_buried");
                    if (decision.equals("exclude"))
                        line.put("amount", BigDecimal.ZERO.setScale(2)).put("reason", "Admin excluded this crew day from pay.");
                    else line.putNull("amount").put("reason", "Only pending or canceled stops were submitted. An admin must include or exclude this crew day.");
                    worker.addDetail(line, work, decision);
                    continue;
                }
                worker.dates.add(day.date());
                worker.feet = worker.feet.add(work.get("feet_buried"));
                if (member.rateCents() == null) base.remove("profile_rate");
                else base.put("profile_rate", BigDecimal.valueOf(member.rateCents(), 2));
                boolean sharedDay = scopesPerDay.get(new WorkerDate(member.id(), day.date())) > 1;
                boolean matched = false;
                for (var formula : config.get("formulas")) {
                    if (!formula.get("basis").textValue().equals(member.basis())) continue;
                    matched = true;
                    worker.addDetail(calculateLine(day, work, member, formula, base, reportInputs, sharedDay, roadBoreRules), work, decision);
                }
                if (!matched) {
                    var line = detail(day, work, member);
                    line.putNull("formula").putNull("expression").putNull("amount").putNull("credited_feet_buried")
                        .putNull("footage_credit").put("reason", "No " + member.basis() + " formula is configured for " + day.entityName() + ".");
                    worker.addDetail(line, work, decision);
                }
            }
        }
        people.values().forEach(person -> result.add(person.result()));
        return week;
    }

    private static ObjectNode detail(CrewDay day, Work work, Member member) {
        BigDecimal feet = work.get("feet_buried");
        var line = App.JSON.createObjectNode().put("date", day.date()).put("entity_id", day.entityId()).put("entity_name", day.entityName())
            .put("team_id", day.teamId()).put("team_name", day.teamName()).put("basis", member.basis())
            .put("feet_buried", feet).put("raw_feet_buried", feet).put("crew_size", day.crew().size());
        line.set("route_ids", App.JSON.valueToTree(work.routeIds));
        line.set("job_ids", App.JSON.valueToTree(work.jobIds));
        var items = line.putArray("reported_items");
        work.reportedItems.forEach((item, qty) -> items.addObject().put("item", item.name()).put("unit", item.unit()).put("quantity", qty));
        line.putObject("inputs");
        return line;
    }

    private static ObjectNode calculateLine(CrewDay day, Work work, Member member, JsonNode formula,
                                           Map<String,BigDecimal> base, Set<String> reportInputs, boolean sharedDay,
                                           Map<String,String> roadBoreRules) {
        var line = detail(day, work, member);
        String expression = formula.get("expression").textValue(), mode = formula.get("footage_credit").textValue();
        line.put("formula", formula.get("label").textValue()).put("expression", expression).put("footage_credit", mode);
        try {
            PayrollExpression.validateRateQuantities(expression, roadBoreRules);
            var values = PayrollInputs.creditFootage(base, mode, base.get("crew_size"));
            line.put("credited_feet_buried", values.get("feet_buried"));
            var variables = PayrollExpression.variables(expression);
            ObjectNode inputs = (ObjectNode)line.get("inputs");
            for (String key : variables) {
                if (work.missing.containsKey(key)) throw new Problem(422, work.missing.get(key));
                if (key.equals("profile_rate") && member.rateCents() == null)
                    throw new Problem(422, "The assignment has no profile rate for this work date.");
                if (!values.containsKey(key)) throw new Problem(422, "The payroll input " + key + " is unavailable.");
                inputs.put(key, values.get(key));
            }
            boolean flatDaily = member.basis().equals("daily") && variables.stream().noneMatch(key ->
                reportInputs.contains(key) && !Set.of("profile_rate","crew_size","days_worked").contains(key));
            if (sharedDay && (variables.contains("days_worked") || flatDaily))
                throw new Problem(422, "This worker belongs to multiple payroll groups on this date. Review the daily payment.");
            BigDecimal amount = PayrollExpression.evaluate(expression, values).setScale(2, RoundingMode.HALF_UP);
            line.put("amount", amount);
        } catch (Problem calculation) {
            line.putNull("amount").put("reason", calculation.getMessage());
        }
        return line;
    }
}
