package com.fracdev.access;

import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DateTimeType;
import io.github.spannm.jackcess.Index;
import io.github.spannm.jackcess.Row;
import io.github.spannm.jackcess.Table;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Copies every local table of an Access database into SQLite, streaming rows with Jackcess (no
 * UCanAccess load). Linked tables and saved queries are skipped: links belong to their backend's
 * export, and Access SQL isn't SQLite SQL.
 */
final class SqliteExport {

  private static final int BATCH = 2_000;
  private static final DateTimeFormatter SQLITE_DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter SQLITE_DATETIME_MS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  record Summary(int tables, long rows, List<String> skippedLinks, List<String> failures) {}

  private SqliteExport() {}

  static Summary export(Database db, Path out, PrintStream log) throws IOException, SQLException {
    db.setDateTimeType(DateTimeType.LOCAL_DATE_TIME);
    List<String> links = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    int tables = 0;
    long rows = 0;
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out.toAbsolutePath())) {
      try (Statement st = c.createStatement()) {
        // Bulk load into a fresh file: durability during the load doesn't matter.
        st.execute("PRAGMA journal_mode=OFF");
        st.execute("PRAGMA synchronous=OFF");
      }
      c.setAutoCommit(false);
      for (String name : db.getTableNames()) {
        if (db.getTableMetaData(name).isLinked()) {
          links.add(name);
          continue;
        }
        Table t = db.getTable(name);
        try {
          long n = copyTable(t, c, log);
          c.commit();
          tables++;
          rows += n;
          log.printf("%-45s %,10d rows%n", name, n);
        } catch (IOException | SQLException | RuntimeException e) {
          c.rollback();
          failures.add(name + ": " + e);
          log.printf("%-45s FAILED: %s%n", name, e);
        }
      }
    }
    return new Summary(tables, rows, links, failures);
  }

  private static long copyTable(Table t, Connection c, PrintStream log) throws IOException, SQLException {
    List<? extends Column> cols = t.getColumns();
    try (Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS " + q(t.getName()));
      st.execute(createTableSql(t));
    }
    StringJoiner names = new StringJoiner(", ");
    StringJoiner marks = new StringJoiner(", ");
    for (Column col : cols) {
      names.add(q(col.getName()));
      marks.add("?");
    }
    String insert = "INSERT INTO " + q(t.getName()) + " (" + names + ") VALUES (" + marks + ")";
    long n = 0;
    try (PreparedStatement ps = c.prepareStatement(insert)) {
      for (Row row : t) {
        for (int i = 0; i < cols.size(); i++) {
          bind(ps, i + 1, cols.get(i), row.get(cols.get(i).getName()));
        }
        ps.addBatch();
        if (++n % BATCH == 0) {
          ps.executeBatch();
        }
      }
      ps.executeBatch();
    }
    createIndexes(t, c, log);
    return n;
  }

  static String createTableSql(Table t) {
    StringJoiner defs = new StringJoiner(", ");
    for (Column col : t.getColumns()) {
      defs.add(q(col.getName()) + " " + sqliteType(col.getType()));
    }
    try {
      Index pk = t.getPrimaryKeyIndex();
      StringJoiner pkCols = new StringJoiner(", ");
      for (Index.Column ic : pk.getColumns()) {
        pkCols.add(q(ic.getName()));
      }
      defs.add("PRIMARY KEY (" + pkCols + ")");
    } catch (IllegalArgumentException noPrimaryKey) {
      // Access tables may have no primary key.
    }
    return "CREATE TABLE " + q(t.getName()) + " (" + defs + ")";
  }

  /** Recreates Access indexes (other than the primary key and foreign-key helpers) after loading. */
  private static void createIndexes(Table t, Connection c, PrintStream log) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (Index idx : t.getIndexes()) {
        if (idx.isPrimaryKey() || idx.isForeignKey() || idx.getColumns().isEmpty()) {
          continue;
        }
        StringJoiner cols = new StringJoiner(", ");
        for (Index.Column ic : idx.getColumns()) {
          cols.add(q(ic.getName()) + (ic.isAscending() ? "" : " DESC"));
        }
        // SQLite index names are database-wide; Access names are per table.
        String name = q(t.getName() + "__" + idx.getName());
        String sql = "CREATE " + (idx.isUnique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS " + name
            + " ON " + q(t.getName()) + " (" + cols + ")";
        try {
          st.execute(sql);
        } catch (SQLException e) {
          // An index is a convenience; keep the data even if SQLite rejects it.
          log.printf("  warning: index %s on %s skipped: %s%n", idx.getName(), t.getName(), e.getMessage());
        }
      }
    }
  }

  static String sqliteType(DataType type) {
    return switch (type) {
      case BOOLEAN, BYTE, INT, LONG, BIG_INT, COMPLEX_TYPE -> "INTEGER";
      case FLOAT, DOUBLE -> "REAL";
      case MONEY, NUMERIC -> "NUMERIC";
      case BINARY, OLE, BIG_BINARY, UNSUPPORTED_FIXEDLEN, UNSUPPORTED_VARLEN -> "BLOB";
      // TEXT, MEMO, GUID, dates (ISO-8601 text, which SQLite's date functions understand)
      default -> "TEXT";
    };
  }

  static void bind(PreparedStatement ps, int i, Column col, Object v) throws SQLException {
    switch (v) {
      case null -> ps.setObject(i, null);
      case Boolean b -> ps.setInt(i, b ? 1 : 0);
      case Byte b -> ps.setInt(i, b & 0xFF); // Access BYTE is unsigned
      case Short s -> ps.setInt(i, s);
      case Integer n -> ps.setInt(i, n);
      case Long n -> ps.setLong(i, n);
      case Float f -> ps.setDouble(i, f);
      case Double d -> ps.setDouble(i, d);
      case BigDecimal d -> ps.setString(i, d.toPlainString());
      case LocalDateTime d ->
          ps.setString(i, d.format(d.getNano() == 0 ? SQLITE_DATETIME : SQLITE_DATETIME_MS));
      case byte[] b -> ps.setBytes(i, b);
      // Attachment / multi-value columns: keep the foreign key into Access's hidden table.
      case io.github.spannm.jackcess.complex.ComplexValueForeignKey fk -> ps.setInt(i, fk.get());
      default -> ps.setString(i, v.toString());
    }
  }

  /** SQLite identifier quoting. */
  static String q(String ident) {
    return '"' + ident.replace("\"", "\"\"") + '"';
  }
}
