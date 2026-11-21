package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LoginSheetTest {
    @TempDir Path root;
    private final Instant now = Instant.parse("2026-01-15T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ObjectNode admin = App.JSON.createObjectNode().put("id", 3).put("is_admin", true);
    private final byte[] content = {1, 2, 3, 4};

    private Path fixture() throws Exception {
        Path file = Files.createDirectories(root.resolve("3")).resolve("contractor-logins.xlsx");
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.from(now));
        return file;
    }

    private static class Reply {
        final Map<String,String> headers = new HashMap<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ServletOutputStream stream = new ServletOutputStream() {
            public boolean isReady() { return true; }
            public void setWriteListener(WriteListener listener) {}
            public void write(int value) { bytes.write(value); }
        };
        final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (obj,m,args) -> {
                switch(m.getName()) {
                    case "setHeader" -> headers.put((String)args[0], (String)args[1]);
                    case "setContentLengthLong" -> headers.put("Content-Length", String.valueOf(args[0]));
                    case "setContentType" -> headers.put("Content-Type", (String)args[0]);
                    case "getOutputStream" -> { return stream; }
                    case "isCommitted" -> { return false; }
                    default -> throw new UnsupportedOperationException(m.getName());
                }
                return null;
            });
    }

    private void serve(String method, ObjectNode user, Path dir, Reply result) throws Exception {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (obj,m,args) -> {
                if (m.getName().equals("getMethod")) return method;
                throw new UnsupportedOperationException(m.getName());
            });
        LoginSheet.serve(request, result.response, user, dir, clock);
    }

    private void denied(String method, ObjectNode user, Path dir, int status) {
        Reply result = new Reply();
        assertEquals(status, assertThrows(Problem.class, () -> serve(method, user, dir, result)).status);
        assertEquals("no-store", result.headers.get("Cache-Control"));
        assertFalse(result.headers.containsKey("Content-Disposition"));
        assertEquals(0, result.bytes.size());
    }

    @Test void onlyTheRecipientCanDownloadOrInspectHeaders() throws Exception {
        fixture();
        for (String method : new String[]{"GET","HEAD"}) {
            Reply reply = new Reply();
            serve(method, admin, root, reply);
            assertArrayEquals(method.equals("GET") ? content : new byte[0], reply.bytes.toByteArray());
            assertEquals("attachment; filename=\"contractor-logins.xlsx\"", reply.headers.get("Content-Disposition"));
            assertEquals("4", reply.headers.get("Content-Length"));
            assertEquals("no-store", reply.headers.get("Cache-Control"));
            denied(method, null, root, 401);
            denied(method, admin.deepCopy().put("is_admin", false), root, 403);
            denied(method, admin.deepCopy().put("id", 4), root, 404);
            denied(method, admin.deepCopy().put("must_change_password", true), root, 403);
        }
        denied("POST", admin, root, 405);
        denied("GET", admin.deepCopy().put("id", "3"), root, 404);
    }

    @Test void rejectsExpiredAndFutureDatedHandoffs() throws Exception {
        Path file = fixture();
        Files.setLastModifiedTime(file, FileTime.from(now.minusSeconds(86399)));
        serve("HEAD", admin, root, new Reply());
        for (Instant invalid : new Instant[]{now.minusSeconds(86400), now.plusSeconds(1)}) {
            Files.setLastModifiedTime(file, FileTime.from(invalid));
            denied("GET", admin, root, 404);
        }
    }

    @Test void rejectsSymlinksAtAnyLevelAndNonregularFiles() throws Exception {
        Path file = fixture();
        Files.createSymbolicLink(root.resolve("4"), root.resolve("3"));
        denied("GET", admin.deepCopy().put("id", 4), root, 404);
        Path nested = Files.createDirectory(root.resolve("links"));
        Path alias = Files.createSymbolicLink(nested.resolve("alias"), root);
        denied("GET", admin, alias, 404);
        Files.move(file, root.resolve("outside"));
        Files.createSymbolicLink(file, root.resolve("outside"));
        denied("GET", admin, root, 404);
        Files.delete(file);
        Files.createDirectory(file);
        denied("GET", admin, root, 404);
    }
}
