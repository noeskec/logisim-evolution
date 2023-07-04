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

package com.cburch.logisim.proj;
import static com.cburch.logisim.proj.Strings.S;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.LoadCanceledByUser;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.gui.start.SplashScreen;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.Chooser;

public class ProjectActions {
  private static class CreateFrame implements Runnable {
    private Loader loader;
    private Project proj;
    private boolean isStartupScreen;

    public CreateFrame(Loader loader, Project proj, boolean isStartup) {
      this.loader = loader;
      this.proj = proj;
      this.isStartupScreen = isStartup;
    }

    public void run() {
      try {
        Frame frame = createFrame(null, proj);
        frame.setVisible(true);
        frame.toFront();
        frame.getCanvas().requestFocus();
        loader.setParent(frame);
        if (isStartupScreen) {
          proj.setStartupScreen(true);
        }
      } catch (Exception e) {
        Writer result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        e.printStackTrace(printWriter);
        JOptionPane.showMessageDialog(null, result.toString());
        System.exit(-1);
      }
    }
  }

  private static Project completeProject(SplashScreen monitor, Loader loader,
      LogisimFile file, boolean isStartup) {
    return completeProject(monitor, loader, new LogisimFile.FileWithSimulations(file), isStartup);
  }

  private static Project completeProject(SplashScreen monitor, Loader loader,
      LogisimFile.FileWithSimulations file, boolean isStartup) {
    if (monitor != null)
      monitor.setProgress(SplashScreen.PROJECT_CREATE);
    Project ret = new Project(file);

    if (monitor != null)
      monitor.setProgress(SplashScreen.FRAME_CREATE);
    SwingUtilities.invokeLater(new CreateFrame(loader, ret, isStartup));
    return ret;
  }

  private static LogisimFile createEmptyFile(Loader loader) {
    InputStream templReader = AppPreferences.getEmptyTemplate().createStream();
    LogisimFile file;
    try {
      file = loader.openLogisimFile(null, templReader).file;
    } catch (Exception t) {
      file = LogisimFile.createNew(loader);
      file.addCircuit(new Circuit("main", file));
    } finally {
      try {
        templReader.close();
      } catch (IOException e) {
      }
    }
    return file;
  }

  private static Frame createFrame(Frame sourceProjectFrame, Project newProject) {
    if (sourceProjectFrame != null)
        sourceProjectFrame.savePreferences();
    return new Frame(newProject);
    // Frame newFrame = new Frame(newProject);
    // newProject.setFrame(newFrame);
    // return newFrame;
  }

  public static LogisimFile createNewFile(Component errReportFrame) {
    Loader loader = new Loader(errReportFrame);
    InputStream templReader = AppPreferences.getTemplate().createStream();
    LogisimFile file;
    try {
      file = loader.openLogisimFile(null, templReader).file;
    } catch (IOException ex) {
      displayException(errReportFrame, ex);
      file = createEmptyFile(loader);
    } catch (LoadCanceledByUser ex) {
      displayException(errReportFrame, ex);
      file = createEmptyFile(loader);
    } finally {
      try {
        templReader.close();
      } catch (IOException e) {
      }
    }
    return file;
  }

  private static void displayException(Component parent, Exception ex) {
    String msg = S.fmt("templateOpenError", ex.toString());
    String ttl = S.get("templateOpenErrorTitle");
    JOptionPane.showMessageDialog(parent, msg, ttl,
        JOptionPane.ERROR_MESSAGE);
  }

  public static Project doNew(Frame baseProjectFrame) {
    LogisimFile file = createNewFile(baseProjectFrame);
    Project newProj = new Project(new LogisimFile.FileWithSimulations(file));
    Frame frame = createFrame(baseProjectFrame, newProj);
    frame.setVisible(true);
    frame.getCanvas().requestFocus();
    newProj.getLogisimFile().getLoader().setParent(frame);
    return newProj;
  }

  public static Project doNew(SplashScreen monitor) {
    return doNew(monitor, false);
  }

  public static Project doNew(SplashScreen monitor, boolean isStartupScreen) {
    if (monitor != null)
      monitor.setProgress(SplashScreen.FILE_CREATE);
    Loader loader = new Loader(monitor);
    InputStream templReader = AppPreferences.getTemplate().createStream();
    LogisimFile file = null;
    try {
      file = loader.openLogisimFile(null, templReader).file;
    } catch (IOException ex) {
      displayException(monitor, ex);
    } catch (LoadCanceledByUser ex) {
      displayException(monitor, ex);
    } finally {
      try {
        templReader.close();
      } catch (IOException e) {
      }
    }
    if (file == null)
      file = createEmptyFile(loader);
    return completeProject(monitor, loader, file, isStartupScreen);
  }

