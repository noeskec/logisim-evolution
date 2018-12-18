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

package com.cburch.logisim.gui.main;
import static com.cburch.logisim.gui.main.Strings.S;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;

import javax.swing.JOptionPane;

import com.cburch.hdl.HdlModel;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitTransaction;
import com.cburch.logisim.circuit.CircuitTransactionResult;
import com.cburch.logisim.circuit.ReplacementMap;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.menu.ProjectCircuitActions;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Dependencies;
import com.cburch.logisim.proj.JoinedAction;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.hdl.VhdlContent;
import com.cburch.logisim.tools.Library;

public class SelectionActions {

  /**
   * Code taken from Cornell's version of Logisim:
   * http://www.cs.cornell.edu/courses/cs3410/2015sp/
   */

  private abstract static class SelectionModifyingAction extends Action {

    protected Selection sel;
    protected int count;
    protected SelectionSave before;
    protected CircuitTransaction xnReverse;

    SelectionModifyingAction(Selection sel, int count) {
      this.sel = sel;
      this.before = SelectionSave.create(sel);
      this.count = count;
    }

    @Override
    public void doIt(Project proj) {
      Circuit circuit = proj.getCurrentCircuit();
      CircuitMutation xn = new CircuitMutation(circuit);
      doIt(proj, circuit, xn);
      CircuitTransactionResult result = xn.execute();
      xnReverse = result.getReverseTransaction();
    }

    protected abstract void doIt(Project proj, Circuit circ, CircuitMutation xn);

    @Override
    public boolean shouldAppendTo(Action other) {
      Action last;
      if (other instanceof JoinedAction)
        last = ((JoinedAction) other).getLastAction();
      else
        last = other;

      SelectionSave otherAfter = null;
      if (last instanceof PasteComponents) {
        otherAfter = ((PasteComponents) last).after;
      } else if (last instanceof Duplicate) {
        otherAfter = ((Duplicate) last).after;
      }
      return otherAfter != null && otherAfter.equals(this.before);
    }

    @Override
    public void undo(Project proj) {
      xnReverse.execute();
    }

  }

  private static class Anchor extends SelectionModifyingAction {
    // Anchor makes all floating elements become anchored, but keeps them in the
    // selection.

    Anchor(Selection sel, int count) {
      super(sel, count);
    }

    @Override
    public String getName() {
      return count == 1 ? S.get("anchorComponentAction") : S.get("anchorComponentsAction");
    }

    @Override
    protected void doIt(Project proj, Circuit circ, CircuitMutation xn) {
      sel.anchorAll(xn);
    }

  }

  private static class Delete extends Action {
    private Selection sel;
    private CircuitTransaction xnReverse;

    Delete(Selection sel) {
      this.sel = sel;
    }

    @Override
    public void doIt(Project proj) {
      Circuit circuit = proj.getCurrentCircuit();
      CircuitMutation xn = new CircuitMutation(circuit);
      sel.deleteAllHelper(xn);
      CircuitTransactionResult result = xn.execute();
      xnReverse = result.getReverseTransaction();
    }

    @Override
    public String getName() {
      return S.get("deleteSelectionAction");
    }

    @Override
    public void undo(Project proj) {
      xnReverse.execute();
    }
  }

  /**
   * Code taken from Cornell's version of Logisim:
   * http://www.cs.cornell.edu/courses/cs3410/2015sp/
   */
  private static class Drop extends SelectionModifyingAction {
    // Drop removes things from the selection.

    private Component[] drops;

    Drop(Selection sel, Collection<Component> toDrop, int numDrops) {
      super(sel, toDrop.size());
      this.drops = toDrop.toArray(new Component[toDrop.size()]);
    }

    @Override
    protected void doIt(Project proj, Circuit circ, CircuitMutation xn) {
      for (Component comp : drops)
        sel.remove(xn, comp);
    }

    @Override
    public String getName() {
      return count == 1 ? S.get("dropComponentAction") : S.get("dropComponentsAction");
    }

  }

  private static class Duplicate extends Action {
    private Selection sel;
    private CircuitTransaction xnReverse;
    private SelectionSave after;

    Duplicate(Selection sel) {
      this.sel = sel;
    }

