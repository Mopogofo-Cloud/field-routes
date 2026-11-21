package fieldops;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import software.amazon.awssdk.core.sync.RequestBody;

public final class PhotoStorageCheck {
    private PhotoStorageCheck() {}

    public static void verify(Map<String,String> settings) {
        try (S3Photos store = S3Photos.from(settings)) {
            if (store == null) throw new Problem(422, "Select S3-compatible private storage for this check.");
            verify(store);
        }
    }

    static void verify(S3Photos store) {
        String key = store.newKey();
        byte[] bytes = new byte[128];
        new SecureRandom().nextBytes(bytes);
        try {
            store.put(key, "application/octet-stream", RequestBody.fromBytes(bytes));
            if (!Arrays.equals(bytes, store.get(key, bytes.length, S3Photos.sha256(bytes)))) throw S3Photos.unavailable();
        } finally { store.delete(key); }
    }

    public static void main(String[] args) {
        try {
            verify(R2Setup.settings());
            System.out.println("Private object upload, verified download, and deletion succeeded.");
        } catch (RuntimeException ex) {
            System.err.println("Private object check failed. Check endpoint, bucket, credentials, and object permissions.");
            System.exit(1);
        }
    }
}
