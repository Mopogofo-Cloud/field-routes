# Field operations on serverpc

On serverpc this repository lives in `~/projects/wg-server/fieldops`. Run commands that use a `fieldops/` path from its parent, `~/projects/wg-server`. To build from the repository root on another computer with Java 21 and Maven, run `mvn package`.

The public Field Routes address is `https://serverpc.tail779787.ts.net/`. Tailscale Funnel forwards HTTPS traffic to the Java 21 service on `http://127.0.0.1:8766/`. The systemd user service starts at boot with the existing `mopogofo` user manager. Field Routes uses America/New_York for a worker’s route date. Java renders both the admin and worker pages as HTML and serves their CSS.

## Administrator access

The first account is `admin`. Its initial password is stored in the owner-readable file `~/.local/state/wg-fieldops/admin-password` on serverpc. Save it to your password manager and remove the handoff file once saved. Share individual subcontractor accounts with their respective workers.

For private administration from a computer with existing SSH access to serverpc, run the following and open `http://localhost:8766/` on that computer.

```sh
ssh -N -L localhost:8766:127.0.0.1:8766 beelink-home
```

To create another administrator, run the following in the server terminal. Replace `new-admin` with the intended username. The command loads the production database settings and prompts privately for the password.

```sh
(
  set -ea
  . "$HOME/.config/wg-fieldops/server.env"
  : "${FIELDOPS_POSTGRES_URL:?Production PostgreSQL connection is missing}"
  /usr/lib/jvm/temurin-21-jdk-amd64/bin/java -cp "$HOME/.local/lib/wg-fieldops/current/field-routes.jar:$HOME/.local/lib/wg-fieldops/current/lib/*" fieldops.Auth new-admin
)
```

## Public HTTPS for subcontractors

Tailscale Funnel can provide the public HTTPS hostname on this server. Workers can open the link on a regular cellular connection. The local web process accepts connections on loopback port 8766. Funnel handles TLS, and the app requires a secure cookie for login.

On serverpc, check the existing forwarding rules. Use port 443 if free. This deployment uses the root of that HTTPS port exclusively for field operations.

```sh
tailscale funnel status --json
tailscale funnel --bg --https=443 http://127.0.0.1:8766
```

