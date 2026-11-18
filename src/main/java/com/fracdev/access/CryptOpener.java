package com.fracdev.access;

import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.DateTimeType;
import io.github.spannm.jackcess.Row;
import io.github.spannm.jackcess.encrypt.CryptCodecProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.ucanaccess.jdbc.IJackcessOpenerInterface;

/**
 * UCanAccess opener that adds jackcess-encrypt (password-protected databases), per-link
 * passwords, and control over whether the file itself is opened writable.
 *
 * <p>UCanAccess's default opener always tries a writable open first; its {@code readOnly=true}
 * URL flag only blocks SQL writes. {@link ReadOnly} opens the file channel read-only, so the
 * source database can't be touched. UCanAccess instantiates openers by class name, hence the
 * two concrete classes rather than a constructor argument.
 *
 * <p>UCanAccess opens linked backends with the <em>front-end's</em> password. Access instead
 * stores each link's password in MSysObjects.Connect ({@code MS Access;PWD=...;}). Whenever a
 * database is opened here, those link passwords are remembered (in memory only) by backend path
 * and used when UCanAccess later asks for that backend.
 */
public class CryptOpener implements IJackcessOpenerInterface {

  private static final Map<Path, String> LINK_PASSWORDS = new ConcurrentHashMap<>();

  private final boolean readOnly;

  public CryptOpener() {
    this(false);
  }

  protected CryptOpener(boolean readOnly) {
    this.readOnly = readOnly;
  }

  @Override
  public Database open(File file, String password) throws IOException {
    return open(file, password, null);
  }

  @Override
  public Database open(File file, String password, Charset charset) throws IOException {
    String pw = LINK_PASSWORDS.getOrDefault(key(file.getPath()), password);
    Database db = builder(file, pw, readOnly).withAutoSync(false).withCharset(charset).open();
    db.setDateTimeType(DateTimeType.LOCAL_DATE_TIME);
    rememberLinkPasswords(db);
    return db;
  }

  static DatabaseBuilder builder(File file, String password, boolean readOnly) {
    return new DatabaseBuilder()
        .withFile(file)
        .withReadOnly(readOnly)
        .withCodecProvider(new CryptCodecProvider(emptyToNull(password)));
  }

  /** Records the PWD of every Access link in {@code db}, keyed the way UCanAccess resolves it. */
  static void rememberLinkPasswords(Database db) throws IOException {
    for (Row row : db.getSystemTable("MSysObjects")) {
      if (row.get("Type") instanceof Number t
          && t.intValue() == Relink.TYPE_LINKED_ACCESS
          && row.get("Database") instanceof String target) {
        String pw = connectPassword((String) row.get("Connect"));
        if (pw != null) {
          LINK_PASSWORDS.put(key(target), pw);
        }
      }
    }
  }

  static String linkPassword(String path) {
    return LINK_PASSWORDS.get(key(path));
  }

  /** PWD value from an Access Connect string such as {@code MS Access;PWD=secret;}. */
  static String connectPassword(String connect) {
    if (connect == null) {
      return null;
    }
    for (String part : connect.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("PWD")) {
        return emptyToNull(part.substring(eq + 1));
      }
    }
    return null;
  }

  // UCanAccess opens links with new File(path), i.e. relative to the working directory.
  private static Path key(String path) {
    return new File(path).getAbsoluteFile().toPath().normalize();
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  /** Opens the database file read-only. */
  public static class ReadOnly extends CryptOpener {
    public ReadOnly() {
      super(true);
    }
  }
}