  public static Project doOpen(Component parent, Project baseProject) {
    File suggest = null;
    if (baseProject != null) {
      Loader oldLoader = baseProject.getLogisimFile().getLoader();
      suggest = oldLoader.getMainFile();
      if (suggest == null)
        suggest = oldLoader.getCurrentDirectory();
    }
    File selected = Chooser.loadPopup(parent, null /* default title */,
        suggest, Loader.LOGISIM_FILTER, Loader.ANY_FILTER);
    if (selected == null)
      return null;
    return doOpen(parent, baseProject, selected);
  }

  public static Project doOpen(Component parent, Project baseProject, File f) {
    Project proj = Projects.findProjectFor(f);
    Loader loader = null;
    if (proj != null) {
      proj.getFrame().toFront();
      loader = proj.getLogisimFile().getLoader();
      if (proj.isFileDirty()) {
        String message = S.fmt("openAlreadyMessage", proj.getLogisimFile().getName());
        String[] options = {
          S.get("openAlreadyLoseChangesOption"),
          S.get("openAlreadyNewWindowOption"),
          S.get("openAlreadyCancelOption"), };
        int result = JOptionPane.showOptionDialog(proj.getFrame(), message,
                S.get("openAlreadyTitle"), 0,
                JOptionPane.QUESTION_MESSAGE, null, options,
                options[2]);
        if (result == 0) {
          ; // keep proj as is, so that load happens into the window
        } else if (result == 1) {
          proj = null; // we'll create a new project
        } else {
          return proj;
        }
      }
    }

    if (proj == null && baseProject != null && baseProject.isStartupScreen()) {
      proj = baseProject;
      proj.setStartupScreen(false);
      loader = baseProject.getLogisimFile().getLoader();
    } else {
      loader = new Loader(baseProject == null ? parent : baseProject.getFrame());
    }

    try {
      LogisimFile.FileWithSimulations libWithSim = loader.openLogisimFile(f);
      AppPreferences.updateRecentFile(f);
      if (libWithSim == null)
        return null;
      if (proj == null) {
        proj = new Project(libWithSim);
      } else {
        proj.setLogisimFile(libWithSim);
      }
    } catch (LoadCanceledByUser ex) {
      // eat exception
      return null;
    } catch (LoadFailedException ex) {
      JOptionPane.showMessageDialog(
          parent,
          S.fmt("fileOpenError", ex.toString()),
          S.get("fileOpenErrorTitle"),
          JOptionPane.ERROR_MESSAGE);
      return null;
    }

    Frame frame = proj.getFrame();
    if (frame == null)
      frame = createFrame(baseProject != null ? baseProject.getFrame() : null, proj);
    frame.setVisible(true);
    frame.toFront();
    frame.getCanvas().requestFocus();
    proj.getLogisimFile().getLoader().setParent(frame);
    return proj;
  }

  public static Project doOpen(SplashScreen monitor, File source,
      Map<String, String> substitutions) throws LoadFailedException, LoadCanceledByUser {
    if (monitor != null)
      monitor.setProgress(SplashScreen.FILE_LOAD);
    Loader loader = new Loader(monitor);
    LogisimFile.FileWithSimulations file = loader.openLogisimFile(source, substitutions);
    AppPreferences.updateRecentFile(source);

    return completeProject(monitor, loader, file, false);
  }

  public static Project doOpenNoWindow(SplashScreen monitor, File source,
      Map<String, String> substitutions)
      throws LoadFailedException, LoadCanceledByUser {
    Loader loader = new Loader(monitor);
    LogisimFile file = loader.openLogisimFile(source).file;
    return new Project(new LogisimFile.FileWithSimulations(file));
  }

  public static boolean doQuit() {
    Frame top = Projects.getTopFrame();
    if (top != null)
      top.savePreferences();

    for (Project proj : new ArrayList<Project>(Projects.getOpenProjects())) {
      if (!proj.confirmClose(S.get("confirmQuitTitle")))
        return false;
    }
    System.exit(0);
    return true; // never reached
  }

  public static boolean doSave(Project proj) {
    Loader loader = proj.getLogisimFile().getLoader();
    File f = loader.getMainFile();
    if (f == null)
      return doSaveAs(proj);
    else
      return doSave(proj, f);
  }

  private static boolean doSave(Project proj, File f) {
    Tool oldTool = proj.getTool();
    proj.setTool(null);
    boolean ret = proj.getLogisimFile().save(f, proj);
    if (ret) {
      AppPreferences.updateRecentFile(f);
      proj.setFileAsClean();
    }
    proj.setTool(oldTool);
    return ret;
  }

  /**
   * Saves a Logisim project in a .circ file.
   *
   * It is the action listener for the File->Save as... menu option.
   *
   * @param proj
   *            project to be saved
   * @return true if success, false otherwise
   */
  public static boolean doSaveAs(final Project proj) {
    Loader loader = proj.getLogisimFile().getLoader();
    File suggest = loader.getMainFile(); // may be null
    File saved = Chooser.savePopup((f) -> doSave(proj, f),
        proj.getFrame(), null /* default title */, suggest,
        Loader.LOGISIM_FILTER);
    return saved != null;
  }

  private ProjectActions() {
  }
}
