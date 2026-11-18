package com.fracdev.access;

import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.Row;
import io.github.spannm.jackcess.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Repoints Access linked tables. A linked table is a row in MSysObjects with Type 6; its
 * {@code Database} column holds the path of the backend file (not indexed, so safe to rewrite).
 */
final class Relink {

  /** MSysObjects.Type for a table linked to another Access file (ODBC links are Type 4). */
  static final int TYPE_LINKED_ACCESS = 6;

  private Relink() {}

  record Change(String table, String from, String to) {}

  /**
   * Points every linked table whose backend file name equals {@code fileName} (case-insensitive,
   * ignoring directory) at {@code newPath}. With {@code dryRun}, reports changes without writing.
   */
  static List<Change> relink(Database db, String fileName, String newPath, boolean dryRun)
      throws IOException {
    Table sys = db.getSystemTable("MSysObjects");
    List<Change> changes = new ArrayList<>();
    for (Row row : sys) {
      if (!(row.get("Type") instanceof Number type) || type.intValue() != TYPE_LINKED_ACCESS) {
        continue;
      }
      String current = (String) row.get("Database");
      if (current == null || !baseName(current).equalsIgnoreCase(fileName) || current.equals(newPath)) {
        continue;
      }
      changes.add(new Change((String) row.get("Name"), current, newPath));
      if (!dryRun) {
        row.put("Database", newPath);
        sys.updateRow(row);
      }
    }
    return changes;
  }

  /** File name of a Windows or POSIX path. */
  static String baseName(String path) {
    int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
    return path.substring(i + 1);
  }
}
