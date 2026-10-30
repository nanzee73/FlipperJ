package de.flipperj.gui;

import de.c64gfx.dither.Dither;
import de.c64gfx.swing.FileDialogs;
import de.c64gfx.swing.Icons;
import de.c64gfx.swing.ImagePanel;
import de.c64gfx.swing.PaletteEditorDialog;
import de.c64gfx.swing.Platform;
import de.c64gfx.swing.SliderControls;
import de.c64gfx.swing.WrapLayout;

import de.flipperj.App;
import de.c64gfx.color.ColorMatcher;
import de.flipperj.convert.*;
import de.c64gfx.swing.I18n;
import de.c64gfx.swing.ImageAdjust;
import de.c64gfx.swing.ImageFitter;
import de.flipperj.output.PrgBuilder;
import de.flipperj.output.RawExporter;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.c64gfx.swing.I18n.tr;

/** Main window, laid out after Mufflon: settings on top, previews (pre-convert / result) below. */
public class MainWindow extends JFrame {
    // mode
    private final JRadioButton[] modeButtons = new JRadioButton[FliMode.values().length];

    // files
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JCheckBox writePrg = I18n.text(new JCheckBox("", true), "file.writePrg");
    private final JCheckBox writeRaw = I18n.text(new JCheckBox("", false), "file.writeRaw");
    /** Output folder chosen by the user; null = next to the input picture. */
    private File outputDirChosen;

    // source palette / matching
    private final DefaultComboBoxModel<Palette> sourceModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<Palette> destModel = new DefaultComboBoxModel<>();
    private final JComboBox<Palette> sourcePalette = new JComboBox<>(sourceModel);
    private final JComboBox<Palette> destPalette = new JComboBox<>(destModel);
    private final JRadioButton spaceRgb = new JRadioButton("RGB");
    private final JRadioButton spaceYuv = new JRadioButton("YUV", true);
    private final JRadioButton spaceHsl = new JRadioButton("HSL");
    private final JCheckBox prepare = I18n.text(new JCheckBox("", true), "src.prepare");
    private final JCheckBox respectLuma = I18n.text(new JCheckBox(), "src.luma");
    private final JSlider weightU = slider(0, 100, 25), weightV = slider(0, 100, 50);
    private final JSlider contrast = slider(-100, 100, 0), brightness = slider(-100, 100, 0);
    private final JSlider gamma = slider(10, 300, 100), saturation = slider(0, 200, 100);

    // dithering
    private final JComboBox<Dither> ditherBox = new JComboBox<>(Dither.values());
    private final JSlider strength = slider(0, 100, 80);
    private final JCheckBox bleeding = I18n.text(new JCheckBox("", true), "dit.bleeding");
    private final JCheckBox serpentine = I18n.text(new JCheckBox(), "dit.serpentine");
    private final JSpinner passes = new JSpinner(new SpinnerNumberModel(2, 1, 5, 1));
    /** -1 = automatic, 0..15 = fixed background color. */
    private final JComboBox<Integer> backgroundBox = new JComboBox<>();

    // destination
    private final JCheckBox renderBug = I18n.text(new JCheckBox("", true), "dst.renderBug");
    private final JCheckBox palAspect = I18n.text(new JCheckBox("", true), "dst.palAspect");

    // previews
    private final ImagePanel leftPanel = new ImagePanel(), rightPanel = new ImagePanel();
    private final JRadioButton showOriginal = I18n.text(new JRadioButton(), "view.original");
    private final JRadioButton showPre = I18n.text(new JRadioButton("", true), "view.pre");
    private final JRadioButton showResult = I18n.text(new JRadioButton("", true), "view.result");
    private final JRadioButton showError = I18n.text(new JRadioButton(), "view.error");
    private final JCheckBox linkViews = I18n.tip(I18n.text(new JCheckBox("", true), "view.link"), "view.link.tip");
    private final JCheckBox showGrid = I18n.tip(I18n.text(new JCheckBox("", true), "view.grid"), "view.grid.tip");

    private final JButton convertButton = new JButton();
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    /** Produces the current status text, so it can be re-translated after a language switch. */
    private java.util.function.Supplier<String> statusText = () -> " ";

    /** Persistent user settings (options dialog). */
    private Settings settings = Settings.load();

    // state
    private BufferedImage source;
    private BufferedImage fitted;
    private ImageFitter.Settings fitSettings = ImageFitter.Settings.defaults();
    private ImageFitter.Settings reframeBackup;
    private float[] preConverted;
    private ConversionResult result;
    private SwingWorker<ConversionResult, Double> worker;
    private final AtomicBoolean cancel = new AtomicBoolean();
    /** Re-fits the picture shortly after the crop frame was changed in the preview. */
    private final Timer fitTimer = new Timer(120, e -> applyFit());

