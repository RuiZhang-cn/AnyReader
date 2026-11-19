package dev.local.docinspector.ui;

import dev.local.docinspector.model.ImagePrivacyMode;
import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.model.ThemePreset;
import dev.local.docinspector.state.DocInspectorStateService;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 配色、排版与隐私策略实时控制面板（可嵌入 {@code JBPopup} 或独立容器）。
 */
public final class ThemeTypographyPopup extends JPanel {

    private final DocInspectorStateService stateService;
    private final Consumer<ReaderSettings> onSettingsChanged;
    private final Runnable onCloseRequested;

    private final JComboBox<ThemePreset> presetCombo = new JComboBox<>(ThemePreset.values());
    private final JTextField customBgField = new JTextField(6);
    private final JTextField customFgField = new JTextField(6);
    private final JComboBox<String> fontCombo;
    private final JSpinner fontSizeSpinner = new JSpinner(new SpinnerNumberModel(15, 12, 28, 1));
    private final JSpinner lineHeightSpinner = new JSpinner(new SpinnerNumberModel(1.7, 1.3, 2.4, 0.1));
    private final JSpinner paddingSpinner = new JSpinner(new SpinnerNumberModel(22, 12, 64, 2));
    private final JComboBox<ImagePrivacyMode> imagePrivacyCombo = new JComboBox<>(ImagePrivacyMode.values());
    private final JComboBox<PdfColorFilter> pdfFilterCombo = new JComboBox<>(PdfColorFilter.values());
    private final JSlider pdfOpacitySlider = new JSlider(15, 100, 100);
    private final JLabel pdfOpacityValueLabel = new JLabel("100%");
    private final JSlider autoScrollSlider = new JSlider(0, 120, 0);
    private final JLabel autoScrollValueLabel = new JLabel("关");
    private final JSpinner autoMaskSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 600, 5));
    private final JCheckBox microBarCheck = new JCheckBox("开启底部状态栏微读");
    private final JButton closeButton = new JButton("关闭");

    private boolean syncing = false;

    public ThemeTypographyPopup(DocInspectorStateService stateService, Consumer<ReaderSettings> onSettingsChanged) {
        this(stateService, onSettingsChanged, null);
    }

    public ThemeTypographyPopup(DocInspectorStateService stateService,
                                Consumer<ReaderSettings> onSettingsChanged,
                                Runnable onCloseRequested) {
        super(new BorderLayout(0, 8));
        this.stateService = stateService != null ? stateService : DocInspectorStateService.getInstance();
        this.onSettingsChanged = onSettingsChanged;
        this.onCloseRequested = onCloseRequested;
        setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        this.fontCombo = new JComboBox<>(buildFontChoices());
        this.fontCombo.setEditable(true);
        this.pdfOpacitySlider.setOpaque(false);
        this.autoScrollSlider.setOpaque(false);
        this.microBarCheck.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(form, gbc, row++, "主题预设", presetCombo);
        addRow(form, gbc, row++, "自定义色", buildCustomColorPanel());
        addRow(form, gbc, row++, "字体族", fontCombo);
        addRow(form, gbc, row++, "字号 (px)", fontSizeSpinner);
        addRow(form, gbc, row++, "行高", lineHeightSpinner);
        addRow(form, gbc, row++, "页边距 (px)", paddingSpinner);
        addRow(form, gbc, row++, "图片隐私", imagePrivacyCombo);
        addRow(form, gbc, row++, "PDF 滤镜", pdfFilterCombo);
        addRow(form, gbc, row++, "PDF 透明度", buildSliderRow(pdfOpacitySlider, pdfOpacityValueLabel));
        addRow(form, gbc, row++, "自动微滚", buildSliderRow(autoScrollSlider, autoScrollValueLabel));
        addRow(form, gbc, row++, "失焦伪装(秒)", autoMaskSpinner);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        form.add(microBarCheck, gbc);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        footer.setOpaque(false);
        closeButton.setFocusable(false);
        closeButton.setMargin(new Insets(3, 12, 3, 12));
        closeButton.addActionListener(e -> requestClose());
        footer.add(closeButton);

        add(form, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeThemePopup");
        getActionMap().put("closeThemePopup", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestClose();
            }
        });

        loadFromSettings(this.stateService.getSettings());
        wireListeners();
    }

    public JButton getCloseButton() {
        return closeButton;
    }

    private void requestClose() {
        if (onCloseRequested != null) {
            onCloseRequested.run();
        }
    }

    private static String[] buildFontChoices() {
        Set<String> fonts = new LinkedHashSet<>();
        fonts.add("Microsoft YaHei");
        fonts.add("JetBrains Mono");
        fonts.add("Consolas");
        fonts.add("PingFang SC");
        fonts.add("SimSun");
        fonts.add("Noto Serif SC");
        fonts.add("SansSerif");
        fonts.add("Serif");
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            for (String f : available) {
                if (fonts.size() >= 30) {
                    break;
                }
                fonts.add(f);
            }
        } catch (Throwable ignored) {
        }
        return fonts.toArray(new String[0]);
    }

    private JPanel buildCustomColorPanel() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setOpaque(false);

        customBgField.setToolTipText("背景色 Hex (如 #1E1F22，留空跟随预设)");
        customFgField.setToolTipText("前景色 Hex (如 #BCBEC4，留空跟随预设)");
        JButton pickBg = new JButton("背景");
        JButton pickFg = new JButton("文字");
        JButton clearBtn = new JButton("重置");
        for (JButton b : new JButton[]{pickBg, pickFg, clearBtn}) {
            b.setMargin(new Insets(1, 5, 1, 5));
            b.setFocusable(false);
        }
        pickBg.addActionListener(e -> chooseColor(customBgField, "选择背景色"));
        pickFg.addActionListener(e -> chooseColor(customFgField, "选择文字色"));
        clearBtn.addActionListener(e -> {
            customBgField.setText("");
            customFgField.setText("");
            commitChange(s -> {
                s.setCustomBgHex("");
                s.setCustomFgHex("");
            });
        });

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.setOpaque(false);
        row1.add(customBgField);
        row1.add(pickBg);
        row1.add(clearBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.setOpaque(false);
        row2.add(customFgField);
        row2.add(pickFg);

        container.add(row1);
        container.add(Box.createVerticalStrut(3));
        container.add(row2);
        return container;
    }

    private void chooseColor(JTextField targetField, String title) {
        Color initial = ReaderThemePalette.parseHexColor(targetField.getText(), Color.DARK_GRAY);
        Color chosen = JColorChooser.showDialog(this, title, initial);
        if (chosen != null) {
            targetField.setText(ReaderThemePalette.toHex(chosen));
            applyCustomColors();
        }
    }

    private JPanel buildSliderRow(JSlider slider, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        slider.setPreferredSize(new Dimension(140, 22));
        valueLabel.setPreferredSize(new Dimension(44, 20));
        panel.add(slider, BorderLayout.CENTER);
        panel.add(valueLabel, BorderLayout.EAST);
        return panel;
    }

    private static void addRow(JPanel form, GridBagConstraints gbc, int row, String label, java.awt.Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        form.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(comp, gbc);
    }

    public void loadFromSettings(ReaderSettings s) {
        if (s == null) {
            return;
        }
        syncing = true;
        try {
            presetCombo.setSelectedItem(s.getThemePreset());
            customBgField.setText(s.getCustomBgHex());
            customFgField.setText(s.getCustomFgHex());
            fontCombo.setSelectedItem(s.getFontFamily());
            fontSizeSpinner.setValue(s.getFontSize());
            lineHeightSpinner.setValue(Math.max(1.3, Math.min(2.4, s.getLineHeight())));
            paddingSpinner.setValue(Math.max(12, Math.min(64, s.getHorizontalPadding())));
            imagePrivacyCombo.setSelectedItem(s.getImagePrivacyMode());
            pdfFilterCombo.setSelectedItem(s.getPdfColorFilter());
            int opacityPct = Math.round(s.getPdfOpacity() * 100f);
            pdfOpacitySlider.setValue(Math.max(15, Math.min(100, opacityPct)));
            pdfOpacityValueLabel.setText(pdfOpacitySlider.getValue() + "%");
            autoScrollSlider.setValue(s.getAutoScrollPxPerSec());
            autoScrollValueLabel.setText(s.getAutoScrollPxPerSec() <= 0 ? "关" : (s.getAutoScrollPxPerSec() + " px/s"));
            autoMaskSpinner.setValue(s.getAutoMaskIdleSeconds());
            microBarCheck.setSelected(s.isMicroBarEnabled());
        } finally {
            syncing = false;
        }
    }

    private void wireListeners() {
        presetCombo.addActionListener(e -> commitChange(s ->
                s.setThemePreset((ThemePreset) presetCombo.getSelectedItem())));

        customBgField.addActionListener(e -> applyCustomColors());
        customFgField.addActionListener(e -> applyCustomColors());

        fontCombo.addActionListener(e -> {
            Object item = fontCombo.getSelectedItem();
            if (item != null) {
                commitChange(s -> s.setFontFamily(item.toString()));
            }
        });

        fontSizeSpinner.addChangeListener(e -> commitChange(s ->
                s.setFontSize(((Number) fontSizeSpinner.getValue()).intValue())));

        lineHeightSpinner.addChangeListener(e -> commitChange(s ->
                s.setLineHeight(((Number) lineHeightSpinner.getValue()).doubleValue())));

        paddingSpinner.addChangeListener(e -> commitChange(s ->
                s.setHorizontalPadding(((Number) paddingSpinner.getValue()).intValue())));

        imagePrivacyCombo.addActionListener(e -> commitChange(s ->
                s.setImagePrivacyMode((ImagePrivacyMode) imagePrivacyCombo.getSelectedItem())));

        pdfFilterCombo.addActionListener(e -> commitChange(s ->
                s.setPdfColorFilter((PdfColorFilter) pdfFilterCombo.getSelectedItem())));

        pdfOpacitySlider.addChangeListener(e -> {
            int val = pdfOpacitySlider.getValue();
            pdfOpacityValueLabel.setText(val + "%");
            if (!pdfOpacitySlider.getValueIsAdjusting()) {
                commitChange(s -> s.setPdfOpacity(val / 100f));
            }
        });

        autoScrollSlider.addChangeListener(e -> {
            int val = autoScrollSlider.getValue();
            autoScrollValueLabel.setText(val <= 0 ? "关" : (val + " px/s"));
            if (!autoScrollSlider.getValueIsAdjusting()) {
                commitChange(s -> s.setAutoScrollPxPerSec(val));
            }
        });

        autoMaskSpinner.addChangeListener(e -> commitChange(s ->
                s.setAutoMaskIdleSeconds(((Number) autoMaskSpinner.getValue()).intValue())));

        microBarCheck.addActionListener(e -> commitChange(s ->
                s.setMicroBarEnabled(microBarCheck.isSelected())));
    }

    private void applyCustomColors() {
        String bg = customBgField.getText().trim();
        String fg = customFgField.getText().trim();
        commitChange(s -> {
            s.setCustomBgHex(ReaderThemePalette.isValidHex(bg) ? ReaderThemePalette.normalizeHex(bg) : "");
            s.setCustomFgHex(ReaderThemePalette.isValidHex(fg) ? ReaderThemePalette.normalizeHex(fg) : "");
        });
    }

    private void commitChange(Consumer<ReaderSettings> mutator) {
        if (syncing) {
            return;
        }
        stateService.updateSettings(mutator);
        if (onSettingsChanged != null) {
            onSettingsChanged.accept(stateService.getSettings());
        }
    }
}
