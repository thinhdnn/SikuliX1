/*
 * Copyright (c) 2010-2022, sikuli.org, sikulix.com - MIT license
 */
package org.sikuli.ide;

import org.sikuli.basics.*;
import org.sikuli.idesupport.ExtensionManager;
import org.sikuli.idesupport.IDEDesktopSupport;
import org.sikuli.idesupport.IDESupport;
import org.sikuli.script.Image;
import org.sikuli.script.Sikulix;
import org.sikuli.script.*;
import org.sikuli.script.runnerSupport.IScriptRunner;
import org.sikuli.script.runnerSupport.JythonSupport;
import org.sikuli.script.runnerSupport.Runner;
import org.sikuli.script.runners.JythonRunner;
import org.sikuli.script.support.Commons;
import org.sikuli.script.support.IScreen;
import org.sikuli.script.support.PreferencesUser;
import org.sikuli.script.support.RunTime;
import org.sikuli.script.support.devices.Device;
import org.sikuli.script.support.devices.ScreenDevice;
import org.sikuli.script.support.gui.SXDialog;
import org.sikuli.util.EventObserver;
import org.sikuli.util.EventSubject;
import org.sikuli.util.OverlayCapturePrompt;
import org.sikuli.util.SikulixFileChooser;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SikulixIDE extends JFrame {

  //<editor-fold desc="00 startup / quit">
  private static String me = "IDE: ";
  private static int lvl = 3;

  private static void log(int level, String message, Object... args) {
    Debug.logx(level, me + message, args);
  }

  static SXDialog ideSplash = null;

  protected static void setIDESplash(SXDialog splash) {
    ideSplash = splash;
  }

  private SikulixIDE() {
  }

  private static final SikulixIDE sikulixIDE = new SikulixIDE();

  static PreferencesUser prefs;

  public static void setIdeWindowState(boolean state) {
    ideWindowState = state;
  }

  static Boolean ideWindowState = null;
  static Rectangle ideWindowRect = null;

  public static Rectangle getWindowRect() {
    if (ideWindowState == null) { //first use - restore from prefs
      prefs = PreferencesUser.get();
      Dimension windowSize = prefs.getIdeSize();
      Point windowLocation = prefs.getIdeLocation();
      Rectangle rectWindow = new Rectangle(windowLocation, windowSize);
      int monitor = ScreenDevice.whichMonitor(rectWindow);
      Rectangle scrRect = ScreenDevice.get(monitor).asRectangle();
      if (monitor < 0) {
        log(3, "IDE window reset to primary monitor - was (%d,%d %dx%d)",
            rectWindow.x, rectWindow.y, rectWindow.width, rectWindow.height);
        rectWindow.x = 30;
        rectWindow.y = 30;
        rectWindow = scrRect.intersection(rectWindow);
      }
      setIdeWindowState(false);
      ideWindowRect = new Rectangle(rectWindow);
      return ideWindowRect;
    } else if (!ideWindowState) { // before visible first time
      return ideWindowRect;
    }
    return new Rectangle(get().getLocation(), get().getSize()); // after visible first time
  }

  public static Point getWindowCenter() {
    return new Point((int) getWindowRect().getCenterX(), (int) getWindowRect().getCenterY());
  }

  public static Point getWindowTop() {
    Rectangle rect = getWindowRect();
    int x = rect.x + rect.width / 2;
    int y = rect.y + 30;
    return new Point(x, y);
  }

  protected static void start(String[] args) {

    //ideWindowRect = getWindowRect();
    Commons.setSXIDE(SikulixIDE.get());

    IDEDesktopSupport.initGUI();

    IDESupport.init();

    sikulixIDE.startGUI();

    while (!areRunnersReady()) {
      Commons.pause(0.3);
    }

    Debug.log(3, "IDE ready: on Java %d", Commons.getJavaVersion());
    if (Debug.getDebugLevel() < 3) {
      Debug.reset();
    }

    showAfterStart();
  }

  public boolean quit() {
    terminate();
    if (getCurrentCodePane() == null) {
      return true;
    } else {
      return false;
    }
  }

  private boolean closeIDE() {
    if (!doBeforeQuit()) {
      return false;
    }
    while (true) {
      EditorPane codePane = sikulixIDE.getCurrentCodePane();
      if (codePane == null) {
        break;
      }
      if (!removeCurrentTab()) {
        return false;
      }
    }
    return true;
  }

  private boolean doBeforeQuit() {
    if (checkDirtyPanes()) {
      int action = askForSaveAll("Quit");
      if (action < 0) {
        return false;
      }
      return saveSession(action, true);
    }
    return saveSession(DO_NOT_SAVE, true);
  }

  private int askForSaveAll(String typ) {
//TODO I18N
    String warn = "Some scripts are not saved yet!";
    String title = SikuliIDEI18N._I("dlgAskCloseTab");
    String[] options;
    int ret = -1;
    options = new String[3];
    options[WARNING_DO_NOTHING] = typ + " immediately";
    options[WARNING_ACCEPTED] = "Save all and " + typ;
    options[WARNING_CANCEL] = SikuliIDEI18N._I("cancel");
    ret = JOptionPane.showOptionDialog(ideWindow, warn, title, 0, JOptionPane.WARNING_MESSAGE,
        null, options, options[options.length - 1]);
    if (ret == WARNING_CANCEL || ret == JOptionPane.CLOSED_OPTION) {
      return -1;
    }
    return ret;
  }
  //</editor-fold>

  //<editor-fold desc="01 IDE instance">
  public static SikulixIDE get() {
    if (sikulixIDE == null) {
      throw new SikuliXception("SikulixIDE:get(): instance should not be null");
    }
    return sikulixIDE;
  }

  static JFrame ideWindow = null;

  public static void setWindow() {
    if (ideWindow == null) {
      ideWindow = get();
    }
  }

  public void setIDETitle(String title) {
    ideWindow.setTitle(Commons.getSXVersionIDE() + " - " + title);
  }

  public static void doShow() {
    showAgain();
  }

  public static boolean notHidden() {
    return ideWindow.isVisible();
  }

  public static void doHide() {
    ideWindow.setVisible(false);
    RunTime.pause(0.5f);
  }

  static void showAgain() {
    EditorPane codePane = sikulixIDE.getCurrentCodePane();
    if (codePane == null) {
      sikulixIDE.newTabEmpty();
      codePane = sikulixIDE.getCurrentCodePane();
    }
    ideWindow.setVisible(true);
    codePane.requestFocusInWindow();
  }

  //TODO showAfterStart to be revised - special case: running as server
  public static void showAfterStart() {
    if (ideWindow != null) {
      org.sikuli.ide.Sikulix.stopSplash();
      ideWindow.setVisible(true);
      ideWindow.setLocation(getWindowRect().getLocation());
      setIdeWindowState(true);
      get().mainPane.setDividerLocation(0.6);
      try {
        EditorPane editorPane = get().getCurrentCodePane();
        if (editorPane.isText()) {
          get().collapseMessageArea();
        }
        editorPane.requestFocusInWindow();
      } catch (Exception e) {
      }
      if (!Debug.isVerbose()) {
      }
      Commons.show();
      Debug.isIDEstarting(false);
      Debug.getIdeStartLog();
      if (Commons.isSandBox()) {
        System.out.println("[INFO] IDE: experimental: running in sandbox mode " +
            "\n[INFO] IDE: --- might have bugs --- use with care");
      }
      get()._inited = true;
    }
  }

  static String _I(String key, Object... args) {
    try {
      return SikuliIDEI18N._I(key, args);
    } catch (Exception e) {
      log(4, "[I18N] missing: " + key); //TODO
      return key;
    }
  }
  //</editor-fold>

  //<editor-fold desc="02 init IDE">
  private static AtomicInteger runnerReadyNum = new AtomicInteger(0);

  public static void resetRunnersReady(int countRunners) {
    runnerReadyNum.set(countRunners);
  }

  public static void setRunnerIsReady() {
    runnerReadyNum.decrementAndGet();
  }

  public static boolean areRunnersReady() {
    return 0 == runnerReadyNum.get();
  }

  private void startGUI() {
    log(3, "starting GUI");
    setWindow();

    if (installCaptureHotkey()) {
      log(3, "Capture HotKey installed");
    } else {
      Debug.error("IDE: Capture HotKey not installed: %s", "PROBLEM?"); //TODO
    }
    if (installStopHotkey()) {
      log(3, "Stop HotKey installed");
    } else {
      Debug.error("IDE: Stop HotKey not installed: %s", "PROBLEM?"); //TODO
    }

    Rectangle windowRect = getWindowRect();
    ideWindow.setSize(windowRect.getSize());
    ideWindow.setLocation(windowRect.getLocation());

    Debug.log(4, "Adding components to window");
    initMenuBars(ideWindow);
    final Container ideContainer = ideWindow.getContentPane();
    ideContainer.setLayout(new BorderLayout());
    Debug.log(4, "creating tabbed editor");
    initTabs();
    Debug.log(4, "creating message area");
    initMessageArea();
    Debug.log(4, "creating combined work window");
    JPanel codePane = new JPanel(new BorderLayout(10, 10));
    codePane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
    codePane.add(tabs, BorderLayout.CENTER);
    if (prefs.getPrefMoreMessage() == PreferencesUser.VERTICAL) {
      mainPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, codePane, messageArea);
    } else {
      mainPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codePane, messageArea);
    }
    mainPane.setResizeWeight(0.6);
    mainPane.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

    Debug.log(4, "Putting all together");
    JPanel editPane = new JPanel(new BorderLayout(0, 0));

    editPane.add(mainPane, BorderLayout.CENTER);
    ideContainer.add(editPane, BorderLayout.CENTER);
    Debug.log(4, "Putting all together - after main pane");

    JToolBar tb = initToolbar();
    ideContainer.add(tb, BorderLayout.NORTH);
    Debug.log(4, "Putting all together - after toolbar");

    ideContainer.add(initStatusbar(), BorderLayout.SOUTH);
    Debug.log(4, "Putting all together - before layout");
    ideContainer.doLayout();
    ideWindow.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

    Debug.log(4, "Putting all together - after layout");
    initShortcutKeys();
    initWindowListener();
    initTooltip();

    //Commons.addlog("Putting all together - Check for Updates");
    //TODO autoCheckUpdate();

    //waitPause();
    Debug.log(4, "Putting all together - Restore last Session");
    restoreSession(0);
    if (tabs.getTabCount() == 0) {
      newTabEmpty();
    }
    tabs.setSelectedIndex(0);
  }


  private JSplitPane mainPane;
  private boolean _inited = false;

  private void initTabs() {
    tabs = new CloseableTabbedPane();
    tabs.setUI(new AquaCloseableTabbedPaneUI());
    tabs.addCloseableTabbedPaneListener(new CloseableTabbedPaneListener() {
      @Override
      public boolean closeTab(int tabIndexToClose) {
        EditorPane editorPane;
        try {
          editorPane = getPaneAtIndex(tabIndexToClose);
          tabs.setLastClosed(editorPane.getSourceReference());
          boolean ret = editorPane.close();
          return ret;
        } catch (Exception e) {
          log(-1, "Problem closing tab %d\nError: %s", tabIndexToClose, e.getMessage());
          return false;
        }
      }
    });
    tabs.addChangeListener(new ChangeListener() {
      @Override
      public void stateChanged(javax.swing.event.ChangeEvent e) {
        EditorPane editorPane;
        JTabbedPane tab = (JTabbedPane) e.getSource();
        int i = tab.getSelectedIndex();
        if (i >= 0) {
          editorPane = getPaneAtIndex(i);
          if (!editorPane.hasEditingFile()) {
            return;
          }
          if (editorPane.isTemp()) {
            setIDETitle(tab.getTitleAt(i));
          } else {
            if (editorPane.isBundle()) {
              setIDETitle(editorPane.getFolderPath());
            } else {
              setIDETitle(editorPane.getFilePath());
            }
          }
          editorPane.setBundleFolder();
          if (null != editorPane.editorPaneRunner && !editorPane.isEmpty()) {
            editorPane.editorPaneRunner.adjustImportPath(editorPane.getFiles(), null);
          }
          editorPane.setCaretPosition(editorPane.getCaret().getDot());
          if (editorPane.isText()) {
            collapseMessageArea();
          } else {
            uncollapseMessageArea();
          }
          chkShowThumbs.setState(getCurrentCodePane().showThumbs);
          getStatusbar().setType(getCurrentCodePane().getType());
        }
        updateUndoRedoStates();
      }
    });
  }

  CloseableTabbedPane getTabs() {
    return tabs;
  }

  private CloseableTabbedPane tabs;
  //</editor-fold>

  //<editor-fold desc="03 init for Mac">
  private static void prepareMacUI() {
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="04 save / restore session">
  final static int WARNING_CANCEL = 2;
  final static int WARNING_ACCEPTED = 1;
  final static int WARNING_DO_NOTHING = 0;
  final static int DO_SAVE_ALL = 3;
  final static int DO_NOT_SAVE = 0;
  private static String[] loadScripts = null;

  private boolean saveSession(int action, boolean quitting) {
    int nTab = tabs.getTabCount();
    int selectedTab = tabs.getSelectedIndex();
    StringBuilder sbuf = new StringBuilder();
    EditorPane editorPane = null;
    boolean success = true;
    for (int tabIndex = 0; tabIndex < nTab; tabIndex++) {
      try {
        editorPane = getPaneAtIndex(tabIndex);
        String fileName = editorPane.editorPaneFileSelected;
        if (action == DO_NOT_SAVE) {
          if (quitting) {
            editorPane.setDirty(false);
          }
          if (editorPane.isTemp()) {
            continue;
          }
        } else if (editorPane.isDirty()) {
          if (!saveQietly(tabIndex)) {
            if (quitting) {
              editorPane.setDirty(false);
            }
            continue;
          }
        }
        if (action == DO_SAVE_ALL) {
          continue;
        }
        if (editorPane.getType().equals(TYPE_SIKULIX_SPECIAL)) {
          continue;
        }
        if (sbuf.length() > 0) {
          sbuf.append(";");
        }
        sbuf.append(fileName);
      } catch (Exception e) {
        log(-1, "Save all: %s: %s", editorPane.saveAndGetCurrentFile(), e.getMessage());
        success = false;
        break;
      }
    }
    if (success) {
      PreferencesUser.get().setIdeSession(sbuf.toString());
    }
    tabs.setSelectedIndex(selectedTab);
    return success;
  }

  boolean saveQietly(int tabIndex) {
    int currentTab = tabs.getSelectedIndex();
    tabs.setSelectedIndex(tabIndex);
    boolean retval = true;
    EditorPane codePane = getPaneAtIndex(tabIndex);
    String fname = null;
    try {
      fname = codePane.saveTabContent();
      if (fname != null) {
        if (!codePane.isBundle()) {
          fname = codePane.getFilePath();
          codePane.showType();
        }
        setFileTabTitle(fname, tabIndex);
      } else {
        retval = false;
      }
    } catch (Exception ex) {
      log(-1, "Problem when trying to save %s\nError: %s",
          fname, ex.getMessage());
      retval = false;
    }
    tabs.setSelectedIndex(currentTab);
    return retval;
  }

  private int restoreSession(int tabIndex) {
    String session_str = prefs.getIdeSession();
    int filesLoaded = 0;
    List<File> filesToLoad = new ArrayList<File>();
    if (Commons.getFilesToLoad().size() > 0) {
      for (File f : Commons.getFilesToLoad()) {
        filesToLoad.add(f);
        if (restoreScriptFromSession(f)) filesLoaded++;
      }
    }
    if (session_str != null && !session_str.isEmpty()) {
      String[] filenames = session_str.split(";");
      if (filenames.length > 0) {
        log(3, "Restore scripts from last session");
        for (int i = 0; i < filenames.length; i++) {
          if (filenames[i].isEmpty()) {
            continue;
          }
          File fileToLoad = new File(filenames[i]);
          File fileToLoadClean = new File(filenames[i].replace("###isText", ""));
          String shortName = fileToLoad.getName();
          if (fileToLoadClean.exists() && !filesToLoad.contains(fileToLoad)) {
            if (shortName.endsWith(".py")) {
              log(4, "Restore Python script: %s", fileToLoad.getName());
            } else if (shortName.endsWith("###isText")) {
              log(4, "Restore Text file: %s", fileToLoad.getName());
            } else {
              log(4, "Restore Sikuli script: %s", fileToLoad);
            }
            filesToLoad.add(fileToLoad);
            if (restoreScriptFromSession(fileToLoad)) {
              filesLoaded++;
            }
          }
        }
      }
    }
    if (loadScripts != null && loadScripts.length > 0) { //TODO
      log(3, "Preload given scripts");
      for (int i = 0; i < loadScripts.length; i++) {
        if (loadScripts[i].isEmpty()) {
          continue;
        }
        File f = new File(loadScripts[i]);
        if (f.exists() && !filesToLoad.contains(f)) {
          if (f.getName().endsWith(".py")) {
            Debug.log(4, "loadScripts: Python script: %s", f.getName());
          } else {
            Debug.log(4, "loadScripts: Sikuli script: %s", f);
          }
          if (restoreScriptFromSession(f)) filesLoaded++;
        }
      }
    }
    return filesLoaded;
  }

  private boolean restoreScriptFromSession(File file) {
    EditorPane editorPane = makeTab(-1);
    editorPane.loadFile(file);
    if (editorPane.hasEditingFile()) {
      setCurrentFileTabTitle(file.getAbsolutePath());
      editorPane.setCaretPosition(0);
      return true;
    }
    log(-1, "restoreScriptFromSession: Can't load: %s", file);
    tabs.remove(tabs.getSelectedIndex());
    return false;
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="06 tabs handling">
  void terminate() {
    log(lvl, "Quit requested");
    if (closeIDE()) {
      Commons.terminate(0, "");
    }
    log(-1, "Quit: cancelled or did not work");
  }

  EditorPane makeTab() {
    EditorPane editorPane = new EditorPane();
    lineNumberColumn = new EditorLineNumberView(editorPane);
    editorPane.getScrollPane().setRowHeaderView(lineNumberColumn);
    return editorPane;
  }

  EditorPane makeTab(int tabIndex) {
    log(lvl + 1, "makeTab: %d", tabIndex);
    EditorPane editorPane = makeTab();
    JScrollPane scrPane = editorPane.getScrollPane();
    if (tabIndex < 0 || tabIndex >= tabs.getTabCount()) {
      tabs.addTab(_I("tabUntitled"), scrPane);
    } else {
      tabs.addTab(_I("tabUntitled"), scrPane, tabIndex);
    }
    tabs.setSelectedIndex(tabIndex < 0 ? tabs.getTabCount() - 1 : tabIndex);
    editorPane.requestFocus();
    return editorPane;
  }

  boolean newTabEmpty() {
    EditorPane editorPane = makeTab(-1);
    editorPane.init(null);
    editorPane.setTemp(true);
    editorPane.setIsBundle();
    IScriptRunner runner = editorPane.getRunner();
    String defaultExtension = runner.getDefaultExtension();
    File tempFile = FileManager.createTempFile(defaultExtension, new File(Commons.getIDETemp(),
        "SikulixIDETempTab" + editorPane.getID()).getAbsolutePath());
    if (null == tempFile) {
      //TODO newTabEmpty: temp problem: how should caller react?
      return false;
    }
    editorPane.setFiles(tempFile); //newTabEmpty (temp)
    editorPane.updateDocumentListeners("empty tab");
    tabs.addTab(_I("tabUntitled"), editorPane.getScrollPane(), 0);
    tabs.setSelectedIndex(0);
    return true;
  }

  EditorPane newTabWithContent(String fname) {
    return newTabWithContent(fname, -1);
  }

  EditorPane newTabWithContent(String fname, int tabIndex) {
    int selectedTab = tabs.getSelectedIndex();
    EditorPane editorPane = makeTab(tabIndex);
    File tabFile;
    boolean tabFileLoaded = false;
    if (null == fname) {
      tabFile = editorPane.selectFile();
      tabFileLoaded = tabFile != null;
    } else {
      tabFile = new File(fname);
    }
    if (tabFile != null) {
      if (!tabFileLoaded) {
        editorPane.loadFile(tabFile);
      }
      if (editorPane.hasEditingFile()) {
        setCurrentFileTabTitle(tabFile.getAbsolutePath());
        recentAdd(tabFile.getAbsolutePath());
        if (editorPane.isText()) {
          collapseMessageArea();
        }
      }
    } else {
      log(4, "selectFile: cancelled");
      removeCurrentTab();
      int alreadyOpen = tabs.getAlreadyOpen();
      if (alreadyOpen < 0) {
        tabs.setSelectedIndex(selectedTab);
      } else {
        tabs.setSelectedIndex(alreadyOpen);
      }
    }
    if (null == tabFile) {
      return null;
    } else {
      return editorPane;
    }
  }

  boolean checkDirtyPanes() {
    for (int i = 0; i < tabs.getTabCount(); i++) {
      try {
        EditorPane codePane = getPaneAtIndex(i);
        if (codePane.isDirty()) {
          return true;
        }
      } catch (Exception e) {
        Debug.error("checkDirtyPanes: " + e.getMessage());
      }
    }
    return false;
  }

  EditorPane getCurrentCodePane() {
    int tabCount = tabs.getTabCount();
    if (tabCount == 0) {
      return null;
    }
    JScrollPane scrPane = (JScrollPane) tabs.getSelectedComponent();
    EditorPane pane = (EditorPane) scrPane.getViewport().getView();
    return pane;
  }

  EditorPane getPaneAtIndex(int index) {
    JScrollPane scrPane = (JScrollPane) tabs.getComponentAt(index);
    EditorPane codePane = (EditorPane) scrPane.getViewport().getView();
    return codePane;
  }

  void setCurrentFileTabTitle(String fname) {
    int tabIndex = tabs.getSelectedIndex();
    setFileTabTitle(fname, tabIndex);
  }

  void setCurrentFileTabTitleDirty(boolean isDirty) {
    int i = tabs.getSelectedIndex();
    String title = tabs.getTitleAt(i);
    if (!isDirty && title.startsWith("*")) {
      title = title.substring(1);
      tabs.setTitleAt(i, title);
    } else if (isDirty && !title.startsWith("*")) {
      title = "*" + title;
      tabs.setTitleAt(i, title);
    }
  }

  void setFileTabTitle(String fName, int tabIndex) {
    String ideTitle;
    EditorPane codePane = getCurrentCodePane();
    if (codePane.isBundle()) {
      String shortName = new File(fName).getName();
      ideTitle = new File(fName).getAbsolutePath();
      int i = shortName.lastIndexOf(".");
      if (i > 0) {
        tabs.setTitleAt(tabIndex, shortName.substring(0, i));
      } else {
        tabs.setTitleAt(tabIndex, shortName);
      }
    } else {
      tabs.setTitleAt(tabIndex, codePane.saveAndGetCurrentFile().getName());
      ideTitle = codePane.getFilePath();
    }
    setIDETitle(ideTitle);
  }

  ArrayList<File> getOpenedFilenames() {
    int nTab = tabs.getTabCount();
    File file = null;
    ArrayList<File> filenames = new ArrayList();
    if (nTab > 0) {
      for (int i = 0; i < nTab; i++) {
        EditorPane codePane = getPaneAtIndex(i);
        file = codePane.getCurrentFile();
        if (file != null) {
          filenames.add(file);
        } else {
          filenames.add(null);
        }
      }
    }
    return filenames;
  }

  int isAlreadyOpen(String filename) {
    int aot = getOpenedFilenames().indexOf(new File(filename));
    if (aot > -1 && aot < (tabs.getTabCount() - 1)) {
      return aot;
    }
    return -1;
  }


  boolean removeCurrentTab() {
    EditorPane pane = getCurrentCodePane();
    tabs.remove(tabs.getSelectedIndex());
    if (pane == getCurrentCodePane()) {
      return false;
    }
    return true;
  }

  void closeCurrentTab() {
    EditorPane codePane = getCurrentCodePane();
    String orgName = codePane.getCurrentShortFilename();
    log(lvl, "doCloseTab requested: %s", orgName);
    try {
      codePane.close();
      tabs.remove(tabs.getSelectedIndex());
    } catch (Exception ex) {
      Debug.error("Can't close this tab: %s", ex.getMessage());
    }
    codePane = getCurrentCodePane();
    if (codePane != null) {
      codePane.requestFocus();
    } else {
      newTabEmpty();
    }
  }
  //</editor-fold>

  //<editor-fold desc="07 menu helpers">
  void recentAdd(String fPath) {
    if (Settings.experimental) {
      log(4, "doRecentAdd: %s", fPath);
      String fName = new File(fPath).getName();
      if (recentProjectsMenu.contains(fName)) {
        recentProjectsMenu.remove(fName);
      } else {
        recentProjects.put(fName, fPath);
        if (recentProjectsMenu.size() == recentMaxMax) {
          String fObsolete = recentProjectsMenu.remove(recentMax - 1);
          recentProjects.remove(fObsolete);
        }
      }
      recentProjectsMenu.add(0, fName);
      recentMenu.removeAll();
      for (String entry : recentProjectsMenu.subList(1, recentProjectsMenu.size())) {
        if (isAlreadyOpen(recentProjects.get(entry)) > -1) {
          continue;
        }
        try {
          recentMenu.add(createMenuItem(entry,
              null,
              new FileAction(FileAction.ENTRY)));
        } catch (NoSuchMethodException ex) {
        }
      }
    }
  }

  public void showAbout() {
  }

  public void showPreferencesWindow() {
    PreferencesWin pwin = new PreferencesWin();
    pwin.setAlwaysOnTop(true);
    pwin.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    Point ideLoc = SikulixIDE.getWindowRect().getLocation();
    ideLoc.y += 120;
    pwin.setLocation(ideLoc);
    pwin.setVisible(true);
  }

  String TYPE_SIKULIX_SPECIAL = "text/sikulixspecial";

  void openSpecial() {
    log(lvl + 1, "Open Special requested");
    List<String> specialFileNames = new ArrayList<>();
    List<File> specialFiles = new ArrayList<>();

    String SX_ADDITIONAL_SITES = "SikuliX Additional Sites";
    File sitesTxt = JythonSupport.getSitesTxt();
    specialFileNames.add(SX_ADDITIONAL_SITES);
    specialFiles.add(sitesTxt);

    String SX_START_LOG = "IDE start-up log";
    File ideStartLog = Debug.getIdeStartLogFile();
    specialFileNames.add(SX_START_LOG);
    specialFiles.add(ideStartLog);

    if (Commons.isSandBox()){
      Commons.saveGlobalOptions();
      String SX_SETTINGS_OPTIONS = "SikuliX Settings & Options";
      File optFile = Commons.getOptionFile();
      specialFileNames.add(SX_SETTINGS_OPTIONS);
      specialFiles.add(optFile);
    }

    Region where = new Location(getWindowTop().x, getWindowTop().y + 150).grow(3);
    String selected = SX.popSelect("Select a special SikuliX file", "Edit a special SikuliX file", "", where, specialFileNames);
    if (selected != null) {
      Object selection = specialFiles.get(specialFileNames.indexOf(selected));
      log(lvl, "Open Special: should load: %s", (selection == null ? selected : selection));
      boolean success = true;
      if (selected.equals(SX_ADDITIONAL_SITES)) {
        if (!sitesTxt.exists()) {
          success = FileManager.writeStringToFile(JythonSupport.getSitesTxtDefault(), (File) selection);
        }
      }
      if (selection != null && success) {
        EditorPane editorPane = newTabWithContent(((File) selection).getAbsolutePath());
        editorPane.setType(TYPE_SIKULIX_SPECIAL);
      } else {
        log(-1, "Open Special: no avail: %s", (selection == null ? selected : selection));
      }
    }
  }
  //</editor-fold>

  //<editor-fold desc="10 Init Menus">
  private JMenuBar _menuBar = new JMenuBar();
  private JMenu _fileMenu = null; //new JMenu(_I("menuFile"));
  private JMenu _editMenu = null; //new JMenu(_I("menuEdit"));
  private UndoAction _undoAction = null; //new UndoAction();
  private RedoAction _redoAction = null; //new RedoAction();
  private JMenu _runMenu = null; //new JMenu(_I("menuRun"));
  private JMenu _viewMenu = null; //new JMenu(_I("menuView"));
  private JMenu _toolMenu = null; //new JMenu(_I("menuTool"));
  private JMenu _helpMenu = null; //new JMenu(_I("menuHelp"));

  void initMenuBars(JFrame frame) {
    try {
      initFileMenu();
      initEditMenu();
      initRunMenu();
      initViewMenu();
      initToolMenu();
      initHelpMenu();
    } catch (NoSuchMethodException e) {
      log(-1, "Problem when initializing menues\nError: %s", e.getMessage());
    }

    _menuBar.add(_fileMenu);
    _menuBar.add(_editMenu);
    _menuBar.add(_runMenu);
    _menuBar.add(_viewMenu);
    _menuBar.add(_toolMenu);
    _menuBar.add(_helpMenu);
    frame.setJMenuBar(_menuBar);
  }

  JMenuItem createMenuItem(JMenuItem item, KeyStroke shortcut, ActionListener listener) {
    if (shortcut != null) {
      item.setAccelerator(shortcut);
    }
    item.addActionListener(listener);
    return item;
  }

  JMenuItem createMenuItem(String name, KeyStroke shortcut, ActionListener listener) {
    JMenuItem item = new JMenuItem(name);
    return createMenuItem(item, shortcut, listener);
  }

  class MenuAction implements ActionListener {

    Method actMethod = null;
    String action;

    MenuAction() {
    }

    MenuAction(String item) throws NoSuchMethodException {
      Class[] paramsWithEvent = new Class[1];
      try {
        paramsWithEvent[0] = Class.forName("java.awt.event.ActionEvent");
        actMethod = this.getClass().getMethod(item, paramsWithEvent);
        action = item;
      } catch (ClassNotFoundException cnfe) {
        log(-1, "Can't find menu action: " + cnfe);
      }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      if (actMethod != null) {
        try {
          log(4, "MenuAction." + action);
          Object[] params = new Object[1];
          params[0] = e;
          actMethod.invoke(this, params);
        } catch (Exception ex) {
          log(-1, "Problem when trying to invoke menu action %s\nError: %s",
              action, ex.getMessage());
        }
      }
    }
  }

  private static JMenu recentMenu = null;
  private static Map<String, String> recentProjects = new HashMap<String, String>();
  private static java.util.List<String> recentProjectsMenu = new ArrayList<String>();
  private static int recentMax = 10;
  private static int recentMaxMax = recentMax + 10;
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="11 Init FileMenu">
  JMenu getFileMenu() {
    return _fileMenu;
  }

  private EditorLineNumberView lineNumberColumn;

  //<editor-fold desc="menu">
  private void initFileMenu() throws NoSuchMethodException {
    _fileMenu = new JMenu(_I("menuFile"));
    JMenuItem jmi;
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _fileMenu.setMnemonic(java.awt.event.KeyEvent.VK_F);

    if (IDEDesktopSupport.showAbout) {
      _fileMenu.add(createMenuItem("About SikuliX",
          KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, scMask),
          new FileAction(FileAction.ABOUT)));
      _fileMenu.addSeparator();
    }

    _fileMenu.add(createMenuItem(_I("menuFileNew"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, scMask),
        new FileAction(FileAction.NEW)));

    jmi = _fileMenu.add(createMenuItem(_I("menuFileOpen"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, scMask),
        new FileAction(FileAction.OPEN)));
    jmi.setName("OPEN");

    recentMenu = new JMenu(_I("menuRecent"));

    if (Settings.experimental) {
      _fileMenu.add(recentMenu);
    }

    jmi = _fileMenu.add(createMenuItem(_I("menuFileSave"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, scMask),
        new FileAction(FileAction.SAVE)));
    jmi.setName("SAVE");

    jmi = _fileMenu.add(createMenuItem(_I("menuFileSaveAs"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
            InputEvent.SHIFT_DOWN_MASK | scMask),
        new FileAction(FileAction.SAVE_AS)));
    jmi.setName("SAVE_AS");

//TODO    _fileMenu.add(createMenuItem(_I("menuFileSaveAll"),
    _fileMenu.add(createMenuItem("Save all",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
            InputEvent.CTRL_DOWN_MASK | scMask),
        new FileAction(FileAction.SAVE_ALL)));

    _fileMenu.add(createMenuItem(_I("menuFileExport"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E,
            InputEvent.SHIFT_DOWN_MASK | scMask),
        new FileAction(FileAction.EXPORT)));

//TODO export as jar / runnable jar
    _fileMenu.add(createMenuItem("Export as jar",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_J, scMask),
        new FileAction(FileAction.ASJAR)));
