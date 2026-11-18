package com.fracdev.access;

import java.io.PrintStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Minimal RFC 4180 CSV output for JDBC result sets. */
final class Csv {

  private Csv() {}

  static int write(ResultSet rs, PrintStream out) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    String[] row = new String[n];
    for (int i = 0; i < n; i++) {
      row[i] = md.getColumnLabel(i + 1);
    }
    out.println(line(row));
    int rows = 0;
    while (rs.next()) {
      for (int i = 0; i < n; i++) {
        Object v = rs.getObject(i + 1);
        row[i] = v == null ? "" : v.toString();
      }
      out.println(line(row));
      rows++;
    }
    return rows;
  }

  static String line(String... fields) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fields.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(escape(fields[i]));
    }
    return sb.toString();
  }

  static String escape(String s) {
    if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
      return s;
    }
    return '"' + s.replace("\"", "\"\"") + '"';
  }
}
