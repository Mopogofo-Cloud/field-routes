package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

final class LoginSheet {
    static final String PATH = "/admin/contractor-logins.xlsx";
    private static final Path FILE = Path.of("contractor-logins.xlsx");
    private static final Duration VALIDITY = Duration.ofHours(24);

    private LoginSheet() {}

    static void serve(HttpServletRequest req, HttpServletResponse res, ObjectNode person) throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".local", "state", "wg-fieldops", "login-handoffs");
        serve(req, res, person, root, Clock.systemUTC());
    }

    static void serve(HttpServletRequest req, HttpServletResponse res, ObjectNode person, Path root, Clock clock)
            throws IOException {
        res.setHeader("Cache-Control", "no-store");
        boolean head = "HEAD".equals(req.getMethod());
        if (!head && !"GET".equals(req.getMethod())) {
            res.setHeader("Allow", "GET, HEAD");
            throw new Problem(405, "Method not supported.");
        }
        if (person == null) throw new Problem(401, "Sign in to continue.");
        if (!person.path("is_admin").booleanValue() || person.path("must_change_password").asBoolean())
            throw new Problem(403, "Administrator access required.");
        var id = person.path("id");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !root.isAbsolute())
            throw unavailable();
        Path recipient = root.resolve(Long.toString(id.longValue()));
        try (SecureDirectoryStream<Path> directory = openDirectory(recipient)) {
            BasicFileAttributeView view = directory.getFileAttributeView(FILE, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes before = view.readAttributes();
            Instant issued = before.lastModifiedTime().toInstant();
            Instant now = clock.instant();
            if (!before.isRegularFile() || before.fileKey() == null || issued.isAfter(now)
                    || !issued.isAfter(now.minus(VALIDITY))) throw unavailable();
            try (SeekableByteChannel file = directory.newByteChannel(FILE,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                BasicFileAttributes after = view.readAttributes();
                if (!after.isRegularFile() || !before.fileKey().equals(after.fileKey())
                        || !before.lastModifiedTime().equals(after.lastModifiedTime()) || file.size() != before.size())
                    throw unavailable();
                res.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                res.setHeader("Content-Disposition", "attachment; filename=\"contractor-logins.xlsx\"");
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setContentLengthLong(before.size());
                if (!head) Channels.newInputStream(file).transferTo(res.getOutputStream());
            }
        } catch (IOException e) {
            if (res.isCommitted()) throw e;
            throw unavailable();
        }
    }

    // Resolve every directory relative to an open parent, so symlinks cannot redirect a handoff.
    private static SecureDirectoryStream<Path> openDirectory(Path target) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(target.getRoot());
        if (!(stream instanceof SecureDirectoryStream<Path> directory)) {
            stream.close();
            throw unavailable();
        }
        try {
            for (Path name : target) {
                var next = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                directory.close();
                directory = next;
            }
            return directory;
        } catch (IOException | RuntimeException e) {
            directory.close();
            throw e;
        }
    }

    private static Problem unavailable() {
        return new Problem(404, "This download is unavailable or has expired.");
    }
}