    @Override
    public void doIt(Project proj) {
      Circuit circuit = proj.getCurrentCircuit();
      CircuitMutation xn = new CircuitMutation(circuit);
      sel.duplicateHelper(xn);
      CircuitTransactionResult result = xn.execute();
      xnReverse = result.getReverseTransaction();
      after = SelectionSave.create(sel);
    }

    @Override
    public String getName() {
      return S.get("duplicateSelectionAction");
    }

    @Override
    public void undo(Project proj) {
      xnReverse.execute();
    }
  }

  private static HashSet<Circuit> getDependencies(Circuit circ, Collection<Circuit> newCircs) {
    LinkedList<Circuit> todo = new LinkedList<>();
    HashSet<Circuit> downstream = new HashSet<>();
    todo.add(circ);
    while (!todo.isEmpty()) {
      Circuit c = todo.remove();
      downstream.add(c);
      if (!newCircs.contains(c))
        continue; // reached the boundary between new and old circuits
      for (Component comp : circ.getNonWires()) {
        if (comp.getFactory() instanceof SubcircuitFactory) {
          SubcircuitFactory factory = (SubcircuitFactory) comp.getFactory();
          Circuit subCirc = factory.getSubcircuit();
          if (subCirc == circ)
            return null; // circular
          if (todo.contains(subCirc) || downstream.contains(subCirc))
            continue; // already visited
          todo.add(subCirc);
        }
      }
    }
    return downstream;
  }


  private static class PasteComponents extends Action {
    private Selection sel;
    private LayoutClipboard.Clip<Collection<Component>> clip;
    private CircuitTransaction xnReverse, cxnReverse;
    private SelectionSave after;

    PasteComponents(Selection sel, LayoutClipboard.Clip<Collection<Component>> clip) {
      this.sel = sel;
      this.clip = clip;
    }

    @Override
    public void doIt(Project proj) {
      Circuit circuit = proj.getCurrentCircuit();
      
      // check if adding these components would cause a circular dependency
      Dependencies dependencies = proj.getDependencies();
      for (Component c : clip.selection) {
        ComponentFactory factory = c.getFactory();
        if (!(factory instanceof SubcircuitFactory))
          continue;
        Circuit subCirc = ((SubcircuitFactory)factory).getSubcircuit();
        if (clip.circuits.contains(subCirc))
          continue; // no dependency info for this subcircuit
        if (!dependencies.canAdd(circuit, subCirc)) {
          proj.getFrame().getCanvas().setErrorMessage(
              com.cburch.logisim.tools.Strings.S.getter("circularError"));
          return;
        }
      }

      // new circuits are not represented in the existing dependencies, so we
      // need to (a) make sure there are no circularities within the new
      // circuits and (b) check whether we can add an edge from the current
      // circuit to every dependency of the new circuits.
      for (Circuit c : clip.circuits) {
        HashSet<Circuit> downstream = getDependencies(c, clip.circuits);
        if (downstream == null) { // failure indicates circularity
          proj.getFrame().getCanvas().setErrorMessage(
              com.cburch.logisim.tools.Strings.S.getter("circularError"));
          return;
        }
        for (Circuit subCirc : downstream) {
          if (clip.circuits.contains(subCirc))
            continue; // not on the boundary between new and old circuits
          if (!dependencies.canAdd(circuit, subCirc)) {
            proj.getFrame().getCanvas().setErrorMessage(
                com.cburch.logisim.tools.Strings.S.getter("circularError"));
            return;
          }
        }
      }

      LogisimFile file = proj.getLogisimFile();
      for (Library lib : clip.libraries)
        file.addLibrary(lib);
      for (VhdlContent vhdl : clip.vhdl)
        file.addVhdlContent(vhdl);
      if (clip.circuitTransaction != null) {
        for (Circuit c : clip.circuits)
          file.addCircuit(c);
        CircuitTransactionResult result = clip.circuitTransaction.execute();
        cxnReverse = result.getReverseTransaction();
      } else {
        cxnReverse = null;
      }

      CircuitMutation xn = new CircuitMutation(circuit);
      sel.pasteHelper(xn, clip.selection);
      CircuitTransactionResult result = xn.execute();
      xnReverse = result.getReverseTransaction();
      after = SelectionSave.create(sel);
    }

