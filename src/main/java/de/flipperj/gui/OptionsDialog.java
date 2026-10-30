package de.flipperj.gui;

import de.c64gfx.swing.Platform;

import de.c64gfx.swing.I18n;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.Locale;

import static de.c64gfx.swing.I18n.tr;

/** Settings dialog: language, preview behaviour and output defaults. */
public class OptionsDialog extends JDialog {
    private final JComboBox<Object> language = new JComboBox<>();
    private final JCheckBox live = new JCheckBox(tr("opt.live"));
    private final JCheckBox grid = new JCheckBox(tr("opt.grid"));
    private final JCheckBox link = new JCheckBox(tr("opt.link"));
    private final JCheckBox pal = new JCheckBox(tr("opt.pal"));
    private final JCheckBox prg = new JCheckBox(tr("opt.writePrg"));
    private final JCheckBox raw = new JCheckBox(tr("opt.writeRaw"));
    private final JRadioButton outNext = new JRadioButton(tr("opt.outNext"));
    private final JRadioButton outLast = new JRadioButton(tr("opt.outLast"));
    private boolean accepted;

    /** Marker item for "follow the system language". */
    private static final Object SYSTEM = new Object();

    public OptionsDialog(Frame owner, Settings s) {
        super(owner, tr("opt.title"), true);
        language.addItem(SYSTEM);
        for (Locale l : I18n.supportedLocales()) language.addItem(l);
        language.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                String text = value == SYSTEM
                        ? tr("opt.languageSystem", I18n.nativeName(I18n.systemLocale()))
                        : I18n.nativeName((Locale) value);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        language.setSelectedItem(I18n.followsSystem() ? SYSTEM : I18n.locale());
        show(s);

        ButtonGroup g = new ButtonGroup();
        g.add(outNext);
        g.add(outLast);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JPanel lang = group("opt.language");
        language.setAlignmentX(LEFT_ALIGNMENT);
        lang.add(language);
        content.add(lang);

        JPanel prev = group("opt.preview");
        prev.add(live); prev.add(grid); prev.add(link); prev.add(pal);
        content.add(prev);

        JPanel out = group("opt.output");
        out.add(prg); out.add(raw);
        JLabel dir = new JLabel(tr("opt.outDir"));
        dir.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 0));
        out.add(dir); out.add(outNext); out.add(outLast);
        content.add(out);

        JButton ok = new JButton(tr("ok")), cancel = new JButton(tr("cancel")), restore = new JButton(tr("opt.restore"));
        ok.addActionListener(e -> { accepted = true; dispose(); });
        cancel.addActionListener(e -> dispose());
        restore.addActionListener(e -> { show(new Settings()); language.setSelectedItem(SYSTEM); });
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(restore);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        // macOS convention: Cancel left of OK
        if (Platform.IS_MAC) { right.add(cancel); right.add(ok); } else { right.add(ok); right.add(cancel); }
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(new Dimension(Math.max(420, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    private static JPanel group(String key) {
        JPanel p = new JPanel() {
            // all groups as wide as the dialog
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(new TitledBorder(tr(key)),
                BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void show(Settings s) {
        live.setSelected(s.livePreview);
        grid.setSelected(s.pixelGrid);
        link.setSelected(s.linkPreviews);
        pal.setSelected(s.palAspect);
        prg.setSelected(s.writePrg);
        raw.setSelected(s.writeRaw);
        outNext.setSelected(s.outputNextToInput);
        outLast.setSelected(!s.outputNextToInput);
    }

    public boolean isAccepted() { return accepted; }

    public Settings getSettings() {
        Settings s = new Settings();
        s.livePreview = live.isSelected();
        s.pixelGrid = grid.isSelected();
        s.linkPreviews = link.isSelected();
        s.palAspect = pal.isSelected();
        s.writePrg = prg.isSelected();
        s.writeRaw = raw.isSelected();
        s.outputNextToInput = outNext.isSelected();
        return s;
    }

    /** null = follow the system language. */
    public Locale getLanguage() {
        Object o = language.getSelectedItem();
        return o == SYSTEM ? null : (Locale) o;
    }
}
