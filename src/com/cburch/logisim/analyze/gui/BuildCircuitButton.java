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

package com.cburch.logisim.analyze.gui;
import static com.cburch.logisim.analyze.model.Strings.S;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.VariableList;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.gui.main.Frame;

class BuildCircuitButton extends JButton {
  private class DialogPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private JLabel projectLabel = new JLabel();
    private JComboBox<Object> project;
    private JLabel nameLabel = new JLabel();
    private JTextField name = new JTextField(10);
    private JCheckBox twoInputs = new JCheckBox();
    private JCheckBox nands = new JCheckBox();

    DialogPanel() {
      List<Project> projects = Projects.getOpenProjects();
      Object[] options = new Object[projects.size() + 1];
      Object initialSelection = null;
      options[0] = new ProjectItem(null);
      for (int i = 1; i < options.length; i++) {
        Project proj = projects.get(i-1);
        options[i] = new ProjectItem(proj);
        if (proj == model.getCurrentProject()) {
          initialSelection = options[i];
        }
      }
      project = new JComboBox<>(options);
      if (options.length == 1) {
        project.setSelectedItem(options[0]);
        project.setEnabled(false);
      } else if (initialSelection != null) {
        project.setSelectedItem(initialSelection);
      } else {
        project.setSelectedItem(options[options.length - 1]);
      }

      Circuit defaultCircuit = model.getCurrentCircuit();
      if (defaultCircuit != null) {
        name.setText(defaultCircuit.getName());
        name.selectAll();
      }

      VariableList outputs = model.getOutputs();

      GridBagLayout gb = new GridBagLayout();
      GridBagConstraints gc = new GridBagConstraints();
      setLayout(gb);
      gc.anchor = GridBagConstraints.LINE_START;
      gc.fill = GridBagConstraints.NONE;

      gc.gridx = 0;
      gc.gridy = 0;
      gb.setConstraints(projectLabel, gc);
      add(projectLabel);
      gc.gridx = 1;
      gb.setConstraints(project, gc);
      add(project);
      gc.gridy++;
      gc.gridx = 0;
      gb.setConstraints(nameLabel, gc);
      add(nameLabel);
      gc.gridx = 1;
      gb.setConstraints(name, gc);
      add(name);
      gc.gridy++;
      gb.setConstraints(twoInputs, gc);
      add(twoInputs);
      gc.gridy++;
      gb.setConstraints(nands, gc);
      add(nands);

      projectLabel.setText(S.get("buildProjectLabel"));
      nameLabel.setText(S.get("buildNameLabel"));
      twoInputs.setText(S.get("buildTwoInputsLabel"));
      nands.setText(S.get("buildNandsLabel"));
    }
  }

  private class MyListener implements ActionListener {
    public void actionPerformed(ActionEvent event) {
      Project dest = null;
      String name = null;
      boolean twoInputs = false;
      boolean useNands = false;
      boolean replace = false;

      boolean ok = false;
      while (!ok) {
        DialogPanel dlog = new DialogPanel();
        int action = JOptionPane.showConfirmDialog(parent, dlog,
            S.get("buildDialogTitle"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
        if (action != JOptionPane.OK_OPTION)
          return;

        ProjectItem projectItem = (ProjectItem) dlog.project
            .getSelectedItem();
        if (projectItem == null) {
          JOptionPane.showMessageDialog(parent,
              S.get("buildNeedProjectError"),
              S.get("buildDialogErrorTitle"),
              JOptionPane.ERROR_MESSAGE);
          continue;
        }
        dest = projectItem.project;

        name = dlog.name.getText().trim();
        if (name.equals("")) {
          JOptionPane.showMessageDialog(parent,
              S.get("buildNeedCircuitError"),
              S.get("buildDialogErrorTitle"),
              JOptionPane.ERROR_MESSAGE);
          continue;
        }

        if (dest != null && dest.getLogisimFile().getCircuit(name) != null) {
          int choice = JOptionPane.showConfirmDialog(parent,
              S.fmt("buildConfirmReplaceMessage", name),
              S.get("buildConfirmReplaceTitle"),
              JOptionPane.YES_NO_OPTION);
          if (choice != JOptionPane.YES_OPTION) {
            continue;
          }
          replace = true;
        }

        twoInputs = dlog.twoInputs.isSelected();
        useNands = dlog.nands.isSelected();
        ok = true;
      }

      performAction(dest, name, replace, twoInputs, useNands);
    }
  }

  private static class ProjectItem {
    Project project;

    ProjectItem(Project project) {
      this.project = project;
    }

    @Override
    public String toString() {
      if (project == null)
        return "< Create New Project >";
      else
        return project.getLogisimFile().getDisplayName();
    }
  }

  private static final long serialVersionUID = 1L;

  private MyListener myListener = new MyListener();
  private JFrame parent;
  private AnalyzerModel model;

  BuildCircuitButton(JFrame parent, AnalyzerModel model) {
    this.parent = parent;
    this.model = model;
    addActionListener(myListener);
  }

  void localeChanged() {
    setText(S.get("buildCircuitButton"));
  }

  private void performAction(Project dest, String name, boolean replace,
      final boolean twoInputs, final boolean useNands) {
    if (replace) {
      final Circuit circuit = dest.getLogisimFile().getCircuit(name);
      if (circuit == null) {
        JOptionPane.showMessageDialog(parent,
            "Internal error prevents replacing circuit.",
            "Internal Error", JOptionPane.ERROR_MESSAGE);
        return;
      }

      CircuitMutation xn = CircuitBuilder.build(circuit, model,
          twoInputs, useNands);
      dest.doAction(xn.toAction(S.getter("replaceCircuitAction")));
    } else {
      // create new project if necessary
      if (dest == null)
        dest = ProjectActions.doNew((Frame)null);
      // add the circuit
      Circuit circuit = new Circuit(name, dest.getLogisimFile());
      CircuitMutation xn = CircuitBuilder.build(circuit, model,
          twoInputs, useNands);
      xn.execute();
      dest.doAction(LogisimFileActions.addCircuit(circuit));
      dest.setCurrentCircuit(circuit);
    }
  }
}
