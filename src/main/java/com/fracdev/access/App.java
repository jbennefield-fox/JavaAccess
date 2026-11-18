package com.fracdev.access;

import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.Table;
import io.github.spannm.jackcess.TableMetaData;
import io.github.spannm.jackcess.encrypt.InvalidCredentialsException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Command-line entry point. */
public final class App {

  static final String USAGE =
      """
      usage: access <command> [options]

      commands:
        tables <db>          list tables: kind, name, rows (local) or link target (linked)
        query  <db> <sql>    run Access SQL through UCanAccess, print CSV to stdout
        relink <db> <file> <new-path>
                             repoint linked tables whose backend file name is <file> to
                             <new-path> (writes <db>). Use an absolute path: UCanAccess
                             resolves relative links against the working directory.

      options:
        --dry-run            relink: list changes, don't write
        --password <pw>      database password (default: $ACCESS_PASSWORD)
        --write              open the file writable (default: read-only)
        --disk               keep UCanAccess's HSQLDB mirror on disk, not the heap (query only)
        --verbose            show UCanAccess warnings (missing linked files, unparsable queries)
      """;

  // Held strongly: JUL loggers are weakly referenced and would otherwise lose the level.
  private static final Logger UCANACCESS_LOG = Logger.getLogger("net.ucanaccess");

  private final PrintStream out;
  private final PrintStream err;
  private final String envPassword;

  App(PrintStream out, PrintStream err, String envPassword) {
    this.out = out;
    this.err = err;
    this.envPassword = envPassword;
  }

  public static void main(String[] args) {
    System.exit(new App(System.out, System.err, System.getenv("ACCESS_PASSWORD")).run(args));
  }

  int run(String[] args) {
    List<String> pos = new ArrayList<>();
    String password = envPassword;
    boolean write = false;
    boolean disk = false;
    boolean verbose = false;
    boolean dryRun = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--password" -> {
          if (++i >= args.length) {
            return usage("--password needs a value");
          }
          password = args[i];
        }
        case "--write" -> write = true;
        case "--disk" -> disk = true;
        case "--verbose" -> verbose = true;
        case "--dry-run" -> dryRun = true;
        case "-h", "--help" -> {
          out.print(USAGE);
          return 0;
        }
        default -> {
          if (args[i].startsWith("--")) {
            return usage("unknown option " + args[i]);
          }
          pos.add(args[i]);
        }
      }
    }
    UCANACCESS_LOG.setLevel(verbose ? Level.ALL : Level.SEVERE);

    if (pos.isEmpty()) {
      return usage(null);
    }
    String cmd = pos.get(0);
    try {
      return switch (cmd) {
        case "tables" -> pos.size() == 2 ? tables(Path.of(pos.get(1)), password, !write) : usage(cmd + " takes <db>");
        case "query" -> pos.size() == 3
            ? query(Path.of(pos.get(1)), pos.get(2), password, new Databases.Options(!write, disk))
            : usage(cmd + " takes <db> <sql>");
        case "relink" -> pos.size() == 4
            ? relink(Path.of(pos.get(1)), pos.get(2), pos.get(3), password, dryRun)
            : usage(cmd + " takes <db> <file> <new-path>");
        default -> usage("unknown command " + cmd);
      };
    } catch (NoSuchFileException e) {
      err.println("error: no such file: " + e.getMessage());
      return 1;
    } catch (InvalidCredentialsException e) {
      err.println("error: wrong or missing password (--password or ACCESS_PASSWORD)");
      return 1;
    } catch (IOException | SQLException e) {
      err.println("error: " + e.getMessage());
      return 1;
    }
  }

  private int tables(Path file, String password, boolean readOnly) throws IOException {
    try (Database db = Databases.open(file, password, readOnly)) {
      out.println("kind\tname\trows_or_target");
      for (String name : db.getTableNames()) {
        TableMetaData md = db.getTableMetaData(name);
        if (md.isLinked()) {
          String target = md.getLinkedDbName() != null ? md.getLinkedDbName() : "ODBC";
          out.println("LINK\t" + name + "\t" + target + " [" + md.getLinkedTableName() + "]");
        } else {
          Table t = db.getTable(name);
          out.println("LOCAL\t" + name + "\t" + t.getRowCount());
        }
      }
    }
    return 0;
  }

  private int query(Path file, String sql, String password, Databases.Options opts)
      throws IOException, SQLException {
    try (Connection c = Databases.connect(file, password, opts);
        Statement st = c.createStatement()) {
      if (st.execute(sql)) {
        Csv.write(st.getResultSet(), out);
      } else {
        err.println(st.getUpdateCount() + " row(s) affected");
      }
    }
    return 0;
  }

  private int relink(Path file, String fileName, String newPath, String password, boolean dryRun)
      throws IOException {
    try (Database db = Databases.open(file, password, dryRun)) {
      List<Relink.Change> changes = Relink.relink(db, fileName, newPath, dryRun);
      for (Relink.Change c : changes) {
        out.println(c.table() + "\t" + c.from() + "\t->\t" + c.to());
      }
      err.println((dryRun ? "would relink " : "relinked ") + changes.size() + " table(s)");
    }
    return 0;
  }

  private int usage(String problem) {
    if (problem != null) {
      err.println("error: " + problem);
    }
    err.print(USAGE);
    return 2;
  }
}
