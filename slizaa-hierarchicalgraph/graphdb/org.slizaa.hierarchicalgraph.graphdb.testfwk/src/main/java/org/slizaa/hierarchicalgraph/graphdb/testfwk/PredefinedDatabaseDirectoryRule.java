package org.slizaa.hierarchicalgraph.graphdb.testfwk;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.imageio.IIOException;
import java.io.*;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 *
 */
public class PredefinedDatabaseDirectoryRule implements TestRule {

  //
  private File parent;

  //
  private InputStream inputStream;

  /**
   * @param inputStream
   */
  public PredefinedDatabaseDirectoryRule(InputStream inputStream, File parent) {
    this.inputStream = checkNotNull(inputStream);
    this.parent = checkNotNull(parent);
  }

  /**
   * @param inputStream
   * @throws IOException
   */
  public PredefinedDatabaseDirectoryRule(InputStream inputStream) {
    this(inputStream, createTempDirectory());
  }

  /**
   * @return
   */
  public File getParentDirectory() {
    return parent;
  }

  /**
   * @param base
   * @param description
   * @return
   */
  @Override
  public Statement apply(final Statement base, final Description description) {

    return new Statement() {

      @Override
      public void evaluate() throws Throwable {
        unzipDatabase(parent, inputStream);

        base.evaluate();

        // delete the old database directory...
        try {

          // get the root path
          Path rootPath = Paths.get(parent.getAbsolutePath());

          // delete all contained files
          Files.walk(rootPath, FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder()).map(Path::toFile)
              .forEach(File::delete);

        } catch (IOException e) {
          // simply ignore
        }
      }
    };
  }

  /**
   * @param parentDirectory
   * @param zippedDatabaseStream
   * @return
   */
  private static String unzipDatabase(File parentDirectory, InputStream zippedDatabaseStream) {

    //
    checkNotNull(parentDirectory);
    checkNotNull(zippedDatabaseStream);

    //
    File realDir = new File(parentDirectory, "databases/graph.db");

    //
    //if (!realDir.exists()) {
      try {
        unzip(zippedDatabaseStream, realDir);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    //}

    //
    return parentDirectory.getAbsolutePath();
  }

  /**
   * <p>
   * </p>
   *
   * @param inputStream
   * @param folder
   */
  private static void unzip(InputStream inputStream, File folder) {

    checkNotNull(inputStream);
    checkNotNull(folder);

    byte[] buffer = new byte[1024];

    try {

      // create output directory is not exists
      if (!folder.exists()) {
        folder.mkdirs();
      }

      // get the zip file content
      try (ZipInputStream zis = new ZipInputStream(inputStream)) {
        // get the zipped file list entry
        ZipEntry ze = zis.getNextEntry();

        while (ze != null) {

          if (!ze.isDirectory()) {

            String fileName = ze.getName();
            File newFile = new File(folder + File.separator + fileName);

            // create all non exists folders
            // else you will hit FileNotFoundException for compressed folder
            new File(newFile.getParent()).mkdirs();

            try (FileOutputStream fos = new FileOutputStream(newFile)) {
              int len;
              while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
              }
            }
          }
          ze = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

//  private static File createTempFileWithDirOldWay() {
//    try {
//      File tempDir = new File("D:\\sssss_hurz", "PredefinedDatabaseDirectoryRule_");
//      if (!tempDir.exists() && !tempDir.mkdirs())
//        throw new IIOException("Failed to create temporary directory " + tempDir);
//      return tempDir;
//    } catch (IOException e) {
//      throw new RuntimeException(e);
//    }
//  }

  private static File createTempDirectory() {
    try {
      return Files.createTempDirectory("PredefinedDatabaseDirectoryRule_").toFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}