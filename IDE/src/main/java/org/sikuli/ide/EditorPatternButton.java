/*
 * Copyright (c) 2010-2022, sikuli.org, sikulix.com - MIT license
 */
package org.sikuli.ide;

import org.sikuli.basics.Debug;
import org.sikuli.basics.FileManager;
import org.sikuli.script.support.PreferencesUser;
import org.sikuli.script.Image;
import org.sikuli.script.Location;
import org.sikuli.util.EventObserver;
import org.sikuli.util.EventSubject;
import org.sikuli.util.PreviewWindow;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

class EditorPatternButton extends JButton implements ActionListener, Serializable, MouseListener, EventObserver {

    static final int DEFAULT_SIMILARITY = 70;
    static double defaultSimilarity() {
        return (double) DEFAULT_SIMILARITY / 100;
    }
    protected static boolean isDefaultSimilarity(double similarity) {
        return Math.round(similarity * 100) == DEFAULT_SIMILARITY;
    }

    private String _imgFilename, _thumbFname, _imgFilenameSaved;
    private Image _image;
    private JLabel patternImageIcon = null;
    private EditorPane _pane;
    private double _similarity, _similaritySaved;
    private float _resizeFactor;
    private String _mask;
//    private int _numMatches = DEFAULT_NUM_MATCHES;
    private boolean _exact, _exactSaved;
    private Location _offset = new Location(0, 0), _offsetSaved;
    private int _imgW, _imgH;
    private float _scale = 1f;
    private static PatternWindow pwin = null;
    private static Font textFont = new Font("arial", Font.BOLD, 12);
    private Color oldC;
    private String buttonFname = "";
    private String buttonSimilar = "";
    private String buttonOffset = "";
    private EditorPatternLabel _lbl;

    protected EditorPatternButton(EditorPane pane) {
        this._image = null;
        init(pane, null, null);
    }

    public EditorPatternButton(EditorPane pane, String imgFilename) {
        this._image = null;
        init(pane, imgFilename, null);
    }

    private EditorPatternButton(EditorPane pane, Image img) {
        this._image = null;
        init(pane, null, img);
    }

    protected EditorPatternButton(EditorPatternLabel lbl) {
        super();
        this._image = null;
        _lbl = lbl;
        _imgFilename = _lbl.getFile();
        _exact = false;
        _similarity = _lbl.getSimilarity();
        _resizeFactor = _lbl.getResizeFactor();
        _offset = _lbl.getTargetOffset();
        //_numMatches = DEFAULT_NUM_MATCHES;
        _pane = _lbl.getPane();
    }

    private void init(EditorPane pane, String imgFilename, Image img) {
        //TODO thumbMax = PreferencesUser.getInstance().getDefaultThumbHeight() == 0 ? false : true;
        _pane = pane;
        _exact = false;
        _similarity = defaultSimilarity();
        //_numMatches = DEFAULT_NUM_MATCHES;
        if (imgFilename != null) {
            setFilename(imgFilename);
        } else if (img != null) {
            setFilename(img);
        }
        setMargin(new Insets(0, 0, 0, 0));
        setBorderPainted(true);
        setCursor(new Cursor(Cursor.HAND_CURSOR));
        addActionListener(this);
        setButtonText();
    }

    public BufferedImage createThumbnailImage(int maxHeight) {
        return createThumbnailImage(_imgFilename, maxHeight);
    }

    public static EditorPatternButton createFromFilename(EditorPane parentPane, String str, EditorPatternLabel lbl) {
        return createFromString(parentPane, "\"" + str + "\"", lbl);
    }

