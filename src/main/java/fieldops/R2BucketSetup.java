package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** One-time management operation. The separate S3 credentials are used for photo uploads. */
final class R2BucketSetup {
    static final String BUCKET = "field-routes-photos";
    private static final URI API = URI.create("https://api.cloudflare.com/client/v4/");
    private static final int MAX_RESPONSE = 128 * 1024;

    private R2BucketSetup() {}

    static String templateLink(String accountId) {
        checkAccount(accountId);
        return "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" +
            "%5B%7B%22key%22%3A%22workers_r2%22%2C%22type%22%3A%22edit%22%7D%5D" +
            "&accountId=" + accountId + "&zoneId=all&name=Field%20Routes%20Bucket%20Setup";
    }

    /** True when created, false when the named bucket already existed. */
    static boolean create(String accountId, String token) {
        return create(API, accountId, token);
    }

    // The public entry point always uses the fixed Cloudflare origin; tests use a local fixture.
    static boolean create(URI api, String accountId, String token) {
        checkAccount(accountId);
        if (token == null || !token.matches("[!-~]{1,4096}"))
            throw new Problem(422, "Enter the Cloudflare setup API token.");
        URI collection = api.resolve("accounts/" + accountId + "/r2/buckets");
        URI bucket = URI.create(collection + "/" + BUCKET);
        Result before = request(bucket, token, false);
        if (before.success()) {
            confirmBucket(before);
            return false;
        }
        if (before.status != 404 || !before.code(10006)) throw failure(before);
        Result created = request(collection, token, true);
        if (created.success()) {
            confirmBucket(created);
            return true;
        }
        // A concurrent request may complete after our lookup and before our creation request.
        if (created.status == 409 || created.code(10073)) {
            Result found = request(bucket, token, false);
            if (!found.success()) throw failure(found);
            confirmBucket(found);
            return false;
        }
        throw failure(created);
    }

    private static void checkAccount(String accountId) {
        if (accountId == null || !accountId.matches("[0-9A-Fa-f]{32}"))
            throw new Problem(422, "Enter a valid Cloudflare account ID.");
    }

    private static void confirmBucket(Result response) {
        JsonNode result = response.data.path("result");
        if (!BUCKET.equals(result.path("name").asText())) throw unexpected();
        if (!"default".equals(result.path("jurisdiction").asText("default")) ||
                !"Standard".equals(result.path("storage_class").asText("Standard")))
            throw new Problem(409, "The existing bucket uses a different jurisdiction or storage class. Check its settings in Cloudflare.");
    }

    private record Result(int status, JsonNode data) {
        boolean success() { return status >= 200 && status < 300 && data.path("success").asBoolean(false); }
        boolean code(int number) {
            for (JsonNode issue : data.path("errors")) if (issue.path("code").asInt() == number) return true;
            return false;
        }
    }

    private static Result request(URI target, String token, boolean create) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection)target.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("cf-r2-jurisdiction", "default");
            if (create) {
                byte[] body = ("{\"name\":\"" + BUCKET + "\",\"storageClass\":\"Standard\"}").getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(body.length);
                try (var out = conn.getOutputStream()) { out.write(body); }
            }
            int status = conn.getResponseCode();
            // Neither Cloudflare error text nor transport exceptions enter application logs or HTML.
            if (status >= 300 && status < 400) throw unexpected();
            try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                JsonNode data = App.JSON.createObjectNode();
                if (in != null) {
                    byte[] bytes = in.readNBytes(MAX_RESPONSE + 1);
                    if (bytes.length > MAX_RESPONSE) throw unexpected();
                    if (bytes.length != 0) data = App.JSON.readTree(bytes);
                }
                if (data == null || !data.isObject()) throw unexpected();
                return new Result(status, data);
            }
        } catch (IOException e) {
            throw new Problem(502, "Could not confirm the bucket with Cloudflare. Retry to check its current state.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Problem failure(Result result) {
        if (result.code(10042) || result.code(10136))
            return new Problem(422, "R2 must be enabled for this account in the Cloudflare dashboard first.");
        if (result.status == 401 || result.status == 403)
            return new Problem(422, "Cloudflare declined the setup token. Check its account and Workers R2 Storage Write permission.");
        if (result.status == 429)
            return new Problem(429, "Cloudflare is rate limiting this request. Try again later.");
        return unexpected();
    }

    private static Problem unexpected() {
        return new Problem(502, "Cloudflare could not confirm the requested R2 bucket. Check the account and retry.");
    }
}