    @Override
    public String getName() {
      return S.get("pasteClipboardAction");
    }

    @Override
    public void undo(Project proj) {
      xnReverse.execute();
      if (cxnReverse != null)
        cxnReverse.execute();
      LogisimFile file = proj.getLogisimFile();
      for (Circuit circ : clip.circuits)
        file.removeCircuit(circ);
      for (VhdlContent vhdl : clip.vhdl)
        file.removeVhdl(vhdl);
      for (Library lib : clip.libraries)
        file.removeLibrary(lib);
    }
  }

  private static String confirmName(Project proj, String suggested, boolean is_vhdl, LayoutClipboard.Clip<?> clip) {
    String newName = suggested;
    LogisimFile file = proj.getLogisimFile();
    if (newName == null || newName.equals("") || file.getTool(newName) != null) {
      // suggested name is bad, generate better suggestion, confirm with user
      int i = 1;
      do {
        newName = suggested + (i++);
      } while (file.getTool(newName) != null);
      if (is_vhdl)
        newName = ProjectCircuitActions.promptForVhdlName(proj.getFrame(), file, newName);
      else
        newName = ProjectCircuitActions.promptForCircuitName(proj.getFrame(), file, newName);
      if (newName == null)
        return null; // user cancelled or picked bad name
    }
    for (Circuit c : clip.circuits) {
      if (newName.equals(c.getName())) {
        JOptionPane.showMessageDialog(proj.getFrame(),
            com.cburch.logisim.gui.menu.Strings.S.get("circuitNameDuplicateError"),
            com.cburch.logisim.gui.menu.Strings.S.get("circuitNameDialogTitle"),
            JOptionPane.ERROR_MESSAGE);
        return null;
      }
    }
    for (VhdlContent vhdl : clip.vhdl) {
      if (newName.equals(vhdl.getName())) {
        JOptionPane.showMessageDialog(proj.getFrame(),
            com.cburch.logisim.gui.menu.Strings.S.get("circuitNameDuplicateError"),
            com.cburch.logisim.gui.menu.Strings.S.get("circuitNameDialogTitle"),
            JOptionPane.ERROR_MESSAGE);
        return null;
      }
    }
    return newName;
  }

  private static class PasteComponentsAsCircuit extends Action {
    private LayoutClipboard.Clip<Collection<Component>> clip;
    private CircuitTransaction xnReverse, cxnReverse;
    private String newName = null;
    private Circuit circuit;

    PasteComponentsAsCircuit(LayoutClipboard.Clip<Collection<Component>> clip) {
      this.clip = clip;
    }

    @Override
    public void doIt(Project proj) {
      if (newName == null) {
        newName = confirmName(proj, "", false, clip);
        if (newName == null)
          return;
      }

      // New components go into a freshly-named circuit, which no other circuit
      // can depend on. So we only need to make sure there are no circularities
      // within the other new circuits.
      for (Circuit c : clip.circuits) {
        HashSet<Circuit> downstream = getDependencies(c, clip.circuits);
        if (downstream == null) { // failure indicates circularity
          proj.getFrame().getCanvas().setErrorMessage(
              com.cburch.logisim.tools.Strings.S.getter("circularError"));
          return;
        }
      }

      LogisimFile file = proj.getLogisimFile();

      for (Library lib : clip.libraries)
        file.addLibrary(lib);
      for (VhdlContent vhdl : clip.vhdl)
        file.addVhdlContent(vhdl);
      if (clip.circuitTransaction != null) {
        for (Circuit c : clip.circuits)
          file.addCircuit(c);
        CircuitTransactionResult result = clip.circuitTransaction.execute();
        cxnReverse = result.getReverseTransaction();
      } else {
        cxnReverse = null;
      }

      circuit = new Circuit(newName, file);
      file.addCircuit(circuit);
      proj.setCurrentCircuit(circuit);
      CircuitMutation xn = new CircuitMutation(circuit);
      for (Component c : clip.selection)
        xn.add(c);

      CircuitTransactionResult result = xn.execute();
      xnReverse = result.getReverseTransaction();
    }

    @Override
    public String getName() {
      return S.get("pasteClipboardAction");
    }

