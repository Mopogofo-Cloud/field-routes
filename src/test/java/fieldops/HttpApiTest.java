package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String base;
    private String cookie = "";

    Connection fixtureDatabase() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("fieldops.sqlite3"));
    }

    void configureDatabase(ProcessBuilder launch) {
        launch.environment().keySet().removeIf(key -> key.startsWith("FIELDOPS_POSTGRES_"));
        launch.environment().put("FIELDOPS_DATA_DIR", temp.toString());
    }

    private HttpResponse<String> request(String method, String path, String data, String type) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        if (type != null) builder.header("Content-Type", type);
        builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode api(String method, String path, String payload, int status) throws Exception {
        var res = request(method, "/api" + path, payload, payload == null ? null : "application/json");
        assertEquals(status, res.statusCode(), res.body());
        return App.JSON.readTree(res.body());
    }

    private String login(String name) throws Exception {
        var data = App.JSON.createObjectNode().put("username", name).put("password", "integration-password");
        var res = request("POST", "/api/login", data.toString(), "application/json");
        assertEquals(200, res.statusCode(), res.body());
        String setCookie = res.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"));
        cookie = setCookie.split(";", 2)[0];
        return App.JSON.readTree(res.body()).get("today").textValue();
    }

    @Test void dispatchReportsAndAccessThroughHttp() throws Exception {
        try (var db = fixtureDatabase()) {
            Db.initialize(db);
            try (var add = db.prepareStatement("INSERT INTO people(name,username,password_hash,is_admin) VALUES('Admin','admin',?,1)")) {
                add.setString(1, Auth.hash("integration-password"));
                add.executeUpdate();
            }
        }
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        var launch = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), "fieldops.App");
        configureDatabase(launch);
        launch.environment().remove("FIELDOPS_PHOTO_STORAGE");
        launch.environment().put("FIELDOPS_CONFIG_DIR", temp.resolve("config").toString());
        launch.environment().put("FIELDOPS_STORAGE_OWNER_ID", "1");
        launch.environment().put("FIELDOPS_R2_ACCOUNT_ID", "a".repeat(32));
        launch.environment().put("FIELDOPS_PORT", String.valueOf(port));
        launch.environment().put("FIELDOPS_SECURE_COOKIE", "0");
        launch.environment().put("RUNTIME_DIRECTORY", temp.resolve("runtime").toString());
        Path log = temp.resolve("server.log");
        Process server = launch.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ready = false;
            for (int i = 0; i < 120 && server.isAlive(); i++) {
                try { ready = request("GET", "/api/me", null, null).statusCode() == 401; }
                catch (java.io.IOException e) { /* Connector is still starting. */ }
                if (ready) break;
                Thread.sleep(100);
            }
            assertTrue(ready, () -> "Server failed to start. See " + log);
            String html = request("GET", "/", null, null).body();
            assertTrue(html.contains("Sign in") && !html.contains("<script"));
            api("GET", "/profiles", null, 401);
            var anonymous = request("GET", R2Setup.PATH, null, null);
            assertTrue(anonymous.body().contains("name='return_to' value='" + R2Setup.PATH + "'"));
            assertFalse(anonymous.body().contains("name='secret'"));
            var payrollLink = request("GET", "/entities/1/payroll", null, null);
            assertTrue(payrollLink.body().contains("name='return_to' value='/entities/1/payroll'"));
            assertTrue(request("GET", "/entities/1/rates", null, null).body().contains("name='return_to' value='/entities/1/rates'"));
            assertTrue(request("GET", JobReportExport.SAMPLE, null, null).body().contains("name='return_to' value='" + JobReportExport.SAMPLE + "'"));
            assertTrue(request("GET", PayrollChecks.SAMPLE_DOWNLOAD, null, null).body().contains(
                "name='return_to' value='" + PayrollChecks.SAMPLE_DOWNLOAD + "'"));
            String today = login("AdMiN");
            assertTrue(request("GET", "/job-reports", null, null).body().contains("Include uploaded photos"));
            verifyJobReportSample();
            assertTrue(request("GET", "/payroll", null, null).body().contains("Weekly payroll"));
            assertTrue(request("GET", "/payroll/check-settings", null, null).body().contains("href='" + PayrollChecks.SAMPLE_DOWNLOAD + "'"));
            var download = client.send(HttpRequest.newBuilder(URI.create(base + PayrollChecks.SAMPLE_DOWNLOAD))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            assertEquals("application/pdf", download.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("attachment; filename=\"Field-Routes-VOID-stock-aligned.pdf\"",
                download.headers().firstValue("Content-Disposition").orElseThrow());
            try (var pdf = getClass().getResourceAsStream("/web/Field-Routes-VOID-revised.pdf")) {
                assertNotNull(pdf);
                assertArrayEquals(pdf.readAllBytes(), download.body());
            }
            var storage = request("GET", R2Setup.PATH, null, null);
            assertEquals(200, storage.statusCode());
            assertTrue(storage.body().contains("Create bucket") && storage.body().contains("Verify and connect R2"));
            var invalid = request("POST", R2Setup.PATH, "endpoint=bad&secret=example-test-value", "application/x-www-form-urlencoded");
            assertEquals(422, invalid.statusCode());
            assertFalse(invalid.body().contains("example-test-value"));
            api("POST", "/roles", "{\"name\":\"Unused catalog role\"}", 201);
            api("POST", "/roles", "{\"name\":\"unused catalog role\"}", 409);
            assertEquals("Unused catalog role", api("GET", "/roles", null, 200).at("/0/name").asText());
            assertTrue(request("GET", "/roles", null, null).body().contains("Unused catalog role"));
            String profile = """
                {"name":"Alex","username":"alex","role":"Installer","pay_basis":"daily","pay_rate":0,
                "email":"alex@example.test","phone":"0123","address":"8 A&B St, Apt #2","password":"integration-password"}
                """;
            long alex = api("POST", "/profiles", profile, 201).get("id").longValue();
            long blair = api("POST", "/profiles", profile.replace("alex","blair").replace("Alex","Blair"), 201).get("id").longValue();
            long casey = api("POST", "/profiles", profile.replace("alex","casey").replace("Alex","Casey & Co"), 201).get("id").longValue();
            long devon = api("POST", "/profiles", profile.replace("alex","devon").replace("Alex","Devon"), 201).get("id").longValue();
            verifyMainlineForms(today, alex, casey, devon);
            verifyProjectSupervisor(today, alex, blair, casey, devon);
            long entity = api("POST", "/entities", "{\"name\":\"Example utility\"}", 201).get("id").longValue();
            String payrollPath = "/entities/" + entity + "/payroll";
            try (var local = fixtureDatabase()) {
                EntityRateCards.importCatalog(local, (com.fasterxml.jackson.databind.node.ObjectNode)EntityRateCards.JSON.readTree("""
                    {"listed":[{"id":800,"companyName":"Example utility"}],"entities":[{"id":800,"companyName":"Example utility",
                    "billingModel":"BASE_PER_FOOT","boreRatePerFoot":"2.712345","roleRates":[]}],"itemNames":{},"roleNames":{}}
                    """));
            }
            String entityPage = request("GET", "/entities/" + entity, null, null).body();
            assertTrue(entityPage.contains(payrollPath) && entityPage.contains("wg_bore_rate_per_foot") && entityPage.contains("Labor Rates"));
            String ratePath = "/entities/" + entity + "/rates";
            assertTrue(entityPage.contains("href='" + ratePath + "'"));
            assertTrue(request("GET",ratePath,null,null).body().contains("2.712345"));
            assertEquals(404,request("GET","/entities/99999/rates",null,null).statusCode());
            var payrollPage = request("GET", payrollPath, null, null);
            assertEquals(200, payrollPage.statusCode());
            assertTrue(payrollPage.body().contains("2.712345"));
            var preview = request("POST", payrollPath,
                "action=calculate&preview_index=0&revision=0&formula_label=Example&formula_basis=production&" +
                "formula_footage_credit=full&formula_expression=wg_bore_rate_per_foot*feet_buried&sample_feet_buried=5",
                "application/x-www-form-urlencoded");
            assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("13.561725"));
            verifyRateCardSave(ratePath, entity);
            assertEquals(404, request("GET", "/entities/99999/payroll", null, null).statusCode());
            String draft = "name=Draft+team&entity_id=" + entity + "&member=" + alex;
            String type = "application/x-www-form-urlencoded";
            var resultList = request("POST", "/teams/new", draft + "&picker=search&contractor_search=BLA", type);
            assertEquals(200, resultList.statusCode());
            assertTrue(resultList.body().contains("name='member' value='" + alex + "' checked"));
            assertTrue(resultList.body().contains("name='member' value='" + blair + "'>"));
            var noMatches = request("POST", "/teams/new", draft + "&picker=search&contractor_search=Absent", type);
            assertTrue(noMatches.body().contains("name='member' value='" + alex + "' checked"));
            assertFalse(noMatches.body().contains("name='member' value='" + blair + "'"));
            assertTrue(api("GET", "/teams", null, 200).isEmpty());
            long team = api("POST", "/teams", """
                {"name":"West","member_ids":[%d],"entity_id":%d}
                """.formatted(alex,entity), 201).get("id").longValue();
            String route = """
                {"team_id":%d,"date":"%s","addresses":[{"address":"8 A&B St, Apt #2","external_job_id":"0007-W"},"Second street"]}
                """.formatted(team,today);
            JsonNode assignedRoute = api("POST", "/routes", route, 201);
            long job = assignedRoute.at("/jobs/0/id").longValue();
            assertEquals("0007-W", assignedRoute.at("/jobs/0/external_job_id").asText());
            api("POST", "/routes", route, 409);
            api("PUT", "/profiles/" + alex, profile.replace("\"pay_rate\":0", "\"pay_rate\":null"), 200);
            assertEquals(0, api("GET","/routes",null,200).at("/0/members/0/pay_rate").intValue());
            assertTrue(request("GET","/people",null,null).body().contains("Alex"));
            String mapsHref = "href='https://www.google.com/maps/dir/?api=1&amp;destination=8+A%26B+St%2C+Apt+%232'";
            assertTrue(request("GET", "/people", null, null).body().contains(mapsHref));
            String teamPath = "/routes/team/" + team + "?date=" + today;
            String gateway = request("GET", "/routes", null, null).body();
            assertTrue(gateway.contains("href='/mainline'") && gateway.contains("href='/routes/drops'"));
            String daily = request("GET", "/routes/drops", null, null).body();
            assertTrue(daily.contains("href='" + teamPath + "'"));
            assertFalse(daily.contains(mapsHref));
            assertFalse(daily.contains("Open report"));
            var detail = request("GET", teamPath, null, null);
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains(mapsHref) && detail.body().contains("Crew · Alex"));
            assertTrue(detail.body().contains("href='/jobs/" + job + "'"));
            assertEquals(404, request("GET", "/routes/team/" + team + "?date=2000-01-01", null, null).statusCode());
            assertEquals(teamPath, request("GET", "/routes?team=" + team + "&date=" + today, null, null)
                .headers().firstValue("location").orElseThrow());
            String editor = request("GET", "/people/" + alex, null, null).body();
            assertTrue(editor.contains("Unused catalog role") && editor.contains("value='Installer'"));
            login("alex");
            assertEquals(403, request("GET", ratePath, null, null).statusCode());
            assertEquals(403, request("POST", ratePath, "action=save&boreRatePerFoot=2", type).statusCode());
            assertEquals(403, request("GET", JobReportExport.SAMPLE, null, null).statusCode());
            assertEquals(403, request("GET", payrollPath, null, null).statusCode());
            assertEquals(403, request("POST", payrollPath, "", type).statusCode());
            for (String restricted : java.util.List.of("/payroll", "/payroll/export", "/payroll/export/workbook",
                    "/payroll/export/details","/payroll/checks", "/payroll/checks/1", "/payroll/statement/1",
                    "/payroll/check-settings", PayrollChecks.SAMPLE_DOWNLOAD)) {
                assertEquals(403, request("GET", restricted, null, null).statusCode());
            }
            assertEquals(403, request("POST", "/payroll", "action=generate", type).statusCode());
            assertEquals(403, request("POST", "/payroll/checks", "", type).statusCode());
            assertEquals(403, request("GET", teamPath, null, null).statusCode());
            assertEquals(403, request("GET", "/job-reports", null, null).statusCode());
            assertEquals(403, request("POST", "/job-reports/export", "start="+today+"&end="+today+"&photos=1", type).statusCode());
            assertEquals(403, request("GET", R2Setup.PATH, null, null).statusCode());
            assertEquals(403, request("POST", R2Setup.PATH, "action=create&setup_token=unused", "application/x-www-form-urlencoded").statusCode());
            String myRoute = request("GET", "/", null, null).body();
            assertTrue(myRoute.contains("0007-W"));
            assertEquals(403, request("GET", "/items", null, null).statusCode());
            assertTrue(myRoute.contains(mapsHref) && myRoute.contains("href='/jobs/" + job + "'>Open report"));
            assertTrue(request("GET", "/jobs/" + job, null, null).body().contains(mapsHref));
            api("GET","/profiles",null,403);
            api("GET", "/roles", null, 403);
            api("POST", "/roles", "{\"name\":\"Example\"}", 403);
            assertEquals(403, request("GET", "/roles", null, null).statusCode());
            assertEquals(403,request("GET","/people",null,null).statusCode());
            assertFalse(api("GET","/routes",null,200).get(0).has("members"));
            String report = "{\"feet_buried\":20,\"bores\":[{\"type\":\"road\",\"feet\":5}],\"fiber_type\":\"Drop\",\"revision\":0}";
            String payload = "--test-boundary\r\nContent-Disposition: form-data; name=\"report\"\r\n\r\n" + report
                    + "\r\n--test-boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"view.webp\"\r\n"
                    + "Content-Type: image/webp\r\n\r\nRIFF0000WEBPsample\r\n--test-boundary--\r\n";
            String endpoint = "/api/jobs/" + job + "/report";
            var result = request("POST",endpoint,payload,"multipart/form-data; boundary=test-boundary");
            assertEquals(200,result.statusCode(),result.body());
            String photo = App.JSON.readTree(result.body()).at("/photos/0/url").textValue();
            assertEquals(200,request("GET",photo,null,null).statusCode());
            assertEquals(409,request("POST",endpoint,payload,"multipart/form-data; boundary=test-boundary").statusCode());
            verifyJobReportDownloads(today, job, photo);
            login("blair");
            assertTrue(request("GET", "/", null, null).body().contains("No routes shown"));
            assertTrue(api("GET","/routes",null,200).isEmpty());
            assertEquals(404,request("GET",photo,null,null).statusCode());
            assertEquals(404,request("POST",endpoint,payload,"multipart/form-data; boundary=test-boundary").statusCode());
            verifyStopEdits(teamPath, assignedRoute, photo);
            verifyJobIdEdit(assignedRoute.at("/jobs/1/id").asLong());
            api("POST","/logout",null,200);
            api("GET","/me",null,401);
            verifyPasswordPermissions(alex, profile);
        } finally {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly();
        }
    }

    private static String redirectTarget(HttpResponse<String> response) {
        assertEquals(303, response.statusCode(), response.body());
        return response.headers().firstValue("Location").orElseThrow();
    }

    private HttpResponse<byte[]> uploadFile(String path, String versionField, String field, String mime, byte[] bytes) throws Exception {
        String prefix = "--test-file\r\nContent-Disposition: form-data; name=\"" + versionField + "\"\r\n\r\n0\r\n"
            + "--test-file\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"fixture\"\r\n"
            + "Content-Type: " + mime + "\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
            HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofString("\r\n--test-file--\r\n"));
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie)
            .header("Content-Type", "multipart/form-data; boundary=test-file").POST(body).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void verifyMainlineForms(String today, long worker, long secondMember, long additionalMember) throws Exception {
        String form = "application/x-www-form-urlencoded";
        String newProject = "/mainline/projects/new";
        String projectForm = request("GET", newProject, null, null).body();
        assertTrue(projectForm.contains("name='supervisor'") && projectForm.contains("list='supervisor-contractors'"));
        var list = java.util.regex.Pattern.compile("<datalist id='supervisor-contractors'>(.*?)</datalist>",
            java.util.regex.Pattern.DOTALL).matcher(projectForm);
        assertTrue(list.find());
        assertTrue(list.group(1).contains("Blair (@blair, #") && list.group(1).contains("Casey &amp; Co (@casey, #"));
        assertFalse(list.group(1).contains("(@admin, #"));
        assertTrue(projectForm.contains("name='next' value='crew'"));
        String independent = redirectTarget(request("POST", newProject,
            "name=Draft&state=Not+Started&supervisor=External+supervisor", form));
        assertTrue(independent.matches("/mainline/projects/[0-9]+"));
        assertTrue(request("GET", independent + "/edit", null, null).body().contains("value='External supervisor'"));
        String assign = redirectTarget(request("POST", newProject,
            "name=Hill+Road&location=North&state=In+Progress&estimated_footage=2000&supervisor=External+lead&next=crew", form));
        assertTrue(assign.matches("/mainline/projects/[0-9]+/dailies/new"));
        String project = assign.substring(0, assign.length() - "/dailies/new".length());
        String projectPage = request("GET", project, null, null).body();
        assertTrue(projectPage.contains("href='" + assign + "'") && projectPage.contains("<dt>Supervisor</dt><dd>External lead</dd>"));
        String editForm = request("GET", project + "/edit", null, null).body();
        assertTrue(editForm.contains("value='External lead'") && editForm.contains("list='supervisor-contractors'"));
        var picker = request("POST", assign,
            "date=" + today + "&member=" + worker + "&picker=search&contractor_search=Casey", form);
        assertEquals(200, picker.statusCode());
        assertTrue(picker.body().contains("name='member' value='" + worker + "' checked"));
        assertTrue(picker.body().contains("name='member' value='" + secondMember + "'>"));
        String member = "&action=create&member=" + worker;
        String earlier = redirectTarget(request("POST", assign, "date=2000-01-01" + member, form));
        String daily = redirectTarget(request("POST", assign, "date=" + today + member + "&member=" + secondMember, form));
        assertTrue(daily.matches("/mainline/dailies/[0-9]+"));
        assertNotEquals(earlier, daily);
        String document = project + "/documents/fiber";
        byte[] pdf = ("%PDF-1.4\n%" + "x".repeat(1024 * 1024) + "\n%%EOF").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(303, uploadFile(document,"revision","document","application/pdf",pdf).statusCode());

        login("alex");
        String home = request("GET","/",null,null).body();
        assertTrue(home.contains("href='" + daily + "'") && !home.contains("href='" + earlier + "'"));
        assertFalse(home.contains("No routes shown"));
        assertEquals(404,request("GET",earlier,null,null).statusCode());
        assertEquals(404,request("POST",earlier,"action=save&revision=0&total_footage=35",form).statusCode());
        assertAccessDenied(request("GET",project,null,null));
        var report = request("GET",daily,null,null);
        assertEquals(200,report.statusCode());
        assertTrue(report.body().contains("href='" + document + "'"));
        String crewPath = daily + "/crew";
        assertFalse(report.body().contains("href='" + crewPath + "'"));
        assertAccessDenied(request("GET", crewPath, null, null));
        assertAccessDenied(request("POST", crewPath, "action=add&member=" + additionalMember, form));
        String photoPage = daily + "/photos";
        assertTrue(report.body().contains("href='" + photoPage + "' target='_blank' rel='noopener'"));
        assertFalse(report.body().contains("type='file'"));
        assertEquals(404,request("GET",earlier + "/photos",null,null).statusCode());
        var downloaded = client.send(HttpRequest.newBuilder(URI.create(base + document)).header("Cookie",cookie).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,downloaded.statusCode());
        assertArrayEquals(pdf,downloaded.body());
        assertEquals(403,uploadFile(document,"revision","document","application/pdf",pdf).statusCode());
        String fields = "action=save&revision=0&total_footage=242.5&priority=HIGH&comments=North+crossing"
            + "&expense_type=Gas&expense_cost=15.23&driver=" + worker;
        assertEquals(daily + "?saved=1",redirectTarget(request("POST",daily,fields,form)));
        assertEquals(409,request("POST",daily,fields,form).statusCode());
        String updated = request("GET",daily,null,null).body();
        assertTrue(updated.contains("North crossing") && updated.contains("15.23"));

        var picture = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",picture);
        var uploaded = uploadFile(daily + "/media/pictures","latest","photos","image/png",picture.toByteArray());
        assertEquals(303,uploaded.statusCode());
        assertEquals(photoPage + "#media-pictures",uploaded.headers().firstValue("Location").orElseThrow());
        assertTrue(request("GET",daily,null,null).body().contains("Pictures (1)"));
        String gallery = request("GET",photoPage,null,null).body();
        assertTrue(gallery.contains("type='file'") && gallery.contains("Remove image 1"));
        assertTrue(gallery.contains("Pictures (1)") && gallery.contains("Map (0)") && gallery.contains("Receipts (0)"));
        var photo = java.util.regex.Pattern.compile("src='(" + daily + "/media/[0-9]+)'").matcher(gallery);
        assertTrue(photo.find());
        String photoPath = photo.group(1);
        assertEquals(200,request("GET",photoPath,null,null).statusCode());
        assertEquals(403,request("POST",project + "/export","scope=all",form).statusCode());

        login("casey");
        assertTrue(request("GET","/",null,null).body().contains("href='" + daily + "'"));
        assertTrue(request("GET",daily,null,null).body().contains("North crossing"));
        String checked = fields.replace("revision=0", "revision=1").replace("North+crossing", "North+crossing+verified");
        assertEquals(daily + "?saved=1",redirectTarget(request("POST",daily,checked,form)));
        login("alex");
        assertTrue(request("GET",daily,null,null).body().contains("North crossing verified"));

        login("devon");
        assertEquals(404, request("GET", daily, null, null).statusCode());
        login("admin");
        assertTrue(request("GET",daily,null,null).body().contains("href='" + crewPath + "'"));
        var more = request("POST", crewPath, "picker=search&contractor_search=Devon", form);
        assertTrue(more.body().contains("name='member' value='" + additionalMember + "'"));
        assertEquals(daily + "?crew=1", redirectTarget(request("POST", crewPath, "action=add&member=" + additionalMember, form)));
        for (String name : java.util.List.of("alex", "casey", "devon")) {
            login(name);
            assertTrue(request("GET","/",null,null).body().contains("href='" + daily + "'"));
            assertTrue(request("GET",daily,null,null).body().contains("North crossing verified"));
        }

        login("blair");
        assertFalse(request("GET","/",null,null).body().contains("Hill Road"));
        for (String hidden : java.util.List.of(document,daily,photoPage,photoPath))
            assertEquals(404,request("GET",hidden,null,null).statusCode(),hidden);
        assertEquals(404,request("POST",daily,fields,form).statusCode());
        assertEquals(404,request("POST",daily + "/media/map","",form).statusCode());
        assertEquals(404,request("POST",photoPath + "/remove","",form).statusCode());

        login("admin");
        String dayId = daily.substring(daily.lastIndexOf('/') + 1);
        var workbook = client.send(HttpRequest.newBuilder(URI.create(base + project + "/export")).header("Cookie",cookie)
            .header("Content-Type",form).POST(HttpRequest.BodyPublishers.ofString("scope=selected&images=1&daily=" + dayId)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,workbook.statusCode());
        var contents = unzip(workbook.body());
        assertTrue(contents.containsKey("xl/workbook.xml"));
        assertTrue(contents.keySet().stream().anyMatch(path -> path.startsWith("xl/media/")));
        String dailies = new String(contents.get("xl/worksheets/sheet2.xml"),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(dailies.contains("North crossing verified") && dailies.contains("242.5"));
        assertFalse(dailies.contains("2000-01-01"));
        assertEquals(photoPage + "#media-pictures", redirectTarget(request("POST",photoPath + "/remove","",form)));
    }

    private static void assertAccessDenied(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
            () -> response.request().method() + " " + response.uri() + " returned " + response.statusCode());
    }

    private void verifyProjectSupervisor(String today, long alex, long blair, long casey, long devon) throws Exception {
        String form = "application/x-www-form-urlencoded";
        String tomorrow = java.time.LocalDate.parse(today).plusDays(1).toString();
        String projects = "/mainline/projects";
        String settings = "name=River+crossing&state=In+Progress&driver_cost=714.43&price_per_foot=13.2468";
        String alexOption = java.net.URLEncoder.encode("Alex (@alex, #" + alex + ")", java.nio.charset.StandardCharsets.UTF_8);
        String unrelated = redirectTarget(request("POST", projects + "/new", "name=Other+pipeline&state=Not+Started&supervisor=" + alexOption, form));
        String unrelatedDaily = redirectTarget(request("POST", unrelated + "/dailies/new", "date=" + today + "&member=" + devon, form));
        // A name typed without choosing the contractor account is only a project label.
        String project = redirectTarget(request("POST", projects + "/new", settings + "&supervisor=Blair", form));
        String assign = project + "/dailies/new";
        String existing = redirectTarget(request("POST", assign, "date=" + today + "&member=" + alex, form));
        String future = redirectTarget(request("POST", assign, "date=" + tomorrow + "&member=" + alex, form));
        String previous = redirectTarget(request("POST", assign, "date=2000-01-01&member=" + alex, form));
        String redline = project + "/documents/redline";
        byte[] pdf = "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(303, uploadFile(redline,"revision","document","application/pdf",pdf).statusCode());
        login("alex");
        assertEquals(200, request("GET", unrelated, null, null).statusCode());
        assertEquals(200, request("GET", unrelatedDaily, null, null).statusCode());
        assertEquals(200, request("GET", existing, null, null).statusCode());
        assertAccessDenied(request("POST", existing + "/crew", "action=add&member=" + casey, form));
        login("blair");
        assertAccessDenied(request("GET", project, null, null));
        assertAccessDenied(request("GET", existing, null, null));

        login("admin");
        String blairOption = "Blair (@blair, #" + blair + ")";
        String edit = project + "/edit";
        String chooseBlair = java.net.URLEncoder.encode(blairOption, java.nio.charset.StandardCharsets.UTF_8);
        String staleOption = java.net.URLEncoder.encode("Old name (@blair, #" + blair + ")", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(422, request("POST", edit, settings + "&revision=0&supervisor=" + staleOption, form).statusCode());
        assertEquals(project, redirectTarget(request("POST", edit, settings + "&revision=0&supervisor=" + chooseBlair, form)));
        assertTrue(request("GET", edit, null, null).body().contains("name='supervisor' value='" + blairOption + "'"));
        login("blair");
        String home = request("GET", "/", null, null).body();
        assertTrue(home.contains("href='" + project + "'") && home.contains("href='" + existing + "'"));
        assertFalse(home.contains("Other pipeline"));
        var projectView = request("GET", project, null, null);
        assertEquals(200, projectView.statusCode());
        assertTrue(projectView.body().contains("href='" + assign + "'"));
        assertEquals(200, request("GET", redline, null, null).statusCode());
        assertEquals(403, uploadFile(redline,"revision","document","application/pdf",pdf).statusCode());
        assertFalse(projectView.body().contains("714.43") || projectView.body().contains("13.2468"));
        assertFalse(projectView.body().contains("href='" + edit + "'") || projectView.body().contains("href='" + previous + "'"));
        var picker = request("GET", assign, null, null);
        assertEquals(200, picker.statusCode());
        assertTrue(picker.body().contains("Blair") && picker.body().contains("Alex"));
        assertFalse(picker.body().contains("alex@example.test") || picker.body().contains("href='/people/new'"));
        for (String restricted : java.util.List.of(projects, projects + "/new", edit, "/teams/new", "/people",
                "/payroll", unrelated, unrelated + "/dailies/new", unrelatedDaily, unrelatedDaily + "/crew",
                previous, previous + "/crew", future, future + "/photos")) {
            assertAccessDenied(request("GET", restricted, null, null));
        }
        assertAccessDenied(request("POST", edit, settings + "&revision=1&supervisor=", form));
        assertAccessDenied(request("POST", project + "/export", "scope=all", form));
        assertAccessDenied(request("POST", unrelated + "/dailies/new", "date=" + today + "&member=" + blair, form));
        assertAccessDenied(request("POST", unrelatedDaily + "/crew", "action=add&member=" + blair, form));
        assertAccessDenied(request("POST", previous + "/crew", "action=add&member=" + casey, form));
        api("GET", "/profiles", null, 403);
        int backdate = request("POST", assign, "date=2000-01-01&member=" + alex, form).statusCode();
        assertTrue(backdate == 403 || backdate == 422);
        assertEquals(200, request("GET", future + "/crew", null, null).statusCode());
        assertTrue(redirectTarget(request("POST", assign, "date=" + tomorrow + "&member=" + alex, form)).startsWith(project));

        // The server includes the supervisor even when the form posts no manually selected members.
        String solo = redirectTarget(request("POST", assign, "date=" + today + "&action=create", form));
        assertTrue(solo.matches("/mainline/dailies/[0-9]+"));
        var report = request("GET", solo, null, null);
        assertEquals(200, report.statusCode());
        assertTrue(report.body().contains("Team members (1)") && report.body().contains("Blair"));
        String update = "action=save&revision=0&comments=Supervisor+field+notes&driver=" + blair;
        assertEquals(solo + "?saved=1", redirectTarget(request("POST", solo, update, form)));
        assertEquals(solo + "?crew=1", redirectTarget(request("POST", solo + "/crew", "action=add&member=" + casey, form)));
        login("casey");
        assertTrue(request("GET", solo, null, null).body().contains("Supervisor field notes"));
        assertAccessDenied(request("POST", solo + "/crew", "action=add&member=" + devon, form));

        login("admin");
        String caseyOption = java.net.URLEncoder.encode("Casey & Co (@casey, #" + casey + ")", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(project, redirectTarget(request("POST", edit, settings + "&revision=1&supervisor=" + caseyOption, form)));
        login("blair");
        assertFalse(request("GET", "/", null, null).body().contains("River crossing"));
        for (String revoked : java.util.List.of(project, assign, solo, existing, future + "/crew", redline))
            assertAccessDenied(request("GET", revoked, null, null));
        login("casey");
        assertEquals(200, request("GET", assign, null, null).statusCode());
        assertEquals(200, request("GET", existing, null, null).statusCode());
        login("admin");
        assertEquals(project, redirectTarget(request("POST", edit, settings + "&revision=2&supervisor=", form)));
        login("casey");
        assertAccessDenied(request("GET", project, null, null));
        assertAccessDenied(request("GET", existing, null, null));
        assertEquals(200, request("GET", solo, null, null).statusCode()); // Explicitly on this crew before promotion.
        assertAccessDenied(request("POST", solo + "/crew", "action=add&member=" + devon, form));
        login("admin");
    }

    private void verifyJobIdEdit(long job) throws Exception {
        String path = "/jobs/" + job + "/address";
        String update = """
            {"address":"Second street","external_job_id":"001-X","revision":0}
            """;
        api("PUT", path, update, 403);
        login("admin");
        assertEquals("001-X", api("PUT", path, update, 200).path("external_job_id").asText());
        api("PUT", path, update, 409);
        String addressOnly = """
            {"address":"Second street, Suite 2","revision":1}
            """;
        assertEquals("001-X", api("PUT", path, addressOnly, 200).path("external_job_id").asText());
        String clear = """
            {"address":"Second street, Suite 2","external_job_id":"","revision":2}
            """;
        assertEquals("", api("PUT", path, clear, 200).path("external_job_id").asText());
    }

    private void verifyPasswordPermissions(long alex, String profile) throws Exception {
        String type = "application/x-www-form-urlencoded";
        String passwordJson = "{\"current_password\":\"integration-password\",\"new_password\":\"updated-password\"}";
        String passwordForm = "current_password=integration-password&new_password=updated-password&confirmation=updated-password";
        try (var db = fixtureDatabase()) {
            StoreSql.change(db, "UPDATE people SET must_change_password=1 WHERE id=?", alex);
            StoreSql.change(db, "UPDATE people SET must_change_password=1 WHERE username='admin'");
        }
        var contractorLogin = request("POST", "/login", "username=alex&password=integration-password", type);
        assertEquals("/", contractorLogin.headers().firstValue("location").orElseThrow());
        cookie = contractorLogin.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertFalse(api("GET", "/me", null, 200).path("must_change_password").asBoolean());
        var home = request("GET", "/", null, null);
        assertEquals(200, home.statusCode());
        assertFalse(home.body().contains("href='/change-password'"));
        assertEquals(403, request("GET", "/change-password", null, null).statusCode());
        assertEquals(403, request("POST", "/change-password", passwordForm, type).statusCode());
        api("POST", "/change-password", passwordJson, 403);
        api("PUT", "/profiles/" + alex, profile, 403);
        String contractorCookie = cookie;

        cookie = "";
        var firstLogin = request("POST", "/login", "username=admin&password=integration-password", type);
        assertEquals("/change-password", firstLogin.headers().firstValue("location").orElseThrow());
        cookie = firstLogin.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertTrue(api("GET", "/me", null, 200).path("must_change_password").asBoolean());
        api("GET", "/routes", null, 403);
        assertEquals("/change-password", request("GET", "/routes", null, null).headers().firstValue("location").orElseThrow());
        assertEquals(200, request("GET", "/change-password", null, null).statusCode());
        var changed = request("POST", "/change-password", passwordForm, type);
        assertEquals("/login?changed=1", changed.headers().firstValue("location").orElseThrow());
        api("GET", "/me", null, 401);
        var adminLogin = request("POST", "/login", "username=admin&password=updated-password", type);
        assertEquals("/", adminLogin.headers().firstValue("location").orElseThrow());
        cookie = adminLogin.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertTrue(request("GET", "/routes", null, null).body().contains("href='/change-password'"));
        assertEquals(200, request("GET", "/change-password", null, null).statusCode());
        api("PUT", "/profiles/" + alex, profile.replace("integration-password", "assigned-password"), 200);
        api("POST", "/change-password", "{\"current_password\":\"incorrect\",\"new_password\":\"updated-again\"}", 422);
        api("POST", "/change-password", "{\"current_password\":\"updated-password\",\"new_password\":\"updated-again\"}", 200);
        api("GET", "/me", null, 401);
        cookie = contractorCookie;
        api("GET", "/me", null, 401);
        api("POST", "/login", "{\"username\":\"alex\",\"password\":\"integration-password\"}", 401);
        assertFalse(api("POST", "/login", "{\"username\":\"alex\",\"password\":\"assigned-password\"}", 200)
                .path("must_change_password").asBoolean());
    }

    private void verifyRateCardSave(String path, long entityId) throws Exception {
        String editor = request("GET", path, null, null).body();
        var digest = java.util.regex.Pattern.compile("name='digest' value='([a-f0-9]{64})'").matcher(editor);
        assertTrue(digest.find());
        StringBuilder form = new StringBuilder("action=save&billingModel=BASE_PER_FOOT&digest=").append(digest.group(1));
        for (var scalar : EntityRateFields.SCALARS)
            form.append("&").append(scalar.name()).append("=").append(scalar.name().equals("boreRatePerFoot") ? "3.012345" : "");
        for (String key : java.util.List.of("footageTiers", "items", "googleLaborItemRates", "roleRates"))
            form.append("&").append(key).append(".count=0");
        String type = "application/x-www-form-urlencoded";
        var save = request("POST", path, form.toString(), type);
        assertEquals(303, save.statusCode(), save.body());
        assertTrue(request("GET", "/entities/" + entityId, null, null).body().contains("3.012345"));
        assertEquals(409, request("POST", path, form.toString(), type).statusCode());
    }

    private void verifyJobReportSample() throws Exception {
        assertTrue(request("GET", JobReportExport.PATH, null, null).body().contains("href='" + JobReportExport.SAMPLE + "'"));
        var response = client.send(HttpRequest.newBuilder(URI.create(base + JobReportExport.SAMPLE))
            .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename=\"Field-Routes-Sample-Job-Report.zip\"", response.headers().firstValue("Content-Disposition").orElseThrow());
        var sample = unzip(response.body());
        assertEquals(2, sample.keySet().stream().filter(name -> name.startsWith("photos/") && name.endsWith(".jpg")).count());
        var workbook = sample.entrySet().stream().filter(entry -> entry.getKey().endsWith(".xlsx")).findFirst().orElseThrow();
        assertTrue(unzip(workbook.getValue()).containsKey("xl/workbook.xml"));
    }

    private void verifyJobReportDownloads(String day, long job, String photoUrl) throws Exception {
        login("admin");
        String type = "application/x-www-form-urlencoded";
        assertEquals(422, request("POST", "/job-reports/export", "start=2024-02-01&end=2024-03-01", type).statusCode());
        assertTrue(request("POST", "/job-reports/export", "start=2001-04-01&end=2001-04-30", type).body()
            .contains("No submitted job reports"));
        for (boolean photos : java.util.List.of(false, true)) {
            var req = HttpRequest.newBuilder(URI.create(base + "/job-reports/export"))
                .header("Cookie", cookie).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString("start=" + day + "&end=" + day + (photos ? "&photos=1" : ""))).build();
            var download = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            String basename = "field-routes-job-reports-" + day + "-to-" + day;
            assertEquals("attachment; filename=\"" + basename + (photos ? ".zip\"" : ".xlsx\""),
                download.headers().firstValue("Content-Disposition").orElseThrow());
            assertEquals(download.body().length, download.headers().firstValueAsLong("Content-Length").orElseThrow());
            var entries = unzip(download.body());
            if (photos) {
                String image = "photos/" + day + "/job-" + job + "/photo-" + photoUrl.substring(photoUrl.lastIndexOf('/') + 1) + ".webp";
                assertEquals(java.util.Set.of(basename + ".xlsx", image), entries.keySet());
                assertArrayEquals("RIFF0000WEBPsample".getBytes(java.nio.charset.StandardCharsets.UTF_8), entries.get(image));
                entries = unzip(entries.get(basename + ".xlsx"));
            }
            assertTrue(entries.containsKey("xl/workbook.xml"));
            String jobs = new String(entries.get("xl/worksheets/sheet1.xml"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(jobs.contains("0007-W"));
            assertFalse(jobs.contains("Second street"));
            assertEquals(photos, jobs.contains("photos/" + day + "/job-" + job + "/"));
        }
    }

    private static java.util.Map<String, byte[]> unzip(byte[] data) throws Exception {
        var entries = new java.util.HashMap<String, byte[]>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    private void verifyStopEdits(String teamPath, JsonNode route, String photo) throws Exception {
        String type = "application/x-www-form-urlencoded";
        String routeField = "route_id=" + route.path("id").longValue();
        String add = "action=add&" + routeField + "&route_revision=0&addresses=Third+St%0AFourth+Ave&stop_label=drop&urgent=1";
        assertEquals(403, request("POST", teamPath, add, type).statusCode());
        login("admin");
        assertEquals(303, request("POST", teamPath, add, type).statusCode());
        assertEquals(409, request("POST", teamPath, add, type).statusCode());
        long removedJob = route.at("/jobs/0/id").longValue();
        long retainedJob = route.at("/jobs/1/id").longValue();
        String classify = "action=classify&" + routeField + "&route_revision=1&job_id=" + retainedJob + "&stop_label=survey";
        assertEquals(303, request("POST", teamPath, classify, type).statusCode());
        String filtered = request("GET", teamPath + "&filter=urgent", null, null).body();
        assertTrue(filtered.contains("Third St") && !filtered.contains("Second street"));
        String remove = "action=remove&" + routeField + "&route_revision=2&job_id=" + removedJob;
        assertEquals(303, request("POST", teamPath, remove, type).statusCode());
        assertTrue(request("GET", teamPath, null, null).body().contains("Removed stops (1)"));
        assertEquals(200, request("GET", photo, null, null).statusCode());
        login("alex");
        var home = request("GET", "/", null, null);
        assertTrue(home.body().contains("Third St") && home.body().contains("Fourth Ave"));
        assertTrue(home.body().contains("urgent-tag") && home.body().contains(">Survey</span>"));
        assertEquals(403, request("POST", teamPath, classify, type).statusCode());
        assertFalse(home.body().contains("href='/jobs/" + removedJob + "'"));
        assertEquals(404, request("GET", photo, null, null).statusCode());
    }
}
