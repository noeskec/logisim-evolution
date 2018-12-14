/**
 * This file is part of Logisim-evolution.
 *
 * Logisim-evolution is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Logisim-evolution is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with Logisim-evolution.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Original code by Carl Burch (http://www.cburch.com), 2011.
 * Subsequent modifications by:
 *   + Haute École Spécialisée Bernoise
 *     http://www.bfh.ch
 *   + Haute École du paysage, d'ingénierie et d'architecture de Genève
 *     http://hepia.hesge.ch/
 *   + Haute École d'Ingénierie et de Gestion du Canton de Vaud
 *     http://www.heig-vd.ch/
 *   + REDS Institute - HEIG-VD, Yverdon-les-Bains, Switzerland
 *     http://reds.heig-vd.ch
 * This version of the project is currently maintained by:
 *   + Kevin Walsh (kwalsh@holycross.edu, http://mathcs.holycross.edu/~kwalsh)
 */

package com.cburch.logisim.file;
import static com.cburch.logisim.file.Strings.S;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;

import com.cburch.logisim.Main;
import com.cburch.logisim.std.Builtin;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.util.JDialogOk;
import com.cburch.logisim.util.JFileChoosers;
import com.cburch.logisim.util.MacCompatibility;
import com.cburch.logisim.util.ZipClassLoader;
import com.cburch.hdl.HdlFile;