    @Override
    public void undo(Project proj) {
      xnReverse.execute();
      LogisimFile file = proj.getLogisimFile();
      file.removeCircuit(circuit);
      if (cxnReverse != null)
        cxnReverse.execute();
      for (Circuit circ : clip.circuits)
        file.removeCircuit(circ);
      for (VhdlContent vhdl : clip.vhdl)
        file.removeVhdl(vhdl);
      for (Library lib : clip.libraries)
        file.removeLibrary(lib);
    }
  }

  private static class PasteCircuit extends Action {
    private LayoutClipboard.Clip<Circuit> clip;
    private CircuitTransaction cxnReverse;
    private String newName;
    private Circuit prevCircuit;
    private HdlModel prevHdl;

    PasteCircuit(LayoutClipboard.Clip<Circuit> clip) {
      this.clip = clip;
    }

    @Override
    public void doIt(Project proj) {
      if (newName == null) {
        String oldName = clip.selection.getName();
        newName = confirmName(proj, oldName, false, clip);
        if (newName == null)
          return;
        if (!newName.equals(oldName))
          clip.selection.setName(newName);
      }

      // New circuit is freshly-named, and no other circuit can depend on it. So
      // we only need to make sure there are no circularities within the other
      // new circuits.
      for (Circuit c : clip.circuits) {
        HashSet<Circuit> downstream = getDependencies(c, clip.circuits);
        if (downstream == null) { // failure indicates circularity
          proj.getFrame().getCanvas().setErrorMessage(
              com.cburch.logisim.tools.Strings.S.getter("circularError"));
          return;
        }
      }

      prevCircuit = proj.getCurrentCircuit();
      prevHdl = proj.getCurrentHdl();

      LogisimFile file = proj.getLogisimFile();
      file.addCircuit(clip.selection);
      proj.setCurrentCircuit(clip.selection);

      for (Library lib : clip.libraries)
        file.addLibrary(lib);
      for (VhdlContent vhdl : clip.vhdl)
        file.addVhdlContent(vhdl);
      for (Circuit c : clip.circuits)
        file.addCircuit(c);

      CircuitTransactionResult result = clip.circuitTransaction.execute();
      cxnReverse = result.getReverseTransaction();
    }

    @Override
    public String getName() {
      return S.get("pasteClipboardAction");
    }

    @Override
    public void undo(Project proj) {
      cxnReverse.execute();
      LogisimFile file = proj.getLogisimFile();
      for (Circuit circ : clip.circuits)
        file.removeCircuit(circ);
      for (VhdlContent vhdl : clip.vhdl)
        file.removeVhdl(vhdl);
      for (Library lib : clip.libraries)
        file.removeLibrary(lib);
      file.removeCircuit(clip.selection);
      if (prevHdl != null)
        proj.setCurrentHdlModel(prevHdl);
      else if (prevCircuit != null)
        proj.setCurrentCircuit(prevCircuit);
    }
  }

  private static class PasteVhdl extends Action {
    private LayoutClipboard.Clip<VhdlContent> clip;
    private String newName;
    private Circuit prevCircuit;
    private HdlModel prevHdl;

    PasteVhdl(LayoutClipboard.Clip<VhdlContent> clip) {
      this.clip = clip;
    }

    @Override
    public void doIt(Project proj) {
      if (newName == null) {
        String oldName = clip.selection.getName();
        newName = confirmName(proj, oldName, false, clip);
        if (newName == null)
          return;
        if (!newName.equals(oldName))
          clip.selection.setName(newName);
      }

      // todo: no dependency tracking for vhdl yet, so no other circuits.

      prevCircuit = proj.getCurrentCircuit();
      prevHdl = proj.getCurrentHdl();

      LogisimFile file = proj.getLogisimFile();
      file.addVhdlContent(clip.selection);
      proj.setCurrentHdlModel(clip.selection);
    }

    @Override
    public String getName() {
      return S.get("pasteClipboardAction");
    }

    @Override
    public void undo(Project proj) {
      LogisimFile file = proj.getLogisimFile();
      file.removeVhdl(clip.selection);
      if (prevHdl != null)
        proj.setCurrentHdlModel(prevHdl);
      else if (prevCircuit != null)
        proj.setCurrentCircuit(prevCircuit);
    }
  }