    public static EditorPatternButton createFromString(EditorPane parentPane, String str, EditorPatternLabel lbl) {
        if (!str.startsWith("Pattern")) {
            String possibleFileName = str.substring(1, str.length() - 1);
            Image img = Image.createThumbNail(possibleFileName);
            if (img.isValid() && img.isBundled()) {
                return new EditorPatternButton(parentPane, img);
            }
            return null;
        }
        EditorPatternButton btn = new EditorPatternButton(parentPane);
        String[] tokens = str.split("\\)\\s*\\.?");
        for (String tok : tokens) {
            //System.out.println("token: " + tok);
            if (tok.startsWith("exact")) {
                btn.setExact(true);
                btn.setSimilarity(0.99f);
            } else if (tok.startsWith("Pattern")) {
                String filename = FileManager.slashify(tok.substring(
                        tok.indexOf("\"") + 1, tok.lastIndexOf("\"")), false);
                Image img = Image.createThumbNail(filename);
                if (img.isValid() && img.isBundled()) {
                    btn.setFilename(img);
                } else {
                    return null;
                }
            } else if (tok.startsWith("similar")) {
                String strArg = tok.substring(tok.lastIndexOf("(") + 1);
                try {
                    btn.setSimilarity(Float.valueOf(strArg));
                } catch (NumberFormatException e) {
                    return null;
                }
//TODO max matches no longer needed
//           }
//            else if (tok.startsWith("firstN")) { // FIXME: replace with limit/max
//                String strArg = tok.substring(tok.lastIndexOf("(") + 1);
//                btn._numMatches = Integer.valueOf(strArg);
            } else if (tok.startsWith("targetOffset")) {
                String strArg = tok.substring(tok.lastIndexOf("(") + 1);
                String[] args = strArg.split(",");
                try {
                    Location offset = new Location(0, 0);
                    offset.x = Integer.valueOf(args[0]);
                    offset.y = Integer.valueOf(args[1]);
                    btn.setTargetOffset(offset);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (tok.startsWith("resize")) {
                String strArg = tok.substring(tok.lastIndexOf("(") + 1);
                float rf;
                try {
                    rf = Float.valueOf(strArg);
                } catch (NumberFormatException e) {
                    rf = 0;
                }
                btn.setResizeFactor(rf);
            } else if (tok.toLowerCase().contains("mask")) {
                btn.setMask(tok);
            }
        }
        btn.setButtonText();
        return btn;
    }

	boolean usePreWin = false;
	PreviewWindow preWin = null;

    @Override
    public void actionPerformed(ActionEvent e) {
        Debug.log(4, "ThumbButtonLabel: open Pattern Settings");
        _pane.saveCaretPosition();
        if (usePreWin) {
            Map<String, Object> options = new HashMap<>();
            options.put("caller", this);
            preWin = new PreviewWindow(options);
        } else {
            if (pwin == null) {
                _offsetSaved = new Location(_offset);
                _similaritySaved = _similarity;
                _exactSaved = _similarity >= 0.99f;
                _imgFilenameSaved = _imgFilename.substring(0);
                pwin = new PatternWindow(this, _exactSaved, _similarity, _offset);
            }
            pwin.requestFocus();
        }
    }

    @Override
    public Point getLocationOnScreen() {
        if (_lbl == null) {
            return super.getLocationOnScreen();
        } else {
            return _lbl.getLocationOnScreen();
        }
    }

    @Override
    public void mouseEntered(MouseEvent me) {
    }

    @Override
    public void mouseExited(MouseEvent me) {
    }

    public PatternWindow getWindow() {
        return pwin;
    }

    public void resetWindow() {
        pwin = null;
    }

    public String getFilename() {
        File img = new File(_imgFilename);
        String oldBundle = img.getParent();
        String newBundle = _pane.getImagePath();
        Debug.log(4, "ImageButton.getFilename: old: " + oldBundle + "\nnew: " + newBundle);
        if (FileManager.pathEquals(newBundle, oldBundle)) {
            return _imgFilename;
        }
        setFilename(new File(newBundle, img.getName()).getAbsolutePath());
        return _imgFilename;
    }

    public void setFilename(String fileName) {
        _image = Image.createThumbNail(fileName);
        _imgFilename = _image.getFilename();
        setIcon(new ImageIcon(createThumbnailImage(_imgFilename, PreferencesUser.get().getDefaultThumbHeight())));
        setButtonText();
    }

    private void setFilename(Image img) {
        _image = img;
        _imgFilename = _image.getFilename();
        setIcon(new ImageIcon(createThumbnailImage(_imgFilename, PreferencesUser.get().getDefaultThumbHeight())));
        setButtonText();
    }

    public void setImage(String fileName) {
        _image = Image.createSilent(fileName);
        _imgFilename = _image.getFilename();
        setIcon(new ImageIcon(createThumbnailImage(_image, PreferencesUser.get().getDefaultThumbHeight())));
        setButtonText();
    }
/*
  private String createThumbnail(String imgFname) {
    return createThumbnail(imgFname, PreferencesUser.get().getDefaultThumbHeight());
  }

	private String createThumbnail(String imgFname, int maxHeight) {
		BufferedImage thumb = createThumbnailImage(imgFname, maxHeight);
		return FileManager.saveTmpImage(thumb);
	}
*/

    private BufferedImage createThumbnailImage(String imgFname, int maxHeight) {
        try {
            BufferedImage img;
            if (_image != null) {
                img = _image.get();
            } else {
//TODO Debug.error("EditorPatternButton: createThumbnailImage: not using Image for: " + imgFname);
                img = ImageIO.read(new File(imgFname));
            }
            int w = img.getWidth(null), h = img.getHeight(null);
            _imgW = w;
            _imgH = h;
            if (maxHeight == 0 || maxHeight >= h) {
                return img;
            }
            _scale = (float) maxHeight / h;
            w *= _scale;
            h *= _scale;
            h = (int) h;
            BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumb.createGraphics();
            g2d.drawImage(img, 0, 0, w, h, null);
            g2d.dispose();
            return thumb;
        } catch (IOException e) {
            Debug.error("Can't read file: " + e.getMessage());
            return null;
        }
    }

    private BufferedImage createThumbnailImage(Image image, int maxHeight) {
        BufferedImage img = image.get();
        int w = img.getWidth(null), h = img.getHeight(null);
        _imgW = w;
        _imgH = h;
        if (maxHeight == 0 || maxHeight >= h) {
            return img;
        }
        _scale = (float) maxHeight / h;
        w *= _scale;
        h *= _scale;
        h = (int) h;
        BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumb.createGraphics();
        g2d.drawImage(img, 0, 0, w, h, null);
        g2d.dispose();
        return thumb;
    }

    public boolean setParameters(boolean exact, double similarity) {
        boolean dirty = false;
        Debug.log(4, "ThumbButtonLabel: setParameters: " + exact + "," + similarity);
        dirty |= setExact(exact);
        dirty |= setSimilarity(similarity);
        setButtonText();
        return dirty;
    }

    public void resetParameters() {
        setFilename(_imgFilenameSaved);
        setParameters(_exactSaved, _similaritySaved);
        setTargetOffset(_offsetSaved);
    }

    public boolean setExact(boolean exact) {
        if (_exact != exact) {
            _exact = exact;
            return true;
        }
        return false;
    }

    public boolean setSimilarity(double val) {
        double sim;
        if (val < 0) {
            sim = 0;
        } else if (val >= 1) {
            sim = 0.99;
        } else {
            sim = val;
        }
        if (sim != _similarity) {
            _similarity = sim;
            return true;
        }
        return false;
    }

    public double getSimilarity() {
        return _similarity;
    }

    public void setResizeFactor(float factor) {
        _resizeFactor = factor;
    }

    public void setMask(String mask) {
        _mask = mask;
    }

    public boolean setTargetOffset(Location offset) {
        Debug.log(4, "ThumbButtonLabel: setTargetOffset: " + offset.toStringShort());
        if (!_offset.equals(offset)) {
            _offset = offset;
            setButtonText();
            return true;
        }
        return false;
    }

    public Location getTargetOffset() {
        return _offset;
    }

    public String getFileName() {
        return _imgFilename;
    }

    //<editor-fold defaultstate="collapsed" desc="paint button">
    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g;
        drawDecoration(g2d);
        if (useThumbnail()) {
            g2d.setColor(new Color(0, 128, 128, 128));
            g2d.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 5, 5);
        }
    }

