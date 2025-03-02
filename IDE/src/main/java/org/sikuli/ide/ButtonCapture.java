/*
 * Copyright (c) 2010-2022, sikuli.org, sikulix.com - MIT license
 */
package org.sikuli.ide;

import org.sikuli.basics.Debug;
import org.sikuli.basics.FileManager;
import org.sikuli.script.support.PreferencesUser;
import org.sikuli.basics.Settings;
import org.sikuli.script.Key;
import org.sikuli.script.Screen;
import org.sikuli.script.ScreenImage;
import org.sikuli.script.Sikulix;
import org.sikuli.script.support.IScreen;
import org.sikuli.script.support.RunTime;
import org.sikuli.script.support.devices.Device;
import org.sikuli.script.support.devices.HelpDevice;
import org.sikuli.util.EventObserver;
import org.sikuli.util.EventSubject;
import org.sikuli.util.OverlayCapturePrompt;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.net.URL;

class ButtonCapture extends ButtonOnToolbar implements ActionListener, Cloneable, EventObserver {

  private static final String me = "ButtonCapture: ";
  protected Element _line;
  protected EditorPane _codePane;
  private boolean captureCancelled = false;
  private EditorPatternLabel _lbl = null;
  private String givenName = "";

  public static boolean debugTrace = true;

  public ButtonCapture() {
    super();
    //"/icons/camera-icon.png"
    URL imageURL = SikulixIDE.class.getResource("/icons/sxcapture-x.png");
    setIcon(new ImageIcon(imageURL));
    PreferencesUser pref = PreferencesUser.get();
    int key = pref.getCaptureHotkey();
    int mod = pref.getCaptureHotkeyModifiers();
    String strHotkey = Key.convertKeyToText(key, mod);
    setToolTipText(SikulixIDE._I("btnCaptureHint", strHotkey));
    setText(SikulixIDE._I("btnCaptureLabel"));
    //setBorderPainted(false);
    //setMaximumSize(new Dimension(26,26));
    addActionListener(this);
    _line = null;
  }

  public ButtonCapture(EditorPane codePane, Element elmLine) {
    this();
    _line = elmLine;
    _codePane = codePane;
    setUI(UIManager.getUI(this));
    setBorderPainted(true);
    setCursor(new Cursor(Cursor.HAND_CURSOR));
    setText(null);
    URL imageURL = SikulixIDE.class.getResource("/icons/capture-small.png");
    setIcon(new ImageIcon(imageURL));
  }

  public ButtonCapture(EditorPatternLabel lbl) {
    // for internal use with the image label __CLICK-TO-CAPTURE__
    super();
    _line = null;
    _codePane = null;
    _lbl = lbl;
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    Debug.log(3, "ButtonCapture: capture started");
    captureWithAutoDelay();
  }

  public void captureWithAutoDelay() {
    PreferencesUser pref = PreferencesUser.get();
    int delay = (int) (pref.getCaptureDelay() * 1000.0) + 1;
    capture(delay);
  }

  IScreen defaultScreen = null;
  ScreenImage sImgNonLocal = null;

  public void capture(int delay) {
    defaultScreen = SikulixIDE.getDefaultScreen();
    if (defaultScreen == null) {
      if (Device.isCaptureBlocked()) {
        Debug.error("FATAL: Capture is blocked");
        return;
      }
    }
    String line = "";
    SikulixIDE ide = SikulixIDE.get();
    if (SikulixIDE.notHidden()) {
      // Set minimum delay if IDE is visible to give
      // the IDE some time to vanish before taking the
      // screenshot. IDE might already be hidden in
      // in case of capture hot key.
      delay = Math.max(delay, 500);
      SikulixIDE.doHide();
    }
    EditorPane codePane = ide.getCurrentCodePane();
    line = codePane.getLineTextAtCaret();
    givenName = codePane.parseLineText("#" + line.trim());
    if (!givenName.isEmpty()) {
      Debug.log(3, "ButtonCapture: doPrompt for %s", givenName);
    }
    RunTime.pause(((float) delay)/1000);
    if (defaultScreen == null) {
      Screen.doPrompt("Select an image", this); // ButtonCapture
    } else {
      if (HelpDevice.isAndroid(defaultScreen) && Sikulix.popAsk("Android capture")) {
        new Thread() {
          @Override
          public void run() {
            sImgNonLocal = (ScreenImage) defaultScreen.action("userCapture");
            ButtonCapture.this.update((EventSubject) null);
          }
        }.start();
      } else {
        ButtonCapture.this.update((EventSubject) null);
      }
    }
  }