/*
    _fileMenu.add(createMenuItem("Export as runnable jar",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_J,
            InputEvent.SHIFT_DOWN_MASK | scMask),
        new FileAction(FileAction.ASRUNJAR)));
*/

    jmi = _fileMenu.add(createMenuItem(_I("menuFileCloseTab"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W, scMask),
        new FileAction(FileAction.CLOSE_TAB)));
    jmi.setName("CLOSE_TAB");

    if (IDEDesktopSupport.showPrefs) {
      _fileMenu.addSeparator();
      _fileMenu.add(createMenuItem(_I("menuFilePreferences"),
          KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P, scMask),
          new FileAction(FileAction.PREFERENCES)));
    }

    _fileMenu.addSeparator();
    _fileMenu.add(createMenuItem("Open Special Files",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, InputEvent.ALT_DOWN_MASK | scMask),
        new FileAction(FileAction.OPEN_SPECIAL)));

//TODO restart IDE
/*
    _fileMenu.add(createMenuItem("Restart IDE",
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
                    InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
            new FileAction(FileAction.RESTART)));
*/
    if (IDEDesktopSupport.showQuit) {
      _fileMenu.addSeparator();
      _fileMenu.add(createMenuItem(_I("menuFileQuit"),
          null, new FileAction(FileAction.QUIT)));
    }
  }

  FileAction getFileAction(int tabIndex) {
    return new FileAction(tabIndex);
  }
  //</editor-fold>

  class FileAction extends MenuAction {

    static final String ABOUT = "doAbout";
    static final String NEW = "doNew";
    static final String INSERT = "doInsert";
    static final String OPEN = "doOpen";
    static final String RECENT = "doRecent";
    static final String SAVE = "doSave";
    static final String SAVE_AS = "doSaveAs";
    static final String SAVE_ALL = "doSaveAll";
    static final String EXPORT = "doExport";
    static final String ASJAR = "doAsJar";
    static final String ASRUNJAR = "doAsRunJar";
    static final String CLOSE_TAB = "doCloseTab";
    static final String PREFERENCES = "doPreferences";
    static final String QUIT = "doQuit";
    static final String ENTRY = "doRecent";
    static final String RESTART = "doRestart";
    static final String OPEN_SPECIAL = "doOpenSpecial";

    FileAction() {
      super();
    }

    FileAction(String item) throws NoSuchMethodException {
      super(item);
    }

    FileAction(int tabIndex) {
      super();
      targetTab = tabIndex;
    }

    private int targetTab = -1;

    public void doNew(ActionEvent ae) {
      newTabEmpty();
    }

    //TODO not used
    public void doInsert(ActionEvent ae) {
      newTabWithContent(tabs.getLastClosed(), targetTab);
    }

    public void doOpen(ActionEvent ae) {
      newTabWithContent(null);
    }

    //TODO not used
    public void doRecent(ActionEvent ae) {
      log(4, "doRecent: menuOpenRecent: %s", ae.getActionCommand());
    }

    class OpenRecent extends MenuAction {

      OpenRecent() {
        super();
      }

      void openRecent(ActionEvent ae) {
        log(lvl, "openRecent: %s", ae.getActionCommand());
      }
    }

    public void doSave(ActionEvent ae) {
      String fname = null;
      try {
        EditorPane codePane = getCurrentCodePane();
        fname = codePane.saveTabContent();
        if (fname != null) {
          if (codePane.isBundle()) {
            fname = codePane.getSrcBundle();
          } else {
            fname = codePane.getFilePath();
          }
          setCurrentFileTabTitle(fname);
          tabs.setLastClosed(fname);
          if (codePane.getType().equals(TYPE_SIKULIX_SPECIAL)) {
            if (Commons.isOptionsfileThenLoadIt(fname)) {
              Debug.info("Settings & Options saved and reloaded");
            }
          }
        }
      } catch (Exception ex) {
        if (ex instanceof IOException) {
          log(-1, "Problem when trying to save %s\nError: %s",
              fname, ex.getMessage());
        } else {
          log(-1, "A non-IOException-problem when trying to save %s\nError: %s",
              fname, ex.getMessage());
        }
      }
    }

    public void doSaveAs(ActionEvent ae) {
      EditorPane codePane = getCurrentCodePane();
      String orgName = codePane.getCurrentShortFilename();
      log(lvl, "doSaveAs requested: %s", orgName);
      try {
        String fname = codePane.saveAsSelect();
        if (fname != null) {
          setCurrentFileTabTitle(fname);
          codePane.doReparse();
          codePane.setDirty(false);
        } else {
          log(-1, "doSaveAs: %s not completed", orgName);
        }
      } catch (Exception ex) {
        log(-1, "doSaveAs: %s Error: %s", orgName, ex.getMessage());
      }
    }

    public void doSaveAll(ActionEvent ae) {
      log(lvl, "doSaveAll requested");
      if (!checkDirtyPanes()) {
        return;
      }
      saveSession(DO_SAVE_ALL, false);
    }

    public void doExport(ActionEvent ae) {
      EditorPane codePane = getCurrentCodePane();
      String orgName = codePane.getCurrentShortFilename();
      log(lvl, "doExport requested: %s", orgName);
      String fname = null;
      try {
        fname = codePane.exportAsZip();
      } catch (Exception ex) {
        log(-1, "Problem when trying to save %s\nError: %s",
            fname, ex.getMessage());
      }
    }

    public void doAsJar(ActionEvent ae) {
      EditorPane codePane = getCurrentCodePane();
      String orgName = codePane.getCurrentShortFilename();
      log(lvl, "doAsJar requested: %s", orgName);
      if (codePane.isDirty()) {
        Sikulix.popError("Please save script before!", "Export as jar");
      } else {
        File fScript = codePane.saveAndGetCurrentFile();
        List<String> options = new ArrayList<>();
        options.add("plain");
        options.add(fScript.getParentFile().getAbsolutePath());
        String fpJar = makeScriptjar(options);
        if (null != fpJar) {
          Sikulix.popup("ok: " + fpJar, "Export as jar ...");
        } else {
          Sikulix.popError("did not work for: " + orgName, "Export as jar ...");
        }
      }
    }

    public void doAsRunJar(ActionEvent ae) {
      EditorPane codePane = getCurrentCodePane();
      String orgName = codePane.getCurrentShortFilename();
      log(lvl, "doAsRunJar requested: %s", orgName);
      if (codePane.isDirty()) {
        Sikulix.popError("Please save script before!", "Export as runnable jar");
      } else {
        File fScript = codePane.saveAndGetCurrentFile();
        List<String> options = new ArrayList<>();
        options.add(fScript.getParentFile().getAbsolutePath());
        String fpJar = makeScriptjar(options);
        if (null != fpJar) {
          Sikulix.popup(fpJar, "Export as runnable jar ...");
        } else {
          Sikulix.popError("did not work for: " + orgName, "Export as runnable jar");
        }
      }
    }

    public void doCloseTab(ActionEvent ae) {
      closeCurrentTab();
    }

    public void doAbout(ActionEvent ae) {
      new SXDialog("sxideabout", SikulixIDE.getWindowTop(), SXDialog.POSITION.TOP).run();
    }

    public void doPreferences(ActionEvent ae) {
      showPreferencesWindow();
    }

    public void doOpenSpecial(ActionEvent ae) {
      (new Thread() {
        @Override
        public void run() {
          openSpecial();
        }
      }).start();
    }

    public void doRestart(ActionEvent ae) {
      log(lvl, "Restart IDE requested");
      if (closeIDE()) {
        log(lvl, "Restarting IDE");
        Commons.terminate(255, "Restarting IDE");
      }
      log(-1, "Restart IDE: did not work");
    }

    public void doQuit(ActionEvent ae) {
      terminate();
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="12 Init EditMenu">
  private String findText = "";

  private void initEditMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

    _editMenu = new JMenu(_I("menuEdit"));
    _editMenu.setMnemonic(java.awt.event.KeyEvent.VK_E);

    _undoAction = new UndoAction();
    JMenuItem undoItem = _editMenu.add(_undoAction);
    undoItem.setAccelerator(
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, scMask));
    _redoAction = new RedoAction();
    JMenuItem redoItem = _editMenu.add(_redoAction);
    redoItem.setAccelerator(
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, scMask | InputEvent.SHIFT_DOWN_MASK));

    _editMenu.addSeparator();
    _editMenu.add(createMenuItem(_I("menuEditCopy"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, scMask),
        new EditAction(EditAction.COPY)));
    _editMenu.add(createMenuItem("Copy line",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, scMask | InputEvent.SHIFT_DOWN_MASK),
        new EditAction(EditAction.COPY)));
    _editMenu.add(createMenuItem(_I("menuEditCut"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, scMask),
        new EditAction(EditAction.CUT)));
    _editMenu.add(createMenuItem("Cut line",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, scMask | InputEvent.SHIFT_DOWN_MASK),
        new EditAction(EditAction.CUT)));
    _editMenu.add(createMenuItem(_I("menuEditPaste"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, scMask),
        new EditAction(EditAction.PASTE)));
    _editMenu.add(createMenuItem(_I("menuEditSelectAll"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, scMask),
        new EditAction(EditAction.SELECT_ALL)));

    _editMenu.addSeparator();
    JMenu findMenu = new JMenu(_I("menuFind"));
    findMenu.setMnemonic(KeyEvent.VK_F);
    findMenu.add(createMenuItem(_I("menuFindFind"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, scMask),
        new FindAction(FindAction.FIND)));
    findMenu.add(createMenuItem(_I("menuFindFindNext"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, scMask),
        new FindAction(FindAction.FIND_NEXT)));
    findMenu.add(createMenuItem(_I("menuFindFindPrev"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, scMask | InputEvent.SHIFT_MASK),
        new FindAction(FindAction.FIND_PREV)));
    _editMenu.add(findMenu);

    _editMenu.addSeparator();
    _editMenu.add(createMenuItem(_I("menuEditIndent"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, 0),
        new EditAction(EditAction.INDENT)));
    _editMenu.add(createMenuItem(_I("menuEditUnIndent"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, InputEvent.SHIFT_MASK),
        new EditAction(EditAction.UNINDENT)));
  }

  class EditAction extends MenuAction {

    static final String CUT = "doCut";
    static final String COPY = "doCopy";
    static final String PASTE = "doPaste";
    static final String SELECT_ALL = "doSelectAll";
    static final String INDENT = "doIndent";
    static final String UNINDENT = "doUnindent";

    EditAction() {
      super();
    }

    EditAction(String item) throws NoSuchMethodException {
      super(item);
    }

    //mac: defaults write -g ApplePressAndHoldEnabled -bool false

    private void performEditorAction(String action, ActionEvent ae) {
      EditorPane pane = getCurrentCodePane();
      pane.getActionMap().get(action).actionPerformed(ae);
    }

    private void selectLine() {
      EditorPane pane = getCurrentCodePane();
      Element lineAtCaret = pane.getLineAtCaret(-1);
      pane.select(lineAtCaret.getStartOffset(), lineAtCaret.getEndOffset());
    }

    public void doCut(ActionEvent ae) {
      if (getCurrentCodePane().getSelectedText() == null) {
        selectLine();
      }
      performEditorAction(DefaultEditorKit.cutAction, ae);
    }

    public void doCopy(ActionEvent ae) {
      if (getCurrentCodePane().getSelectedText() == null) {
        selectLine();
      }
      performEditorAction(DefaultEditorKit.copyAction, ae);
    }

    public void doPaste(ActionEvent ae) {
      performEditorAction(DefaultEditorKit.pasteAction, ae);
    }

    public void doSelectAll(ActionEvent ae) {
      performEditorAction(DefaultEditorKit.selectAllAction, ae);
    }

    public void doIndent(ActionEvent ae) {
      EditorPane pane = getCurrentCodePane();
      (new SikuliEditorKit.InsertTabAction()).actionPerformed(pane);
    }

    public void doUnindent(ActionEvent ae) {
      EditorPane pane = getCurrentCodePane();
      (new SikuliEditorKit.DeindentAction()).actionPerformed(pane);
    }
  }

  class FindAction extends MenuAction {

    static final String FIND = "doFind";
    static final String FIND_NEXT = "doFindNext";
    static final String FIND_PREV = "doFindPrev";

    FindAction() {
      super();
    }

    FindAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void doFind(ActionEvent ae) {
//      _searchField.selectAll();
//      _searchField.requestFocus();
      Sikulix.popat(SikulixIDE.get());
      findText = Sikulix.input(
          "Enter text to be searched (case sensitive)\n" +
              "Start with ! to search case insensitive\n",
          findText, "SikuliX IDE -- Find");
      if (null == findText) {
        return;
      }
      if (findText.isEmpty()) {
        Debug.error("Find(%s): search text is empty", findText);
        return;
      }
      if (!findStr(findText)) {
        Debug.error("Find(%s): not found", findText);
      }
    }

    public void doFindNext(ActionEvent ae) {
      if (!findText.isEmpty()) {
        findNext(findText);
      }
    }

    public void doFindPrev(ActionEvent ae) {
      if (!findText.isEmpty()) {
        findPrev(findText);
      }
    }

    private boolean _find(String str, int begin, boolean forward) {
      if (str == "!") {
        return false;
      }
      EditorPane codePane = getCurrentCodePane();
      int pos = codePane.search(str, begin, forward);
      log(4, "find \"" + str + "\" at " + begin + ", found: " + pos);
      if (pos < 0) {
        return false;
      }
      return true;
    }

    boolean findStr(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str, getCurrentCodePane().getCaretPosition(), true);
      }
      return false;
    }

    boolean findPrev(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str, getCurrentCodePane().getCaretPosition(), false);
      }
      return false;
    }

    boolean findNext(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str,
            getCurrentCodePane().getCaretPosition() + str.length(),
            true);
      }
      return false;
    }