If Tailscale prints an activation link, the Tailscale account owner or admin must open it and approve Funnel. This enables HTTPS certificate issuance and the Funnel permission in the tailnet policy. Rerun the command if the original prompt has exited. A local Tailscale operator or system administrator may need to run the command. Public DNS can take up to ten minutes to update. [Tailscale Funnel documentation](https://tailscale.com/docs/features/tailscale-funnel)

Check `tailscale funnel status` for the published URL and that its only forwarding target on port 443 is `http://127.0.0.1:8766`. Open that URL with Tailscale and Wi-Fi off on a phone. Check the login screen and sign in with a worker account to verify the assigned daily route. To withdraw this field operations endpoint, run `tailscale funnel --https=443 off` on serverpc.

## Service and storage

```sh
systemctl --user status wg-fieldops
journalctl --user -u wg-fieldops --since today
systemctl --user restart wg-fieldops
```

Field Routes stores production data in Supabase PostgreSQL 17. Open Table Editor in project `hckgfxwxrnenwpvsmwdf` and select the private `fieldroutes` schema to inspect the records. The Java service runs on serverpc as `mopogofo` and connects as `fieldroutes_app` through the session pooler at `aws-0-us-west-2.pooler.supabase.com:5432`. JDBC verifies the server certificate and hostname. The systemd service reads the connection settings from `~/.config/wg-fieldops/server.env`. The service user's protected `~/.config/wg-fieldops/supabase/` directory holds the database password file and trusted certificate. [Supabase Table Editor](https://supabase.com/dashboard/project/hckgfxwxrnenwpvsmwdf/editor)

PostgreSQL holds accounts, routes, job reports, rate cards, payroll and the object keys linking reports to private Cloudflare R2 photos. Keep the `fieldroutes` schema private and outside the Data API's exposed schemas.

Mainline projects, daily assignments, footage, expenses and attachment references live in the same schema. To upgrade an existing PostgreSQL installation to the first Mainline release, apply [the Mainline migration](deploy/migrations/20261121-mainline.sql) as a database administrator before restarting the Java service. Fresh installations create the tables during initialization.

For project supervisor access, apply [the supervisor migration](deploy/migrations/20261121-mainline-supervisors.sql) after the Mainline tables exist and before deploying the corresponding Java release. An admin grants access by selecting a contractor account in the project editor. Supervisor names saved as plain text can be linked to an account there.

### Connect private Cloudflare R2 storage

1. Enable R2 in the Cloudflare account and sign in as the configured Field Routes owner. Open **Photo storage** in the account header.
2. To create the bucket in Field Routes, expand **Create field-routes-photos for me**, follow the link to issue a temporary Cloudflare R2 management token and submit it there. This creates a Standard bucket. Revoke that token after setup. You can also use a private bucket you created in Cloudflare. Leave public development access disabled and custom domains disconnected.
3. Under **R2 → Overview → API Tokens → Manage**, create a token with **Object Read & Write** for only your bucket. In **Connect your bucket**, enter the S3 endpoint, bucket name, access key ID and secret access key. **Verify and connect R2** tests an upload, download and deletion before enabling new uploads. [Cloudflare R2 setup guide](https://developers.cloudflare.com/r2/get-started/s3/)

The website stores the verified connection in `~/.config/wg-fieldops/r2.properties`, readable only by the service user. `FIELDOPS_STORAGE_OWNER_ID` in `~/.config/wg-fieldops/server.env` specifies the local administrator who can configure storage. `FIELDOPS_R2_ACCOUNT_ID` enables the bucket creation form and prefills the endpoint. Restart the service after changing the environment. Operators can rotate the S3 key ID and secret in the protected properties file and restart while retaining the same endpoint, bucket and prefix.

Cloud photo bytes live under `field-routes/photos/` in the bucket. Field Routes checks job access before serving a photo, and earlier local photos keep their original links. If R2 is unavailable, a new upload reports an error and the report can be retried. Removed or abandoned objects are queued for deletion by the service.

### Backup and restore

Use a PostgreSQL backup for current Field Routes records. A `pg_dump` custom-format backup of schema `fieldroutes` includes the data, views, constraints, triggers and identity sequences. Use a backup role with access to the entire schema, save the archive in protected storage, and test restoration in an isolated database. Preserve the role setup and the server's protected connection settings separately. Back up the corresponding R2 objects as well. Restoring a report can require a photo that was subsequently removed from the bucket. Isolate restoration tests from the production R2 credentials because the cleanup worker can delete queued objects.

The final pre-switch SQLite backup is `/home/mopogofo/.local/share/wg-fieldops/backups/supabase-cutover-20261120T234128Z-sApTW6EN`. It contains the database and SQLite sidecars, checksums, the previous release reference and service configuration, and the import and verification logs. The switch on November 20, 2026 verified all 437 rows across 29 tables. Work submitted after the switch is recorded in PostgreSQL. Keep both the archive and its configuration owner-readable.

For an application rollback, use a PostgreSQL-compatible release with the live database. To return to SQLite, pause all writers, back up the current PostgreSQL data and R2 objects, migrate or reconcile changes since the cutover into a working copy of the archived SQLite database, and verify records, account access and photo links before reopening service. Preserve the original archive for recovery.

### Rehearsing a SQLite import into a fresh PostgreSQL destination

The production service already uses PostgreSQL. These steps support a future migration rehearsal in a separate destination. `FIELDOPS_POSTGRES_URL` selects PostgreSQL. A process started without that variable opens SQLite. Field Routes preserves the original record IDs for logins, contractor access and R2 photo links.

1. In the destination project's **Connect** dialog, choose **Session pooler** for an IPv4 connection. Copy the hostname and port 5432. The pooler login is `ROLE.PROJECT_REF`. Download the project's root certificate from **Database Settings → SSL Configuration** if the server's trusted roots do not include the endpoint's issuing authority. [Supabase connection guide](https://supabase.com/docs/guides/database/connecting-to-postgres)
2. For the import, use a database role that can create and own the `fieldroutes` schema. Supply the following variables to the importer process. Store the password in a mode-0600 file readable by the server's service user. Substitute the actual hostname, project reference, role and certificate path. The executable and `lib` directory must come from the PostgreSQL-capable build.

   ```sh
   export FIELDOPS_POSTGRES_URL='jdbc:postgresql://POOLER_HOST:5432/postgres?sslmode=verify-full&sslrootcert=/absolute/path/to/trusted-roots.pem'
   export FIELDOPS_POSTGRES_USER='IMPORT_ROLE.PROJECT_REF'
   export FIELDOPS_POSTGRES_PASSWORD_FILE='/absolute/path/to/import-password'
   java -cp 'fieldops/target/field-routes.jar:fieldops/target/lib/*' fieldops.PostgresImport copy /absolute/path/to/fieldops.sqlite3
   java -cp 'fieldops/target/field-routes.jar:fieldops/target/lib/*' fieldops.PostgresImport verify /absolute/path/to/fieldops.sqlite3
   ```

   Use a fresh destination for each copy. The importer creates the tables, transfers rows and IDs, and checks every column before committing. The separate verification command reads both databases again. Both commands open SQLite read-only. For rehearsals, use a consistent backup. Run application tests against isolated databases with separate photo settings.

3. Create a separate login for the running application. As the schema owner, grant that login the following permissions, substituting its role name.

   ```sql
   GRANT USAGE ON SCHEMA fieldroutes TO fieldroutes_app;
   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fieldroutes TO fieldroutes_app;
   GRANT USAGE ON ALL SEQUENCES IN SCHEMA fieldroutes TO fieldroutes_app;
   ```

4. For a rehearsal, start an isolated instance with the test database and separate photo settings. Verify sign-in, assigned routes, a report and the Friday payroll preview.

For automated PostgreSQL regression tests, set `FIELDOPS_TEST_POSTGRES_PORT`, `FIELDOPS_TEST_POSTGRES_USER` and `FIELDOPS_TEST_POSTGRES_PASSWORD_FILE` for a disposable local PostgreSQL server. Its test login needs permission to create databases. Maven runs the PostgreSQL cases alongside the SQLite suite. The PostgreSQL tests create temporary databases on loopback and remove them after use.

## Build and install a Java release

When rolling back a deployment after a dispatcher has removed stops, use a release that enforces the `job_removals` table. Earlier releases would show those archived stops and reports to assigned contractors again.

From the project directory, build the JAR and run the tests with the server’s Maven installation. Each release has its own directory so rebuilding leaves the running service intact until restart.

```sh
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
"$HOME/.local/opt/apache-maven-3.9.16/bin/mvn" -f fieldops/pom.xml clean package
release_root="$HOME/.local/lib/wg-fieldops"
install -d -m 700 "$release_root" "$HOME/.local/share/wg-fieldops"
release_dir=$(mktemp -d "$release_root/release.XXXXXXXX")
cp -r fieldops/target/field-routes.jar fieldops/target/lib "$release_dir/"
ln -s "$release_dir" "$release_dir/activate"
mv -Tf "$release_dir/activate" "$release_root/current"
systemctl --user enable "$PWD/fieldops/deploy/wg-fieldops.service"
systemctl --user daemon-reload
systemctl --user restart wg-fieldops
curl -fsS http://127.0.0.1:8766/login
```
