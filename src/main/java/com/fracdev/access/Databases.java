package com.fracdev.access;

import io.github.spannm.jackcess.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Opens Access databases either directly (Jackcess) or through JDBC (UCanAccess). */
final class Databases {

  private Databases() {}

  /** Low-level Jackcess handle: metadata, link info, raw table access. */
  static Database open(Path file, String password, boolean readOnly) throws IOException {
    requireFile(file);
    return CryptOpener.builder(file.toFile(), password, readOnly).open();
  }

  /** JDBC connection with Access SQL via UCanAccess (loads the database into HSQLDB). */
  static Connection connect(Path file, String password, Options opts)
      throws IOException, SQLException {
    requireFile(file);
    Properties props = new Properties();
    props.setProperty("user", "");
    props.setProperty("password", password == null ? "" : password);
    return DriverManager.getConnection(url(file, opts), props);
  }

  static String url(Path file, Options opts) {
    Class<?> opener = opts.readOnly() ? CryptOpener.ReadOnly.class : CryptOpener.class;
    return "jdbc:ucanaccess://"
        + file.toAbsolutePath()
        + ";jackcessOpener=" + opener.getName()
        + ";readOnly=" + opts.readOnly()
        // Indexes only matter for enforcing constraints on writes; skipping them loads faster.
        + ";skipIndexes=" + opts.readOnly()
        + ";ignoreCase=true"
        // memory=false keeps the HSQLDB mirror on disk instead of the heap (large files).
        + ";memory=" + !opts.disk();
  }

  private static void requireFile(Path file) throws NoSuchFileException {
    if (!Files.isRegularFile(file)) {
      throw new NoSuchFileException(file.toString());
    }
  }

  record Options(boolean readOnly, boolean disk) {}
}