//    public void setFailed(boolean failed) {
//      log(4, "search failed: " + failed);
//      _searchField.setBackground(Color.white);
//      if (failed) {
//        _searchField.setForeground(COLOR_SEARCH_FAILED);
//      } else {
//        _searchField.setForeground(COLOR_SEARCH_NORMAL);
//      }
//    }
  }

  class UndoAction extends AbstractAction {

    UndoAction() {
      super(_I("menuEditUndo"));
      setEnabled(false);
    }

    void updateUndoState() {
      EditorPane pane = getCurrentCodePane();
      if (pane != null) {
        if (pane.getUndoRedo(pane).getUndoManager().canUndo()) {
          setEnabled(true);
        }
      } else {
        setEnabled(false);
      }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      EditorPane pane = getCurrentCodePane();
      if (pane != null) {
        try {
          pane.getUndoRedo(pane).getUndoManager().undo();
        } catch (CannotUndoException ex) {
        }
        updateUndoState();
        _redoAction.updateRedoState();
      }
    }
  }

  class RedoAction extends AbstractAction {

    RedoAction() {
      super(_I("menuEditRedo"));
      setEnabled(false);
    }

    protected void updateRedoState() {
      EditorPane pane = getCurrentCodePane();
      if (pane != null) {
        if (pane.getUndoRedo(pane).getUndoManager().canRedo()) {
          setEnabled(true);
        }
      } else {
        setEnabled(false);
      }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      EditorPane pane = getCurrentCodePane();
      if (pane != null) {
        try {
          pane.getUndoRedo(pane).getUndoManager().redo();
        } catch (CannotRedoException ex) {
        }
        updateRedoState();
        _undoAction.updateUndoState();
      }
    }
  }

  void updateUndoRedoStates() {
    _undoAction.updateUndoState();
    _redoAction.updateRedoState();
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="13 Init Run Menu">
  JMenu getRunMenu() {
    return _runMenu;
  }

  private void initRunMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _runMenu = new JMenu(_I("menuRun"));
    _runMenu.setMnemonic(java.awt.event.KeyEvent.VK_R);
    _runMenu.add(createMenuItem(_I("menuRunRun"),
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, scMask),
        new RunAction(RunAction.RUN)));
    _runMenu.add(createMenuItem(_I("menuRunRunAndShowActions"),
        KeyStroke.getKeyStroke(KeyEvent.VK_R,
            InputEvent.ALT_DOWN_MASK | scMask),
        new RunAction(RunAction.RUN_SHOW_ACTIONS)));
    _runMenu.add(createMenuItem("Run selection",
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
            InputEvent.SHIFT_DOWN_MASK | scMask),
        new RunAction(RunAction.RUN_SELECTION)));
  }

  class RunAction extends MenuAction {

    static final String RUN = "runNormal";
    static final String RUN_SHOW_ACTIONS = "runShowActions";
    static final String RUN_SELECTION = "runSelection";

    RunAction() {
      super();
    }

    RunAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void runNormal(ActionEvent ae) {
      _btnRun.runCurrentScript();
    }

    public void runShowActions(ActionEvent ae) {
      _btnRunViz.runCurrentScript();
    }

    public void runSelection(ActionEvent ae) {
      getCurrentCodePane().runSelection();
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="14 Init View Menu">
  private JCheckBoxMenuItem chkShowThumbs;

  private void initViewMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _viewMenu = new JMenu(_I("menuView"));
    _viewMenu.setMnemonic(java.awt.event.KeyEvent.VK_V);

    boolean prefMorePlainText = PreferencesUser.get().getPrefMorePlainText();

    chkShowThumbs = new JCheckBoxMenuItem(_I("menuViewShowThumbs"), !prefMorePlainText);
    _viewMenu.add(createMenuItem(chkShowThumbs,
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, scMask),
        new ViewAction(ViewAction.SHOW_THUMBS)));

//TODO Message Area clear
//TODO Message Area LineBreak
  }

  class ViewAction extends MenuAction {

    static final String SHOW_THUMBS = "toggleShowThumbs";

    ViewAction() {
      super();
    }

    ViewAction(String item) throws NoSuchMethodException {
      super(item);
    }

    private long lastWhen = -1;

    public void toggleShowThumbs(ActionEvent ae) {
      if (Commons.runningMac()) {
        if (lastWhen < 0) {
          lastWhen = new Date().getTime();
        } else {
          long delay = new Date().getTime() - lastWhen;
          lastWhen = -1;
          if (delay < 500) {
            JCheckBoxMenuItem source = (JCheckBoxMenuItem) ae.getSource();
            source.setState(!source.getState());
            return;
          }
        }
      }
      boolean showThumbsState = chkShowThumbs.getState();
      getCurrentCodePane().showThumbs = showThumbsState;
      getCurrentCodePane().doReparse();
      return;
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="15 Init ToolMenu">
  private void initToolMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _toolMenu = new JMenu(_I("menuTool"));
    _toolMenu.setMnemonic(java.awt.event.KeyEvent.VK_T);

    _toolMenu.add(createMenuItem(_I("menuToolExtensions"),
        null,
        new ToolAction(ToolAction.EXTENSIONS)));

    _toolMenu.add(createMenuItem(_I("menuToolAndroid"),
        null,
        new ToolAction(ToolAction.ANDROID)));
  }

  class ToolAction extends MenuAction {

    static final String EXTENSIONS = "extensions";
    static final String ANDROID = "android";

    ToolAction() {
      super();
    }

    ToolAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void extensions(ActionEvent ae) {
      showExtensions();
    }

    public void android(ActionEvent ae) {
      androidSupport();
    }
  }

  private void showExtensions() {
    ExtensionManager.show();
  }

  private static IScreen defaultScreen = null;

  static IScreen getDefaultScreen() {
    return defaultScreen;
  }

  private String getRunningJar(Class clazz) {
    String jarName = "";
    CodeSource codeSrc = clazz.getProtectionDomain().getCodeSource();
    if (codeSrc != null && codeSrc.getLocation() != null) {
      try {
        jarName = codeSrc.getLocation().getPath();
        jarName = URLDecoder.decode(jarName, "utf8");
      } catch (UnsupportedEncodingException e) {
        log(-1, "URLDecoder: not possible: " + jarName);
        jarName = "";
      }
    }
    return jarName;
  }

  private void androidSupport() {
//    final ADBScreen aScr = new ADBScreen();
//    String title = "Android Support - !!EXPERIMENTAL!!";
//    if (aScr.isValid()) {
//      String warn = "Device found: " + aScr.getDeviceDescription() + "\n\n" +
//          "click Check: a short test is run with the device\n" +
//          "click Default...: set device as default screen for capture\n" +
//          "click Cancel: capture is reset to local screen\n" +
//          "\nBE PREPARED: Feature is experimental - no guarantee ;-)";
//      String[] options = new String[3];
//      options[WARNING_DO_NOTHING] = "Check";
//      options[WARNING_ACCEPTED] = "Default Android";
//      options[WARNING_CANCEL] = "Cancel";
//      int ret = JOptionPane.showOptionDialog(this, warn, title, 0, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
//      if (ret == WARNING_CANCEL || ret == JOptionPane.CLOSED_OPTION) {
//        defaultScreen = null;
//        return;
//      }
//      if (ret == WARNING_DO_NOTHING) {
//        SikulixIDE.hideIDE();
//        Thread test = new Thread() {
//          @Override
//          public void run() {
//            androidSupportTest(aScr);
//          }
//        };
//        test.start();
//      } else if (ret == WARNING_ACCEPTED) {
//        defaultScreen = aScr;
//        return;
//      }
//    } else if (!ADBClient.isAdbAvailable) {
//      Sikulix.popError("Package adb seems not to be available.\nIt must be installed for Android support.", title);
//    } else {
//      Sikulix.popError("No android device attached", title);
//    }
  }

  private void androidSupportTest(IScreen aScr) {
//    ADBTest.ideTest(aScr);
//    ADBScreen.stop();
    SikulixIDE.doShow();
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="16 Init Help Menu">
  private static Boolean newBuildAvailable = null;
  private static String newBuildStamp = "";

  private void initHelpMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _helpMenu = new JMenu(_I("menuHelp"));
    _helpMenu.setMnemonic(java.awt.event.KeyEvent.VK_H);

    _helpMenu.add(createMenuItem(_I("menuHelpQuickStart"),
        null, new HelpAction(HelpAction.QUICK_START)));
    _helpMenu.addSeparator();

    _helpMenu.add(createMenuItem(_I("menuHelpGuide"),
        null, new HelpAction(HelpAction.OPEN_DOC)));
//    _helpMenu.add(createMenuItem(_I("menuHelpDocumentations"),
//            null, new HelpAction(HelpAction.OPEN_GUIDE)));
    _helpMenu.add(createMenuItem(_I("menuHelpFAQ"),
        null, new HelpAction(HelpAction.OPEN_FAQ)));
    _helpMenu.add(createMenuItem(_I("menuHelpAsk"),
        null, new HelpAction(HelpAction.OPEN_ASK)));
    _helpMenu.add(createMenuItem(_I("menuHelpBugReport"),
        null, new HelpAction(HelpAction.OPEN_BUG_REPORT)));

//    _helpMenu.add(createMenuItem(_I("menuHelpTranslation"),
//            null, new HelpAction(HelpAction.OPEN_TRANSLATION)));
    _helpMenu.addSeparator();
    _helpMenu.add(createMenuItem(_I("menuHelpHomepage"),
        null, new HelpAction(HelpAction.OPEN_HOMEPAGE)));

//    _helpMenu.addSeparator();
//    _helpMenu.add(createMenuItem("SikuliX1 Downloads",
//        null, new HelpAction(HelpAction.OPEN_DOWNLOADS)));
//    _helpMenu.add(createMenuItem(_I("menuHelpCheckUpdate"),
//        null, new HelpAction(HelpAction.CHECK_UPDATE)));
  }

  private void lookUpdate() {
    newBuildAvailable = null;
    String token = "This version was built at ";
    String httpDownload = "#https://raiman.github.io/SikuliX1/downloads.html";
    String pageDownload = FileManager.downloadURLtoString(httpDownload);
    if (!pageDownload.isEmpty()) {
      newBuildAvailable = false;
    }
    int locStamp = pageDownload.indexOf(token);
    if (locStamp > 0) {
      locStamp += token.length();
      String latestBuildFull = pageDownload.substring(locStamp, locStamp + 16);
      String latestBuild = latestBuildFull.replaceAll("-", "").replace("_", "").replace(":", "");
      String actualBuild = Commons.getSxBuildStamp();
      try {
        long lb = Long.parseLong(latestBuild);
        long ab = Long.parseLong(actualBuild);
        if (lb > ab) {
          newBuildAvailable = true;
          newBuildStamp = latestBuildFull;
        }
        log(lvl, "latest build: %s this build: %s (newer: %s)", latestBuild, actualBuild, newBuildAvailable);
      } catch (NumberFormatException e) {
        log(-1, "check for new build: stamps not readable");
      }
    }
  }

  class HelpAction extends MenuAction {

    static final String CHECK_UPDATE = "doCheckUpdate";
    static final String QUICK_START = "openQuickStart";
    static final String OPEN_DOC = "openDoc";
    static final String OPEN_GUIDE = "openTutor";
    static final String OPEN_FAQ = "openFAQ";
    static final String OPEN_ASK = "openAsk";
    static final String OPEN_BUG_REPORT = "openBugReport";
    static final String OPEN_TRANSLATION = "openTranslation";
    static final String OPEN_HOMEPAGE = "openHomepage";
    static final String OPEN_DOWNLOADS = "openDownloads";

    HelpAction() {
      super();
    }

    HelpAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void openQuickStart(ActionEvent ae) {
      FileManager.openURL("http://sikulix.com/quickstart/");
    }

    public void openDoc(ActionEvent ae) {
      FileManager.openURL("http://sikulix-2014.readthedocs.org/en/latest/index.html");
    }

    public void openTutor(ActionEvent ae) {
      FileManager.openURL("http://www.sikuli.org/videos.html");
    }

    public void openFAQ(ActionEvent ae) {
      FileManager.openURL("https://answers.launchpad.net/sikuli/+faqs");
    }

    public void openAsk(ActionEvent ae) {
      String title = "SikuliX - Ask a question";
      String msg = "If you want to ask a question about SikuliX\n%s\n"
          + "\nplease do the following:"
          + "\n- after having clicked yes"
          + "\n   the page on Launchpad should open in your browser."
          + "\n- You should first check using Launchpad's search funktion,"
          + "\n   wether similar questions have already been asked."
          + "\n- If you decide to ask a new question,"
          + "\n   try to enter a short but speaking title"
          + "\n- In a new questions's text field first paste using ctrl/cmd-v"
          + "\n   which should enter the SikuliX version/system/java info"
          + "\n   that was internally stored in the clipboard before"
          + "\n\nIf you do not want to ask a question now: click No";
      askBugOrAnswer(msg, title, "https://answers.launchpad.net/sikuli");
    }

    public void openBugReport(ActionEvent ae) {
      String title = "SikuliX - Report a bug";
      String msg = "If you want to report a bug for SikuliX\n%s\n"
          + "\nplease do the following:"
          + "\n- after having clicked yes"
          + "\n   the page on Launchpad should open in your browser"
          + "\n- fill in a short but speaking bug title and create the bug"
          + "\n- in the bug's text field first paste using ctrl/cmd-v"
          + "\n   which should enter the SikuliX version/system/java info"
          + "\n   that was internally stored in the clipboard before"
          + "\n\nIf you do not want to report a bug now: click No";
      askBugOrAnswer(msg, title, "https://bugs.launchpad.net/sikuli/+filebug");
    }

    private void askBugOrAnswer(String msg, String title, String url) {
      String si = Commons.getSystemInfo();
      System.out.println(si);
      msg = String.format(msg, si);
      if (SX.popAsk(msg, title, Commons.getSXIDERegion())) {
        Clipboard clb = Toolkit.getDefaultToolkit().getSystemClipboard();
        StringSelection sic = new StringSelection(si.toString());
        clb.setContents(sic, sic);
        FileManager.openURL(url);
      }
    }

    public void openTranslation(ActionEvent ae) {
      FileManager.openURL("https://translations.launchpad.net/sikuli/sikuli-x/+translations");
    }

    public void openHomepage(ActionEvent ae) {
      FileManager.openURL("http://sikulix.com");
    }

    public void openDownloads(ActionEvent ae) {
      FileManager.openURL("https://raiman.github.io/SikuliX1/downloads.html");
    }

    public void doCheckUpdate(ActionEvent ae) {
      if (!checkUpdate(false)) {
        lookUpdate();
        int msgType = newBuildAvailable != null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE;
        String updMsg = newBuildAvailable != null ? (newBuildAvailable ?
            _I("msgUpdate") + ": " + newBuildStamp :
            _I("msgNoUpdate")) : _I("msgUpdateError");
        JOptionPane.showMessageDialog(null, updMsg,
            Commons.getSXVersionIDE(), msgType);
      }
    }

    boolean checkUpdate(boolean isAutoCheck) { //TODO
      String ver = "";
      String details;
      AutoUpdater au = new AutoUpdater();
      PreferencesUser pref = PreferencesUser.get();
      int whatUpdate = au.checkUpdate();
      if (whatUpdate >= AutoUpdater.SOMEBETA) {
        whatUpdate -= AutoUpdater.SOMEBETA;
      }
      if (whatUpdate > 0) {
        if (whatUpdate == AutoUpdater.BETA) {
          ver = au.getBeta();
          details = au.getBetaDetails();
        } else {
          ver = au.getVersion();
          details = au.getDetails();
        }
        if (isAutoCheck && pref.getLastSeenUpdate().equals(ver)) {
          return false;
        }
        au.showUpdateFrame(_I("dlgUpdateAvailable", ver), details, whatUpdate);
        PreferencesUser.get().setLastSeenUpdate(ver);
        return true;
      }
      return false;
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="20 Init ToolBar Buttons">
  private ButtonCapture _btnCapture;
  private ButtonRun _btnRun = null, _btnRunViz = null;

  private JToolBar initToolbar() {
//    if (ENABLE_UNIFIED_TOOLBAR) {
//      MacUtils.makeWindowLeopardStyle(this.getRootPane());
//    }

    JToolBar toolbar = new JToolBar();
    JButton btnInsertImage = new ButtonInsertImage();
    JButton btnSubregion = new ButtonSubregion().init();
    JButton btnLocation = new ButtonLocation().init();
    JButton btnOffset = new ButtonOffset().init();
//TODO ButtonShow/ButtonShowIn
/*
    JButton btnShow = new ButtonShow().init();
    JButton btnShowIn = new ButtonShowIn().init();
*/
    _btnCapture = new ButtonCapture();
    toolbar.add(_btnCapture);
    toolbar.add(btnInsertImage);
    toolbar.add(btnSubregion);
    toolbar.add(btnLocation);
    toolbar.add(btnOffset);
/*
    toolbar.add(btnShow);
    toolbar.add(btnShowIn);
*/
    toolbar.add(Box.createHorizontalGlue());
    _btnRun = new ButtonRun();
    toolbar.add(_btnRun);
    _btnRunViz = new ButtonRunViz();
    toolbar.add(_btnRunViz);
    toolbar.add(Box.createHorizontalGlue());

//    JComponent jcSearchField = createSearchField();
//    toolbar.add(jcSearchField);

    toolbar.add(Box.createRigidArea(new Dimension(7, 0)));
    toolbar.setFloatable(false);
    //toolbar.setMargin(new Insets(0, 0, 0, 5));
    return toolbar;
  }

  class ButtonInsertImage extends ButtonOnToolbar implements ActionListener {

    ButtonInsertImage() {
      super();
      URL imageURL = SikulixIDE.class.getResource("/icons/insert-image-icon.png");
      setIcon(new ImageIcon(imageURL));
      setText(SikulixIDE._I("btnInsertImageLabel"));
      //setMaximumSize(new Dimension(26,26));
      setToolTipText(SikulixIDE._I("btnInsertImageHint"));
      addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      EditorPane codePane = getCurrentCodePane();
      File file = new SikulixFileChooser(ideWindow).loadImage();
      if (file == null) {
        return;
      }
      String path = FileManager.slashify(file.getAbsolutePath(), false);
      log(4, "load image: " + path);
      EditorPatternButton icon;
      String img = codePane.copyFileToBundle(path).getAbsolutePath();
      if (prefs.getDefaultThumbHeight() > 0) {
        icon = new EditorPatternButton(codePane, img);
        codePane.insertComponent(icon);
      } else {
        codePane.insertString("\"" + (new File(img)).getName() + "\"");
      }
    }
  }

  class ButtonSubregion extends ButtonOnToolbar implements ActionListener, EventObserver {

    String promptText;
    String buttonText;
    String iconFile;
    String buttonHint;

    ButtonSubregion() {
      super();
      promptText = SikulixIDE._I("msgCapturePrompt");
      buttonText = "Region"; // SikuliIDE._I("btnRegionLabel");
      iconFile = "/icons/region-icon.png";
      buttonHint = SikulixIDE._I("btnRegionHint");
    }

    ButtonSubregion init() {
      URL imageURL = SikulixIDE.class.getResource(iconFile);
      setIcon(new ImageIcon(imageURL));
      setText(buttonText);
      //setMaximumSize(new Dimension(26,26));
      setToolTipText(buttonHint);
      addActionListener(this);
      return this;
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      if (shouldRun()) {
        ideWindow.setVisible(false);
        RunTime.pause(0.5f);
        Screen.doPrompt(promptText, this); // ButtonSubregion
      } else {
        nothingTodo();
      }
    }

    void nothingTodo() {
    }

    boolean shouldRun() {
      log(4, "ButtonSubRegion");
      if (Device.isCaptureBlocked()) { // Button SubRegion
        Debug.error("FATAL: Capture is blocked");
        return false;
      }
      return true;
    }

    @Override
    public void update(EventSubject es) {
      OverlayCapturePrompt ocp = (OverlayCapturePrompt) es;
      ScreenImage simg = ocp.getSelection();
      Screen.closePrompt();
      Screen.resetPrompt(ocp);
      captureComplete(simg);
      SikulixIDE.showAgain();
    }

    void captureComplete(ScreenImage simg) {
      int x, y, w, h;
      EditorPane codePane = getCurrentCodePane();
      if (simg != null) {
        Rectangle roi = simg.getROI();
        x = (int) roi.getX();
        y = (int) roi.getY();
        w = (int) roi.getWidth();
        h = (int) roi.getHeight();
        ideWindow.setVisible(false);
        if (codePane.showThumbs) {
          if (prefs.getPrefMoreImageThumbs()) {
            codePane.insertComponent(new EditorRegionButton(codePane, x, y, w, h));
          } else {
            codePane.insertComponent(new EditorRegionLabel(codePane,
                new EditorRegionButton(codePane, x, y, w, h).toString()));
          }
        } else {
          codePane.insertString(codePane.getRegionString(x, y, w, h));
        }
      }
    }
  }

  class ButtonLocation extends ButtonSubregion {

    ButtonLocation() {
      super();
      promptText = "Select a Location";
      buttonText = "Location";
      iconFile = "/icons/region-icon.png";
      buttonHint = "Define a Location as center of your selection";
    }

    @Override
    public void captureComplete(ScreenImage simg) {
      int x, y, w, h;
      if (simg != null) {
        Rectangle roi = simg.getROI();
        x = (int) (roi.getX() + roi.getWidth() / 2);
        y = (int) (roi.getY() + roi.getHeight() / 2);
        getCurrentCodePane().insertString(String.format("Location(%d, %d)", x, y));
      }
    }
  }

  class ButtonOffset extends ButtonSubregion {

    ButtonOffset() {
      super();
      promptText = "Select an Offset";
      buttonText = "Offset";
      iconFile = "/icons/region-icon.png";
      buttonHint = "Define an Offset as width and height of your selection";
    }

    @Override
    public void captureComplete(ScreenImage simg) {
      int x, y, ox, oy;
      if (simg != null) {
        Rectangle roi = simg.getROI();
        ox = (int) roi.getWidth();
        oy = (int) roi.getHeight();
        Location start = simg.getStart();
        Location end = simg.getEnd();
        if (end.x < start.x) {
          ox *= -1;
        }
        if (end.y < start.y) {
          oy *= -1;
        }
        getCurrentCodePane().insertString(String.format("Offset(%d, %d)", ox, oy));
      }
    }
  }

  class ButtonShow extends ButtonOnToolbar implements ActionListener {

    String buttonText;
    String iconFile;
    String buttonHint;

    ButtonShow() {
      super();
      buttonText = "Show";
      iconFile = "/icons/region-icon.png";
      buttonHint = "Find and highlight the image in the line having the cursor";
    }

    ButtonShow init() {
      URL imageURL = SikulixIDE.class.getResource(iconFile);
      setIcon(new ImageIcon(imageURL));
      setText(buttonText);
      //setMaximumSize(new Dimension(26,26));
      setToolTipText(buttonHint);
      addActionListener(this);
      return this;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      String line = "";
      EditorPane codePane = getCurrentCodePane();
      line = codePane.getLineTextAtCaret();
      final String item = codePane.parseLineText(line);
      if (!item.isEmpty()) {
//TODO ButtonShow action performed
        SikulixIDE.doHide();
        new Thread(new Runnable() {
          @Override
          public void run() {
            String eval = "";
            eval = item.replaceAll("\"", "\\\"");
            if (item.startsWith("Region")) {
              if (item.contains(".asOffset()")) {
                eval = item.replace(".asOffset()", "");
              }
              eval = "Region.create" + eval.substring(6) + ".highlight(2);";
            } else if (item.startsWith("Location")) {
              eval = "new " + item + ".grow(10).highlight(2);";
            } else if (item.startsWith("Pattern")) {
              eval = "m = Screen.all().exists(new " + item + ", 0);";
              eval += "if (m != null) m.highlight(2);";
            } else if (item.startsWith("\"")) {
              eval = "m = Screen.all().exists(" + item + ", 0); ";
              eval += "if (m != null) m.highlight(2);";
            }
            log(4, "ButtonShow:\n%s", eval);
            SikulixIDE.doShow();
          }
        }).start();
        return;
      }
      Sikulix.popup("ButtonShow: Nothing to show!" +
          "\nThe line with the cursor should contain:" +
          "\n- an absolute Region or Location" +
          "\n- an image file name or" +
          "\n- a Pattern with an image file name");
    }
  }

  class ButtonShowIn extends ButtonSubregion {

    String item = "";

    ButtonShowIn() {
      super();
      buttonText = "Show in";
      iconFile = "/icons/region-icon.png";
      buttonHint = "Same as Show, but in the selected region";
    }

    @Override
    public boolean shouldRun() {
      log(4, "ButtonShowIn");
      EditorPane codePane = getCurrentCodePane();
      String line = codePane.getLineTextAtCaret();
      item = codePane.parseLineText(line);
      item = item.replaceAll("\"", "\\\"");
      if (item.startsWith("Pattern")) {
        item = "m = null; r = #region#; "
            + "if (r != null) m = r.exists(new " + item + ", 0); "
            + "if (m != null) m.highlight(2); else print(m);";
      } else if (item.startsWith("\"")) {
        item = "m = null; r = #region#; "
            + "if (r != null) m = r.exists(" + item + ", 0); "
            + "if (m != null) m.highlight(2); else print(m);";
      } else {
        item = "";
      }
      return !item.isEmpty();
    }

    @Override
    void nothingTodo() {
      Sikulix.popup("ButtonShowIn: Nothing to show!" +
          "\nThe line with the cursor should contain:" +
          "\n- an image file name or" +
          "\n- a Pattern with an image file name");
    }

    @Override
    public void captureComplete(ScreenImage simg) {
      if (simg != null) {
        Region reg = new Region(simg.getROI());
        String itemReg = String.format("new Region(%d, %d, %d, %d)", reg.x, reg.y, reg.w, reg.h);
        item = item.replace("#region#", itemReg);
        final String evalText = item;
        new Thread(new Runnable() {
          @Override
          public void run() {
            log(4, "ButtonShowIn:\n%s", evalText);
            //TODO ButtonShowIn perform show
          }
        }).start();
        RunTime.pause(2);
      } else {
        SikulixIDE.showAgain();
        nothingTodo();
      }
    }
  }

  //</editor-fold>

  //<editor-fold desc="21 Init Run Buttons">
  boolean trySaveScriptsBeforeRun() {
    int action;
    if (checkDirtyPanes()) {
      if (prefs.getPrefMoreRunSave()) {
        action = WARNING_ACCEPTED;
      } else {
        action = askForSaveAll("Run");
        if (action < 0) {
          return false;
        }
      }
      saveSession(action, false);
    }
    return true;
  }

  synchronized boolean isRunningScript() {
    return ideIsRunningScript;
  }

  synchronized void setIsRunningScript(boolean state) {
    ideIsRunningScript = state;
  }

  public Boolean ideIsRunningScript = false;

  public IScriptRunner getCurrentRunner() {
    return currentRunner;
  }

  public void setCurrentRunner(IScriptRunner currentRunner) {
    this.currentRunner = currentRunner;
  }

  private IScriptRunner currentRunner = null;

  public File getCurrentScript() {
    return currentScript;
  }

  public void setCurrentScript(File currentScript) {
    this.currentScript = currentScript;
  }

  private File currentScript = null;

  void addErrorMark(int line) {
    if (line < 1) {
      return;
    }
    JScrollPane scrPane = (JScrollPane) tabs.getSelectedComponent();
    EditorLineNumberView lnview = (EditorLineNumberView) (scrPane.getRowHeader().getView());
    lnview.addErrorMark(line);
    EditorPane codePane = SikulixIDE.this.getCurrentCodePane();
    codePane.jumpTo(line);
    codePane.requestFocus();
  }

  void resetErrorMark() {
    JScrollPane scrPane = (JScrollPane) tabs.getSelectedComponent();
    EditorLineNumberView lnview = (EditorLineNumberView) (scrPane.getRowHeader().getView());
    lnview.resetErrorMark();
  }

  class ButtonRun extends ButtonOnToolbar implements ActionListener {

    private Thread thread = null;

    ButtonRun() {
      super();

      URL imageURL = SikulixIDE.class.getResource("/icons/run_big_green.png");
      setIcon(new ImageIcon(imageURL));
      initTooltip();
      addActionListener(this);
      setText(_I("btnRunLabel"));
      //setMaximumSize(new Dimension(45,45));
    }

    private void initTooltip() {
      PreferencesUser pref = PreferencesUser.get();
      String strHotkey = Key.convertKeyToText(
          pref.getStopHotkey(), pref.getStopHotkeyModifiers());
      String stopHint = _I("btnRunStopHint", strHotkey);
      setToolTipText(_I("btnRun", stopHint));
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      runCurrentScript();
    }

    void runCurrentScript() {
      log(4, "************** before RunScript");
      if (!trySaveScriptsBeforeRun()) {
        log(-1, "Run script cancelled or problems saving scripts");
        return;
      }
      EditorPane editorPane = getCurrentCodePane();
      if (editorPane.getDocument().getLength() == 0) {
        log(-1, "Run script not possible: Script is empty");
        return;
      }
      File scriptFile = editorPane.getCurrentFile();
      if (editorPane.isDirty()) {
        if (editorPane.isTemp()) {
          scriptFile = editorPane.getCurrentFile();
        } else {
          scriptFile = FileManager.createTempFile(editorPane.getRunner().getDefaultExtension(),
              Commons.getIDETemp().getAbsolutePath());
        }
        if (scriptFile != null) {
          try {
            editorPane.write(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(scriptFile), "UTF8")));
          } catch (Exception ex) {
            scriptFile = null;
          }
        }
        if (scriptFile == null) {
          log(-1, "Run Script: not yet saved: temp file for running not available");
          return;
        }
      }
      final File scriptFileToRun = scriptFile;
      new Thread(new Runnable() {
        @Override
        public void run() {
          synchronized (ideIsRunningScript) {
            if (isRunningScript()) {
              log(-1, "Run Script: not possible: already running another script");
              return;
            }
            if (System.out.checkError()) {
              boolean shouldContinue = Sikulix.popAsk("System.out is broken (console output)!"
                  + "\nYou will not see any messages anymore!"
                  + "\nSave your work and restart the IDE!"
                  + "\nYou may ignore this on your own risk!" +
                  "\nYes: continue  ---  No: back to IDE", "Fatal Error");
              if (!shouldContinue) {
                log(-1, "Run script aborted: System.out is broken (console output)");
                return;
              }
              log(-1, "Run script continued, though System.out is broken (console output)");
            }
            sikulixIDE.setIsRunningScript(true);
          }

          SikulixIDE.getStatusbar().resetMessage();
          SikulixIDE.doHide();
          RunTime.pause(0.1f);
          messages.clear();
          resetErrorMark();
          doBeforeRun();

          IScriptRunner.Options runOptions = new IScriptRunner.Options();
          runOptions.setRunningInIDE();

          int exitValue = -1;
          try {
            IScriptRunner runner = editorPane.editorPaneRunner;
            setCurrentRunner(runner);
            setCurrentScript(scriptFileToRun);
            //TODO make reloadImported specific for each editor tab
            if (runner.getType().equals(JythonRunner.TYPE)) {
              JythonSupport.get().reloadImported();
            }
            exitValue = runner.runScript(scriptFileToRun.getAbsolutePath(), Commons.getUserArgs(), runOptions);
          } catch (Exception e) {
            log(-1, "Run Script: internal error:");
            e.printStackTrace();
          } finally {
            setCurrentRunner(null);
            setCurrentScript(null);
            Runner.setLastScriptRunReturnCode(exitValue);
          }

          log(4, "************** after RunScript");
          addErrorMark(runOptions.getErrorLine());
          if (Image.getIDEshouldReload()) {
            EditorPane pane = getCurrentCodePane();
            int line = pane.getLineNumberAtCaret(pane.getCaretPosition());
            getCurrentCodePane().doReparse();
            getCurrentCodePane().jumpTo(line);
          }

          Commons.cleanUpAfterScript();
          SikulixIDE.showAgain();

          synchronized (ideIsRunningScript) {
            setIsRunningScript(false);
          }

        }
      }).start();
    }

    void doBeforeRun() {
      Settings.ActionLogs = prefs.getPrefMoreLogActions();
      Settings.DebugLogs = prefs.getPrefMoreLogDebug();
      Settings.InfoLogs = prefs.getPrefMoreLogInfo();
      Settings.Highlight = prefs.getPrefMoreHighlight();
    }
  }

  class ButtonRunViz extends ButtonRun {

    ButtonRunViz() {
      super();
      URL imageURL = SikulixIDE.class.getResource("/icons/run_big_yl.png");
      setIcon(new ImageIcon(imageURL));
      setToolTipText(_I("menuRunRunAndShowActions"));
      setText(_I("btnRunSlowMotionLabel"));
    }

    @Override
    protected void doBeforeRun() {
      super.doBeforeRun();
      Settings.setShowActions(true);
    }
  }
  //</editor-fold>

  //<editor-fold desc="30 MsgArea, Statusbar">
  private void initMessageArea() {
    messageArea = new JTabbedPane();
    messages = new EditorConsolePane();
    messageArea.addTab(_I("paneMessage"), null, messages, "DoubleClick to hide/unhide");
    if (Settings.isWindows() || Settings.isLinux()) {
      messageArea.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
    }
    messageArea.addMouseListener(new MouseListener() {
      @Override
      public void mouseClicked(MouseEvent me) {
        if (me.getClickCount() < 2) {
          return;
        }
        toggleCollapsed();
      }
      //<editor-fold defaultstate="collapsed" desc="mouse events not used">

      @Override
      public void mousePressed(MouseEvent me) {
      }

      @Override
      public void mouseReleased(MouseEvent me) {
      }

      @Override
      public void mouseEntered(MouseEvent me) {
      }

      @Override
      public void mouseExited(MouseEvent me) {
      }
      //</editor-fold>
    });
  }

  private JTabbedPane messageArea;
  private EditorConsolePane messages;

  void clearMessageArea() {
    messages.clear();
  }

  void collapseMessageArea() {
    if (messageAreaCollapsed) {
      return;
    }
    toggleCollapsed();
  }

  void uncollapseMessageArea() {
    if (messageAreaCollapsed) {
      toggleCollapsed();
    }
  }

  private void toggleCollapsed() {
    if (messageAreaCollapsed) {
      mainPane.setDividerLocation(mainPane.getLastDividerLocation());
      messageAreaCollapsed = false;
    } else {
      int pos = mainPane.getWidth() - 35;
      mainPane.setDividerLocation(pos);
      messageAreaCollapsed = true;
    }
  }

  private boolean messageAreaCollapsed = false;

  private SikuliIDEStatusBar initStatusbar() {
    _status = new SikuliIDEStatusBar();
    return _status;
  }

  static SikuliIDEStatusBar getStatusbar() {
    if (sikulixIDE == null) {
      return null;
    } else {
      return sikulixIDE._status;
    }
  }

  private SikuliIDEStatusBar _status = null;

  private void initWindowListener() {
    ideWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    ideWindow.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        SikulixIDE.this.quit();
      }
    });
    ideWindow.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        PreferencesUser.get().setIdeSize(ideWindow.getSize());
      }

      @Override
      public void componentMoved(ComponentEvent e) {
        PreferencesUser.get().setIdeLocation(ideWindow.getLocation());
      }
    });
  }

  private void initTooltip() {
    ToolTipManager tm = ToolTipManager.sharedInstance();
    tm.setDismissDelay(30000);
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="25 Init ShortCuts HotKeys">
  private void nextTab() {
    int i = tabs.getSelectedIndex();
    int next = (i + 1) % tabs.getTabCount();
    tabs.setSelectedIndex(next);
  }

  private void prevTab() {
    int i = tabs.getSelectedIndex();
    int prev = (i - 1 + tabs.getTabCount()) % tabs.getTabCount();
    tabs.setSelectedIndex(prev);
  }

  private void initShortcutKeys() {
    final int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
      private boolean isKeyNextTab(java.awt.event.KeyEvent ke) {
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_TAB
            && ke.getModifiers() == InputEvent.CTRL_MASK) {
          return true;
        }
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_CLOSE_BRACKET
            && ke.getModifiers() == (InputEvent.META_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        return false;
      }

      private boolean isKeyPrevTab(java.awt.event.KeyEvent ke) {
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_TAB
            && ke.getModifiers() == (InputEvent.CTRL_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_OPEN_BRACKET
            && ke.getModifiers() == (InputEvent.META_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        return false;
      }

      public void eventDispatched(AWTEvent e) {
        java.awt.event.KeyEvent ke = (java.awt.event.KeyEvent) e;
        if (ke.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
          if (isKeyNextTab(ke)) {
            nextTab();
          } else if (isKeyPrevTab(ke)) {
            prevTab();
          }
        }
      }
    }, AWTEvent.KEY_EVENT_MASK);

  }

  void removeCaptureHotkey() {
    HotkeyManager.getInstance().removeHotkey("Capture");
  }

  boolean installCaptureHotkey() {
    return HotkeyManager.getInstance().addHotkey("Capture", new HotkeyListener() {
      @Override
      public void hotkeyPressed(HotkeyEvent e) {
        if (!isRunningScript()) {
          onQuickCapture();
        }
      }
    });
  }

  void onQuickCapture() {
    onQuickCapture(null);
  }

  void onQuickCapture(String arg) {
    if (_inited) {
      log(4, "QuickCapture");
      _btnCapture.capture(0);
    }
  }

  void removeStopHotkey() {
    HotkeyManager.getInstance().removeHotkey("Abort");
  }

  boolean installStopHotkey() {
    return HotkeyManager.getInstance().addHotkey("Abort", new HotkeyListener() {
      @Override
      public void hotkeyPressed(HotkeyEvent e) {
        onStopRunning();
      }
    });
  }

  void onStopRunning() {
    log(3, "AbortKey was pressed: aborting all running scripts");
    Runner.abortAll();
  }
  //</editor-fold>

  public static String makeScriptjar(List<String> options) {
    File fSikulixTemp = new File(Commons.getAppDataStore(), "SikulixTemp");
    FileManager.resetFolder(fSikulixTemp);
    String target = doMakeScriptjar(options, fSikulixTemp);
    FileManager.deleteFileOrFolder(fSikulixTemp);
    return target;
  }

  private static String doMakeScriptjar(List<String> options, File fSikulixTemp) {
    boolean makingScriptjarPlain = false;
    if (options.size() > 0 && "plain".equals(options.get(0))) {
      makingScriptjarPlain = true;
      options.remove(0);
    }
    if (!makingScriptjarPlain) {
      log(-1, "makingScriptJar: adding jars not implemented");
      return null;
    }

    if (options.size() == 0) {
      log(-1, "makingScriptJar: no script file given");
      return null;
    }
    File scriptFolder = new File(options.get(0)).getAbsoluteFile();
    if (!scriptFolder.exists()) {
      scriptFolder = new File(scriptFolder + ".sikuli");
      if (!scriptFolder.exists()) {
        log(-1, "makingScriptJar: script folder invalid: " + scriptFolder);
        return null;
      }
    }

    String fpScriptJar = "";
    File fScriptSource = new File(fSikulixTemp, "scriptSource");
    File fScriptCompiled = new File(fSikulixTemp, "scriptCompiled");
    File fWorkdir = scriptFolder.getParentFile();
    FileManager.FileFilter skipCompiled = new FileManager.FileFilter() {
      @Override
      public boolean accept(File entry) {
        if (entry.getName().contains("$py.class")) {
          return false;
        }
        return true;
      }
    };

    log(lvl, "makingScriptJar: compiling script folder: %s", scriptFolder);
    String scriptName = scriptFolder.getName().replace(".sikuli", "");
    fpScriptJar = scriptName + "_sikuli.jar";
    File scriptFile = new File(scriptFolder, scriptName + ".py");
    if (!scriptFile.exists()) {
      log(-1, "makingScriptJar: script folder invalid: " + scriptFolder);
      return null;
    }
    FileManager.xcopy(scriptFolder, fScriptSource, skipCompiled);
    String script = "";
    String prolog = "import org.sikuli.script.SikulixForJython\n" +
        "from sikuli import *\n" +
        "Debug.on(3)\n" +
        "for e in sys.path:\n" +
        "    print e\n" +
        "    if e.endswith(\".jar\"):\n" +
        "        jar = e\n" +
        "        break\n" +
        "ImagePath.addJar(jar, \"\")\n" +
        "import " + scriptName + "\n";
    FileManager.writeStringToFile(prolog + script, new File(fScriptSource, "__run__.py"));
    FileManager.writeStringToFile(prolog + script, new File(fScriptSource, "__main__.py"));
    script = FileManager.readFileToString(new File(fScriptSource, scriptName + ".py"));
    prolog = "from sikuli import *; addImportPath(getBundlePath())\n# coding: utf-8\n";
    FileManager.writeStringToFile(prolog + script, new File(fScriptSource, scriptName + ".py"));
    FileManager.xcopy(scriptFolder, fScriptSource, skipCompiled);

    JythonSupport.get().compileJythonFolder(fScriptSource.getAbsolutePath(), fScriptCompiled.getAbsolutePath());
    FileManager.xcopy(fScriptCompiled, fSikulixTemp);
    FileManager.deleteFileOrFolder(fScriptSource);
    FileManager.deleteFileOrFolder(fScriptCompiled);
    String[] fileList = new String[]{fSikulixTemp.getAbsolutePath()};

// non-plain jar
/*
    String[] jarsList = new String[]{null, null};
    if (!makingScriptjarPlain) {
//      File fJarRunner = new File(runTime.fSikulixExtensions, "archiv");
//      fJarRunner = new File(fJarRunner, "JarRunner.class");
//      File fJarRunnerDir = new File(fSikulixTemp, "org/python/util");
//      fJarRunnerDir.mkdirs();
//      FileManager.xcopy(fJarRunner, fJarRunnerDir);

      String manifest = "Manifest-Version: 1.0\nMain-Class: org.sikuli.script.support.SikulixRun\n";
      File fMetaInf = new File(fSikulixTemp, "META-INF");
      fMetaInf.mkdir();
      FileManager.writeStringToFile(manifest, new File(fMetaInf, "MANIFEST.MF"));
      Class<?> cSikulixRun = null;
      try {
        cSikulixRun = Class.forName("org.sikuli.script.support.SikulixRun");
      } catch (ClassNotFoundException e) {
      }
      if (null != cSikulixRun) {
        InputStream resourceAsStream = cSikulixRun.getResourceAsStream("SikulixRun.class");
        File targetFile = new File(fSikulixTemp, "org/sikuli/script/support");
        targetFile.mkdirs();
        targetFile = new File(targetFile, "SikulixRun.class");
        try {
          byte[] buffer = new byte[resourceAsStream.available()];
          resourceAsStream.read(buffer);
          OutputStream outStream = new FileOutputStream(targetFile);
          outStream.write(buffer);
          outStream.close();
        } catch (IOException e) {
          cSikulixRun = null;
        }
      }
      if (null == cSikulixRun) {
        log(-1, "makingRunnableScriptJar: problems creating main class org.sikuli.script.support.SikulixRun");
        return null;
      }
    }
*/

    String targetJar = (new File(fWorkdir, fpScriptJar)).getAbsolutePath();
    if (!FileManager.buildJar(targetJar, null, fileList, null, null)) {
      log(-1, "makingScriptJar: problems building jar - for details see logfile");
      return null;
    }
    log(lvl, "makingScriptJar: ended successfully: %s", targetJar);
    return targetJar;
  }
}