public class Loader implements LibraryLoader {
  private static class JarFileFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
      return f.isDirectory() || f.getName().endsWith(".jar");
    }

    @Override
    public String getDescription() {
      return S.get("jarFileFilter");
    }
  }

  private static class LogisimFileFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
      return f.isDirectory() || f.getName().endsWith(LOGISIM_EXTENSION) || f.getName().endsWith(LOGISIM_EXTENSION_ALT);
    }

    @Override
    public String getDescription() {
      return S.get("logisimFileFilter");
    }
  }

  private static class TclFileFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
      return f.isDirectory() || f.getName().endsWith(".tcl");
    }

    @Override
    public String getDescription() {
      return S.get("tclFileFilter");
    }
  }

  private static class TxtFileFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
      return f.isDirectory() || f.getName().endsWith(".txt");
    }

    @Override
    public String getDescription() {
      return S.get("txtFileFilter");
    }
  }

  private static class VhdlFileFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
      return f.isDirectory() || f.getName().endsWith(".vhd") || f.getName().endsWith(".vhdl");
    }

    @Override
    public String getDescription() {
      return S.get("vhdlFileFilter");
    }
  }

  private static File determineBackupName(File base) {
    File dir = base.getParentFile();
    String name = base.getName();
    if (name.endsWith(LOGISIM_EXTENSION)) {
      name = name.substring(0, name.length() - LOGISIM_EXTENSION.length());
    } else if (name.endsWith(LOGISIM_EXTENSION_ALT)) {
      name = name.substring(0, name.length() - LOGISIM_EXTENSION_ALT.length());
    }
    for (int i = 1; i <= 20; i++) {
      String ext = i == 1 ? ".bak" : (".bak" + i);
      File candidate = new File(dir, name + ext);
      if (!candidate.exists())
        return candidate;
    }
    return null;
  }

  private static void recoverBackup(File backup, File dest) {
    if (backup != null && backup.exists()) {
      if (dest.exists())
        dest.delete();
      backup.renameTo(dest);
    }
  }

  public static final String LOGISIM_EXTENSION = ".circ";
  public static final String LOGISIM_EXTENSION_ALT = ".circ.xml";

  public static final FileFilter LOGISIM_FILTER = new LogisimFileFilter();

  public static final FileFilter JAR_FILTER = new JarFileFilter();
  public static final FileFilter TXT_FILTER = new TxtFileFilter();
  public static final FileFilter TCL_FILTER = new TclFileFilter();
  public static final FileFilter VHDL_FILTER = new VhdlFileFilter();

  // fixed
  private Component parent;
  private Builtin builtin = new Builtin();
  // to be cleared with each new file
  private File mainFile = null;

  private Stack<File> filesOpening = new Stack<File>();

  private Map<File, File> substitutions = new HashMap<File, File>();

  public Loader(Component parent) {
    this.parent = parent;
    clear();
  }

  //
  // more substantive methods accessed from outside this package
  //
  public void clear() {
    filesOpening.clear();
    mainFile = null;
  }

  public JFileChooser createChooser() {
    return JFileChoosers.createAt(getCurrentDirectory());
  }

  public Builtin getBuiltin() {
    return builtin;
  }

  // used here and in LibraryManager only, also in MemMenu
  public File getCurrentDirectory() {
    File ref;
    if (!filesOpening.empty()) {
      ref = filesOpening.peek();
    } else {
      ref = mainFile;
    }
    return ref == null ? null : ref.getParentFile();
  }

  public String getDescriptor(Library lib) {
    return LibraryManager.instance.getDescriptor(this, lib);
  }

  //
  // helper methods
  //
  File getFileFor(String name, FileFilter filter) throws LoadCanceledByUser {
    // Determine the actual file name.
    File file = new File(name);
    if (!file.isAbsolute()) {
      File currentDirectory = getCurrentDirectory();
      if (currentDirectory != null)
        file = new File(currentDirectory, name);
    }
    while (!file.canRead()) {
      // It doesn't exist. Figure it out from the user.
      JOptionPane.showMessageDialog(parent, S.fmt("fileLibraryMissingError", file.getName()));
      JFileChooser chooser = createChooser();
      chooser.setFileFilter(filter);
      chooser.setDialogTitle(S.fmt("fileLibraryMissingTitle", file.getName()));
      int action = chooser.showDialog(parent, S.get("fileLibraryMissingButton"));
      if (action != JFileChooser.APPROVE_OPTION)
        throw new LoadCanceledByUser();
      file = chooser.getSelectedFile();
    }
    return file;
  }

  //
  // file chooser related methods
  //
  public File getMainFile() {
    return mainFile;
  }

  private File getSubstitution(File source) {
    File ret = substitutions.get(source);
    return ret == null ? source : ret;
  }

  Library loadJarFile(File request, String className)
      throws LoadFailedException {
    File actual = getSubstitution(request);
    // Up until 2.1.8, this was written to use a URLClassLoader, which
    // worked pretty well, except that the class never releases its file
    // handles. For this reason, with 2.2.0, it's been switched to use
    // a custom-written class ZipClassLoader instead. The ZipClassLoader
    // is based on something downloaded off a forum, and I'm not as sure
    // that it works as well. It certainly does more file accesses.

    // Anyway, here's the line for this new version:
    ZipClassLoader loader = new ZipClassLoader(actual);

    // And here's the code that was present up until 2.1.8, and which I
    // know to work well except for the closing-files bit. If necessary, we
    // can revert by deleting the above declaration and reinstating the
    // below.
    /*
     * URL url; try { url = new URL("file", "localhost",
     * file.getCanonicalPath()); } catch (MalformedURLException e1) { throw
     * new LoadFailedException("Internal error: Malformed URL"); } catch
     * (IOException e1) { throw new
     * LoadFailedException(S.get("jarNotOpenedError")); }
     * URLClassLoader loader = new URLClassLoader(new URL[] { url });
     */

    // load library class from loader
    Class<?> retClass;
    try {
      retClass = loader.loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new LoadFailedException(S.fmt("jarClassNotFoundError", className));
    }
    if (!(Library.class.isAssignableFrom(retClass))) {
      throw new LoadFailedException(S.fmt("jarClassNotLibraryError", className));
    }

    // instantiate library
    Library ret;
    try {
      ret = (Library) retClass.newInstance();
    } catch (Exception e) {
      throw new LoadFailedException(S.fmt("jarLibraryNotCreatedError", className));
    }
    return ret;
  }

  public Library loadJarLibrary(File file, String className) {
    File actual = getSubstitution(file);
    return LibraryManager.instance.loadJarLibrary(this, actual, className);
  }

  //
  // Library methods
  //
  public Library loadLibrary(String desc) throws LoadCanceledByUser {
    return LibraryManager.instance.loadLibrary(this, desc);
  }

  //
  // methods for LibraryManager
  //
  LogisimFile loadLogisimFile(File request) throws LoadFailedException {
    File actual = getSubstitution(request);
    for (File fileOpening : filesOpening) {
      if (fileOpening.equals(actual)) {
        throw new LoadFailedException(
            S.fmt("logisimCircularError", toProjectName(actual)));
      }
    }

    LogisimFile ret = null;
    filesOpening.push(actual);
    try {
      ret = LogisimFile.load(actual, this);
    } catch (IOException e) {
      throw new LoadFailedException(
            S.fmt("logisimLoadError", toProjectName(actual), e.toString()));
    } finally {
      filesOpening.pop();
    }
    if (ret != null)
      ret.setName(toProjectName(actual));
    return ret;
  }

  public Library loadLogisimLibrary(File file) {
    File actual = getSubstitution(file);
    LoadedLibrary ret = LibraryManager.instance.loadLogisimLibrary(this, actual);
    if (ret != null) {
      LogisimFile retBase = (LogisimFile) ret.getBase();
      showMessages(retBase);
    }
    return ret;
  }

  public LogisimFile openLogisimFile(File file) throws LoadFailedException {
      LogisimFile ret = loadLogisimFile(file);
      if (ret == null)
        throw new LoadFailedException("File could not be opened"); // fixme i18n
      setMainFile(file);
      showMessages(ret);
      return ret;
  }

  public LogisimFile openLogisimFile(File file, Map<File, File> substitutions)
      throws LoadFailedException {
    this.substitutions = substitutions;
    try {
      return openLogisimFile(file);
    } finally {
      this.substitutions = Collections.emptyMap();
    }
  }

  public LogisimFile openLogisimFile(InputStream reader)
      throws /* LoadFailedException, */ IOException, LoadCanceledByUser {
    LogisimFile ret = LogisimFile.load(reader, this);
    showMessages(ret);
    return ret;
  }

  public void reload(LoadedLibrary lib) {
    LibraryManager.instance.reload(this, lib);
  }

  public boolean save(LogisimFile file, File dest) {
    Library reference = LibraryManager.instance.findReference(file, dest);
    if (reference != null) {
      JOptionPane.showMessageDialog(parent, 
            S.fmt("fileCircularError", reference.getDisplayName()),
            S.get("fileSaveErrorTitle"),
            JOptionPane.ERROR_MESSAGE);
      return false;
    }

    File backup = determineBackupName(dest);
    boolean backupCreated = backup != null && dest.renameTo(backup);

    FileOutputStream fwrite = null;
    try {
      try {
        MacCompatibility.setFileCreatorAndType(dest, "LGSM", "circ");
      } catch (IOException e) {
      }
      fwrite = new FileOutputStream(dest);
      file.write(fwrite, this, dest);
      file.setName(toProjectName(dest));

      File oldFile = getMainFile();
      setMainFile(dest);
      LibraryManager.instance.fileSaved(this, dest, oldFile, file);
    } catch (IOException e) {
      if (backupCreated)
        recoverBackup(backup, dest);
      if (dest.exists() && dest.length() == 0)
        dest.delete();
      JOptionPane.showMessageDialog(
          parent,
          S.fmt("fileSaveError", e.toString()),
          S.get("fileSaveErrorTitle"),
          JOptionPane.ERROR_MESSAGE);
      return false;
    } finally {
      if (fwrite != null) {
        try {
          fwrite.close();
        } catch (IOException e) {
          if (backupCreated)
            recoverBackup(backup, dest);
          if (dest.exists() && dest.length() == 0)
            dest.delete();
          JOptionPane.showMessageDialog(parent, 
              S.fmt("fileSaveCloseError", e.toString()),
              S.get("fileSaveErrorTitle"),
              JOptionPane.ERROR_MESSAGE);
          return false;
        }
      }
    }

    if (!dest.exists() || dest.length() == 0) {
      if (backupCreated && backup != null && backup.exists()) {
        recoverBackup(backup, dest);
      } else {
        dest.delete();
      }
      JOptionPane.showMessageDialog(parent,
          S.get("fileSaveZeroError"),
          S.get("fileSaveErrorTitle"),
          JOptionPane.ERROR_MESSAGE);
      return false;
    }

    if (backupCreated && backup.exists()) {
      backup.delete();
    }
    return true;
  }

  private void setMainFile(File value) {
    mainFile = value;
  }

  public void setParent(Component value) {
    parent = value;
  }

  public void showError(String description, Throwable ...errs) {
    if (!filesOpening.empty()) {
      File top = filesOpening.peek();
      String init = toProjectName(top) + ":";
      if (description.contains("\n")) {
        description = init + "\n" + description;
      } else {
        description = init + " " + description;
      }
    }

    if (Main.headless) {
      System.err.println(S.get("fileErrorTitle") + ": " + description);
      if (errs.length > 1)
        System.err.println(errs.length + " associated errors:");
      for (Throwable t: errs)
        t.printStackTrace();
      return;
    }
    Icon errIcon = UIManager.getIcon("OptionPane.errorIcon");
    JComponent msg;
    if (description.contains("\n") || description.length() > 60) {
      int lines = 1;
      for (int pos = description.indexOf('\n'); pos >= 0; pos = description.indexOf('\n', pos + 1))
        lines++;
      lines = Math.max(4, Math.min(lines, 7));

      JTextArea textArea = new JTextArea(lines, 60);
      textArea.setEditable(false);
      textArea.setText(description);
      textArea.setCaretPosition(0);

      JScrollPane scrollPane = new JScrollPane(textArea);
      Box box = Box.createHorizontalBox();
      box.add(new JLabel(errIcon));
      box.add(scrollPane);
      box.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

      msg = box;
    } else {
      msg = new JLabel(description, errIcon, SwingConstants.LEFT);
      msg.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
    }
    if (errs.length > 0) {

      StringWriter errors = new StringWriter();
      PrintWriter out = new PrintWriter(errors);
      if (errs.length > 1)
        out.println(errs.length + " associated errors:");
      for (Throwable t: errs)
        t.printStackTrace(out);

      msg.setAlignmentX(0);

      JTextArea textArea = new JTextArea(description + "\n" + errors.toString());
      textArea.setEditable(false);
      textArea.setCaretPosition(0);
      JScrollPane errPane = new JScrollPane(textArea);
      errPane.setAlignmentX(0);

      JButton button = new JButton(S.get("fileErrorShowDetail"));
      button.setContentAreaFilled(false);
      button.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
      button.setAlignmentX(0);

      Icon iconClosed = UIManager.getIcon("Tree.collapsedIcon");
      if (iconClosed == null) UIManager.getIcon("Tree.closedIcon");
      Icon iconOpen = UIManager.getIcon("Tree.expandedIcon");
      if (iconOpen == null) UIManager.getIcon("Tree.openIcon");
      button.setIcon(iconClosed);

      JPanel details = new JPanel();
      details.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
      GridBagLayout gb = new GridBagLayout();
      GridBagConstraints gc = new GridBagConstraints();
      details.setLayout(gb);
      gc.anchor = GridBagConstraints.NORTHWEST;
      gc.weightx = 1;
      gc.fill = GridBagConstraints.HORIZONTAL;
      gc.gridx = gc.gridy = 0;
      gb.setConstraints(msg, gc);
      details.add(msg);
      gc.fill = GridBagConstraints.NONE;
      gc.weightx = 0;
      gc.gridy = 1;
      gb.setConstraints(button, gc);
      details.add(button);
      button.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent event) {
          if (errPane.isShowing()) {
            button.setIcon(iconClosed);
            details.remove(errPane);
          } else {
            button.setIcon(iconOpen);
            gc.fill = GridBagConstraints.BOTH;
            gc.weightx = gc.weighty = 1;
            gc.gridy = 2;
            gb.setConstraints(errPane, gc);
            details.add(errPane);
          }
          details.revalidate();
          JDialog topFrame = (JDialog)SwingUtilities.getWindowAncestor(details);
          topFrame.pack();
          details.repaint();
        }
      });
      msg = details;
    }
    JDialog dialog = new JDialogOk(S.get("fileErrorTitle"), false) {
      public void okClicked() { }
    };
    dialog.getContentPane().add(msg);
    dialog.pack();
    dialog.show();
  }

  private void showMessages(LogisimFile source) {
    if (source == null)
      return;
    String message = source.getMessage();
    while (message != null) {
      if (Main.headless) {
        System.err.println(S.get("fileMessageTitle") + ": " + message);
      } else {
        JOptionPane.showMessageDialog(parent, message,
            S.get("fileMessageTitle"),
            JOptionPane.INFORMATION_MESSAGE);
      }
      message = source.getMessage();
    }
  }

  private String toProjectName(File file) {
    String ret = file.getName();
    if (ret.endsWith(LOGISIM_EXTENSION)) {
      return ret.substring(0, ret.length() - LOGISIM_EXTENSION.length());
    } else if (ret.endsWith(LOGISIM_EXTENSION_ALT)) {
      return ret.substring(0, ret.length() - LOGISIM_EXTENSION_ALT.length());
    } else {
      return ret;
    }
  }

  public String vhdlImportChooser(Component window) {
    JFileChooser chooser = createChooser();
    chooser.setFileFilter(Loader.VHDL_FILTER);
    chooser.setDialogTitle(S.get("hdlOpenDialog"));
    int returnVal = chooser.showOpenDialog(window);
    if (returnVal != JFileChooser.APPROVE_OPTION)
      return null;
    File selected = chooser.getSelectedFile();
    if (selected == null)
      return null;
    try {
      String vhdl = HdlFile.load(selected);
      return vhdl;
    } catch (IOException e) {
      JOptionPane.showMessageDialog(window,
          e.getMessage(),
          S.get("hexOpenErrorTitle"),
          JOptionPane.ERROR_MESSAGE);
      return null;
    }
  }

}
