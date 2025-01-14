/*
 * Copyright (c) 2010-2022, sikuli.org, sikulix.com - MIT license
 */
package org.sikuli.script.runnerSupport;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.python.core.*;
import org.python.util.PythonInterpreter;
import org.sikuli.basics.Debug;
import org.sikuli.basics.FileManager;
import org.sikuli.basics.Settings;
import org.sikuli.ide.SikulixIDE;
import org.sikuli.script.ImagePath;
import org.sikuli.script.support.Commons;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JythonSupport implements IRunnerSupport {

  //<editor-fold defaultstate="collapsed" desc="00 logging">
  private static final String me = "Jython: ";
  private static int lvl = 3;

  public void log(int level, String message, Object... args) {
    Debug.logx(level, me + message, args);
  }
  //</editor-fold>

  //<editor-fold desc="01 instance">
  private static JythonSupport instance = null;

  private static PythonInterpreter interpreter = null;

//  private static RunTime runTime;

  private JythonSupport() {
  }

  public static JythonSupport get() {
    if (null == instance) {
      instance = new JythonSupport();
      init();
    }
    return instance;
  }

  public static boolean isSupported() {
    try {
      Class.forName("org.python.util.PythonInterpreter");
      return true;
    } catch (ClassNotFoundException ex) {
      Debug.log(-1, "no Jython on classpath --- consult the docs for a solution, if needed");
      return false;
    }
  }

  private static void init() {
    try {
      //TODO is this initialize needed?
      //PythonInterpreter.initialize(System.getProperties(), null, new String[0]);
    } catch (Exception ex) {
      Debug.log("Jython: not found on classpath");
      return;
    }
    File pyLib = new File(Commons.getAppDataPath(), "Lib");

    if (!pyLib.exists() && !pyLib.mkdirs()) {
      Commons.terminate(998, "JythonSupport: failed: %s", pyLib);
    } else {
      FileManager.deleteFileOrFolder(pyLib, new FileManager.FileFilter() {
        @Override
        public boolean accept(File entry) {
          if (entry.getPath().contains("site-packages")) {
            return false;
          }
          return true;
        }
      });
    }
    File sitePackages = getSitePackages();
    File sitesTxt = getSitesTxt();
    if ((!sitePackages.exists() && sitePackages.mkdir()) || !sitesTxt.exists()) {
      FileManager.writeStringToFile(getSitesTxtDefault(), sitesTxt);
    }

    try {
      interpreter = new PythonInterpreter();
    } catch (Exception ex) {
      String cause = ex.getMessage();
      instance.log(-1, "Problem: New PythonInterpreter: %s", cause);
      interpreter = null;
    }
    if (interpreter != null) {
      try {
        cPyException = Class.forName("org.python.core.PyException");
        cPyFunction = Class.forName("org.python.core.PyFunction");
        cPyMethod = Class.forName("org.python.core.PyMethod");
        cPyInstance = Class.forName("org.python.core.PyInstance");
        cPyString = Class.forName("org.python.core.PyString");
      } catch (ClassNotFoundException ex) {
        instance.log(-1, "reflection problem: %s", ex.getMessage());
        interpreter = null;
      }
    }
  }

  public static boolean isReady() {
    return interpreter != null;
  }

  /**
   * For experts, who want to tweak the Jython interprter instance<br>
   * Usage: org.sikuli.script.runnerSupport.JythonSupport.get().interpreterGet()
   *
   * @return the singleton Jython interpreter instance (org.python.util.PythonInterpreter)
   */
  public PythonInterpreter interpreterGet() {
    return interpreter;
  }

  public void interpreterCleanup() {
    if (null != interpreter) {
      interpreter.cleanup();
    }
  }

  public void interpreterClose() {
    if (null != interpreter) {
      interpreter.close();
    }
  }

  public boolean interpreterRedirect(PrintStream stdout, PrintStream stderr) {
    if (interpreter == null) {
      return false;
    }
    try {
      interpreter.setOut(stdout);
    } catch (Exception e) {
      log(-1, "Jython: redirect STDOUT: %s", e.getMessage());
      return false;
    }
    try {
      interpreter.setErr(stderr);
    } catch (Exception e) {
      log(-1, "Jython: redirect STDERR: %s", e.getMessage());
      return false;
    }
    return true;
  }
  //</editor-fold>

  public static File getSitePackages() {
    return new File(Commons.getAppDataPath(), "Lib/site-packages");
  }

  public static File getSitesTxt() {
    return new File(getSitePackages(), "sites.txt");
  }

  public static String getSitesTxtDefault() {
    return "# add absolute paths one per line, that point to other directories/jars,\n" +
        "# where importable modules (Jython, plain Python, SikuliX scripts, ...) can be found.\n" +
        "# They will be added automatically at startup to the end of sys.path in the given sequence\n" +
        "\n" +
        "# lines beginning with # and blank lines are ignored and can be used as comments\n";
  }

  public static void exportLib() {
    Commons.copyResourceFiles("Lib", Commons.getAppDataPath().getPath(), SikulixIDE.class);
  }

  //<editor-fold desc="05 Jython reflection">
  static Class cPyMethod = null;

  static Class cPyException = null;

  class SXPyException {

    Object inst = null;
    Field fType = null;
    Field fValue = null;
    Field fTrBack = null;

    public SXPyException(Object i) {
      inst = i;
      cPyException.cast(inst);
      try {
        fType = cPyException.getField("type");
        fValue = cPyException.getField("value");
        fTrBack = cPyException.getField("traceback");
      } catch (Exception ex) {
      }
    }

    public int isTypeExit() {
      try {
        if (fType.get(inst).toString().contains("SystemExit")) {
          return Integer.parseInt(fValue.get(inst).toString());
        }
      } catch (Exception ex) {
        return -999;
      }
      return -1;
    }
  }

  static Class cPyInstance = null;

  class SXPyInstance {

    Object inst = null;
    Method mGetAttr = null;
    Method mInvoke = null;

    public SXPyInstance(Object i) {
      inst = i;
      cPyInstance.cast(inst);
      try {
        mGetAttr = cPyInstance.getMethod("__getattr__", String.class);
        mInvoke = cPyInstance.getMethod("invoke", String.class, Class.forName("org.python.core.PyObject"));
      } catch (Exception ex) {
      }
    }

    public Object get() {
      return inst;
    }

    Object __getattr__(String mName) {
      if (mGetAttr == null) {
        return null;
      }
      Object method = null;
      try {
        method = mGetAttr.invoke(inst, mName);
      } catch (Exception ex) {
      }
      return method;
    }

    public void invoke(String mName, Object arg) {
      if (mInvoke != null) {
        try {
          mInvoke.invoke(inst, mName, arg);
        } catch (Exception ex) {
        }
      }
    }
  }

  static Class cPyFunction = null;

  class SXPyFunction {

    public String __name__;
    Object func = null;
    Method mCall = null;
    Method mCall1 = null;

    public SXPyFunction(Object f) {
      func = f;
      try {
        cPyFunction.cast(func);
        mCall = cPyFunction.getMethod("__call__");
        mCall1 = cPyFunction.getMethod("__call__", Class.forName("org.python.core.PyObject"));
      } catch (Exception ex) {
        func = null;
      }
      if (func == null) {
        try {
          func = f;
          cPyMethod.cast(func);
          mCall = cPyMethod.getMethod("__call__");
          mCall1 = cPyMethod.getMethod("__call__", Class.forName("org.python.core.PyObject"));
        } catch (Exception ex) {
          func = null;
        }
      }
    }

    void __call__(Object arg) {
      if (mCall1 != null) {
        try {
          mCall1.invoke(func, arg);
        } catch (Exception ex) {
        }
      }
    }

    void __call__() {
      if (mCall != null) {
        try {
          mCall.invoke(func);
        } catch (Exception ex) {
        }
      }
    }
  }

  class SXPy {

    Method mJava2py = null;

    public SXPy() {
      try {
        mJava2py = Class.forName("org.python.core.Py").getMethod("java2py", Object.class);
      } catch (Exception ex) {
      }
    }

    Object java2py(Object arg) {
      if (mJava2py == null) {
        return null;
      }
      Object pyObject = null;
      try {
        pyObject = mJava2py.invoke(null, arg);
      } catch (Exception ex) {
      }
      return pyObject;
    }
  }
  //</editor-fold>

  //<editor-fold desc="17 exec/eval">
  public Object interpreterEval(String expression) {
    if (interpreter == null) {
      return "";
    }
    return interpreter.eval(expression);
  }

  public boolean interpreterExecString(String script) {
    interpreter.exec(script);
    return true;
  }

  public void interpreterExecCode(File compiledScript) {
    String scriptFile = compiledScript.getAbsolutePath();
    byte[] data = new byte[0];
    try {
      data = FileUtils.readFileToByteArray(compiledScript);
    } catch (IOException e) {
      log(-1, "exec compiled script: %s", e.getMessage());
    }
    PyCode pyCode = BytecodeLoader.makeCode(FilenameUtils.getBaseName(scriptFile), data, scriptFile);
    interpreter.exec(pyCode);
  }

  public void interpreterExecFile(String script) {
    interpreter.execfile(script);
  }

  public void executeScriptHeader(List<String> codeBefore) {
//    Debug.on(4);
    for (String line : SCRIPT_HEADER) {
      log(lvl + 1, "executeScriptHeader: %s", line);
      interpreterExecString(line);
    }
    if (codeBefore != null) {
      for (String line : codeBefore) {
        interpreterExecString(line);
      }
    }
  }

  /**
   * The header commands, that are executed before every script
   */
  private static String[] SCRIPT_HEADER = new String[]{
      "# -*- coding: utf-8 -*- ",
      "import time; start = time.time()",
      "from sikuli import *",
      "resetToPrimaryScreen()",
      "Debug.log(4, 'Jython: BeforeScript: %s (%f)',  SCREEN, time.time()-start)",
  };
  //</editor-fold>

  //<editor-fold desc="10 sys.path handling">
  List<String> sysPath = new ArrayList<String>();
  int nPathAdded = 0;
  int nPathSaved = -1;
  private List<File> importedScripts = new ArrayList<File>();
  String name = "";
  private long lastRun = 0;

  public void getSysPath() {
    synchronized (sysPath) {
      if (null == interpreter) {
        return;
      }
      sysPath.clear();
      try {
        PySystemState pyState = interpreter.getSystemState();
        PyList pyPath = pyState.path;
        int pathLen = pyPath.__len__();
        for (int i = 0; i < pathLen; i++) {
          String entry = (String) pyPath.get(i);
          log(lvl + 1, "sys.path[%2d] = %s", i, entry);
          sysPath.add(entry);
        }
      } catch (Exception ex) {
        sysPath.clear();
      }
    }
  }

  public List<String> getSysPathAsList() {
    List<String> paths = new ArrayList<>();
    if (null != interpreter) {
      try {
        PySystemState pyState = interpreter.getSystemState();
        PyList pyPath = pyState.path;
        int pathLen = pyPath.__len__();
        for (int i = 0; i < pathLen; i++) {
          String entry = (String) pyPath.get(i);
          paths.add(entry);
        }
      } catch (Exception ex) {
        paths.clear();
      }
    }
    return paths;
  }

  public void setSysPath() {
    synchronized (sysPath) {
      if (null == interpreter || null == sysPath) {
        return;
      }
      try {
        PySystemState pyState = interpreter.getSystemState();
        PyList pyPath = pyState.path;
        int pathLen = pyPath.__len__();
        for (int i = 0; i < pathLen && i < sysPath.size(); i++) {
          String entry = sysPath.get(i);
          log(lvl + 1, "sys.path.set[%2d] = %s", i, entry);
          pyPath.set(i, entry);
        }
        if (pathLen < sysPath.size()) {
          for (int i = pathLen; i < sysPath.size(); i++) {
            String entry = sysPath.get(i);
            log(lvl + 1, "sys.path.add[%2d] = %s", i, entry);
            pyPath.add(entry);
          }
        }
        if (pathLen > sysPath.size()) {
          for (int i = sysPath.size(); i < pathLen; i++) {
            String entry = (String) pyPath.get(i);
            log(lvl + 1, "sys.path.rem[%2d] = %s", i, entry);
            pyPath.remove(i);
          }
        }
      } catch (Exception ex) {
        sysPath.clear();
      }
    }
  }

  public void addSysPath(File fFolder) {
    addSysPath(fFolder.getAbsolutePath()); // addSysPath(File fFolder)
  }

  public void addSysPath(String fpFolder) {
    synchronized (sysPath) {
      if (!hasSysPath(fpFolder)) {
        sysPath.add(0, fpFolder);
        setSysPath();
        nPathAdded++;
      }
    }
  }

  public void appendSysPath(String fpFolder) {
    synchronized (sysPath) {
      if (!hasSysPath(fpFolder)) {
        sysPath.add(fpFolder);
        setSysPath();
        nPathAdded++;
      }
    }
  }

  public void putSysPath(String fpFolder, int n) {
    synchronized (sysPath) {
      if (n < 1 || n > sysPath.size()) {
        addSysPath(fpFolder); // putSysPath(String fpFolder, int n)
      } else {
        sysPath.add(n, fpFolder);
        setSysPath();
        nPathAdded++;
      }
    }
  }

  public void replaceSysPath(String fpFolder, int n) {
    synchronized (sysPath) {
      if (n >= 0 && n <= sysPath.size()) {
        sysPath.set(n, fpFolder);
        setSysPath();
      }
    }
  }

  public void insertSysPath(File fFolder) {
    synchronized (sysPath) {
      getSysPath();
      sysPath.add((nPathSaved > -1 ? nPathSaved : 0), fFolder.getAbsolutePath());
      setSysPath();
      nPathSaved = -1;
    }
  }

  public void removeSysPath(File fFolder) {
    synchronized (sysPath) {
      int n;
      if (-1 < (n = getSysPathEntry(fFolder))) {
        sysPath.remove(n);
        nPathSaved = n;
        setSysPath();
        nPathAdded = nPathAdded == 0 ? 0 : nPathAdded--;
      }
    }
  }

  public boolean hasSysPath(String fpFolder) {
    synchronized (sysPath) {
      getSysPath();
      for (String fpPath : sysPath) {
        if (FileManager.pathEquals(fpPath, fpFolder)) {
          return true;
        }
      }
      return false;
    }
  }

  public int getSysPathEntry(File fFolder) {
    synchronized (sysPath) {
      getSysPath();
      int n = 0;
      for (String fpPath : sysPath) {
        if (FileManager.pathEquals(fpPath, fFolder.getAbsolutePath())) {
          return n;
        }
        n++;
      }
      return -1;
    }
  }

  public void showSysPath() { //TODO
    synchronized (sysPath) {
      if (Debug.isVerbose()) {
        getSysPath();
        log(lvl, "***** sys.path");
        for (int i = 0; i < sysPath.size(); i++) {
          if (sysPath.get(i).startsWith("__")) {
            continue;
          }
          Debug.print(String.format("%2d: %s", i, sysPath.get(i)));
        }
      }
    }
  }

  public void addSitePackages() {
    synchronized (sysPath) {
      File fSitePackages = getSitePackages();
      if (fSitePackages.exists()) {
        addSysPath(fSitePackages);
        if (hasSysPath(fSitePackages.getAbsolutePath())) {
          log(lvl, "added as Jython::sys.path[0]:\n%s", fSitePackages);
        }
        File fSites = new File(fSitePackages, "sites.txt");
        String sSites = "";
        if (fSites.exists()) {
          sSites = FileManager.readFileToString(fSites);
          if (!sSites.isEmpty()) {
            log(lvl, "found Lib/site-packages/sites.txt");
            String[] listSites = sSites.split("\n");
            for (String site : listSites) {
              String path = site.trim();
              if (path.startsWith("#")) {
                continue;
              }
              if (!path.isEmpty()) {
                addSysPath(path); // addSitePackages()
                log(lvl, "added as Jython::sys.path[0] from Lib/site-packages/sites.txt:\n%s", path);
              }
            }
          }
        }
      }
      String pythonPath = System.getProperty("python.path");
      if (pythonPath != null) {
        log(lvl, "given python.path: %s", pythonPath);
        addPythonPath(pythonPath);
      }
      pythonPath = System.getenv("PYTHONPATH");
      if (pythonPath != null) {
        log(lvl, "given python.path: %s", pythonPath);
        addPythonPath(pythonPath);
      }
      String fpBundle = ImagePath.getBundlePath();
      if (fpBundle != null) {
        addSysPath(fpBundle); // addSitePackages()
      }
    }
  }

  private void addPythonPath(String path) {
    final String[] pathItems = path.split(File.pathSeparator);
    for (String pythonPath : pathItems) {
      File fpp = new File(pythonPath);
      if (!fpp.isAbsolute()) {
        fpp = new File(Commons.getWorkDir(), pythonPath);
      }
      if (fpp.exists()) {
        try {
          fpp = fpp.getCanonicalFile();
        } catch (IOException e) {
          fpp = null;
        }
      }
      if (fpp != null) {
        addSysPath(fpp);
        log(lvl, "added python.path: %s", fpp);
      }
    }
  }

  public void resetSysPath(List<String> importPlaces) {
    synchronized (sysPath) {
        sysPath.clear();
        for (String path : importPlaces) {
          sysPath.add(path);
        }
        setSysPath();
    }
  }
  //</editor-fold>

  //<editor-fold desc="12 import handling">
  public void reloadImported() {
    if (lastRun > 0) {
      for (File fMod : importedScripts) {
        name = getPyName(fMod);
        if (new File(fMod, name + ".py").lastModified() > lastRun) {
          log(lvl, "reload: %s", fMod);
          interpreterExecString("reload(" + name + ")");
        }
        ;
      }
    }
    lastRun = new Date().getTime();
  }

  private String getPyName(File fMod) {
    String ending = ".sikuli";
    String name = fMod.getName();
    if (name.endsWith(ending)) {
      name = name.substring(0, name.length() - ending.length());
    }
    return name;
  }

  public String findModule(String modName, Object packPath, Object sysPath) {
    if (modName.endsWith(".*")) {
      log(lvl + 1, "findModule: %s", modName);
      return null;
    }
    if (packPath != null) {
      log(lvl + 1, "findModule: in pack: %s (%s)", modName, packPath);
      return null;
    }
    int nDot = modName.lastIndexOf(".");
    String modNameFull = modName;
    if (nDot > -1) {
      modName = modName.substring(nDot + 1);
    }
    File fModule = null;
    if (ImagePath.getBundlePath() != null) {
      File fParentBundle = new File(ImagePath.getBundlePath()).getParentFile();
      fModule = existsModule(modName, fParentBundle);
    }
    if (fModule == null) {
      fModule = existsSysPathModule(modName);
      if (fModule == null) {
        return null;
      }
    }
    log(lvl + 1, "findModule: final: %s [%s]", fModule.getName(), fModule.getParent());
    if (fModule.getName().endsWith(".sikuli")) {
      importedScripts.add(fModule);
      return fModule.getAbsolutePath();
    }
    return null;
  }

  public String loadModulePrepare(String modName, String modPath) {
    log(lvl, "loadModulePrepare: %s in %s", modName, modPath);
    int nDot = modName.lastIndexOf(".");
    if (nDot > -1) {
      modName = modName.substring(nDot + 1);
    }
    addSysPath(modPath); // loadModulePrepare(String modName, String modPath)
    if (modPath.endsWith(".sikuli")) {
      ImagePath.appendFromImport(modPath);
    }
    return modName;
  }

  private File existsModule(String mName, File fFolder) {
    if (mName.endsWith(".sikuli") || mName.endsWith(".py")) {
      return null;
    }
    File fSikuli = new File(fFolder, mName + ".sikuli");
    if (fSikuli.exists()) {
      return fSikuli;
    }
    File fPython = new File(fFolder, mName + ".py");
    if (fPython.exists()) {
      return fPython;
    }
    return null;
  }

  public File existsSysPathModule(String modname) {
    synchronized (sysPath) {
      getSysPath();
      File fModule = null;
      for (String fpPath : sysPath) {
        fModule = existsModule(modname, new File(fpPath));
        if (null != fModule) {
          break;
        }
      }
      return fModule;
    }
  }

  public File existsSysPathJar(String fpJar) {
    synchronized (sysPath) {
      getSysPath();
      File fJar = null;
      for (String fpPath : sysPath) {
        fJar = new File(fpPath, fpJar);
        if (fJar.exists()) {
          break;
        }
        fJar = null;
      }
      return fJar;
    }
  }
  //</editor-fold>

  //<editor-fold desc="15 sys.argv handling">
  public void interpreterFillSysArgv(File pyFile, String[] argv) {
    List<String> jyargv = new ArrayList<>();
    jyargv.add(pyFile.getAbsolutePath());
    if (argv != null) {
      jyargv.addAll(Arrays.asList(argv));
    }
    setSysArgv(jyargv);
  }

  List<String> sysArgv = new ArrayList<String>();

  public List<String> getSysArgv() {
    sysArgv = new ArrayList<String>();
    if (null == interpreter) {
      sysArgv = null;
      return null;
    }
    try {
      PyList pyArgv = interpreter.getSystemState().argv;
      Integer argvLen = pyArgv.__len__();
      for (int i = 0; i < argvLen; i++) {
        String entry = (String) pyArgv.get(i);
        log(lvl + 1, "sys.path[%2d] = %s", i, entry);
        sysArgv.add(entry);
      }
    } catch (Exception ex) {
      sysArgv = null;
    }
    return sysArgv;
  }

  public void setSysArgv(List<String> args) {
    if (null == interpreter) {
      return;
    }
    try {
      PyList pyArgv = interpreter.getSystemState().argv;
      pyArgv.clear();
      for (String arg : args) {
        pyArgv.add(arg);
      }
    } catch (Exception ex) {
      sysArgv = null;
    }
  }
  //</editor-fold>

  //<editor-fold desc="18 RobotFramework support">
  public boolean prepareRobot() {
    if (Commons.isRunningFromJar()) {
      final File libFolder = Commons.getLibFolder();
      File fLibRobot = new File(libFolder, "robot");
      if (!fLibRobot.exists()) {
        log(-1, "prepareRobot: not available: %s", fLibRobot);
        return false;
      }
      if (!hasSysPath(libFolder.getAbsolutePath())) {
        insertSysPath(libFolder);
      }
    }
    if (!hasSysPath(new File(Settings.BundlePath).getParent())) {
      appendSysPath(new File(Settings.BundlePath).getParent());
    }
    interpreterExecString("import robot");
    return true;
  }
  //</editor-fold>

  //<editor-fold desc="20 error/exception handling">
  public String getCurrentLine() {
    String trace = "";
    Object frame = null;
    Object back = null;
    try {
      Method mGetFrame = Class.forName("org.python.core.Py").getMethod("getFrame", new Class[0]);
      Class cPyFrame = Class.forName("org.python.core.PyFrame");
      Field fLineno = cPyFrame.getField("f_lineno");
      Field fCode = cPyFrame.getField("f_code");
      Field fBack = cPyFrame.getField("f_back");
      Class cPyBaseCode = Class.forName("org.python.core.PyBaseCode");
      Field fFilename = cPyBaseCode.getField("co_filename");
      frame = mGetFrame.invoke(Class.forName("org.python.core.Py"), (Object[]) null);
      back = fBack.get(frame);
      if (null == back) {
        trace = "Jython: at " + getCurrentLineTraceElement(fLineno, fCode, fFilename, frame);
      } else {
        trace = "Jython traceback - current first:\n"
            + getCurrentLineTraceElement(fLineno, fCode, fFilename, frame);
        while (null != back) {
          String line = getCurrentLineTraceElement(fLineno, fCode, fFilename, back);
          if (!line.startsWith("Region (")) {
            trace += "\n" + line;
          }
          back = fBack.get(back);
        }
      }
    } catch (Exception ex) {
      trace = String.format("Jython: getCurrentLine: INSPECT EXCEPTION: %s", ex);
    }
    return trace;
  }

  private String getCurrentLineTraceElement(Field fLineno, Field fCode, Field fFilename, Object frame) {
    String trace = "";
    try {
      int lineno = fLineno.getInt(frame);
      Object code = fCode.get(frame);
      Object filename = fFilename.get(code);
      String fname = FileManager.getName((String) filename);
      fname = fname.replace(".py", "");
      trace = String.format("%s (%d)", fname, lineno);
    } catch (Exception ex) {
    }
    return trace;
  }

  private int errorLine;
  private int errorColumn;
  private String errorCause;
  private String errorText;
  private int errorType;
  private String errorTrace;

  private static final int PY_SYNTAX = 0;
  private static final int PY_RUNTIME = 1;
  private static final int PY_JAVA = 2;
  private static final int SX_RUNTIME = 3;
  private static final int SX_ABORT = 4;
  private static final int PY_UNKNOWN = -1;

  private static String NL = "\n";

  public int findErrorSource(Throwable throwable, String filename) {
    log(lvl + 1, "Run Script Error: %s", throwable);
    String err = throwable.toString().replaceAll("\\r", "");
//    try {
//      err = thr.toString();
//    } catch (Exception ex) {
//      errorType = PY_JAVA;
//      err = thr.getCause().toString();
//    }
    Class errorClass = throwable.getClass();
    Class pySyntaxError = null;
    try {
      pySyntaxError = Class.forName("org.python.core.PySyntaxError");
    } catch (ClassNotFoundException e) {
    }

    if (errorClass.equals(org.python.core.PyException.class)) {
      errorType = PY_RUNTIME;
    } else if (errorClass.equals(pySyntaxError)) {
      errorType = PY_SYNTAX;
    } else {
      errorType = PY_JAVA;
    }

//    log(-1,"------------- Traceback -------------\n" + err +
//            "------------- Traceback -------------\n");
    errorLine = -1;
    errorColumn = -1;
    errorCause = "--UnKnown--";
    errorText = "--UnKnown--";


    //  File ".../mainpy.sikuli/mainpy.py", line 25, in <module> NL func() NL
    //  File ".../subpy.py", line 4, in func NL 1/0 NL
    Pattern pFile = Pattern.compile("File..(.*?\\.py).*?" + ",.*?line.*?(\\d+),.*?in(.*?)" + NL + "(.*?)" + NL);

    String msg;
    Matcher mFile = null;

    if (PY_JAVA != errorType) {
      if (PY_RUNTIME == errorType) {
        Pattern pError = Pattern.compile(NL + "(.*?):.(.*)$");
        mFile = pFile.matcher(err);
        if (mFile.find()) {
          log(lvl + 2, "Runtime error line: " + mFile.group(2) + "\n in function: " + mFile.group(3) + "\n statement: "
              + mFile.group(4));
          errorLine = Integer.parseInt(mFile.group(2));
          Matcher mError = pError.matcher(err);
          if (mError.find()) {
            log(lvl + 2, "Error:" + mError.group(1));
            log(lvl + 2, "Error:" + mError.group(2));
            errorCause = mError.group(1);
            errorText = mError.group(2);
          } else {
//org.sikuli.core.FindFailed: FindFailed: can not find 1352647716171.png on the screen
            Pattern pFF = Pattern.compile(": FindFailed: (.*?)" + NL);
            Matcher mFF = pFF.matcher(err);
            if (mFF.find()) {
              errorCause = "FindFailed";
              errorText = mFF.group(1);
            } else {
              errorType = PY_UNKNOWN;
            }
          }
        }
      } else if (errorType == PY_SYNTAX) {
        Pattern pLineS = Pattern.compile(", (\\d+), (\\d+),");
        java.util.regex.Matcher mLine = pLineS.matcher(err);
        if (mLine.find()) {
          log(lvl + 2, "SyntaxError error line: " + mLine.group(1));
          Pattern pText = Pattern.compile("\\((.*?)\\(");
          java.util.regex.Matcher mText = pText.matcher(err);
          mText.find();
          errorText = mText.group(1) == null ? errorText : mText.group(1);
          log(lvl + 2, "SyntaxError: " + errorText);
          errorLine = Integer.parseInt(mLine.group(1));
          errorColumn = Integer.parseInt(mLine.group(2));
          errorCause = "SyntaxError";
        }
      }
    }

    msg = String.format("script [ %s ]", new File(filename).getName().replace(".py", ""));

    if (errorLine != -1) {
      // log(-1,_I("msgErrorLine", srcLine));
      msg += " stopped with error in line " + errorLine;
      if (errorColumn != -1) {
        msg += " at column " + errorColumn;
      }
    } else {
      msg += " stopped with error at line --unknown--";
    }

    if (errorType == PY_RUNTIME || errorType == PY_SYNTAX) {
      if (errorText.startsWith(errorCause)) {
        if (errorText.startsWith("java.lang.RuntimeException: SikuliX: ")) {
          errorText = errorText.replace("java.lang.RuntimeException: SikuliX: ", "sikulix.RuntimeException: ");
          errorType = SX_RUNTIME;
        } else if (errorText.startsWith("java.lang.ThreadDeath")) {
          errorType = SX_ABORT;
        }
      }
      if (errorType != SX_ABORT) {
        Debug.error(msg);
        Debug.error(errorCause + " ( " + errorText + " )");
      }
      if (errorType == PY_RUNTIME) {
        mFile = pFile.matcher(err);
        errorTrace = findErrorSourceWalkTrace(mFile, filename);
        if (errorTrace.length() > 0) {
          Debug.error("--- Traceback --- error source first\n" + "line: module ( function ) statement \n" + errorTrace
              + "[error] --- Traceback --- end --------------");
        }
      }
    } else {
      Debug.error(msg);
      Debug.error("Error caused by: %s", err);
    }
    return errorLine;
  }

  private String findErrorSourceWalkTrace(Matcher m, String filename) {
    Pattern pModule;
    if (Commons.runningWindows()) {
      pModule = Pattern.compile(".*\\\\(.*?)\\.py");
    } else {
      pModule = Pattern.compile(".*/(.*?)\\.py");
    }
    String mod;
    String modIgnore = "SikuliImporter,Region,";
    StringBuilder trace = new StringBuilder();
    String telem;
    while (m.find()) {
      if (m.group(1).equals(filename)) {
        mod = "main";
      } else {
        Matcher mModule = pModule.matcher(m.group(1));
        mModule.find();
        mod = mModule.group(1);
        if (modIgnore.contains(mod + ",")) {
          continue;
        }
      }
      telem = m.group(2) + ": " + mod + " ( " + m.group(3) + " ) " + m.group(4) + NL;
      trace.insert(0, telem);
    }
    return trace.toString();
  }

  private void findErrorSourceFromJavaStackTrace(Throwable thr, String filename) {
    log(-1, "findErrorSourceFromJavaStackTrace: seems to be an error in the Java API supporting code");
    StackTraceElement[] s;
    Throwable t = thr;
    while (t != null) {
      s = t.getStackTrace();
      log(lvl + 2, "stack trace:");
      for (int i = s.length - 1; i >= 0; i--) {
        StackTraceElement si = s[i];
        log(lvl + 2, si.getLineNumber() + " " + si.getFileName());
        if (si.getLineNumber() >= 0 && filename.equals(si.getFileName())) {
          errorLine = si.getLineNumber();
        }
      }
      t = t.getCause();
      log(lvl + 2, "cause: " + t);
    }
  }
  //</editor-fold>

  //<editor-fold desc="30 TODO: check/revise: exec/load/runJar">
  public int runJar(String fpJarOrFolder) {
    return runJar(fpJarOrFolder, "");
  }

  public int runJar(String fpJarOrFolder, String imagePath) {
    //SikulixForJython.get();
    String fpJar = load(fpJarOrFolder, true);
    ImagePath.addJar(fpJar, imagePath);
    String scriptName = new File(fpJar).getName().replace("_sikuli.jar", "");
    if (interpreterExecString("try: reload(" + scriptName + ")\nexcept: import " + scriptName)) {
      return 0;
    } else {
      return -1;
    }
  }

  public String load(String fpJarOrFolder) {
    return load(fpJarOrFolder, false);
  }

  public String load(String fpJar, boolean scriptOnly) {
    log(lvl, "load: %s", fpJar);
    if (!fpJar.endsWith(".jar")) {
      fpJar += ".jar";
    }
    File fJar = new File(FileManager.normalizeAbsolute(fpJar));
    if (!fJar.exists()) {
      String fpBundle = ImagePath.getBundlePath();
      fJar = null;
      if (null != fpBundle) {
        fJar = new File(fpBundle, fpJar);
        if (!fJar.exists()) { // in bundle
          fJar = new File(new File(fpBundle).getParentFile(), fpJar);
          if (!fJar.exists()) { // in bundle parent
            fJar = null;
          }
        }

      }
      if (fJar == null) {
        fJar = new File(Commons.getExtensionsFolder(), fpJar);
        if (!fJar.exists()) { // in extensions
          fJar = new File(Commons.getLibFolder(), fpJar);
          if (!fJar.exists()) { // in Lib folder
            fJar = null;
          }
        }
      }
    }
    if (fJar == null) {
      log(-1, "load: not found: %s", fJar);
      return fpJar;
    } else {
      if (!hasSysPath(fJar.getPath())) {
        insertSysPath(fJar);
      }
      return fJar.getAbsolutePath();
    }
  }
  //</editor-fold>

  //<editor-fold desc="40 callback">
  static Class cPyString = null;

  class PyString {

    String aString = "";
    Object pyString = null;

    public PyString(String s) {
      aString = s;
      try {
        pyString = cPyString.getConstructor(String.class).newInstance(aString);
      } catch (Exception ex) {
      }
    }

    public Object get() {
      return pyString;
    }
  }

  //TODO check signature (instance method)
  public boolean checkCallback(Object[] args) {
    SXPyInstance inst = new SXPyInstance(args[0]);
    String mName = (String) args[1];
    Object method = inst.__getattr__(mName);
    if (method == null || !method.getClass().getName().contains("PyMethod")) {
      log(-100, "checkCallback: Object: %s, Method not found: %s", inst, mName);
      return false;
    }
    return true;
  }

  public boolean runLoggerCallback(Object[] args) {
    SXPyInstance inst = new SXPyInstance(args[0]);
    String mName = (String) args[1];
    String msg = (String) args[2];
    Object method = inst.__getattr__(mName);
    if (method == null || !method.getClass().getName().contains("PyMethod")) {
      log(-100, "runLoggerCallback: Object: %s, Method not found: %s", inst, mName);
      return false;
    }
    try {
      PyString pmsg = new PyString(msg);
      inst.invoke(mName, pmsg.get());
    } catch (Exception ex) {
      log(-100, "runLoggerCallback: invoke: %s", ex.getMessage());
      return false;
    }
    return true;
  }

  @Override
  public boolean runObserveCallback(Object[] args) {
    SXPyFunction func = new SXPyFunction(args[0]);
    boolean success = true;
    try {
      func.__call__(new SXPy().java2py(args[1]));
    } catch (Exception ex) {
//      if (!"<lambda>".equals(func.__name__)) {
      if (!func.toString().contains("<lambda>")) {
        log(-1, "runObserveCallback: jython invoke: %s", ex.getMessage());
        return false;
      }
      success = false;
    }
    if (success) {
      return true;
    }
    try {
      func.__call__();
    } catch (Exception ex) {
      log(-1, "runObserveCallback: jython invoke <lambda>: %s", ex.getMessage());
      return false;
    }
    return true;
  }

  //TODO implement generalized callback
  public boolean runCallback(Object[] args) {
    SXPyInstance inst = (SXPyInstance) args[0];
    String mName = (String) args[1];
    Object method = inst.__getattr__(mName);
    if (method == null || !method.getClass().getName().contains("PyMethod")) {
      log(-1, "runCallback: Object: %s, Method not found: %s", inst, mName);
      return false;
    }
    try {
      PyString pmsg = new PyString("not yet supported");
      inst.invoke(mName, pmsg.get());
    } catch (Exception ex) {
      log(-1, "runCallback: invoke: %s", ex.getMessage());
      return false;
    }
    return true;
  }
  //</editor-fold>

  /**
   * the foo.py files in the given source folder are compiled to JVM-ByteCode-classfiles foo$py.class
   * and stored in the target folder (thus securing your code against changes).<br>
   * A folder structure is preserved. All files not ending as .py will be copied also.
   * The target folder might then be packed to a jar using buildJarFromFolder.<br>
   * Be aware: you will get no feedback about any compile problems,
   * so make sure your code compiles error free. Currently there is no support for running such a jar,
   * it can only be used with load()/import, but you might provide a simple script that does load()/import
   * and then runs something based on available functions in the jar code.
   *
   * @param fpSource absolute path to a folder/folder-tree containing the stuff to be copied/compiled
   * @param fpTarget the folder that will contain the copied/compiled stuff (folder is first deleted)
   * @return false if anything goes wrong, true means should have worked
   */
  public static boolean compileJythonFolder(String fpSource, String fpTarget) {
    JythonSupport jython = JythonSupport.get();
    if (jython != null) {
      File fTarget = new File(fpTarget);
      FileManager.deleteFileOrFolder(fTarget);
      fTarget.mkdirs();
      if (!fTarget.exists()) {
        Debug.log(-1, "compileJythonFolder: target folder not available\n%", fTarget);
        return false;
      }
      File fSource = new File(fpSource);
      if (!fSource.exists()) {
        Debug.log(-1, "compileJythonFolder: source folder not available\n", fSource);
        return false;
      }
      if (fTarget.equals(fSource)) {
        Debug.log(-1, "compileJythonFolder: target folder cannot be the same as the source folder");
        return false;
      }
      FileManager.xcopy(fSource, fTarget);
      if (!jython.interpreterExecString("import compileall")) {
        return false;
      }
      jython = doCompileJythonFolder(jython, fTarget);
      FileManager.traverseFolder(fTarget, new CompileJythonFilter(jython));
    }
    return false;
  }

  private static class CompileJythonFilter extends FileManager.FileFilter {

    JythonSupport jython = null;

    CompileJythonFilter(JythonSupport jython) {
      this.jython = jython;
    }

    public boolean accept(File entry) {
      if (jython != null && entry.isDirectory()) {
        jython = doCompileJythonFolder(jython, entry);
      }
      return false;
    }
  }

  private static JythonSupport doCompileJythonFolder(JythonSupport jython, File fSource) {
    if (!jython.interpreterExecString(String.format("compileall.compile_dir(\"%s\","
        + "maxlevels = 0, quiet = 1)", fSource))) {
      return null;
    }
    for (File aFile : fSource.listFiles()) {
      if (aFile.getName().endsWith(".py")) {
        aFile.delete();
      }
    }
    return jython;
  }

}
