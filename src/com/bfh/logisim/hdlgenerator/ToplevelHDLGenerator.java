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

package com.bfh.logisim.hdlgenerator;

import java.util.ArrayList;

import com.bfh.logisim.fpga.BoardIO;
import com.bfh.logisim.fpga.PinActivity;
import com.bfh.logisim.fpga.PinBindings;
import com.bfh.logisim.library.DynamicClock;
import com.bfh.logisim.netlist.ClockBus;
import com.bfh.logisim.netlist.Netlist;
import com.bfh.logisim.netlist.Netlist.Int3;
import com.bfh.logisim.netlist.NetlistComponent;
import com.bfh.logisim.netlist.Path;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.hdl.Hdl;
import com.cburch.logisim.std.wiring.ClockHDLGenerator;
import com.cburch.logisim.std.wiring.Pin;

public class ToplevelHDLGenerator extends HDLGenerator {

  // Name of the top-level HDL module.
  public static final String HDL_NAME = "LogisimToplevelShell";

	private Circuit circUnderTest;
	private PinBindings ioResources;
  private Netlist _circNets; // Netlist of the circUnderTest.

  private TickHDLGenerator ticker;
  private ArrayList<ClockHDLGenerator.CounterPart> clkgens = new ArrayList<>();
  private CircuitHDLGenerator circgen;

  // There is no parent netlist for TopLevel, because it is not embedded inside
  // anything. There are no attributes either.
  // There is no parent netlist for circgen or ticker, because TopLevel doesn't
  // create a netlist for itself. Neither of those components uses attributes,
  // so we can leave them empty. So both can use a single context with null nets
  // and empty attributes.
	
  public ToplevelHDLGenerator(Netlist.Context ctx, PinBindings ioResources) {
    super(new ComponentContext(ctx, null, null), "toplevel", HDL_NAME, "i_Toplevel");

		this.circUnderTest = ctx.circUnderTest;
		this.ioResources = ioResources;

    _circNets = ctx.getNetlist(circUnderTest);
    int numclk = ctx.clockbus.shapes().size();

    // raw oscillator input
    ioResources.requiresOscillator = numclk > 0;
    if (numclk > 0)
      inPorts.add(TickHDLGenerator.FPGA_CLK_NET, 1, -1, null);

    // io resources
    Netlist.Int3 ioPinCount = ioResources.countFPGAPhysicalIOPins();
		for (int i = 0; i < ioPinCount.in; i++)
      inPorts.add("FPGA_INPUT_PIN_"+i, 1, -1, null);
		for (int i = 0; i < ioPinCount.inout; i++)
      inOutPorts.add("FPGA_INOUT_PIN_"+i, 1, -1, null);
		for (int i = 0; i < ioPinCount.out; i++)
      outPorts.add("FPGA_OUTPUT_PIN_"+i, 1, -1, null);

    // internal networks for unconnected bidirectional ports
    Netlist.Int3 openCount = ioResources.countFPGAUnconnectedIOMappings();
    for (int i = 0; i < openCount.inout; i++)
      wires.add("FPGA_OPEN_"+i, 1);

    // internal clock networks
		if (numclk > 0) {
      wires.add(TickHDLGenerator.FPGA_CLKp_NET, 1);
      wires.add(TickHDLGenerator.FPGA_CLKn_NET, 1);
      wires.add(TickHDLGenerator.FPGA_TICK_NET, 1);
			for (int i = 0; i < numclk; i++)
				wires.add(ClockHDLGenerator.CLK_TREE_NET+i,
              ClockHDLGenerator.CLK_TREE_WIDTH);
		}

    // wires for hidden ports for circuit design under test
    // note: inout ports never get inversions, so no wire for those
    Netlist.Int3 hidden = _circNets.numHiddenBits();
    wires.addVector("s_LOGISIM_HIDDEN_FPGA_INPUT", hidden.in);
		// wires.AddVector("s_LOGISIM_HIDDEN_FPGA_INOUT", hidden.inout); // see circuit gen
    wires.addVector("s_LOGISIM_HIDDEN_FPGA_OUTPUT", hidden.out);

    // wires for normal ports for circuit design under test
    for (NetlistComponent shadow : _circNets.inpins) {
      int w = shadow.original.getEnd(0).getWidth().getWidth();
      wires.add("s_"+shadow.pathName(), w);
    }
    for (NetlistComponent shadow : _circNets.outpins) {
      int w = shadow.original.getEnd(0).getWidth().getWidth();
      wires.add("s_"+shadow.pathName(), w);
    }

    // wires for dynamic clock
    NetlistComponent dynClock = _circNets.dynamicClock();
    if (dynClock != null) {
      int w = dynClock.original.getAttributeSet().getValue(DynamicClock.WIDTH_ATTR).getWidth();
      wires.add("s_LOGISIM_DYNAMIC_CLOCK", w);
    }

    ComponentContext subctx = new ComponentContext(ctx, null, null);
		if (numclk > 0) {
			ticker = new TickHDLGenerator(subctx, dynClock);
			long id = 0;
      for (ClockBus.Shape shape : ctx.clockbus.shapes())
        clkgens.add(new ClockHDLGenerator.CounterPart(subctx, shape, id++));
    }

		circgen = new CircuitHDLGenerator(subctx, circUnderTest);
	}