    private void drawDecoration(Graphics2D g2d) {
        String strSim = null, strOffset = null;
        if (!isDefaultSimilarity(_similarity)
                || (_resizeFactor > 0 && _resizeFactor != 1)
                || (null != _mask && !_mask.isEmpty())) {
            if (_exact) {
                strSim = "99";
            } else {
                strSim = String.format("%d", (int) (_similarity * 100));
            }
            if (_resizeFactor > 0 && _resizeFactor != 1) {
                strSim += " +";
            }
            if (null != _mask && !_mask.isEmpty()) {
                strSim += " M";
            }
        }
        if (_offset != null && (_offset.x != 0 || _offset.y != 0)) {
            strOffset = _offset.toStringShort();
        }
        if (strOffset == null && strSim == null) {
            return;
        }

        final int fontH = g2d.getFontMetrics().getMaxAscent();
        final int x = getWidth(), y = 0;
        drawSimBadge(g2d, strSim, x, y);
        if (_offset != null) {
            drawCross(g2d);
        }
    }

    private void drawSimBadge(Graphics2D g2d, String strSim, int x, int y) {
        if (strSim == null) {
            return;
        }
        final int w = g2d.getFontMetrics().stringWidth(strSim);
        final int fontH = g2d.getFontMetrics().getMaxAscent();
        final int borderW = 3;
        g2d.setFont(textFont);
        Color simBack = new Color(0, 128, 0, 128);
//    if (_resizeFactor > 0 && _resizeFactor != 1) {
//      simBack = new Color(128, 0, 0, 128);
//    }
        g2d.setColor(simBack);
        g2d.fillRoundRect(x - borderW * 2 - w - 1, y, w + borderW * 2 + 1, fontH + borderW * 2 + 1, 3, 3);
        g2d.setColor(Color.white);
        g2d.drawString(strSim, x - w - 3, y + fontH + 3);
    }

