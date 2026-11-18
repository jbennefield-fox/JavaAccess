package com.fracdev.access;

import static io.github.spannm.jackcess.DatabaseBuilder.newColumn;
import static io.github.spannm.jackcess.DatabaseBuilder.newIndex;
import static io.github.spannm.jackcess.DatabaseBuilder.newTable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.Database.FileFormat;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.DateTimeType;
import io.github.spannm.jackcess.Table;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteExportTest {

  @TempDir Path tmp;
  Path src;
  Path out;

  @BeforeEach
  void createSource() throws Exception {
    src = tmp.resolve("src.accdb");
    out = tmp.resolve("out.sqlite");
    try (Database d = DatabaseBuilder.newDatabase(src).withFileFormat(FileFormat.V2010).create()) {
      d.setDateTimeType(DateTimeType.LOCAL_DATE_TIME);
      Table cars =
          newTable("Cars")
              .addColumn(newColumn("ID", DataType.LONG).withAutoNumber(true))
              .addColumn(newColumn("VIN", DataType.TEXT))
              .addColumn(newColumn("Sold", DataType.BOOLEAN))
              .addColumn(newColumn("Cost", DataType.MONEY))
              .addColumn(newColumn("Rate", DataType.DOUBLE))
              .addColumn(newColumn("Age", DataType.BYTE))
              .addColumn(newColumn("Bought", DataType.SHORT_DATE_TIME))
              .addColumn(newColumn("Notes", DataType.MEMO))
              .addColumn(newColumn("Raw", DataType.OLE))
              .withPrimaryKey("ID")
              .addIndex(newIndex("VinIdx").withColumns("VIN"))
              .toTable(d);
      cars.addRow(Column.AUTO_NUMBER, "1C4AJWAG0GL175275", true, new BigDecimal("16465.0000"),
          0.0014, (byte) 200, LocalDateTime.of(2014, 6, 13, 9, 30, 15), "long note",
          new byte[] {1, 2, 3});
      cars.addRow(Column.AUTO_NUMBER, null, false, null, null, null, null, null, null);
      newTable("Odd \"Name\" ***").addColumn(newColumn("user", DataType.TEXT)).toTable(d)
          .addRow("x");
      d.createLinkedTable("Remote", "C:\\Remarketing\\RMDATA\\RMData.accdb", "Remote");
    }
  }

  @Test
  void copiesLocalTablesWithTypesAndSkipsLinks() throws Exception {
    Result r = run("to-sqlite", src.toString(), out.toString());
    assertEquals(0, r.code, r.err);
    assertTrue(r.err.contains("2 tables, 3 rows"), r.err);
    assertTrue(r.err.contains("skipped 1 linked table(s)"), r.err);

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out);
        Statement st = c.createStatement()) {
      ResultSet rs = st.executeQuery(
          "SELECT ID, VIN, Sold, Cost, Rate, Age, Bought, Notes, Raw,"
              + " typeof(Cost), date(Bought) FROM Cars ORDER BY ID");
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("ID"));
      assertEquals("1C4AJWAG0GL175275", rs.getString("VIN"));
      assertEquals(1, rs.getInt("Sold"));
      assertEquals(0, new BigDecimal("16465").compareTo(rs.getBigDecimal("Cost")));
      assertEquals(0.0014, rs.getDouble("Rate"));
      assertEquals(200, rs.getInt("Age"), "Access BYTE is unsigned");
      assertEquals("2014-06-13 09:30:15", rs.getString("Bought"));
      assertEquals("2014-06-13", rs.getString(11), "dates work with SQLite date functions");
      assertEquals("long note", rs.getString("Notes"));
      assertArrayEquals(new byte[] {1, 2, 3}, rs.getBytes("Raw"));

      assertTrue(rs.next());
      assertNull(rs.getString("VIN"));
      assertEquals(0, rs.getInt("Sold"));
      assertNull(rs.getObject("Cost"));
      assertFalse(rs.next());

      rs = st.executeQuery("SELECT \"user\" FROM \"Odd \"\"Name\"\" ***\"");
      assertTrue(rs.next());
      assertEquals("x", rs.getString(1));

      rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name = 'Remote'");
      assertEquals(0, rs.getInt(1));

      rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'Cars'");
      assertTrue(rs.getString(1).contains("PRIMARY KEY (\"ID\")"), rs.getString(1));
      rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='Cars__VinIdx'");
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void refusesToOverwriteWithoutForce() throws Exception {
    Files.writeString(out, "keep me");
    Result r = run("to-sqlite", src.toString(), out.toString());
    assertEquals(1, r.code);
    assertTrue(r.err.contains("--force"), r.err);
    assertEquals("keep me", Files.readString(out));

    assertEquals(0, run("--force", "to-sqlite", src.toString(), out.toString()).code);
  }

  @Test
  void sourceIsNotModified() throws Exception {
    byte[] before = Files.readAllBytes(src);
    assertEquals(0, run("to-sqlite", src.toString(), out.toString()).code);
    assertArrayEquals(before, Files.readAllBytes(src));
  }

  @Test
  void quoting() {
    assertEquals("\"a\"\"b\"", SqliteExport.q("a\"b"));
  }

  private Result run(String... args) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteArrayOutputStream e = new ByteArrayOutputStream();
    int code = new App(new PrintStream(o, true, StandardCharsets.UTF_8),
        new PrintStream(e, true, StandardCharsets.UTF_8), null).run(args);
    return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
  }

  private record Result(int code, String out, String err) {}
}