  // Top-level entry point: write all HDL files for the project.
  public boolean writeAllHDLFiles(String rootDir) {
    if (!circgen.writeAllHDLFiles(rootDir)) {
      _err.AddInfo("Circuit HDL files could not be generated.");
      return false;
    }
    if (ticker != null && !ticker.writeHDLFiles(rootDir)) {
      _err.AddInfo("Clock ticker HDL files could not be generated.");
      return false;
    }
    if (!clkgens.isEmpty() && !clkgens.get(0).writeHDLFiles(rootDir)) {
      _err.AddInfo("Clock HDL files could not be generated.");
      return false;
    }
    if (!writeHDLFiles(rootDir)) {
      _err.AddInfo("Top level HDL module could not be generated.");
      return false;
    }
    return true;
  }

  @Override
  public boolean hdlDependsOnCircuitState() { // for NVRAM
    return circgen.hdlDependsOnCircuitState();
  }

  @Override
  public boolean writeAllHDLThatDependsOn(CircuitState cs,
      NetlistComponent ignored1, Path ignored2, String rootDir) { // for NVRAM
    return circgen.writeAllHDLThatDependsOn(cs, null,
        new Path(circUnderTest), rootDir);
  }

	@Override
	protected void declareNeededComponents(Hdl out) {
		if (ticker != null) {
      ticker.generateComponentDeclaration(out);
      // Declare clock gen module. All are identical, so only one declaration needed.
      clkgens.get(0).generateComponentDeclaration(out);
		}
    circgen.generateComponentDeclaration(out);
	}

	@Override
  protected void generateBehavior(Hdl out) {

    out.comment("signal adaptions for I/O related components and top-level pins");
    ioResources.components.forEach((path, shadow) -> {
      generateInlinedCodeSignal(out, path, shadow);
		});
    out.stmt();

		if (ticker != null) {
      out.comment("clock signal distribution");
      ticker.generateComponentInstance(out, 0L /*id*/, null /*comp*/ /*, null path*/);

			long id = 0;
			for (ClockHDLGenerator.CounterPart clkgen : clkgens)
        clkgen.generateComponentInstance(out, clkgen.id, null /*comp*/ /*, null path*/);
      out.stmt();
		}

    out.comment("connections for circuit design under test");
    circgen.generateTopComponentInstance(out, this);
	}

  private void pinVectorAssign(Hdl out, String pinName, String portName, int seqno, int n) {
    if (n == 1)
      out.assign(pinName, portName, seqno);
    else if (n > 1)
      out.assign(pinName, portName, seqno+n-1, seqno);
  }