    private void drawCross(Graphics2D g2d) {
        int drawX, drawY;
        final String cross = "+";
        g2d.setFont(new Font("arial", Font.PLAIN, 24));
        Rectangle2D crossBounds = g2d.getFontMetrics().getStringBounds("+", g2d);
        final int crossW = (int) crossBounds.getWidth();
        final int crossH = (int) crossBounds.getHeight();
        if (_offset.x > _imgW / 2) {
            drawX = getWidth() - crossW;
        } else if (_offset.x < -_imgW / 2) {
            drawX = 0;
        } else {
            drawX = (int) (getWidth() / 2 + _offset.x * _scale - crossW / 2);
        }
        if (_offset.y > _imgH / 2) {
            drawY = getHeight() + crossH / 2 - 3;
        } else if (_offset.y < -_imgH / 2) {
            drawY = crossH / 2 + 2;
        } else {
            drawY = (int) (getHeight() / 2 + _offset.y * _scale + crossH / 2);
            drawY -= 4;
        }
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.drawString(cross, drawX + 1, drawY + 1);
        g2d.setColor(new Color(255, 0, 0, 180));
        g2d.drawString(cross, drawX, drawY);
    }

    private boolean useThumbnail() {
        return !_imgFilename.equals(_thumbFname);
    }
    //</editor-fold>

    @Override
    public String toString() {
        return _pane.getPatternString(_imgFilename, _similarity, _offset, _image, _resizeFactor, _mask);
    }

    private void setButtonText() {
        if (_lbl == null) {
            setToolTipText(toString());
        } else {
            _lbl.resetLabel(_imgFilename, _similarity, _offset, _resizeFactor);
        }
    }

    //<editor-fold defaultstate="collapsed" desc="mouse events not used">
    @Override
    public void mouseClicked(MouseEvent me) {
    }

    @Override
    public void mousePressed(MouseEvent me) {
    }

    @Override
    public void mouseReleased(MouseEvent me) {
    }

    @Override
    public void update(EventSubject s) {
        SikulixIDE.get().setVisible(true);
    }
    //</editor-fold>
}
