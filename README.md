# access

A small Java CLI for reading Microsoft Access databases (`.accdb`, `.accde`, `.mdb`) on Linux/macOS without Access or Windows, and for exporting them to SQLite.

Built for the Remarketing databases: **Fleet.accde** (the front end: local tables, forms, reports, plus links into the back ends) and **RMData.accdb** (the main back end, password-protected).

Everything runs in Docker. You don't need a local JDK or Maven.

## Stack

| Library | Used for |
|---|---|
| [Jackcess](https://github.com/spannm/jackcess) (`io.github.spannm`) | Reading the Access file format directly: tables, metadata, links, streaming rows |
| [jackcess-encrypt](https://github.com/spannm/jackcess-encrypt) | Opening password-protected / encrypted databases |
| [UCanAccess](https://github.com/spannm/ucanaccess) | JDBC driver: Access SQL via an in-memory or on-disk HSQLDB mirror |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Writing the SQLite exports |

> **Note:** UCanAccess 5.3 is built against **spannm's fork** of Jackcess (`io.github.spannm`). The original `com.healthmarketscience` jars fail with `NoClassDefFoundError`. Don't mix the two.

## Setup

1. Put the Access files in `access-dbs/` (the folder is gitignored):

   ```
   access-dbs/Fleet.accde
   access-dbs/RMData.accdb
   ```

2. Create `.env` (also gitignored) with the RMData password:

   ```sh
   ACCESS_PASSWORD='the-password'
   ```

   **Keep the single quotes.** Compose expands `$` inside `.env` values, and the password contains one. Without the quotes, part of the value silently disappears and you get `wrong or missing password`.

3. Build the image and load the data:

   ```sh
   mise run build   # compiles and runs the tests
   mise run load    # copies the Access files into the Docker volume, then relinks Fleet
   ```

The container reads the Access files from the named volume `access-data` (mounted at `/data`), not from a bind mount. `mise run load` copies them in from `access-dbs/` with `docker cp`, and commands refer to them by bare file name (`Fleet.accde`). Edits to the originals don't show up until you run `load` again.

## Usage

```sh
mise run tables Fleet.accde                                  # list tables, row counts, link targets
mise run query RMData.accdb "SELECT TOP 5 * FROM Inventory"  # Access SQL, CSV to stdout
mise run sqlite                                              # write out/Fleet.sqlite and out/RMData.sqlite
mise run access -- --help                                    # anything else
```

### Commands

| Command | What it does |
|---|---|
| `tables <db>` | Lists every table: `LOCAL` with its row count, or `LINK` with its target file. Uses Jackcess, so it's instant. |
| `query <db> <sql>` | Runs Access SQL through UCanAccess and prints CSV. Loads the whole database first (see [Performance](#performance)). |
| `to-sqlite <db> <out.sqlite>` | Streams every local table (data, primary keys, indexes) into a new SQLite file. Skips linked tables and saved queries. |
| `relink <db> <file> <new-path>` | Repoints linked tables whose target file name is `<file>` to `<new-path>`. Writes `<db>`. |

### Options

| Option | |
|---|---|
| `--password <pw>` | Database password. Defaults to `$ACCESS_PASSWORD`. |
| `--write` | Open the file writable. **Default is read-only**, at the file level, not just SQL. |
| `--memory` | `query`: keep UCanAccess's HSQLDB mirror in the heap instead of on disk (the default). Faster for small files, but RMData runs out of memory. |
| `--dry-run` | `relink`: show what would change without writing. |
| `--force` | `to-sqlite`: replace an existing output file. |
| `--verbose` | Show UCanAccess warnings (missing linked files, Access queries it can't convert). |

## SQLite export

`mise run sqlite` writes `out/RMData.sqlite` (about 805 MB, 229 tables, 2.79M rows) and `out/Fleet.sqlite` (about 71 MB, 249 tables) in roughly 40 seconds.

- Every local table is copied, with its primary key and indexes. Index names are prefixed with the table name (`Cars__VinIdx`) because SQLite index names are database-wide.
- **Type mapping:**
  - Yes/No becomes `0`/`1`.
  - Currency and Decimal are stored as exact `NUMERIC` (no float rounding).
  - Dates become `YYYY-MM-DD HH:MM:SS` text, which works with SQLite's `date()` and `datetime()`.
  - OLE and binary columns become `BLOB`.
- **Linked tables are skipped.** Fleet's links point into RMData and other back ends. To query them together:

  ```sql
  ATTACH 'out/RMData.sqlite' AS rm;
  ```

- **Saved queries are not exported.** Access SQL isn't SQLite SQL.
- A few RMData tables (Orders, OrderDetails, Damage_Review_History, HFS) have a stale row count in their Access table header. The export counts actual rows, so it differs slightly from what Access reports. Compact & Repair in Access fixes the header.

## Linked tables and passwords

Fleet.accde has 290 linked tables. 209 of them point at `C:\Remarketing\RMDATA\RMData.accdb`, and the rest at other back ends we don't have (GM, Issues, IncentiveCompensation, and a few ODBC/SQL Server sources).

- `mise run load` runs `mise run relink`, which rewrites the RMData links in the **volume copy** of Fleet to `/data/RMData.accdb`. `access-dbs/Fleet.accde` is never modified.
- Link paths must be **absolute**. UCanAccess resolves a relative link against the working directory, not the front-end's folder.
- Access stores each link's password in the link itself (`MSysObjects.Connect`, `MS Access;PWD=...`). UCanAccess ignores that and tries the front end's password instead, which fails for RMData. `CryptOpener` reads those per-link passwords into memory when it opens a front end and uses them for the matching back end. They're never logged or written anywhere.

## Performance

UCanAccess copies **every reachable table** into HSQLDB before it runs any SQL, so even `SELECT COUNT(*)` pays the full load cost:

| | Load time |
|---|---|
| Fleet.accde, local tables only | ~12 s |
| RMData.accdb (on-disk mirror, the default) | ~24 s |
| RMData.accdb with `--memory` | **killed for running out of memory** (exit 137) in a 7.7 GB Docker VM |
| Fleet.accde with RMData links resolved | > 10 min, didn't finish |

For bulk reads, prefer `tables` / `to-sqlite` (Jackcess streams rows and loads nothing up front), or query the SQLite exports. Use `query` against RMData directly rather than going through Fleet.

## Forms, reports and VBA

None of these libraries can interpret Access forms or reports.

- **Names are available.** Fleet has 494 forms, 203 reports, 92 macros, 36 modules and 2,215 saved queries, all listed in `MSysObjects` and readable with Jackcess.
- **Definitions are raw binary.** They're stored as undocumented streams in `MSysAccessStorage`. Readable strings (control names, bound fields) can be pulled out heuristically, but not full layouts or event logic.
- **VBA source is gone.** Fleet is an `.accde`, which keeps only compiled code. For real form and report definitions or VBA source, you need the original `Fleet.accdb` and Access on Windows (`Application.SaveAsText`).

## Development

```sh
mise run test    # JUnit tests in the Maven build container, uncached
mise run build   # build the runtime image (also runs the tests)
mise run ls      # list files in the data volume
mise run down    # remove containers, keep the data volume
mise run clean   # remove containers, image and the data volume
```

Tests build throwaway `.accdb` fixtures with Jackcess, so they never need the real data. jackcess-encrypt can't *create* encrypted databases, so the password path is covered by a unit test of the link-password parsing, not by an end-to-end encrypted fixture.

### Layout

```
src/main/java/com/fracdev/access/
  App.java           CLI: argument parsing and commands
  Databases.java     Opens via Jackcess (direct) or UCanAccess (JDBC); builds the JDBC URL
  CryptOpener.java   UCanAccess opener: encryption, per-link passwords, file-level read-only
  Relink.java        Rewrites MSysObjects link targets
  SqliteExport.java  Jackcess to SQLite streaming export
  Csv.java           RFC 4180 output for query results
```

The image is multi-stage: `maven:3.9.11-eclipse-temurin-21` builds and tests, and `eclipse-temurin:21.0.8_9-jre` runs as a non-root user. It's a CLI, so there are no ports and no Traefik route.