  private boolean needTopLevelInversion(Component comp, BoardIO io) {
    boolean boardIsActiveHigh = io.activity != PinActivity.ACTIVE_LOW;
    boolean compIsActiveHigh = comp.getFactory().ActiveOnHigh(comp.getAttributeSet());
    return boardIsActiveHigh ^ compIsActiveHigh;
  }

  private void generateInlinedCodeSignal(Hdl out, Path path, NetlistComponent shadow) {
    // Note: Any logisim component that is not active-high will get an inversion
    // here. Also, any FPGA I/O device that is not active-high will get an
    // inversion. In cases where there would be two inversions, we leave them
    // both off.
    // Note: The signal being mapped might be an entire signal, e.g. s_SomePin,
    // or it might be a slice of some hidden net, e.g. s_LOGISIM_HIDDEN_OUTPUT[7 downto 3].
    // And that signal might get mapped together to a single I/O device, or each
    // bit might be individually mapped to different I/O devices.
    String signal; // e.g.: s_SomePin or s_LOGISIM_HIDDEN_OUTPUT[7 downto 3].
    String bit;    // e.g.: s_SomePin or s_LOGISIM_HIDDEN_OUTPUT
    int offset;    // e.g.: 0 or 3
    if (shadow.original.getFactory() instanceof Pin) {
      signal = "s_" + shadow.pathName();
      bit = signal;
      offset = 0;
    } else {
      NetlistComponent.Range3 indices = shadow.getGlobalHiddenPortIndices(path);
      if (indices == null) {
        out.err.AddFatalError("INTERNAL ERROR: Missing index data for I/O component %s", path);
        return;
      }
      if (indices.end.in == indices.start.in) {
        // foo[5] is the only bit
        offset = indices.start.in;
        bit = "s_LOGISIM_HIDDEN_FPGA_INPUT";
        signal = String.format(bit+out.idx, offset);
      } else if (indices.end.in > indices.start.in) {
        // foo[8:3]
        offset = indices.start.in;
        bit = "s_LOGISIM_HIDDEN_FPGA_INPUT";
        signal = String.format(bit+out.range, indices.end.in, offset);
      } else if (indices.end.out == indices.start.out) {
        // foo[5] is the only bit
        offset = indices.start.out;
        bit = "s_LOGISIM_HIDDEN_FPGA_OUTPUT";
        signal = String.format(bit+out.idx, offset);
      } else if (indices.end.out > indices.start.out) {
        // foo[8:3]
        offset = indices.start.out;
        bit = "s_LOGISIM_HIDDEN_FPGA_OUTPUT";
        signal = String.format(bit+out.range, indices.end.out, offset);
      } else {
        // This is an inout signal, handled elsewhere (no inversions possible)
        return;
      }
    }

    PinBindings.Source src = ioResources.sourceFor(path);
    PinBindings.Dest dest = ioResources.mappings.get(src);
    if (dest != null) { // Entire port is mapped to one BoardIO resource.
      boolean invert = needTopLevelInversion(shadow.original, dest.io);
      String maybeNot = (invert ? out.not + " " : "");
      Netlist.Int3 destwidth = dest.io.getPinCounts();
      if (dest.io.type == BoardIO.Type.Unconnected) {
        // If user assigned type "unconnected", do nothing. Synthesis will warn,
        // but optimize away the signal.
      } else if (!BoardIO.PhysicalTypes.contains(dest.io.type)) {
        // Handle synthetic input types.
        int constval = dest.io.syntheticValue;
        out.assign(signal, maybeNot+out.literal(constval, destwidth.in));
      } else {
        // Handle physical I/O device types.
        Netlist.Int3 seqno = dest.seqno();
        // Input pins
        if (src.width.in == 1)
          out.assign(signal, maybeNot+"FPGA_INPUT_PIN_"+seqno.in);
        else for (int i = 0; i < src.width.in; i++)
          out.assign(bit, offset+i, maybeNot+"FPGA_INPUT_PIN_"+(seqno.in+i));
        // Output pins
        if (src.width.out == 1)
          out.assign("FPGA_OUTPUT_PIN_"+seqno.out, maybeNot+signal);
        else for (int i = 0; i < src.width.out; i++)
          out.assign("FPGA_OUTPUT_PIN_"+(seqno.out+i), maybeNot+bit, offset+i);
        // Note: no such thing as inout pins
      }
    } else { // Each bit of pin is assigned to a different BoardIO resource.
      ArrayList<PinBindings.Source> srcs = ioResources.bitSourcesFor(path);
      for (int i = 0; i < srcs.size(); i++)  {
        src = srcs.get(i);
        dest = ioResources.mappings.get(src);
        Netlist.Int3 destwidth = dest.io.getPinCounts();
        boolean invert = needTopLevelInversion(shadow.original, dest.io);
        String maybeNot = (invert ? out.not + " " : "");
        if (dest.io.type == BoardIO.Type.Unconnected) {
          // If user assigned type "unconnected", do nothing. Synthesis will warn,
          // but optimize away the signal.
          continue;
        } else if (!BoardIO.PhysicalTypes.contains(dest.io.type)) {
          // Handle synthetic input types.
          int constval = dest.io.syntheticValue;
          out.assign(bit, offset+i, maybeNot+out.literal(constval, 1));
        } else {
          // Handle physical I/O device types.
          Netlist.Int3 seqno = dest.seqno();
          //if ((destwidth.in > 0) || ((destwidth.inout > 0) && (src.width.in > 0)))
          if (src.width.in > 0)	// use src-signals for distinction, because they have always a direction 
            out.assign(bit, offset+i, maybeNot+"FPGA_INPUT_PIN_"+seqno.in);
          // Output pins
          //if ((destwidth.out > 0) || ((destwidth.inout > 0) && (src.width.out > 0))) 
          if (src.width.out > 0) 
            out.assign("FPGA_OUTPUT_PIN_"+seqno.out, maybeNot+bit, offset+i);
          // Note: no such thing as inout pins
        }
      }
    }
  }