    // live preview
    private final JCheckBox livePreview = I18n.tip(I18n.text(new JCheckBox("", true), "bottom.live"), "bottom.live.tip");
    private final Timer liveTimer = new Timer(350, e -> runLivePreview());
    private AtomicBoolean liveCancel = new AtomicBoolean();
    /** Incremented on every change that affects the conversion. */
    private long settingsGeneration;
    /** Generation the current result was computed for. */
    private long resultGeneration = -1;

    public MainWindow() {
        I18n.bind(() -> setTitle(tr("app.title", App.VERSION)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(Icons.appIcons());
        for (Palette p : Palettes.builtIns()) { sourceModel.addElement(p); destModel.addElement(p); }
        for (int i = -1; i < 16; i++) backgroundBox.addItem(i);
        backgroundBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                int v = value instanceof Integer i ? i : -1;
                String text = v < 0 ? tr("dit.bgAuto") : v + " " + tr("color." + v);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        ditherBox.setRenderer(I18n.enumRenderer("dither"));
        ditherBox.setSelectedItem(Dither.FLOYD_STEINBERG);
        fitTimer.setRepeats(false);
        liveTimer.setRepeats(false);

        setJMenuBar(buildMenu());
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(buildModePanel());
        north.add(buildFilePanel());
        north.add(buildSettingsPanel());
        root.add(north, BorderLayout.NORTH);
        root.add(buildPreviewPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);
        setContentPane(root);
        installDragAndDrop(root);
        wireListeners();
        I18n.bind(() -> status.setText(statusText.get()));
        I18n.bind(() -> leftPanel.setPlaceholder(tr("preview.placeholder.left")));
        I18n.bind(() -> rightPanel.setPlaceholder(tr("preview.placeholder.right")));
        I18n.tip(leftPanel, "preview.left.tip");
        I18n.tip(rightPanel, "preview.tip");
        leftPanel.setRightClickAction(this::startReframe);
        applySettings();
        pack();
        setMinimumSize(new Dimension(1000, 760));
        setLocationRelativeTo(null);
    }

    private static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setPreferredSize(new Dimension(120, s.getPreferredSize().height));
        return s;
    }

    // ------------------------------------------------------------------ UI construction

    private JMenuBar buildMenu() {
        int mask = Platform.menuShortcutMask();
        JMenuBar bar = new JMenuBar();
        JMenu file = I18n.text(new JMenu(), "menu.file");
        JMenuItem open = I18n.text(new JMenuItem(), "menu.file.open");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, mask));
        open.addActionListener(e -> chooseInput());
        JMenuItem crop = I18n.text(new JMenuItem(), "menu.file.crop");
        crop.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, mask));
        crop.addActionListener(e -> openCropDialog());
        JMenuItem reframe = I18n.text(new JMenuItem(), "menu.file.reframe");
        reframe.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, mask));
        reframe.addActionListener(e -> startReframe());
        JMenuItem convert = I18n.text(new JMenuItem(), "bottom.convert");
        convert.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask));
        convert.addActionListener(e -> convertOrCancel());
        file.add(open);
        file.add(crop);
        file.add(reframe);
        file.addSeparator();
        file.add(convert);
        if (!Platform.IS_MAC) {
            // on macOS, Settings and Quit live in the application menu (see App)
            JMenuItem options = I18n.text(new JMenuItem(), "menu.file.settings");
            options.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, mask));
            options.addActionListener(e -> showOptions());
            JMenuItem exit = I18n.text(new JMenuItem(), "menu.file.exit");
            exit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask));
            exit.addActionListener(e -> dispose());
            file.addSeparator();
            file.add(options);
            file.addSeparator();
            file.add(exit);
        }

        JMenu language = I18n.text(new JMenu(), "menu.language");
        ButtonGroup lg = new ButtonGroup();
        JRadioButtonMenuItem system = new JRadioButtonMenuItem();
        I18n.bind(() -> system.setText(tr("opt.languageSystem", I18n.nativeName(I18n.systemLocale()))));
        system.addActionListener(e -> useSystemLanguage());
        lg.add(system);
        language.add(system);
        language.addSeparator();
        java.util.Map<Locale, JRadioButtonMenuItem> items = new java.util.HashMap<>();
        for (Locale l : I18n.supportedLocales()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(I18n.nativeName(l));
            item.addActionListener(e -> switchLanguage(l));
            lg.add(item);
            language.add(item);
            items.put(l, item);
        }
        // keep the radio selection in sync, also when the language is changed in the settings dialog
        I18n.bind(() -> (I18n.followsSystem() ? system : items.get(I18n.locale())).setSelected(true));

        bar.add(file);
        bar.add(language);
        JMenu help = I18n.text(new JMenu(), "menu.help");
        JMenuItem test = I18n.text(new JMenuItem(), "menu.help.testPicture");
        test.addActionListener(e -> writeTestPicture());
        help.add(test);
        if (!Platform.IS_MAC) {                 // on macOS "About" sits in the application menu
            JMenuItem about = I18n.text(new JMenuItem(), "menu.help.about");
            about.addActionListener(e -> showAbout());
            help.addSeparator();
            help.add(about);
        }
        bar.add(help);
        return bar;
    }

    /** Writes the FLI test picture (.prg) and the expected screen (PNG) to check the displayer in VICE. */
    private void writeTestPicture() {
        File f = FileDialogs.save(this, "dlg.testPicture.title", FileDialogs.lastDir("output"), "fli-test.prg",
                "filter.prg", "prg");
        if (f == null) return;
        try {
            ConversionResult r = de.flipperj.output.TestPicture.result();
            java.nio.file.Files.write(f.toPath(), PrgBuilder.build(r));
            File png = new File(f.getParentFile(), f.getName().replaceFirst("\\.[^.]*$", "") + "_expected.png");
            ImageIO.write(r.render((Palette) sourcePalette.getSelectedItem()), "png", png);
            setStatus("status.testPicture", f.getName(), png.getName());
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    /** About box (also used by the macOS application menu). */
    public void showAbout() {
        JOptionPane.showMessageDialog(this, tr("about.text", App.VERSION), tr("about.title"),
                JOptionPane.INFORMATION_MESSAGE, aboutIcon());
    }

    /** Settings dialog (also used by the macOS application menu, ⌘,). */
    public void showOptions() {
        OptionsDialog d = new OptionsDialog(this, settings);
        d.setVisible(true);
        if (!d.isAccepted()) return;
        settings = d.getSettings();
        settings.save();
        applySettings();
        Locale l = d.getLanguage();
        if (l == null) {
            if (!I18n.followsSystem()) useSystemLanguage();
        } else if (I18n.followsSystem() || !l.equals(I18n.locale())) {
            switchLanguage(l);
        }
    }

    /** Applies the persistent settings to the controls they preset. */
    private void applySettings() {
        livePreview.setSelected(settings.livePreview);
        showGrid.setSelected(settings.pixelGrid);
        leftPanel.setShowGrid(settings.pixelGrid);
        rightPanel.setShowGrid(settings.pixelGrid);
        linkViews.setSelected(settings.linkPreviews);
        palAspect.setSelected(settings.palAspect);
        writePrg.setSelected(settings.writePrg);
        writeRaw.setSelected(settings.writeRaw);
        updateAspect();
    }

    private void useSystemLanguage() {
        I18n.useSystemLocale();
        refreshUi();
    }

    private void switchLanguage(Locale l) {
        I18n.setLocale(l);
        refreshUi();
    }

    private void refreshUi() {
        // refresh Swing's own texts and all cached sizes (combo boxes, labels)
        SwingUtilities.updateComponentTreeUI(this);
        updateAspect();
        revalidate();
        repaint();
    }

    private JPanel buildModePanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setBorder(BorderFactory.createEtchedBorder());
        ButtonGroup g = new ButtonGroup();
        FliMode[] modes = FliMode.values();
        for (int i = 0; i < modes.length; i++) {
            JRadioButton b = new JRadioButton("", i == 0);
            FliMode m = modes[i];
            I18n.bind(() -> b.setText(m.toString()));
            modeButtons[i] = b;
            g.add(b);
            p.add(b);
        }
        for (String planned : new String[]{"NUFLI", "MUIFLI"}) {
            JRadioButton b = new JRadioButton(planned);
            b.setEnabled(false);
            I18n.tip(b, "mode.planned");
            p.add(b);
        }
        return p;
    }

    private JPanel buildFilePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEtchedBorder());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        JButton selIn = I18n.text(new JButton(), "file.select"), selOut = I18n.text(new JButton(), "file.select");
        selIn.addActionListener(e -> chooseInput());
        selOut.addActionListener(e -> chooseOutput());
        c.gridy = 0; c.gridx = 0; p.add(fixedLabel("file.input"), c);
        c.gridx = 1; c.weightx = 1; p.add(inputField, c);
        c.gridx = 2; c.weightx = 0; p.add(selIn, c);
        c.gridy = 1; c.gridx = 1;
        JLabel hint = I18n.label("file.hint");
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(hint, c);
        c.gridy = 2; c.gridx = 0; p.add(fixedLabel("file.output"), c);
        c.gridx = 1; c.weightx = 1; p.add(outputField, c);
        c.gridx = 2; c.weightx = 0; p.add(selOut, c);
        c.gridy = 3; c.gridx = 1;
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        opts.add(writePrg);
        opts.add(writeRaw);
        p.add(opts, c);
        return p;
    }

    private JPanel buildSettingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        p.add(buildSourcePanel(), c);
        c.gridx = 1;
        p.add(buildDitherPanel(), c);
        c.gridx = 2;
        c.weightx = 0.6;
        p.add(buildDestPanel(), c);
        return p;
    }

    private JPanel buildSourcePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(I18n.titled(p, "src.title"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 3, 1, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JButton custom = I18n.text(new JButton(), "src.custom");
        custom.addActionListener(e -> editCustomPalette(sourcePalette));
        c.gridy = 0; c.gridx = 0; c.gridwidth = 2; p.add(sourcePalette, c);
        c.gridx = 2; c.gridwidth = 1; p.add(custom, c);

        ButtonGroup g = new ButtonGroup();
        g.add(spaceRgb); g.add(spaceYuv); g.add(spaceHsl);
        JPanel spaces = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        spaces.add(I18n.label("src.matchIn"));
        spaces.add(spaceRgb); spaces.add(spaceYuv); spaces.add(spaceHsl);
        c.gridy = 1; c.gridx = 0; c.gridwidth = 3; p.add(spaces, c);
        c.gridy = 2; p.add(row(prepare, respectLuma), c);
        c.gridwidth = 1;
        addSlider(p, c, 3, "src.weightU", weightU);
        addSlider(p, c, 4, "src.weightV", weightV);
        addSlider(p, c, 5, "src.contrast", contrast);
        addSlider(p, c, 6, "src.brightness", brightness);
        addSlider(p, c, 7, "src.gamma", gamma);
        addSlider(p, c, 8, "src.saturation", saturation);
        addDefaultButton(p, this::resetSource);
        return p;
    }

    private JPanel buildDitherPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(I18n.titled(p, "dit.title"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 3, 1, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0; c.gridx = 0; p.add(fixedLabel("dit.dither"), c);
        c.gridx = 1; c.gridwidth = 2; p.add(ditherBox, c);
        c.gridwidth = 1;
        addSlider(p, c, 1, "dit.strength", strength);
        c.gridy = 2; c.gridx = 0; c.gridwidth = 3; p.add(bleeding, c);
        c.gridy = 3; p.add(serpentine, c);
        c.gridwidth = 1;
        c.gridy = 4; c.gridx = 0; p.add(fixedLabel("dit.passes"), c);
        c.gridx = 1; p.add(passes, c);
        c.gridy = 5; c.gridx = 0; p.add(fixedLabel("dit.background"), c);
        c.gridx = 1; c.gridwidth = 2; p.add(backgroundBox, c);
        addDefaultButton(p, this::resetDither);
        return p;
    }

    private JPanel buildDestPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(I18n.titled(p, "dst.title"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 3, 1, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        JButton custom = I18n.text(new JButton(), "dst.custom");
        custom.addActionListener(e -> editCustomPalette(destPalette));
        c.gridy = 0; p.add(destPalette, c);
        c.gridy = 1; p.add(custom, c);
        c.gridy = 2; p.add(renderBug, c);
        c.gridy = 3; p.add(palAspect, c);
        c.gridy = 4; c.weighty = 1; p.add(Box.createVerticalGlue(), c);
        addDefaultButton(p, this::resetDestination);
        return p;
    }

    /** A label whose minimum width always fits its (translated) text. */
    private static JLabel fixedLabel(String key) {
        JLabel l = new JLabel();
        I18n.bind(() -> {
            l.setText(tr(key));
            l.setMinimumSize(null);
            l.setMinimumSize(l.getPreferredSize());
        });
        return l;
    }

    /** Adds a "Defaults" button to the bottom right of a settings group. */
    private static void addDefaultButton(JPanel p, Runnable reset) {
        JButton b = I18n.tip(I18n.text(new JButton(), "defaults"), "defaults.tip");
        b.setMargin(new Insets(1, 6, 1, 6));
        b.addActionListener(e -> reset.run());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 99;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.anchor = GridBagConstraints.SOUTHEAST;
        c.weighty = 1;                       // push the button to the bottom of the group
        c.insets = new Insets(4, 3, 2, 3);
        p.add(b, c);
    }

    private void resetSource() {
        sourcePalette.setSelectedItem(Palettes.PEPTO);
        spaceYuv.setSelected(true);
        prepare.setSelected(true);
        respectLuma.setSelected(false);
        weightU.setValue(25);
        weightV.setValue(50);
        contrast.setValue(0);
        brightness.setValue(0);
        gamma.setValue(100);
        saturation.setValue(100);
        updatePreConvert();
    }

    private void resetDither() {
        ditherBox.setSelectedItem(Dither.FLOYD_STEINBERG);
        strength.setValue(80);
        bleeding.setSelected(true);
        serpentine.setSelected(false);
        passes.setValue(2);
        backgroundBox.setSelectedIndex(0);
        settingsChanged();
    }

    private void resetDestination() {
        destPalette.setSelectedItem(Palettes.PEPTO);
        renderBug.setSelected(true);
        palAspect.setSelected(true);
        updateAspect();
        updateRight();
        settingsChanged();
    }

    private static JPanel row(JComponent... comps) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent comp : comps) r.add(comp);
        return r;
    }

    /** Label | ◀ slider ▶ | value. Only the slider track shrinks when space gets short. */
    private static void addSlider(JPanel p, GridBagConstraints c, int y, String key, JSlider s) {
        JLabel value = new JLabel(String.valueOf(s.getValue()));
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        FontMetrics fm = value.getFontMetrics(value.getFont());
        int w = Math.max(fm.stringWidth(String.valueOf(s.getMinimum())), fm.stringWidth(String.valueOf(s.getMaximum()))) + 6;
        Dimension d = new Dimension(w, value.getPreferredSize().height);
        value.setPreferredSize(d);
        value.setMinimumSize(d);
        s.addChangeListener(e -> value.setText(String.valueOf(s.getValue())));
        double oldWeight = c.weightx;
        c.gridy = y;
        c.gridx = 0; c.weightx = 0; p.add(fixedLabel(key), c);
        c.gridx = 1; c.weightx = 1; p.add(SliderControls.wrap(s), c);
        c.gridx = 2; c.weightx = 0; p.add(value, c);
        c.weightx = oldWeight;
    }

    private JPanel buildPreviewPanel() {
        JPanel p = new JPanel(new GridLayout(1, 2, 6, 0));
        ButtonGroup gl = new ButtonGroup();
        gl.add(showOriginal); gl.add(showPre);
        ButtonGroup gr = new ButtonGroup();
        gr.add(showResult); gr.add(showError);
        p.add(previewBox(leftPanel, row(showOriginal, showPre)));
        p.add(previewBox(rightPanel, row(showResult, showError)));
        // linked views: zooming/panning one preview moves the other one too
        leftPanel.addViewListener(src -> { if (linkViews.isSelected()) rightPanel.copyViewFrom(src); });
        rightPanel.addViewListener(src -> { if (linkViews.isSelected()) leftPanel.copyViewFrom(src); });
        linkViews.addActionListener(e -> { if (linkViews.isSelected()) rightPanel.copyViewFrom(leftPanel); });
        showGrid.addActionListener(e -> {
            leftPanel.setShowGrid(showGrid.isSelected());
            rightPanel.setShowGrid(showGrid.isSelected());
        });
        return p;
    }

    /** One preview with its header: view choice on the left, zoom/pan buttons on the right. */
    private JPanel previewBox(ImagePanel panel, JPanel viewChoice) {
        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createEtchedBorder());
        JPanel header = new JPanel(new BorderLayout());
        header.add(viewChoice, BorderLayout.WEST);
        // wraps onto a second line instead of cutting off buttons when the preview gets narrow
        JPanel tools = new JPanel(new WrapLayout(FlowLayout.RIGHT, 2, 0));
        tools.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth = -1;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                // the wrapped height depends on the width: re-layout once the width is known
                if (tools.getWidth() != lastWidth) {
                    lastWidth = tools.getWidth();
                    SwingUtilities.invokeLater(tools::revalidate);
                }
            }
        });
        tools.add(toolButton("−", "zoom.out.tip", panel::zoomOut));
        tools.add(toolButton("+", "zoom.in.tip", panel::zoomIn));
        JButton fit = toolButton("", "zoom.fit.tip", panel::fit);
        I18n.text(fit, "zoom.fit");
        tools.add(fit);
        tools.add(toolButton("4×", "zoom.4x.tip", () -> panel.setPixelScale(4)));
        tools.add(Box.createHorizontalStrut(6));
        tools.add(toolButton("◀", "pan.left.tip", () -> panel.panBy(-0.25, 0)));
        tools.add(toolButton("▲", "pan.up.tip", () -> panel.panBy(0, -0.25)));
        tools.add(toolButton("▼", "pan.down.tip", () -> panel.panBy(0, 0.25)));
        tools.add(toolButton("▶", "pan.right.tip", () -> panel.panBy(0.25, 0)));
        if (panel == leftPanel) {
            tools.add(Box.createHorizontalStrut(6));
            tools.add(linkViews);
            tools.add(showGrid);
        }
        header.add(tools, BorderLayout.CENTER);
        box.add(header, BorderLayout.NORTH);
        box.add(panel, BorderLayout.CENTER);
        return box;
    }

    private static Icon aboutIcon() {
        for (Image img : Icons.appIcons())
            if (img.getWidth(null) == 128) return new ImageIcon(img);
        return null;
    }

    private static JButton toolButton(String text, String tipKey, Runnable action) {
        JButton b = new JButton(text);
        I18n.tip(b, tipKey);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel buildBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton crop = I18n.text(new JButton(), "bottom.crop");
        JButton savePre = I18n.text(new JButton(), "bottom.savePre");
        JButton saveRes = I18n.text(new JButton(), "bottom.saveResult");
        I18n.bind(() -> convertButton.setText(tr(isConverting() ? "bottom.cancel" : "bottom.convert")));
        I18n.tip(convertButton, "bottom.convert.tip");
        crop.addActionListener(e -> openCropDialog());
        savePre.addActionListener(e -> savePreConvert());
        saveRes.addActionListener(e -> saveResultImages());
        convertButton.addActionListener(e -> convertOrCancel());
        buttons.add(livePreview);
        buttons.add(crop);
        buttons.add(savePre);
        buttons.add(saveRes);
        buttons.add(convertButton);
        // progress bar: only visible while something is being computed
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(140, progress.getPreferredSize().height));
        progress.setVisible(false);
        JPanel st = new JPanel(new BorderLayout(6, 0));
        st.add(progress, BorderLayout.WEST);
        st.add(status, BorderLayout.CENTER);
        p.add(st, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    /** Sets a translated status text (re-translated on language switch). Arguments must be final values. */
    private void setStatus(String key, Object... args) {
        statusText = () -> tr(key, args);
        status.setText(statusText.get());
    }

    private boolean isConverting() { return worker != null && !worker.isDone(); }

    private void showProgress(double fraction) {
        progress.setValue((int) Math.round(fraction * 1000));
        progress.setString(Math.round(fraction * 100) + " %");
        if (!progress.isVisible()) {
            progress.setVisible(true);
            progress.getParent().revalidate();
        }
    }

    private void hideProgress() {
        if (progress.isVisible()) {
            progress.setVisible(false);
            progress.getParent().revalidate();
        }
    }

    private void wireListeners() {
        for (JSlider s : new JSlider[]{contrast, brightness, gamma, saturation}) s.addChangeListener(e -> updatePreConvert());
        prepare.addActionListener(e -> updatePreConvert());
        showOriginal.addActionListener(e -> updateLeft());
        showPre.addActionListener(e -> updateLeft());
        showResult.addActionListener(e -> updateRight());
        showError.addActionListener(e -> updateRight());
        destPalette.addActionListener(e -> updateRight());
        palAspect.addActionListener(e -> updateAspect());

        // everything that changes the conversion triggers a (debounced) live preview
        for (JSlider sl : new JSlider[]{weightU, weightV, strength}) sl.addChangeListener(e -> settingsChanged());
        for (AbstractButton b : new AbstractButton[]{respectLuma, bleeding, serpentine, renderBug, spaceRgb, spaceYuv, spaceHsl})
            b.addActionListener(e -> settingsChanged());
        for (JRadioButton b : modeButtons) b.addActionListener(e -> settingsChanged());
        sourcePalette.addActionListener(e -> settingsChanged());
        ditherBox.addActionListener(e -> settingsChanged());
        backgroundBox.addActionListener(e -> settingsChanged());
        passes.addChangeListener(e -> settingsChanged());
        livePreview.addActionListener(e -> {
            if (livePreview.isSelected()) settingsChanged();
            else cancelLivePreview();
        });
    }

    // ------------------------------------------------------------------ live preview

    /** Marks the current result as outdated and schedules a live preview. */
    private void settingsChanged() {
        settingsGeneration++;
        if (livePreview.isSelected() && preConverted != null) {
            liveTimer.restart();
            setStatus("status.updating");
        }
    }

    private void cancelLivePreview() {
        liveTimer.stop();
        liveCancel.set(true);
        hideProgress();
    }

    private void runLivePreview() {
        if (preConverted == null || !livePreview.isSelected()) return;
        if (isConverting()) return;                            // an explicit conversion is running
        liveCancel.set(true);                                  // abandon a preview that is still running
        AtomicBoolean myCancel = new AtomicBoolean();
        liveCancel = myCancel;
        long gen = settingsGeneration;
        ConvertSettings cs = collectSettings();
        float[] target = preConverted.clone();
        long t0 = System.currentTimeMillis();
        SwingWorker<ConversionResult, Double> live = new SwingWorker<>() {
            @Override protected ConversionResult doInBackground() {
                return FliConverter.convert(target, cs, new FliConverter.Progress() {
                    @Override public void update(String message, double fraction) { publish(fraction); }
                    @Override public boolean cancelled() { return myCancel.get(); }
                });
            }

            @Override protected void process(List<Double> chunks) {
                if (myCancel.get()) return;
                setStatus("status.updating");
                showProgress(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                if (myCancel.get() || gen != settingsGeneration) return;
                hideProgress();
                try {
                    result = get();
                    resultGeneration = gen;
                    updateRight();
                    setStatus("status.previewReady",
                            (System.currentTimeMillis() - t0) / 1000.0, result.meanError(cs.sourcePalette()));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("status.previewFailed", cause);
                }
            }
        };
        live.execute();
    }

    private void installDragAndDrop(JComponent c) {
        c.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) SwingUtilities.invokeLater(() -> loadInput(files.get(0)));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    // ------------------------------------------------------------------ files

    private void chooseInput() {
        File dir = FileDialogs.lastDir("input");
        if (dir == null && !inputField.getText().isBlank()) dir = new File(inputField.getText()).getParentFile();
        File f = FileDialogs.openImage(this, dir);
        if (f != null) loadInput(f);
    }

    private void chooseOutput() {
        File current = outputField.getText().isBlank() ? null : new File(outputField.getText());
        File f = FileDialogs.save(this, "dlg.output.title", current != null ? current.getAbsoluteFile().getParentFile() : null,
                current != null ? current.getName() : null, "filter.prg", "prg");
        if (f != null) {
            outputField.setText(f.getPath());
            outputDirChosen = f.getAbsoluteFile().getParentFile();
            FileDialogs.rememberDir("output", outputDirChosen);
        }
    }

    /**
     * Output = same name as the input picture. Folder: next to the input, or (setting) the last folder
     * chosen for output, if there is one.
     */
    private void updateOutputPath(File input) {
        String base = input.getName().replaceFirst("\\.[^.]+$", "");
        File last = outputDirChosen != null ? outputDirChosen : FileDialogs.lastDir("output");
        File dir = !settings.outputNextToInput && last != null ? last : input.getAbsoluteFile().getParentFile();
        outputField.setText(new File(dir, base + ".prg").getPath());
    }

    /**
     * Loads a picture. Pictures that are not C64-sized open the crop & scale dialog first;
     * nothing is converted or shown before it is confirmed, and cancelling keeps the previous picture.
     */
    private void loadInput(File f) {
        BufferedImage img;
        try {
            // C64 pictures (Koala, FLI, Gunpaint, …) are drawn with the selected source palette
            img = de.c64gfx.swing.Images.read(f, (Palette) sourcePalette.getSelectedItem());
        } catch (Exception ex) {
            error(tr("err.read", ex.getMessage()));
            return;
        }
        if (img == null) {
            error(tr("err.unsupported", f.getName()));
            return;
        }
        FileDialogs.rememberDir("input", f);
        ImageFitter.Settings fs = ImageFitter.Settings.defaults();
        if (!ImageFitter.isNativeSize(img)) {
            CropScaleDialog d = new CropScaleDialog(this, img, fs);
            d.setVisible(true);
            if (!d.isAccepted()) {
                setStatus("status.loadCancelled");
                return;
            }
            fs = d.getSettings();
        }
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        cancelLivePreview();
        source = img;
        fitSettings = fs;
        inputField.setText(f.getPath());
        updateOutputPath(f);
        result = null;
        resultGeneration = -1;
        updateRight();
        leftPanel.fit();
        applyFit();
        if (!livePreview.isSelected()) setStatus("status.loaded", f.getName(), img.getWidth(), img.getHeight());
    }

    private void openCropDialog() {
        if (source == null) return;
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        CropScaleDialog d = new CropScaleDialog(this, source, fitSettings);
        d.setVisible(true);
        if (d.isAccepted()) {
            fitSettings = d.getSettings();
            applyFit();
        }
    }

    /** Right click on the original: choose the frame directly in the preview. */
    private void startReframe() {
        if (source == null || leftPanel.isCropMode()) return;
        double ratio = fitSettings.aspect().ratio > 0 ? fitSettings.aspect().ratio : 1.6;
        reframeBackup = fitSettings;
        leftPanel.startCrop(source, fitSettings.crop(), ratio, new ImagePanel.CropListener() {
            @Override public void cropChanged(Rectangle crop) {
                fitSettings = new ImageFitter.Settings(crop, ImageFitter.ScaleMode.FILL,
                        fitSettings.filter(), fitSettings.aspect(), fitSettings.fill());
                fitTimer.restart();
            }

            @Override public void cropFinished(boolean accepted) {
                fitTimer.stop();
                if (!accepted) fitSettings = reframeBackup;
                applyFit();
                if (leftPanel.getZoom() == 1.0 && linkViews.isSelected()) rightPanel.copyViewFrom(leftPanel);
                setStatus(accepted ? "status.reframeApplied" : "status.reframeCancelled");
            }
        });
        setStatus("status.reframe");
    }

    private void applyFit() {
        if (source == null) return;
        fitted = ImageFitter.fit(source, fitSettings);
        updatePreConvert();
    }

    private ImageAdjust.Settings adjustSettings() {
        return new ImageAdjust.Settings(brightness.getValue(), contrast.getValue(), gamma.getValue(), saturation.getValue());
    }

    private void updatePreConvert() {
        if (fitted == null) return;
        preConverted = ImageAdjust.toAdjustedRgb(fitted, adjustSettings(), prepare.isSelected());
        updateLeft();
        settingsChanged();
    }

    private void updateLeft() {
        if (fitted == null) return;
        leftPanel.setImage(showOriginal.isSelected() ? fitted : ImageAdjust.toImage(preConverted, 320, 200));
    }

    private void updateRight() {
        if (result == null) {
            rightPanel.setImage(null);
            return;
        }
        Palette dest = (Palette) destPalette.getSelectedItem();
        Palette src = (Palette) sourcePalette.getSelectedItem();
        rightPanel.setImage(showError.isSelected() ? result.errorMap(src) : result.render(dest));
    }

    private void updateAspect() {
        double a = palAspect.isSelected() ? ImageFitter.Aspect.PAL.ratio : 1.6;
        leftPanel.setDisplayAspect(a);
        rightPanel.setDisplayAspect(a);
    }

    private FliMode selectedMode() {
        for (int i = 0; i < modeButtons.length; i++) if (modeButtons[i].isSelected()) return FliMode.values()[i];
        return FliMode.FLI;
    }

    private ConvertSettings collectSettings() {
        ColorMatcher.Space space = spaceRgb.isSelected() ? ColorMatcher.Space.RGB
                : spaceHsl.isSelected() ? ColorMatcher.Space.HSL : ColorMatcher.Space.YUV;
        return new ConvertSettings(selectedMode(), (Palette) sourcePalette.getSelectedItem(), space,
                weightU.getValue(), weightV.getValue(), respectLuma.isSelected(),
                (Dither) ditherBox.getSelectedItem(), strength.getValue(), bleeding.isSelected(),
                serpentine.isSelected(), renderBug.isSelected(), (Integer) passes.getValue(),
                backgroundBox.getSelectedIndex() - 1);
    }

    // ------------------------------------------------------------------ convert & save

    private void convertOrCancel() {
        if (isConverting()) {
            cancel.set(true);
            return;
        }
        if (preConverted == null) {
            error(tr("err.openFirst"));
            return;
        }
        if (result != null && resultGeneration == settingsGeneration) {
            // the live preview is up to date: just write the files
            try {
                String written = writeOutputs();
                if (written.isEmpty()) setStatus("status.nothing");
                else setStatus("status.saved", written);
            } catch (Exception ex) {
                error(tr("err.write", ex.getMessage()));
            }
            return;
        }
        cancelLivePreview();
        long gen = settingsGeneration;
        ConvertSettings cs = collectSettings();
        float[] target = preConverted.clone();
        cancel.set(false);
        long t0 = System.currentTimeMillis();
        worker = new SwingWorker<>() {
            @Override protected ConversionResult doInBackground() {
                return FliConverter.convert(target, cs, new FliConverter.Progress() {
                    @Override public void update(String message, double fraction) { publish(fraction); }
                    @Override public boolean cancelled() { return cancel.get(); }
                });
            }

            @Override protected void process(List<Double> chunks) {
                setStatus("status.computing");
                showProgress(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                convertButton.setText(tr("bottom.convert"));
                hideProgress();
                try {
                    if (cancel.get()) {
                        setStatus("status.cancelled");
                        return;
                    }
                    result = get();
                    resultGeneration = gen;
                    showResult.setSelected(true);
                    updateRight();
                    String written = writeOutputs();
                    double secs = (System.currentTimeMillis() - t0) / 1000.0, err = result.meanError(cs.sourcePalette());
                    statusText = () -> tr("status.converted", secs, err)
                            + (written.isEmpty() ? "" : " – " + tr("status.saved", written));
                    status.setText(statusText.get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    error(tr("err.convert", cause));
                }
            }
        };
        worker.execute();
        convertButton.setText(tr("bottom.cancel"));
    }

    /** Writes the enabled outputs; returns a short description of what was written ("" = nothing). */
    private String writeOutputs() throws Exception {
        String out = outputField.getText().trim();
        if (out.isEmpty() || (!writePrg.isSelected() && !writeRaw.isSelected())) return "";
        Path prgPath = Path.of(out);
        StringBuilder sb = new StringBuilder();
        if (writePrg.isSelected()) {
            Files.write(prgPath, PrgBuilder.build(result));
            sb.append(prgPath.getFileName());
        }
        if (writeRaw.isSelected()) {
            Path dir = prgPath.toAbsolutePath().getParent();
            String base = prgPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            int n = RawExporter.export(result, dir, base).size();
            sb.append(sb.length() > 0 ? " + " : "").append(tr("status.rawFiles", n));
        }
        return sb.toString();
    }

    private void savePreConvert() {
        if (preConverted == null) return;
        Path p = choosePng("pre-convert.png");
        if (p == null) return;
        try {
            ImageIO.write(ImageAdjust.toImage(preConverted, 320, 200), "png", p.toFile());
            setStatus("status.saved", p.getFileName());
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    private void saveResultImages() {
        if (result == null) return;
        Path p = choosePng("result.png");
        if (p == null) return;
        try {
            ImageIO.write(result.render((Palette) destPalette.getSelectedItem()), "png", p.toFile());
            Path err = Path.of(p.toString().replaceFirst("\\.png$", "") + "_errormap.png");
            ImageIO.write(result.errorMap((Palette) sourcePalette.getSelectedItem()), "png", err.toFile());
            setStatus("status.savedImages", p.getFileName(), err.getFileName());
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    private Path choosePng(String def) {
        File f = FileDialogs.save(this, "dlg.savePng.title", FileDialogs.lastDir("png"), def, "filter.png", "png");
        if (f == null) return null;
        FileDialogs.rememberDir("png", f);
        return f.toPath();
    }

    private void editCustomPalette(JComboBox<Palette> target) {
        PaletteEditorDialog d = new PaletteEditorDialog(this, (Palette) target.getSelectedItem());
        d.setVisible(true);
        if (!d.isAccepted()) return;
        Palette p = d.getPalette();
        // make the new palette available in both lists
        sourceModel.addElement(p);
        destModel.addElement(p);
        target.setSelectedItem(p);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, App.NAME, JOptionPane.ERROR_MESSAGE);
    }
}
