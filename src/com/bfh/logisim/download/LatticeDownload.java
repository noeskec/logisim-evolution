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
 *   
 * This file for supporting the Lattice toolchain in LogSim was written by: 
 *   + Carsten Noeske (noe@ghse.de, https://www.ghse.de)  
 */

package com.bfh.logisim.download;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.bfh.logisim.fpga.Chipset;
import com.bfh.logisim.fpga.DriveStrength;
import com.bfh.logisim.fpga.IoStandard;
import com.bfh.logisim.fpga.PinBindings;
import com.bfh.logisim.fpga.PullBehavior;
import com.bfh.logisim.gui.Commander;
import com.bfh.logisim.hdlgenerator.FileWriter;
import com.bfh.logisim.settings.Settings;
import com.cburch.logisim.hdl.Hdl;

public class LatticeDownload extends FPGADownload {

  public LatticeDownload() { super("Lattice"); }
  
  public enum TOOLCHAIN { DIAMOND_WIN, DIAMOND_UNIX, ISP_LEVER_WIN, ISP_LEVER_UNIX, UNKNOWN };
  
  public static TOOLCHAIN getToolChainType() {
	  return getToolChainType(Settings.getSettings().GetLatticeToolPath());
  }

  public static TOOLCHAIN getToolChainType(String toolPathBinDirectory) {
	  Path toolPath = Paths.get(toolPathBinDirectory);
	  if (Files.exists(toolPath.resolve("pnmainc.exe"))) {
		  return TOOLCHAIN.DIAMOND_WIN;
	  }
	  if (Files.exists(toolPath.resolve("diamondc"))) {
		  return TOOLCHAIN.DIAMOND_UNIX;
	  }
	  if (Files.exists(toolPath.resolve("projnav.exe"))) {
		  return TOOLCHAIN.ISP_LEVER_WIN;
	  }
	  return TOOLCHAIN.UNKNOWN;
  }

  
  private String getRelativePath(String pathRoot, String... pathes) {
	  return getRelativePathAsPath(pathRoot,pathes).toString();
  }
  
  private String getAbsolutePath(String pathRoot, String... pathes) {
	  return getAbsolutePathAsPath(pathRoot,pathes).toString();
  }

  private Path getRelativePathAsPath(String pathRoot, String... pathes) {
	  return Paths.get("..").resolve(Paths.get(projectPath).relativize(Paths.get(pathRoot,pathes)));
  }
  
  private Path getAbsolutePathAsPath(String pathRoot, String... pathes) {
	  return Paths.get(pathRoot,pathes).toAbsolutePath();
  }
  
  private String toWinPath(Path path) {
	  return path.toString().replace("/","\\");
  }

  private String toTclPath(Path path) {
	  return path.toString().replace("\\","/");
  }

  private String toUnixPath(Path path) {
	  String s = toTclPath(path);
	  if (s.indexOf(':') == 1) {
		  s = "/"+Character.toLowerCase(s.charAt(0))+s.substring(2);
	  }
	  return s;
  }

  @Override
  public boolean readyForDownload() {
	String bitFileExt = ".jed";
	//return new File(sandboxPath + TOP_HDL + bitFileExt).exists();
	//return Files.exists(Paths.get(sandboxPath).resolve("impl1").resolve(PROJECT_NAME+"_impl1"+bitFileExt));
	return Files.exists(getAbsolutePathAsPath(sandboxPath,"impl1",PROJECT_NAME+"_impl1"+bitFileExt));
  }

  private ArrayList<String> cmd(String prog, String... args) {
    ArrayList<String> command = new ArrayList<>();
    //command.add(Paths.get(settings.GetLatticeToolPath()).resolve(prog).toString());
    if (prog.endsWith(".cmd") || prog.endsWith(".bat")) {
    	command.add("cmd");
    	command.add("/c");
    }
    command.add(prog);
    for (String arg: args)
      command.add(arg);
    return command;
  }

  @Override
	public ArrayList<Stage> initiateDownload(Commander cmdr) {
    ArrayList<Stage> stages = new ArrayList<>();

	if (!readyForDownload()) {
		
	  String synthFile;
	  switch (getToolChainType()) {
	  case DIAMOND_WIN: synthFile = PROJECT_RUN_FILE; break;
	  case DIAMOND_UNIX: synthFile = PROJECT_RUN_FILE_UNIX; break;
	  case ISP_LEVER_WIN: synthFile = PROJECT_RUN_FILE_ISPLEVER; break;
	  case ISP_LEVER_UNIX: 
	  case UNKNOWN:
	  default:
		  return stages;
	  }
	  	
      String script = getRelativePath(scriptPath,synthFile);
      //String log = script.subSequence(0, script.lastIndexOf('.'))+".log"; 
      stages.add(new Stage(
            "synthesize", "Synthesizing (may take a while)",
            //cmd(script, ">", log),
            cmd(script),
            "Failed to synthesize Lattice project, cannot download"));
	}
	if (!board.fpga.USBTMCDownload) {
	 // TODO: support LPT-Download!
	}
	
	String downloadFile;
	switch (getToolChainType()) {
	case DIAMOND_WIN: downloadFile = PROJECT_DOWNLOAD_FILE; break;
	case DIAMOND_UNIX: downloadFile = PROJECT_DOWNLOAD_FILE_UNIX; break;
	case ISP_LEVER_WIN: downloadFile = PROJECT_DOWNLOAD_FILE_ISPLEVER; break;
	case ISP_LEVER_UNIX: 
	case UNKNOWN:
	default:
	 return stages;
	}
	downloadFile = getRelativePath(scriptPath,downloadFile);
	
	stages.add(new Stage("download", "Downloading to FPGA", cmd(downloadFile),
	  "Failed to download design; did you connect the board?") {
	    @Override
		protected boolean prep() {
			if (!cmdr.confirmDownload()) {
				cancelled = true;
				return false;
			}
			return true;
		}
	  });
    return stages;
  }

  private boolean generateProjectFile(ArrayList<String> hdlFiles) {
	  
	// --- ispLEVER project file ---  
    Hdl out = new Hdl(lang, err);

    Chipset chip = board.fpga;
    String device = String.format("%s%s%s", chip.Part, chip.SpeedGrade, chip.Package);
    
    out.stmt("JDF B");
    out.stmt("// Created by Version 6.1");
    out.stmt("DESIGN %s Normal",name);
    out.stmt("DEVKIT %s",device);
    String hdl_type = lang.toUpperCase();
    out.stmt("ENTRY Schematic/%s",hdl_type);
	// File flpf = FileWriter.GetFilePointer(ucfPath, lpf_file, err);
	// out.stmt("DOCUMENT %s",flpf.getAbsolutePath().replace("\\","/"));
	for (String fname : hdlFiles) {
		out.stmt("MODULE %s",getRelativePath(fname));
		
		String moduleName = fname;
		if (moduleName.endsWith(".vhdl")) moduleName = moduleName.substring(0, moduleName.length()-1);
		if (!moduleName.contains("_entity.vhd")) {
			//moduleName = fname.replace("_entity.vhd","");
			moduleName = moduleName.replace("_behavior.vhd","");
		}
		int idx = moduleName.lastIndexOf('\\');
		if (idx <= 0) idx = moduleName.lastIndexOf('/');
		if (idx >= 0) moduleName = moduleName.substring(idx+1);
		out.stmt("MODSTYLE %s Normal",moduleName);
	}
	out.stmt("SYNTHESIS_TOOL Synplify");
	out.stmt("TOPMODULE LogisimToplevelShell");
	
	File f = FileWriter.GetFilePointer(sandboxPath, PROJECT_FILE_ISPLEVER, err);
	boolean success = f != null && FileWriter.WriteContents(f, out, err);
	
	// ---- lattice diamond scripts
	// tcl-project
	out = new Hdl(lang, err);
	final String synth_engine = "lse";
	out.stmt("prj_project new -name \"%s\" -impl \"impl1\" -dev %s -synthesis \"%s\"",PROJECT_NAME,device,synth_engine);

	for (String fname : hdlFiles) {
		//String path = fname.replace(projectPath,"../");
		out.stmt("prj_src add \"%s\"",toUnixPath(getRelativePathAsPath(fname)));
	}
	out.stmt("prj_project save");
	
	f = FileWriter.GetFilePointer(scriptPath, PROJECT_CREATION_TCL_FILE, err);
	success &= f != null && FileWriter.WriteContents(f, out, err);
	
	return success;
  }

  private boolean generateLpfFile(PinBindings ioResources) {
	  /*
	   * BLOCK RESETPATHS ; 
	   * BLOCK ASYNCPATHS ;
	   * FREQUENCY NET "clk_c" 12.000000 MHz ;
	   * IOBUF ALLPORTS IO_TYPE=LVCMOS33 ;
	   * LOCATE COMP "count_3" SITE "U2" ;
	   * LOCATE COMP "count_2" SITE "B2" ;
	   * IOBUF PORT "reset" IO_TYPE=LVCMOS33 ;
	   */
    Hdl out = new Hdl(lang, err);
		if (ioResources.requiresOscillator) {
			/*
			out.stmt("NET \"%s\" %s ;", CLK_PORT, latticeClockSpec(board.fpga));
			out.stmt("NET \"%s\" TNM_NET = \"%s\" ;", CLK_PORT, CLK_PORT);
			out.stmt("TIMESPEC \"TS_%s\" = PERIOD \"%s\" %s HIGH 50 %% ;",
          CLK_PORT, CLK_PORT, board.fpga.Speed);
			out.stmt();
			*/
			out.stmt("FREQUENCY PORT \"%s\" %s;",CLK_PORT,board.fpga.Speed.toUpperCase());
			out.stmt("LOCATE COMP \"%s\" SITE \"%s\";",CLK_PORT,board.fpga.ClockPinLocation);
			
			String iospec = generateIoSpec(CLK_PORT, board.fpga.ClockPullBehavior, board.fpga.ClockIOStandard, DriveStrength.UNKNOWN);
			if (iospec.length() > 0) {
				out.stmt("%s;",iospec);
			}
		}
    ioResources.forEachPhysicalPin((pin, net, io, label) -> {
      String spec = String.format("LOCATE COMP \"%s\" SITE \"%s\"", net, pin);
      String iospec = generateIoSpec(net, io.pull, io.standard, io.strength);
      
      out.stmt("%s; # %s", spec, label);
      if (iospec.length() > 0) out.stmt("%s; ",iospec);
    });
    
    // ispLEVER needs the file next to the project file
    String s = PROJECT_FILE_ISPLEVER; s = s.replace(".syn",".lpf");
    if (!PROJECT_RUN_FILE.equals(s)) {
		File f = FileWriter.GetFilePointer(sandboxPath, s, err);
		FileWriter.WriteContents(f, out, err);
    }
    
    File f = FileWriter.GetFilePointer(ucfPath, PROJECT_CONSTRAINT_FILE, err);
	return FileWriter.WriteContents(f, out, err);
  }

  private String generateIoSpec(String net, PullBehavior pull, IoStandard standard, DriveStrength strength) {
      String iospec = "";
      if (pull == PullBehavior.PULL_UP || pull == PullBehavior.PULL_DOWN  || pull == PullBehavior.FLOAT)
        iospec += " PULLMODE=" + pull.lattice;
      if (standard != IoStandard.UNKNOWN && standard != IoStandard.DEFAULT)
          iospec += " IO_TYPE=" + standard;
      if (strength != DriveStrength.UNKNOWN && strength != DriveStrength.DEFAULT)
        iospec += " DRIVE=" + strength.ma;
      if (iospec.length() > 0) {
    	  iospec = "IOBUF PORT \""+net+"\" "+iospec;
      }
      return iospec;
  }
  
  
  private boolean generateRunScript() {

    //String vhdlListPath = scriptPath.replace(projectPath, "../") + vhdl_list_file;
    String prjFile = toTclPath(getRelativePathAsPath(sandboxPath,PROJECT_FILE));
    
    Hdl out = new Hdl(lang, err);

    out.stmt("prj_project open \"%s\"",prjFile);
    out.stmt("");
    out.stmt("prj_run Synthesis -impl impl1");
    out.stmt("prj_run Translate -impl impl1");
    out.stmt("prj_run Map -impl impl1");
    out.stmt("prj_run PAR -impl impl1");
    out.stmt("prj_run PAR -impl impl1 -task PARTrace");
    out.stmt("prj_run Export -impl impl1 -task Bitgen");
    out.stmt("prj_project close");
    
	File f = FileWriter.GetFilePointer(scriptPath, PROJECT_RUN_TCL_FILE, err);
	boolean success = f != null && FileWriter.WriteContents(f, out, err);
    
	// --- windows synthesis script ---
	out = new Hdl(lang, err);
	
	Path latticeToolPath = Paths.get(Settings.getSettings().GetLatticeToolPath());
	String binDirectory = latticeToolPath.getFileName().toString();	// either "nt" or "nt64"
	
	// detect directory of tcl-library by going upwards above the bin-directory and downwards to the tcl-lib
	Path toolPath = latticeToolPath;
	while (((toolPath != null) && Files.notExists(toolPath.resolve("bin")))) {
		toolPath = toolPath.getParent();
	}
	Path tclLibPath = toolPath.resolve("tcltk").resolve("lib");
	try {
		Path tcllib = Paths.get("");
		List<Path> list = Files.list(tclLibPath).filter(p -> (p.getFileName().toString().startsWith("tcl") && Files.isDirectory(p))).collect(Collectors.toList()); 
		for (Path ptcl : list) {
			// take the directory with the longest name
			if (tcllib.toString().length() < ptcl.toString().length()) {
				tcllib = ptcl;
			}
		}
		tclLibPath = tcllib;
	} catch (IOException e) {
	}
	
	Path tcl_prj_creation_file = getRelativePathAsPath(scriptPath,PROJECT_CREATION_TCL_FILE);
	Path tcl_prj_synth_file = getRelativePathAsPath(scriptPath,PROJECT_RUN_TCL_FILE);
	
	out.stmt("@echo off",toWinPath(toolPath));
	out.stmt("set LCD_DIAMOND_PATH=%s",toWinPath(toolPath));
	out.stmt("");
	out.stmt("set LSC_INI_PATH=");
	out.stmt("set LSC_DIAMOND=true");
	out.stmt("set TCL_LIBRARY=%s",toWinPath(tclLibPath));
	out.stmt("set FOUNDRY=%s\\ispfpga",toWinPath(toolPath));
	out.stmt("set PATH=%%FOUNDRY%%\\bin\\%s;%%PATH%%",binDirectory);
	out.stmt("\"%s/pnmainc.exe\" %s",toWinPath(latticeToolPath),toWinPath(tcl_prj_creation_file));
	out.stmt("\"%s/pnmainc.exe\" %s",toWinPath(latticeToolPath),toWinPath(tcl_prj_synth_file));
	out.stmt("EXIT /B %%ERRORLEVEL%%");
    
	f = FileWriter.GetFilePointer(scriptPath, PROJECT_RUN_FILE, err);
	success &= f != null && FileWriter.WriteContents(f, out, err);
	
	// ---- linux synthesis script ----
	out = new Hdl(lang, err);
	
	out.stmt("export TEMP=/tmp");
	out.stmt("export LSC_INI_PATH=\"\"");
	out.stmt("export LSC_DIAMOND=true");
	out.stmt("export TCL_LIBRARY=%s",toUnixPath(tclLibPath));
	out.stmt("export FOUNDRY=%s/ispFPGA",toUnixPath(toolPath));
	out.stmt("export PATH=$FOUNDRY/bin/%s:$PATH",binDirectory);
	out.stmt("%s/diamondc %s",toUnixPath(latticeToolPath), toUnixPath(tcl_prj_creation_file));
	out.stmt("%s/diamondc %s",toUnixPath(latticeToolPath), toUnixPath(tcl_prj_synth_file));
			
	f = FileWriter.GetFilePointer(scriptPath, PROJECT_RUN_FILE_UNIX, err);
	success &= f != null && FileWriter.WriteContents(f, out, err);

	// --- ispLEVER synthesis file => open project navigator for the user
	String prj_isplever_file = getRelativePath(sandboxPath,PROJECT_FILE_ISPLEVER);
	String projnav = toWinPath(latticeToolPath.resolve("projnav.exe"));

	out = new Hdl(lang, err);
	out.stmt("@echo off");
	out.stmt("rem start project navigator and wait until it is finished. The user has to perfrom the synthesis!");
	out.stmt("start /B /wait \"%s\" \"%s\"",projnav,prj_isplever_file);
	f = FileWriter.GetFilePointer(scriptPath, PROJECT_RUN_FILE_ISPLEVER, err);
	success &= f != null && FileWriter.WriteContents(f, out, err);	
	
	return success;
  }

  private boolean generateDownloadScript() {
	  // --- download tcl file --- 
	  Hdl out = new Hdl(lang, err);
	  out.stmt("pgr_project open \"%s\"",toTclPath(getRelativePathAsPath(sandboxPath,PROJECT_NAME+".xcf")));
	  out.stmt("pgr_program set -cable %s -portaddress %s","usb2","FTUSB-0");
	  out.stmt("pgr_project save \"%s_latest.xcf\"",PROJECT_NAME);
	  out.stmt("pgr_project close");
	  out.stmt("");
	  out.stmt("pgr_project open \"%s_latest.xcf\"",PROJECT_NAME);
	  out.stmt("pgr_program run");
	  out.stmt("pgr_project close");
	  File f = FileWriter.GetFilePointer(scriptPath, PROJECT_DOWNLOAD_TCL_FILE, err);
	  boolean success = f != null && FileWriter.WriteContents(f, out, err);
	  
	  // --- download xcf-filefor Lattice Diamond ----
	  Chipset chip = board.fpga;
	  String chip_family = chip.Technology;
	  
	  out = new Hdl(lang, err);
	  out.stmt("<?xml version='1.0' encoding='utf-8' ?>");
	  out.stmt("<!DOCTYPE  ispXCF SYSTEM \"IspXCF.dtd\" >");
	  out.stmt("<ispXCF version=\"3.12\">");
	  out.stmt("  <Comment></Comment>");
	  out.stmt("  <Chain>");
	  out.stmt("    <Comm>JTAG</Comm>");
	  out.stmt("    <Device>");
	  out.stmt("      <Pos>%d</Pos>",chip.JTAGPos);
	  out.stmt("      <Vendor>%s</Vendor>",chip.VendorName);
	  out.stmt("      <Family>%s</Family>",chip_family);
	  out.stmt("      <Name>%s</Name>",chip.Part);
	  out.stmt("      <Package>All</Package>");
	  out.stmt("      <PON>%s</PON>",chip.Part);
	  
	  out.stmt("      <Bypass>");
	  out.stmt("        <InstrLen>8</InstrLen>");
	  out.stmt("        <InstrVal>11111111</InstrVal>");
	  out.stmt("        <BScanLen>1</BScanLen>");
	  out.stmt("        <BScanVal>0</BScanVal>");
	  out.stmt("      </Bypass>");
	  
	  out.stmt("      <File>%simpl1/%s_impl1.jed</File>","../sandbox/",PROJECT_NAME);
	  out.stmt("      <Operation>FLASH Erase,Program,Verify</Operation>");
	  
	  out.stmt("      <Option>");
	  out.stmt("        <SVFVendor>JTAG STANDARD</SVFVendor>");
	  out.stmt("        <IOState>HighZ</IOState>");
	  out.stmt("        <IOVectorData>0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF</IOVectorData>");
	  out.stmt("        <OverideUES value=\"TRUE\"/>");
	  out.stmt("        <TCKFrequency>1.000000 MHz</TCKFrequency>");
	  out.stmt("        <SVFProcessor>ispVM</SVFProcessor>");
	  out.stmt("      </Option>");
	  
	  out.stmt("    </Device>");
	  out.stmt("  </Chain>");
	  
	  out.stmt("  <ProjectOptions>");
	  out.stmt("    <Program>SEQUENTIAL</Program>");
	  out.stmt("    <Process>ENTIRED CHAIN</Process>");
	  out.stmt("    <OperationOverride>No Override</OperationOverride>");
	  out.stmt("    <StartTAP>TLR</StartTAP>");
	  out.stmt("    <EndTAP>TLR</EndTAP>");
	  out.stmt("    <DeGlitch value=\"TRUE\"/>");
	  out.stmt("    <VerifyUsercode value=\"TRUE\"/>");
	  out.stmt("    <TCKDelay>1</TCKDelay>");
	  out.stmt("  </ProjectOptions>");
	  
	  out.stmt("</ispXCF>");

	  f = FileWriter.GetFilePointer(sandboxPath, PROJECT_DOWNLOAD_CONFIG_FILE, err);
	  success &= f != null && FileWriter.WriteContents(f, out, err);
	  
	  // --- download run scripts ----
	  Path latticeToolPath = Paths.get(Settings.getSettings().GetLatticeToolPath());
	  
	  out = new Hdl(lang, err);
	  out.stmt("\"%s\\pnmainc.exe\" \"%s\"",toWinPath(latticeToolPath),toTclPath(getRelativePathAsPath(scriptPath,PROJECT_DOWNLOAD_TCL_FILE)));
	  f = FileWriter.GetFilePointer(scriptPath, PROJECT_DOWNLOAD_FILE, err);
	  success &= f != null && FileWriter.WriteContents(f, out, err);
	  
	  out = new Hdl(lang, err);
	  out.stmt("%s/diamondc \"%s\"",toUnixPath(latticeToolPath),toTclPath(getRelativePathAsPath(scriptPath,PROJECT_DOWNLOAD_TCL_FILE)));
	  f = FileWriter.GetFilePointer(scriptPath, PROJECT_DOWNLOAD_FILE_UNIX, err);
	  success &= f != null && FileWriter.WriteContents(f, out, err);

	  out = new Hdl(lang, err);
	  out.stmt("@echo off");
	  out.stmt("rem start ispVM and wait until it is finished. The user has to perfrom the download!");
	  out.stmt("cd \"%s\"",toWinPath(getRelativePathAsPath(sandboxPath)));
	  out.stmt("if not exist impl1\\ md impl1");
	  out.stmt("copy /Y %s.jed impl1\\%s_impl1.jed",PROJECT_NAME,PROJECT_NAME);
	  out.stmt("start /B /wait %s/../../ispvmsystem/ispVM.exe -infile %s -cabletype %s -portaddress %s -logfile %s -o",toWinPath(latticeToolPath),PROJECT_DOWNLOAD_CONFIG_FILE,"usb2","ftusb-0","output_download.txt");
	  // -o opens a windows with information
      out.stmt("EXIT /B %%ERRORLEVEL%%");

	  f = FileWriter.GetFilePointer(scriptPath, PROJECT_DOWNLOAD_FILE_ISPLEVER, err);
	  success &= f != null && FileWriter.WriteContents(f, out, err);	
	  
	  return success;
  }

  @Override
  public boolean generateScripts(PinBindings ioResources, ArrayList<String> hdlFiles) {
    return generateProjectFile(hdlFiles)
        && generateLpfFile(ioResources)
        && generateRunScript()
        && generateDownloadScript();
	}

	private final static String PROJECT_NAME = "LatticeProject";

	private final static String PROJECT_FILE = PROJECT_NAME+".ldf";

	private final static String PROJECT_FILE_ISPLEVER = PROJECT_NAME+".syn";
	
	private final static String PROJECT_CONSTRAINT_FILE = PROJECT_NAME+".lpf";
	
	private final static String PROJECT_CREATION_TCL_FILE = PROJECT_NAME+"_create.tcl";
	
	private final static String PROJECT_RUN_TCL_FILE = PROJECT_NAME+"_run.tcl";

	private final static String PROJECT_DOWNLOAD_TCL_FILE = PROJECT_NAME+"_download.tcl";

	private final static String PROJECT_RUN_FILE = PROJECT_NAME+"_run.cmd";

	private final static String PROJECT_RUN_FILE_UNIX = PROJECT_NAME+"_run.sh";
	private final static String PROJECT_RUN_FILE_ISPLEVER = PROJECT_NAME+"_run_ispLEVER.cmd";

	private final static String PROJECT_DOWNLOAD_CONFIG_FILE = PROJECT_NAME+".xcf";
	
	private final static String PROJECT_DOWNLOAD_FILE = PROJECT_NAME+"_download.cmd";

	private final static String PROJECT_DOWNLOAD_FILE_ISPLEVER = PROJECT_NAME+"_download_ispLEVER.cmd";
	
	private final static String PROJECT_DOWNLOAD_FILE_UNIX = PROJECT_NAME+"_download.sh";

}