  protected String[] getBidirPinAssignments(int n) {
    String[] fpgaPins = new String[n];
    // go through each hidden inout device
    ioResources.components.forEach((path, shadow) -> 
      getBidirPinAssignments(path, shadow, fpgaPins));
    // Sanity check:
    for (int i = 0; i < n; i++) {
      if (fpgaPins[i] == null) {
        _err.AddFatalError("INTERNAL ERROR: Some bidirectional ports were not correctly mapped.");
        fpgaPins[i] = "unknown"; // will cause hdl gen to fail
      }
    }
    return fpgaPins;
  }

  private void getBidirPinAssignments(Path path, NetlistComponent shadow, String[] fpgaPins) {
    // Note: Any logisim component that needs a bidirectional port (which are
    // only hidden ports, at least so far), gets handled here. This is used by
    // CircuitHDLGenerator to create the instance port mapping for the top-most
    // circuit under test.
    // Note: The signal being mapped is always a slice of
    // LOGISIM_HIDDEN_FPGA_INOUT[n downto 0].
    // And that signal might get mapped together to a single I/O device, or each
    // bit might be individually mapped to different I/O devices. Or to open, or
    // a constant.
    String signal; // e.g.: s_SomePin or s_LOGISIM_HIDDEN_OUTPUT[7 downto 3].
    String bit;    // e.g.: s_SomePin or s_LOGISIM_HIDDEN_OUTPUT
    int offset;    // e.g.: 0 or 3
    if (shadow.original.getFactory() instanceof Pin)
      return; // Pin is never bidir
    NetlistComponent.Range3 indices = shadow.getGlobalHiddenPortIndices(path);
    if (indices == null)
      return; // error, handled above
    if (indices.end.inout == indices.start.inout) {
      // foo[5] is the only bit
      offset = indices.start.inout;
      bit = "LOGISIM_HIDDEN_FPGA_INOUT";
      signal = String.format(bit+_hdl.idx, offset);
    } else if (indices.end.inout > indices.start.inout) {
      // foo[8:3]
      offset = indices.start.inout;
      bit = "LOGISIM_HIDDEN_FPGA_INOUT";
      signal = String.format(bit+_hdl.range, indices.end.inout, offset);
    } else {
      return; // not bidir
    }

    if (indices.start.inout < 0 || indices.end.inout >= fpgaPins.length) {
      _hdl.err.AddFatalError("INTERNAL ERROR: Bidirectional device '%s' "
          + "has offsets %d..%d, but should be within 0..%d.",
          path, indices.start.inout, indices.end.inout, fpgaPins.length-1);
      return;
    }
    for (int i = indices.start.inout; i <= indices.end.inout; i++) {
      if (fpgaPins[i] != null) {
        _hdl.err.AddFatalError("INTERNAL ERROR: Bidirectional device '%s' "
            + "offset %d is already mapped to pin '%s'.",
            path, i, fpgaPins[i]);
        return;
      }
    }

    PinBindings.Source src = ioResources.sourceFor(path);
    PinBindings.Dest dest = ioResources.mappings.get(src);
    if (dest != null) { // Entire port is mapped to one BoardIO resource.
      boolean invert = needTopLevelInversion(shadow.original, dest.io);
      if (invert) {
        _hdl.err.AddSevereWarning("Bidirectional device '%s' is mapped to active-low pin '%s': "
            + "HDL generator can't insert an inverter in this case, so user circuit must "
            + "explicitly account for active-low logic for this signal.",
            src, dest);
      }
      Netlist.Int3 destwidth = dest.io.getPinCounts();
      Netlist.Int3 seqno = dest.seqno();
      if (seqno != null)
        seqno = seqno.copy();
      if (dest.io.type == BoardIO.Type.Unconnected) {
        // If user assigned type "unconnected", use bogus "FPGA_OPEN_" signal for mapping.
        // VHDL could use keyword "open", but that isn't possible for Verilog.
        for (int i = indices.start.inout; i <= indices.end.inout; i++)
          fpgaPins[i] = "FPGA_OPEN_"+(seqno.inout++);
      } else if (!BoardIO.PhysicalTypes.contains(dest.io.type)) {
        // Handle synthetic input types.
        int constval = dest.io.syntheticValue;
        for (int i = indices.start.inout; i <= indices.end.inout; i++)
          fpgaPins[i] = _hdl.literal((constval>>i)&1, 1);
      } else {
        // Handle physical I/O device types.
        for (int i = indices.start.inout; i <= indices.end.inout; i++)
          fpgaPins[i] = "FPGA_INOUT_PIN_"+(seqno.inout++);
      }
    } else { // Each bit of port is assigned to a different BoardIO resource.
      ArrayList<PinBindings.Source> srcs = ioResources.bitSourcesFor(path);
      for (int i = 0; i < srcs.size(); i++)  {
        src = srcs.get(i);
        dest = ioResources.mappings.get(src);
        Netlist.Int3 destwidth = dest.io.getPinCounts();
        Netlist.Int3 seqno = dest.seqno();
        if (seqno != null)
          seqno = seqno.copy();
        boolean invert = needTopLevelInversion(shadow.original, dest.io);
        if (invert) {
        _hdl.err.AddSevereWarning("Bidirectional device '%s' bit %d is mapped to active-low pin '%s': "
            + "HDL generator can't insert an inverter in this case, so user circuit must "
            + "explicitly account for active-low logic for this signal.",
            src, i, dest);
        }
        if (dest.io.type == BoardIO.Type.Unconnected) {
          fpgaPins[i] = "FPGA_OPEN_"+(seqno.inout++);
        } else if (!BoardIO.PhysicalTypes.contains(dest.io.type)) {
          // Handle synthetic input types.
          int constval = dest.io.syntheticValue;
          fpgaPins[i] = _hdl.literal(constval, 1);
        } else {
          // Handle physical I/O device types.
          if (destwidth.inout == 1)
            fpgaPins[i] = "FPGA_INOUT_PIN_"+(seqno.inout++);
        }
      }
    }
  }
  
  public void notifyNetlistReady() {
    circgen.notifyNetlistReady();
  }

}
