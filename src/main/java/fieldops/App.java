package fieldops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.RemoteIpValve;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class App extends HttpServlet {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final int MIB = 1024 * 1024;
    // A full 1,000-stop draft may contain URL-encoded multibyte addresses and Job IDs.
    private static final int PLAN_FORM_LIMIT = 8 * MIB;
    private static final long REPORT_LIMIT = 121L * MIB;
    private static final String COOKIE = "fieldops_session";
    private static final java.util.concurrent.Semaphore PHOTO_RESPONSES = new java.util.concurrent.Semaphore(6, true);

    public static void main(String[] args) throws Exception {
        Db.initialize();
        PhotoStorage.start();
        Db.today();
        int port = Integer.parseInt(System.getenv().getOrDefault("FIELDOPS_PORT", "8766"));
        Tomcat server = server(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
                server.destroy();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Server shutdown failed", e);
            } finally {
                PayrollRuns.stop();
                PhotoStorage.stop();
            }
        }));
        server.start();
        PayrollRuns.start();
        server.getServer().await();
    }

    static Tomcat server(int port) throws IOException {
        String runtime = System.getenv("RUNTIME_DIRECTORY");
        Path base = runtime == null ? Files.createTempDirectory("field-routes-") : Path.of(runtime);
        Path uploads = Files.createDirectories(base.resolve("uploads"));
        Tomcat server = new Tomcat();
        server.setBaseDir(base.toString());
        server.setPort(port);
        Connector connector = server.getConnector();
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxThreads", "50");
        connector.setMaxPostSize(PLAN_FORM_LIMIT);
        connector.setMaxParameterCount(6000);
        connector.setProperty("maxPartCount", "1100");
        var proxy = new RemoteIpValve();
        proxy.setInternalProxies("127\\.0\\.0\\.1");
        proxy.setRemoteIpHeader("X-Forwarded-For");
        proxy.setProtocolHeader("X-Forwarded-Proto");
        server.getEngine().getPipeline().addValve(proxy);
        Context ctx = server.addContext("", Files.createDirectories(base.resolve("web")).toString());
        ctx.setUseRelativeRedirects(true);
        var servlet = Tomcat.addServlet(ctx, "routes", new App());
        servlet.setMultipartConfigElement(new MultipartConfigElement(uploads.toString(), 12L * MIB, REPORT_LIMIT, 0));
        ctx.addServletMappingDecoded("/", "routes");
        return server;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        req.setCharacterEncoding("UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'");
        String path = req.getRequestURI();
        boolean api = path.startsWith("/api/");
        try {
            String method = req.getMethod();
            if (!Set.of("GET", "POST", "PUT", "HEAD").contains(method)) throw new Problem(405, "Method not supported.");
            if (!method.equals("GET") && !method.equals("HEAD")) {
                checkOrigin(req);
                long limit = path.matches("/(?:api/)?jobs/[0-9]+(?:/report)?(?:/.*)?") ? REPORT_LIMIT : MIB;
                if (path.matches("/mainline/dailies/[0-9]+/media/(map|pictures|receipts)") ||
                        path.matches("/mainline/projects/[0-9]+/documents/(redline|fiber|locates)")) limit = REPORT_LIMIT;
                if (path.equals(TeamRouteWizard.PATH)) limit = PLAN_FORM_LIMIT;
                if (req.getContentLengthLong() > limit) throw new Problem(413, "Request is too large.");
            }
            if (Set.of("/app.css", "/worker.css", "/payroll-checks.css", "/payroll-print.css").contains(path)) {
                if (!method.equals("GET") && !method.equals("HEAD")) throw new Problem(405, "Method not supported.");
                res.setContentType("text/css");
                try (InputStream in = App.class.getResourceAsStream("/web" + path)) {
                    Objects.requireNonNull(in).transferTo(res.getOutputStream());
                }
                return;
            }
            try (Connection db = Db.open()) {
                String token = token(req);
                ObjectNode person = null;
                if (!token.isEmpty()) {
                    try { person = Auth.me(db, token); }
                    catch (Problem err) { if (err.status != 401) throw err; }
                }
                if (person != null && person.path("must_change_password").asBoolean()) {
                    if (api) {
                        boolean allowed = method.equals("GET") && path.equals("/api/me")
                            || method.equals("POST") && Set.of("/api/change-password", "/api/logout").contains(path);
                        if (!allowed) throw new Problem(403, "Change your temporary password to continue.");
                    } else if (!path.equals("/change-password") && !(method.equals("POST") && path.equals("/logout"))) {
                        String after = method.equals("GET") && person.path("is_admin").asBoolean() ? Views.returnPath(path) : "";
                        String next = "/change-password" + (after.isEmpty() ? "" : "?return_to=" + Views.url(after));
                        Views.redirect(res, next);
                        return;
                    }
                }
                if (api) handleApi(req, res, db, person);
                else Views.handle(req, res, db, person);
            }
        } catch (Problem e) {
            error(res, api, e.status, e.getMessage());
        } catch (IllegalStateException e) {
            if (req.getContentType() != null && req.getContentType().startsWith("multipart/")) {
                error(res, api, 413, "The upload exceeds the allowed size or number of files.");
            } else {
                LOG.log(Level.SEVERE, "Request failed", e);
                error(res, api, 500, "The request could not be completed.");
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Request failed", e);
            error(res, api, 500, "The request could not be completed.");
        }
    }

    private static void checkOrigin(HttpServletRequest req) {
        if ("cross-site".equals(req.getHeader("Sec-Fetch-Site"))) throw new Problem(403, "Use the Field Routes site to submit changes.");
        String origin = req.getHeader("Origin");
        if (origin == null) return;
        try {
            URI uri = URI.create(origin);
            if (Set.of("http", "https").contains(uri.getScheme()) && req.getHeader("Host").equalsIgnoreCase(uri.getRawAuthority())) return;
        } catch (IllegalArgumentException | NullPointerException ignored) { }
        throw new Problem(403, "Origin does not match this site.");
    }

    private static String token(HttpServletRequest req) {
        if (req.getCookies() != null) {
            for (Cookie cookie : req.getCookies()) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return "";
    }

    private static void cookie(HttpServletRequest req, HttpServletResponse res, String value, int age) {
        boolean secure = "1".equals(System.getenv().getOrDefault("FIELDOPS_SECURE_COOKIE", req.isSecure() ? "1" : "0"));
        res.addHeader("Set-Cookie", COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + age + (secure ? "; Secure" : ""));
    }

    static ObjectNode signIn(HttpServletRequest req, HttpServletResponse res, Connection db, String username, String password) throws Exception {
        Auth.Login login = Auth.login(db, username, password, req.getRemoteAddr());
        cookie(req, res, login.token(), 43200);
        return login.person();
    }

    static void signOut(HttpServletRequest req, HttpServletResponse res, Connection db) throws Exception {
        Auth.logout(db, token(req));
        cookie(req, res, "", 0);
    }

    static void changePassword(HttpServletRequest req, HttpServletResponse res, Connection db,
                               String currentPassword, String newPassword) throws Exception {
        Auth.changePassword(db, token(req), currentPassword, newPassword);
        cookie(req, res, "", 0);
    }

    private static void handleApi(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode person) throws Exception {
        String method = req.getMethod();
        String path = req.getRequestURI().substring(4);
        if (method.equals("POST") && path.equals("/login")) {
            ObjectNode input = body(req);
            if (input.size() != 2 || !input.path("username").isTextual() || !input.path("password").isTextual())
                throw new Problem(422, "Enter your username and password.");
            json(res, 200, signIn(req, res, db, input.get("username").textValue(), input.get("password").textValue()));
            return;
        }
        if (method.equals("POST") && path.equals("/logout")) {
            signOut(req, res, db);
            json(res, 200, JSON.createObjectNode().put("ok", true));
            return;
        }
        if (person == null) throw new Problem(401, "Sign in to continue.");
        if (method.equals("POST") && path.equals("/change-password")) {
            if (!person.path("is_admin").asBoolean()) throw new Problem(403, "Your administrator manages your password.");
            ObjectNode input = body(req);
            if (input.size() != 2 || !input.path("current_password").isTextual() || !input.path("new_password").isTextual())
                throw new Problem(422, "Enter your current and new passwords.");
            changePassword(req, res, db, input.get("current_password").textValue(), input.get("new_password").textValue());
            json(res, 200, JSON.createObjectNode().put("ok", true));
            return;
        }
        String[] parts = path.split("/", -1);
        String resource = parts[1];
        if (Set.of("profiles", "roles", "teams", "entities").contains(resource) || (resource.equals("routes") && method.equals("POST"))) {
            if (!person.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        }
        if (parts.length == 2) {
            if (method.equals("GET")) {
                JsonNode result = switch (resource) {
                    case "me" -> person;
                    case "profiles" -> Store.profiles(db);
                    case "roles" -> Store.roles(db);
                    case "teams" -> Store.teams(db);
                    case "entities" -> Store.entities(db);
                    case "routes" -> Store.routes(db, person, req.getParameter("date"));
                    default -> throw new Problem(404, "Resource not found.");
                };
                json(res, 200, result);
                return;
            }
            if (method.equals("POST")) {
                ObjectNode input = body(req);
                ObjectNode result = switch (resource) {
                    case "profiles" -> Store.saveProfile(db, input, null);
                    case "roles" -> Store.addRole(db, input);
                    case "teams" -> Store.saveTeam(db, input, null);
                    case "entities" -> Store.addEntity(db, input);
                    case "routes" -> Store.createRoute(db, input);
                    default -> throw new Problem(404, "Resource not found.");
                };
                json(res, 201, result);
                return;
            }
        } else if (parts.length == 3) {
            long id = id(parts[2]);
            if (method.equals("GET") && resource.equals("jobs")) {
                json(res, 200, JobDetails.get(db, person, id));
                return;
            }
            if (method.equals("PUT") && resource.equals("profiles")) { json(res, 200, Store.saveProfile(db, body(req), id)); return; }
            if (method.equals("PUT") && resource.equals("teams")) { json(res, 200, Store.saveTeam(db, body(req), id)); return; }
            if (method.equals("GET") && resource.equals("photos")) {
                sendPhoto(res, db, person, id);
                return;
            }
        } else if (parts.length == 4 && resource.equals("jobs")) {
            long id = id(parts[2]);
            if (method.equals("POST") && parts[3].equals("report")) {
                Store.requireJob(db, person, id);
                json(res, 200, jobReport(req, db, person, id));
                return;
            }
            if (method.equals("POST") && parts[3].equals("notes")) {
                ObjectNode input = body(req);
                StoreInput.fields(input, "text");
                json(res, 201, JobDetails.addNote(db, person, id, input.path("text").textValue()));
                return;
            }
            if (method.equals("PUT") && parts[3].equals("address")) {
                ObjectNode input = body(req);
                StoreInput.fields(input, "address", "revision", "external_job_id");
                long revision = StoreInput.integer(input.get("revision"), "revision");
                String externalId = input.has("external_job_id") ? StoreInput.text(input, "external_job_id", 0, 120) : null;
                json(res, 200, JobDetails.updateAddress(db, person, id, input.path("address").textValue(), revision, externalId));
                return;
            }
        }
        throw new Problem(404, "Resource not found.");
    }

    private static void sendPhoto(HttpServletResponse res, Connection db, ObjectNode person, long id) throws Exception {
        sendPhoto(res, () -> Store.photo(db, person, id));
    }

    static void sendPhoto(HttpServletResponse res, StoreSql.Work<Store.Photo, Exception> reader) throws Exception {
        // The verified image stays in memory until the servlet has sent it to the client.
        boolean acquired;
        try { acquired = PHOTO_RESPONSES.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); acquired = false; }
        if (!acquired) {
            res.setHeader("Retry-After", "3");
            throw new Problem(503, "Photo downloads are busy. Please try again.");
        }
        try {
            Store.Photo photo = reader.run();
            res.setContentType(photo.mime());
            res.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            res.setContentLength(photo.content().length);
            res.getOutputStream().write(photo.content());
        } finally { PHOTO_RESPONSES.release(); }
    }

    private static long id(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new Problem(422, "Invalid record number."); }
    }

    private static ObjectNode jobReport(HttpServletRequest req, Connection db, ObjectNode person, long id) throws Exception {
        List<Part> photos = new ArrayList<>();
        String type = Objects.toString(req.getContentType(), "");
        String report = null;
        if (type.startsWith("multipart/form-data")) {
            try {
                for (Part part : req.getParts()) {
                    if (part.getName().equals("photos") && part.getSubmittedFileName() != null) photos.add(part);
                    else if (part.getName().equals("report") && report == null && part.getSubmittedFileName() == null && part.getSize() <= MIB) {
                        try (InputStream in = part.getInputStream()) { report = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                    } else throw new Problem(422, "Invalid report fields.");
                }
            } catch (ServletException e) { throw new Problem(422, "Invalid multipart report."); }
        } else if (type.startsWith("application/x-www-form-urlencoded")) {
            if (!req.getParameterMap().keySet().equals(Set.of("report"))) throw new Problem(422, "Invalid report fields.");
            report = req.getParameter("report");
        }
        if (report == null || report.length() > MIB || photos.size() > 10) throw new Problem(422, "Include one report and up to 10 photos.");
        return Store.report(db, person, id, object(report), photos);
    }

    private static ObjectNode body(HttpServletRequest req) throws IOException {
        byte[] data = req.getInputStream().readNBytes(MIB + 1);
        if (data.length > MIB) throw new Problem(413, "Request is too large.");
        return object(new String(data, StandardCharsets.UTF_8));
    }

    private static ObjectNode object(String value) {
        try {
            if (JSON.readTree(value) instanceof ObjectNode object) return object;
        } catch (JsonProcessingException ignored) { }
        throw new Problem(422, "Provide a valid JSON object.");
    }

    private static void json(HttpServletResponse res, int status, JsonNode value) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        JSON.writeValue(res.getOutputStream(), value);
    }

    private static void error(HttpServletResponse res, boolean api, int status, String message) throws IOException {
        if (res.isCommitted()) return;
        res.resetBuffer();
        if (api) json(res, status, JSON.createObjectNode().put("detail", message));
        else {
            res.setStatus(status);
            res.setContentType("text/plain");
            res.getWriter().write(message);
        }
    }
}