  private static class Translate extends SelectionModifyingAction {
    private int dx, dy;
    private ReplacementMap replacements;

    Translate(Selection sel, int dx, int dy, ReplacementMap replacements) {
      super(sel, 0);
      this.dx = dx;
      this.dy = dy;
      this.replacements = replacements;
    }

    @Override
    protected void doIt(Project proj, Circuit circ, CircuitMutation xn) {
      sel.translateHelper(xn, dx, dy);
      if (replacements != null)
        xn.replace(replacements);
    }

    @Override
    public String getName() {
      return S.get("moveSelectionAction");
    }

  }

  /**
   * Code taken from Cornell's version of Logisim:
   * http://www.cs.cornell.edu/courses/cs3410/2015sp/
   */

  // anchors all floating elements, keeping elements in selection
  public static Action anchorAll(Selection sel) {
    int numAnchor = sel.getFloatingComponents().size();
    if (numAnchor == 0) {
      return null;
    } else {
      return new Anchor(sel, numAnchor);
    }
  }

  public static Action clear(Selection sel) {
    return new Delete(sel);
  }

  public static void copy(Project proj, Selection sel) { // Note: copy is not an Action
    LayoutClipboard.forComponents.set(proj, sel.getComponents());
  }

  public static Action cut(Project proj, Selection sel) {
    LayoutClipboard.forComponents.set(proj, sel.getComponents());
    return new Delete(sel);
  }

  // clears the selection, anchoring all floating elements in selection
  public static Action drop(Selection sel, Collection<Component> comps) {
    HashSet<Component> floating = new HashSet<Component>(
        sel.getFloatingComponents());
    HashSet<Component> anchored = new HashSet<Component>(
        sel.getAnchoredComponents());
    ArrayList<Component> toDrop = new ArrayList<Component>();
    ArrayList<Component> toIgnore = new ArrayList<Component>();
    for (Component comp : comps) {
      if (floating.contains(comp)) {
        toDrop.add(comp);
      } else if (anchored.contains(comp)) {
        toDrop.add(comp);
        toIgnore.add(comp);
      }
    }
    if (toDrop.size() == toIgnore.size()) {
      for (Component comp : toIgnore) {
        sel.remove(null, comp);
      }
      return null;
    } else {
      int numDrop = toDrop.size() - toIgnore.size();
      return new Drop(sel, toDrop, numDrop);
    }
  }

  public static Action dropAll(Selection sel) {
    return drop(sel, sel.getComponents());
  }

  public static Action duplicate(Selection sel) {
    return new Duplicate(sel);
  }

  public static Action pasteMaybe(Project proj, Selection sel) {
    Action act;
    act = pasteComponentsMaybe(proj, sel);
    if (act == null)
      act = pasteCircuitMaybe(proj);
    if (act == null)
      act = pasteVhdlMaybe(proj);
    return act;
  }

  public static Action pasteComponentsMaybe(Project proj, Selection sel) {
    LayoutClipboard.Clip<Collection<Component>> clip = LayoutClipboard.forComponents.get(proj);
    if (clip == null)
      return null;
    return new PasteComponents(sel, clip);
  }

  public static Action pasteComponentsAsCircuitMaybe(Project proj) {
    LayoutClipboard.Clip<Collection<Component>> clip = LayoutClipboard.forComponents.get(proj);
    if (clip == null)
      return null;
    return new PasteComponentsAsCircuit(clip);
  }

  public static Action pasteCircuitMaybe(Project proj) {
    LayoutClipboard.Clip<Circuit> clip = LayoutClipboard.forCircuit.get(proj);
    if (clip == null)
      return null;
    return new PasteCircuit(clip);
  }

  public static Action pasteVhdlMaybe(Project proj) {
    LayoutClipboard.Clip<VhdlContent> clip = LayoutClipboard.forVhdl.get(proj);
    if (clip == null)
      return null;
    return new PasteVhdl(clip);
  }

  public static Action translate(Selection sel, int dx, int dy, ReplacementMap repl) {
    return new Translate(sel, dx, dy, repl);
  }

  private SelectionActions() {
  }
}
