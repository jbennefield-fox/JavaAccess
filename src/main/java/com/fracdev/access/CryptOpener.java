package com.fracdev.access;

import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.DateTimeType;
import io.github.spannm.jackcess.encrypt.CryptCodecProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import net.ucanaccess.jdbc.IJackcessOpenerInterface;

/**
 * UCanAccess opener that adds jackcess-encrypt (password-protected databases) and controls
 * whether the file itself is opened writable.
 *
 * <p>UCanAccess's default opener always tries a writable open first; its {@code readOnly=true}
 * URL flag only blocks SQL writes. {@link ReadOnly} opens the file channel read-only, so the
 * source database can't be touched. UCanAccess instantiates openers by class name, hence the
 * two concrete classes rather than a constructor argument.
 */
public class CryptOpener implements IJackcessOpenerInterface {

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
    Database db = builder(file, password, readOnly).withAutoSync(false).withCharset(charset).open();
    db.setDateTimeType(DateTimeType.LOCAL_DATE_TIME);
    return db;
  }

  static DatabaseBuilder builder(File file, String password, boolean readOnly) {
    return new DatabaseBuilder()
        .withFile(file)
        .withReadOnly(readOnly)
        .withCodecProvider(new CryptCodecProvider(emptyToNull(password)));
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
