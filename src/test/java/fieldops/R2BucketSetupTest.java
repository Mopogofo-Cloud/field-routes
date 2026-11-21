package fieldops;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R2BucketSetupTest {
    private static final String ACCOUNT = "aa".repeat(16);
    private static final String TOKEN = "synthetic-test-token";
    private static final String API_PATH = "/client/v4/accounts/" + ACCOUNT + "/r2/buckets";
    private static final String GET = "GET " + API_PATH + "/field-routes-photos";
    private static final String POST = "POST " + API_PATH;
    private static final String BUCKET = """
        {"success":true,"result":{"name":"field-routes-photos","jurisdiction":"default","storage_class":"Standard"}}
        """;
    private static final String ABSENT = """
        {"success":false,"errors":[{"code":10006}]}
        """;
    private record Reply(int status, String data) {}
    private record Call(String target, String token, String jurisdiction, String body) {}

    private HttpServer fixture;
    private URI api;
    private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Call> calls = new ArrayList<>();

    @BeforeEach void setUp() throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort() + "/client/v4/");
        fixture.createContext("/", exchange -> {
            try (exchange) {
                calls.add(new Call(exchange.getRequestMethod() + " " + exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("cf-r2-jurisdiction"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(503, "{}");
                if (reply.status == 307) exchange.getResponseHeaders().add("Location", "/unexpected-target");
                byte[] body = reply.data.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        fixture.start();
    }

    @AfterEach void tearDown() { fixture.stop(0); }

    @Test void createsStandardBucketAndDetectsRepeat() throws Exception {
        replies.add(new Reply(404, ABSENT));
        replies.add(new Reply(200, BUCKET));
        replies.add(new Reply(200, BUCKET));
        assertTrue(R2BucketSetup.create(api, ACCOUNT, TOKEN));
        assertFalse(R2BucketSetup.create(api, ACCOUNT, TOKEN));
        assertEquals(List.of(GET, POST, GET), calls.stream().map(Call::target).toList());
        assertEquals(App.JSON.readTree("{\"name\":\"field-routes-photos\",\"storageClass\":\"Standard\"}"),
            App.JSON.readTree(calls.get(1).body));
        for (Call call : calls) {
            assertEquals("Bearer " + TOKEN, call.token);
            assertEquals("default", call.jurisdiction);
        }
    }

    @Test void concurrentCreateIsConfirmedWithoutChangingBucket() {
        replies.add(new Reply(404, ABSENT));
        replies.add(new Reply(409, "{\"success\":false,\"errors\":[{\"code\":10073}]}"));
        replies.add(new Reply(200, BUCKET));
        assertFalse(R2BucketSetup.create(api, ACCOUNT, TOKEN));
        assertEquals(List.of(GET, POST, GET), calls.stream().map(Call::target).toList());
    }

    @Test void safeErrorsNeverForwardTokenOrModifyAnExistingBucket() {
        for (Reply reply : List.of(new Reply(403, "{\"errors\":[{\"message\":\"" + TOKEN + "\"}]}"),
                new Reply(307, "{}"), new Reply(404, "{}"), new Reply(429, "{}"),
                new Reply(200, BUCKET.replace("Standard", "InfrequentAccess")),
                new Reply(500, "unparseable " + TOKEN))) {
            replies.add(reply);
            int count = calls.size();
            Problem e = assertThrows(Problem.class, () -> R2BucketSetup.create(api, ACCOUNT, TOKEN));
            assertFalse(e.getMessage().contains(TOKEN));
            assertEquals(count + 1, calls.size());
            assertEquals(GET, calls.get(count).target);
        }
        assertThrows(Problem.class, () -> R2BucketSetup.create(api, "../", TOKEN));
        assertThrows(Problem.class, () -> R2BucketSetup.create(api, ACCOUNT, "bad\nheader"));
    }

    @Test void templateSelectsOnlyR2EditForOneAccount() {
        URI link = URI.create(R2BucketSetup.templateLink(ACCOUNT));
        assertEquals("https", link.getScheme());
        assertEquals("dash.cloudflare.com", link.getHost());
        assertEquals("/profile/api-tokens", link.getPath());
        String query = URLDecoder.decode(link.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("permissionGroupKeys=[{\"key\":\"workers_r2\",\"type\":\"edit\"}]"));
        assertTrue(query.contains("accountId=" + ACCOUNT + "&zoneId=all&"));
        assertThrows(Problem.class, () -> R2BucketSetup.templateLink("bad&zoneId=all"));
    }
}