  @Override
  public void update(EventSubject event) {
    Debug.log(3, "ButtonCapture: finished");
    ScreenImage simg = null;
    OverlayCapturePrompt ocp = null;
    if (null == event) {
      simg = sImgNonLocal;
    } else {
      ocp = (OverlayCapturePrompt) event;
      simg = ocp.getSelection();
      Screen.closePrompt();
    }
    String filename = null;
    String fullpath = null;
    boolean saveOverwrite = Settings.OverwriteImages;
    if (simg != null) {
      if (!givenName.isEmpty()) {
        filename = givenName + ".png";
        Settings.OverwriteImages = true;
      } else {
        int naming = PreferencesUser.get().getAutoNamingMethod();
        if (naming == PreferencesUser.AUTO_NAMING_TIMESTAMP) {
          filename = Settings.getTimestamp();
        } else if (naming == PreferencesUser.AUTO_NAMING_OCR) {
          filename = PatternPaneNaming.getFilenameFromImage(simg.getImage());
          if (filename == null || filename.length() == 0) {
            filename = Settings.getTimestamp();
          }
        } else {
          String nameOCR = "";
          try {
            nameOCR = PatternPaneNaming.getFilenameFromImage(simg.getImage());
          } catch (Exception e) {
          }
          filename = getFilenameFromUser(nameOCR);
        }
      }

      if (filename != null) {
        fullpath = FileManager.saveImage(simg.getImage(), filename, SikulixIDE.get().getCurrentCodePane().getImagePath());
        if (fullpath != null) {
          fullpath = FileManager.slashify(fullpath, false);
        }
      }
    }
    Settings.OverwriteImages = saveOverwrite;
    captureCompleted(fullpath);
    if (ocp != null) {
      Screen.resetPrompt(ocp);
    }
    SikulixIDE.showAgain();
  }

  public void captureCompleted(String imgFullPath) {
    Element src = getSrcElement();
    if (imgFullPath != null) {
      Debug.log(3, "captureCompleted: " + imgFullPath);
      if (src == null) {
        if (_codePane == null) {
          if (_lbl == null) {
            insertAtCursor(SikulixIDE.get().getCurrentCodePane(), imgFullPath);
          } else {
            _lbl.setFile(imgFullPath);
          }
        } else {
          insertAtCursor(_codePane, imgFullPath);
        }
      } else {
        replaceButton(src, imgFullPath);
      }
    } else {
      Debug.log(3, "ButtonCapture: Capture cancelled");
      if (src != null) {
        captureCancelled = true;
        replaceButton(src, "");
        captureCancelled = false;
      }
    }
  }

  //<editor-fold defaultstate="collapsed" desc="RaiMan not used">
	/*public boolean hasNext() {
   * return false;
   * }*/
  /*public CaptureButton getNextDiffButton() {
   * return null;
   * }*/
  /*public void setParentPane(SikuliPane parent) {
   * _codePane = parent;
   * }*/
  /*public void setDiffMode(boolean flag) {
   * }*/
  /*public void setSrcElement(Element elmLine) {
   * _line = elmLine;
   * }*/
  //</editor-fold>

  private String getFilenameFromUser(String hint) {
    return (String) JOptionPane.showInputDialog(
            _codePane,
            SikuliIDEI18N._I("msgEnterScreenshotFilename"),
            SikuliIDEI18N._I("dlgEnterScreenshotFilename"),
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            hint);
  }

  private Element getSrcElement() {
    return _line;
  }

  private boolean replaceButton(Element src, String imgFullPath) {
    if (captureCancelled) {
      if (_codePane.showThumbs && PreferencesUser.get().getPrefMoreImageThumbs()
              || !_codePane.showThumbs) {
        return true;
      }
    }
    int start = src.getStartOffset();
    int end = src.getEndOffset();
    int old_sel_start = _codePane.getSelectionStart(),
            old_sel_end = _codePane.getSelectionEnd();
    try {
      StyledDocument doc = (StyledDocument) src.getDocument();
      String text = doc.getText(start, end - start);
      Debug.log(3, text);
      for (int i = start; i < end; i++) {
        Element elm = doc.getCharacterElement(i);
        if (elm.getName().equals(StyleConstants.ComponentElementName)) {
          AttributeSet attr = elm.getAttributes();
          Component com = StyleConstants.getComponent(attr);
          boolean isButton = com instanceof ButtonCapture;
          boolean isLabel = com instanceof EditorPatternLabel;
          if (isButton || isLabel && ((EditorPatternLabel) com).isCaptureButton()) {
            Debug.log(5, "button is at " + i);
            int oldCaretPos = _codePane.getCaretPosition();
            _codePane.select(i, i + 1);
            if (!_codePane.showThumbs) {
              _codePane.insertString((new EditorPatternLabel(_codePane, imgFullPath, true)).toString());
            } else {
              if (PreferencesUser.get().getPrefMoreImageThumbs()) {
                com = new EditorPatternButton(_codePane, imgFullPath);
              } else {
                if (captureCancelled) {
                  com = new EditorPatternLabel(_codePane, "");
                } else {
                  com = new EditorPatternLabel(_codePane, imgFullPath, true);
                }
              }
              _codePane.insertComponent(com);
            }
            _codePane.setCaretPosition(oldCaretPos);
            break;
          }
        }
      }
    } catch (BadLocationException ble) {
      Debug.error(me + "Problem inserting Button!\n%s", ble.getMessage());
    }
    _codePane.select(old_sel_start, old_sel_end);
    _codePane.requestFocus();
    return true;
  }

  protected void insertAtCursor(EditorPane pane, String imgFilename) {
    String img = "\"" + (new File(imgFilename)).getName() + "\"";
    if (!pane.showThumbs) {
      pane.insertString(img);
    } else {
      if (PreferencesUser.get().getPrefMoreImageThumbs()) {
        EditorPatternButton comp = EditorPatternButton.createFromFilename(pane, imgFilename, null);
        if (comp != null) {
          pane.insertComponent(comp);
        }
      } else {
        EditorPatternLabel label = new EditorPatternLabel(pane, imgFilename, true);
        pane.insertComponent(label);
      }
    }
//TODO set Caret
    pane.requestFocus();
  }

  @Override
  public String toString() {
    return "\"__CLICK-TO-CAPTURE__\"";
  }
}
