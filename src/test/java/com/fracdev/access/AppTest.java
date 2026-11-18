package com.fracdev.access;

import static io.github.spannm.jackcess.DatabaseBuilder.newColumn;
import static io.github.spannm.jackcess.DatabaseBuilder.newTable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.Database.FileFormat;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.Table;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppTest {

  @TempDir Path tmp;
  Path db;

  @BeforeEach
  void createDatabase() throws Exception {
    db = tmp.resolve("fixture.accdb");
    try (Database d = DatabaseBuilder.newDatabase(db).withFileFormat(FileFormat.V2010).create()) {
      Table cars =
          newTable("Cars")
              .addColumn(newColumn("ID", DataType.LONG).withAutoNumber(true))
              .addColumn(newColumn("VIN", DataType.TEXT))
              .addColumn(newColumn("Note", DataType.TEXT))
              .withPrimaryKey("ID")
              .toTable(d);
      cars.addRow(Column.AUTO_NUMBER, "1C4AJWAG0GL175275", "plain");
      cars.addRow(Column.AUTO_NUMBER, "2T1BURHE0JC012345", "has, comma \"quoted\"");
      newTable("Empty").addColumn(newColumn("X", DataType.LONG)).toTable(d);
    }
  }

  @Test
  void tablesListsLocalTablesWithRowCounts() {
    Result r = run("tables", db.toString());
    assertEquals(0, r.code, r.err);
    assertTrue(r.out.contains("LOCAL\tCars\t2\n"), r.out);
    assertTrue(r.out.contains("LOCAL\tEmpty\t0\n"), r.out);
  }

  @Test
  void queryPrintsCsvWithEscaping() {
    Result r = run("query", db.toString(), "SELECT VIN, Note FROM Cars ORDER BY ID");
    assertEquals(0, r.code, r.err);
    assertEquals(
        "VIN,Note\n"
            + "1C4AJWAG0GL175275,plain\n"
            + "2T1BURHE0JC012345,\"has, comma \"\"quoted\"\"\"\n",
        r.out);
  }

  @Test
  void readOnlyByDefaultRejectsWritesAndLeavesFileUntouched() throws Exception {
    byte[] before = Files.readAllBytes(db);
    Result r = run("query", db.toString(), "INSERT INTO Cars (VIN, Note) VALUES ('X', 'y')");
    assertEquals(1, r.code, r.out);
    assertArrayEquals(before, Files.readAllBytes(db));
  }

  @Test
  void writeFlagAllowsInserts() {
    Result w = run("--write", "query", db.toString(), "INSERT INTO Cars (VIN, Note) VALUES ('X', 'y')");
    assertEquals(0, w.code, w.err);
    assertTrue(w.err.contains("1 row(s) affected"), w.err);

    Result r = run("query", db.toString(), "SELECT COUNT(*) AS N FROM Cars");
    assertEquals("N\n3\n", r.out);
  }

  @Test
  void diskModeQueriesToo() {
    Result r = run("--disk", "query", db.toString(), "SELECT COUNT(*) AS N FROM Cars");
    assertEquals(0, r.code, r.err);
    assertEquals("N\n2\n", r.out);
  }

  @Test
  void missingFileIsAnError() {
    Result r = run("tables", tmp.resolve("nope.accdb").toString());
    assertEquals(1, r.code);
    assertTrue(r.err.contains("no such file"), r.err);
  }

  @Test
  void badUsageExitsTwo() {
    assertEquals(2, run().code);
    assertEquals(2, run("bogus").code);
    assertEquals(2, run("query", db.toString()).code);
    assertEquals(2, run("--nope", "tables", db.toString()).code);
    assertEquals(2, run("--password").code);
  }

  @Test
  void readOnlyUrlUsesReadOnlyOpener() {
    String ro = Databases.url(db, new Databases.Options(true, false));
    assertTrue(ro.contains("jackcessOpener=" + CryptOpener.ReadOnly.class.getName()), ro);
    assertTrue(ro.contains(";readOnly=true"), ro);
    String rw = Databases.url(db, new Databases.Options(false, true));
    assertTrue(rw.contains("jackcessOpener=" + CryptOpener.class.getName() + ";"), rw);
    assertTrue(rw.contains(";memory=false"), rw);
  }

  /** Front-end with Windows-path links, like Fleet.accde: Cars -> fixture.accdb, Other -> elsewhere. */
  private Path createFrontEnd() throws Exception {
    Path front = tmp.resolve("front.accdb");
    try (Database d = DatabaseBuilder.newDatabase(front).withFileFormat(FileFormat.V2010).create()) {
      d.createLinkedTable("Cars", "C:\\Remarketing\\RMDATA\\FIXTURE.accdb", "Cars");
      d.createLinkedTable("Other", "C:\\Remarketing\\RMDATA\\other.accdb", "Other");
    }
    return front;
  }

  @Test
  void relinkRepointsMatchingLinksOnly() throws Exception {
    Path front = createFrontEnd();
    Result r = run("relink", front.toString(), "fixture.accdb", "fixture.accdb");
    assertEquals(0, r.code, r.err);
    assertTrue(r.err.contains("relinked 1 table(s)"), r.err);

    Result t = run("tables", front.toString());
    assertTrue(t.out.contains("LINK\tCars\tfixture.accdb [Cars]\n"), t.out);
    assertTrue(t.out.contains("LINK\tOther\tC:\\Remarketing\\RMDATA\\other.accdb [Other]\n"), t.out);

    // Second run is a no-op.
    assertTrue(run("relink", front.toString(), "fixture.accdb", "fixture.accdb").err.contains("relinked 0"));
  }

  @Test
  void relinkedTableIsQueryableThroughUcanaccess() throws Exception {
    Path front = createFrontEnd();
    run("relink", front.toString(), "fixture.accdb", db.toAbsolutePath().toString());
    Result r = run("query", front.toString(), "SELECT COUNT(*) AS N FROM Cars");
    assertEquals(0, r.code, r.err);
    assertEquals("N\n2\n", r.out);
  }

  @Test
  void relinkDryRunDoesNotWrite() throws Exception {
    Path front = createFrontEnd();
    byte[] before = Files.readAllBytes(front);
    Result r = run("--dry-run", "relink", front.toString(), "fixture.accdb", "fixture.accdb");
    assertEquals(0, r.code, r.err);
    assertTrue(r.err.contains("would relink 1 table(s)"), r.err);
    assertTrue(r.out.startsWith("Cars\tC:\\Remarketing\\RMDATA\\FIXTURE.accdb\t->\tfixture.accdb"), r.out);
    assertArrayEquals(before, Files.readAllBytes(front));
  }

  @Test
  void baseNameHandlesWindowsAndPosixPaths() {
    assertEquals("RMData.accdb", Relink.baseName("C:\\Remarketing\\RMDATA\\RMData.accdb"));
    assertEquals("RMData.accdb", Relink.baseName("/data/RMData.accdb"));
    assertEquals("RMData.accdb", Relink.baseName("RMData.accdb"));
  }

  @Test
  void csvEscape() {
    assertEquals("a", Csv.escape("a"));
    assertEquals("\"a,b\"", Csv.escape("a,b"));
    assertEquals("\"a\"\"b\"", Csv.escape("a\"b"));
    assertEquals("\"a\nb\"", Csv.escape("a\nb"));
  }

  private Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        new App(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                null)
            .run(args);
    return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private record Result(int code, String out, String err) {}
}
