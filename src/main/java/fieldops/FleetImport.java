package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static fieldops.StoreSql.*;
import static fieldops.Views.h;

/** Imports a private, normalized copy of the Vehicles worksheet. The workbook record ID identifies the row. */
public final class FleetImport {
    private FleetImport() {}

    static final List<String> HEADERS = List.of("Record ID", "Company code", "Brand", "Model", "Year", "Plate number",
        "VIN number", "Notes", "Files saved", "Photos / attachments", "Full profile", "Profile folder", "Source profile");
    static final List<String> KEYS = List.of("record_id", "company_code", "brand", "model", "year", "plate_number",
        "vin_number", "notes", "files_saved", "photos_attachments", "full_profile", "profile_folder", "source_profile");
    private static final List<String> FILE_KEYS = List.of("file_type","result","open_file","original_filename","bytes","notes","target");

    static void initialize(Connection db) throws SQLException {
        String vehicleId = postgres(db) ? "BIGINT" : "INTEGER";
        try (var sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS vehicle_source_profiles (
                    vehicle_id %s PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
                    source_record_id TEXT NOT NULL UNIQUE, source_record TEXT NOT NULL)
                """.formatted(vehicleId));
        }
    }

    private record Vehicle(String id, String name, String brand, String plate, String vin, JsonNode profile) {}
    record Result(int newVehicles, int matched, int rows) {}

    static String operational(JsonNode profile, String field) {
        String text = profile.get(field).textValue().strip();
        return "N/A".equalsIgnoreCase(text) ? "" : text;
    }

    private static String key(String name) { return name.strip().toLowerCase(Locale.ROOT); }

    static String displayName(JsonNode profile) {
        String code = operational(profile,"company_code");
        if (!code.isEmpty()) return code;
        String description = (operational(profile,"brand") + " " + operational(profile,"model")).strip();
        return description.isEmpty() ? "Vehicle" : description;
    }

    private static Map<String,Vehicle> catalog(JsonNode raw) {
        if (!(raw instanceof ArrayNode array) || array.isEmpty()) throw new IllegalArgumentException("An array of vehicle rows is required.");
        var profiles = new LinkedHashMap<String,ObjectNode>();
        for (JsonNode row : array) {
            if (!(row instanceof ObjectNode source)) throw new IllegalArgumentException("Invalid vehicle row.");
            ObjectNode profile = source.deepCopy();
            if (!profile.has("links")) profile.putObject("links");
            if (!profile.has("source_files")) profile.putArray("source_files");
            for (String field : KEYS) {
                if (!profile.path(field).isTextual()) throw new IllegalArgumentException("Missing or invalid vehicle field " + field);
            }
            if (profile.size() != KEYS.size()+2 || !(profile.path("links") instanceof ObjectNode links)
                    || !(profile.path("source_files") instanceof ArrayNode attachments))
                throw new IllegalArgumentException("Unexpected vehicle fields.");
            String id = profile.get("record_id").textValue();
            if (!id.matches("[0-9]{1,18}") || profiles.putIfAbsent(id, profile) != null)
                throw new IllegalArgumentException("Duplicate or invalid vehicle record ID.");
            for (var link : links.properties()) {
                if (!KEYS.contains(link.getKey()) || !link.getValue().isTextual())
                    throw new IllegalArgumentException("Invalid link for record " + id);
            }
            for (var file : attachments) {
                if (file.size()!=FILE_KEYS.size() || FILE_KEYS.stream().anyMatch(k->!file.path(k).isTextual()))
                    throw new IllegalArgumentException("Invalid attachment entry on vehicle record " + id);
            }
        }
        var vehicles = new LinkedHashMap<String,Vehicle>();
        for (var record : profiles.entrySet()) {
            String id = record.getKey();
            var profile = record.getValue();
            String name = displayName(profile);
            if (name.length()>120) throw new IllegalArgumentException("Vehicle name too long for record " + id);
            vehicles.put(id,new Vehicle(id,name,operational(profile,"brand"),operational(profile,"plate_number"),operational(profile,"vin_number"),profile));
        }
        return vehicles;
    }

    static Result run(Connection db, JsonNode raw, boolean apply) throws Exception {
        var source = catalog(raw);
        return transaction(db,apply,()->{
            var localNames = new HashSet<String>();
            var unlinkedIdentifiers = new HashSet<String>();
            var linked = new HashMap<String,JsonNode>();
            for (var existing : rows(db,"""
                SELECT v.name,v.plate,p.source_record_id,p.source_record
                  FROM vehicles v LEFT JOIN vehicle_source_profiles p ON p.vehicle_id=v.id
                """)) {
                if (existing.hasNonNull("source_record_id")) {
                    var storedSource = App.JSON.readTree(existing.get("source_record").asText());
                    linked.put(existing.get("source_record_id").asText(),storedSource);
                    String name=key(existing.get("name").asText());
                    if (!name.equals(key(displayName(storedSource)))) localNames.add(name);
                } else {
                    localNames.add(key(existing.get("name").asText()));
                    String plate = key(existing.get("plate").asText());
                    if (!plate.isEmpty() && !plate.equals("n/a")) unlinkedIdentifiers.add(plate);
                }
            }
            int matched = 0;
            for (Vehicle row : source.values()) {
                var prior = linked.get(row.id());
                if (prior != null) {
                    if (!prior.equals(row.profile())) throw new IllegalStateException("Stored source data differ for record " + row.id());
                    matched++;
                } else if (localNames.contains(key(row.name())) || unlinkedIdentifiers.contains(key(row.plate()))
                        || unlinkedIdentifiers.contains(key(row.vin()))) {
                    throw new IllegalStateException("Existing vehicle conflicts with source record " + row.id() + ". Review before importing.");
                }
            }
            if (apply) {
                try (var save = db.prepareStatement("INSERT INTO vehicle_source_profiles(vehicle_id,source_record_id,source_record) VALUES(?,?,?)")) {
                    for (Vehicle row : source.values()) {
                        if (linked.containsKey(row.id())) continue;
                        long id = insertId(db,"INSERT INTO vehicles(name,brand,plate) VALUES(?,?,?)",row.name(),row.brand(),row.plate());
                        bind(save,id,row.id(),row.profile().toString());
                        save.addBatch();
                    }
                    save.executeBatch();
                }
            }
            return new Result(source.size()-matched,matched,source.size());
        });
    }

    static String adminProfile(Connection db, ObjectNode user, long vehicleId) throws SQLException, IOException {
        if (!user.path("is_admin").asBoolean()) throw new Problem(403,"Administrator access required.");
        var stored = one(db,"SELECT source_record FROM vehicle_source_profiles WHERE vehicle_id=?",vehicleId);
        if (stored==null) return "";
        var profile = App.JSON.readTree(stored.get("source_record").asText());
        var page = new StringBuilder("<section class='panel stack editor'><h2>Vehicles worksheet</h2><dl class='project-facts'>");
        for (int i=0; i<KEYS.size(); i++) {
            String key=KEYS.get(i), value=profile.path(key).asText(), target=profile.path("links").path(key).asText();
            page.append("<div><dt>").append(h(HEADERS.get(i))).append("</dt><dd>").append(reference(value,target)).append("</dd></div>");
        }
        page.append("</dl>");
        var files=profile.path("source_files");
        if (!files.isEmpty()) {
            page.append("<details><summary>Workbook file index (").append(files.size()).append(")</summary><div class='stack'>");
            for (var file : files) {
                page.append("<dl class='project-facts'>");
                for (String field : List.of("original_filename","file_type","result","bytes","notes","open_file")) {
                    String label = switch (field) {
                        case "original_filename" -> "Original filename";
                        case "file_type" -> "File type";
                        case "open_file" -> "File reference";
                        default -> Character.toUpperCase(field.charAt(0)) + field.substring(1);
                    };
                    String value = file.path(field).asText();
                    page.append("<div><dt>").append(h(label)).append("</dt><dd>")
                        .append(reference(value,field.equals("open_file") ? file.path("target").asText() : ""))
                        .append("</dd></div>");
                }
                page.append("</dl>");
            }
            page.append("</div></details>");
        }
        return page.append("</section>").toString();
    }

    private static String reference(String value, String target) {
        if (webLink(target)) return "<a href='" + h(target) + "' target='_blank' rel='noopener noreferrer'>" +
                h(value.isBlank() ? target : value) + "</a>";
        return h(value) + (target.isBlank() ? "" : "<br><span class='helper'>" + h(target) + "</span>");
    }

    private static boolean webLink(String target) {
        try {
            URI url = URI.create(target);
            return url.getHost()!=null && url.getUserInfo()==null &&
                ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length!=2 || !Set.of("--check","--apply","--verify").contains(args[0]))
            throw new IllegalArgumentException("Use --check, --apply, or --verify followed by the normalized Vehicles JSON file.");
        JsonNode source;
        try (var file = Files.newInputStream(Path.of(args[1]))) { source = App.JSON.readTree(file); }
        try (var db = Db.open()) {
            var result = run(db,source,args[0].equals("--apply"));
            if (args[0].equals("--verify") && result.newVehicles()!=0) throw new IllegalStateException("Some source rows are missing.");
            System.out.printf("Vehicles worksheet rows %d, new %d, matched %d.%n",result.rows(),result.newVehicles(),result.matched());
        } catch (SQLException ex) {
            throw new IllegalStateException("Vehicle database operation failed, SQL state " + ex.getSQLState());
        }
    }
}
