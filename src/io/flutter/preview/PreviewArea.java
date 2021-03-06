/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.preview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO: have a way to tell the panel whether it's hidden or shown

// TODO: we'll need to know whether the preview element is stateless widget or a state of a stateful
//       widget; for the 2nd case, we'll need to find the corresponding stateful widget class

// TODO: we want to preview anything in a state, stateful, or stateless class (not
//       just things contained in a build method)

// TODO: we should be bolding stateful and stateless (and state) classes, not build() methods
//       or, show all elements of these classes with some additional emphasis (italic? background color?)

public class PreviewArea {
  public static int BORDER_WITH = 5;

  @SuppressWarnings("UseJBColor")
  private static final Color[] pastelColors = new Color[]{
    new Color(255, 132, 203),
    new Color(168, 231, 234),
    new Color(251, 255, 147),
    new Color(143, 255, 159),
    new Color(193, 149, 255),
  };

  private final Listener myListener;

  private final JLayeredPane layeredPanel = new JLayeredPane();
  private final JPanel primaryLayer = new JPanel();
  private final JPanel handleLayer = new JPanel(null);

  private boolean isBeingRendered = false;

  private final Map<Integer, FlutterOutline> idToOutline = new HashMap<>();

  private int rootWidgetId;
  private Rectangle rootWidgetBounds;
  private final Map<Integer, Rectangle> idToGlobalBounds = new HashMap<>();

  private final Map<FlutterOutline, JComponent> outlineToComponent = new HashMap<>();
  private final List<SelectionEditPolicy> selectionComponents = new ArrayList<>();

  private int colorIndex = 0;

  public PreviewArea(Listener listener) {
    this.myListener = listener;

    primaryLayer.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        final int width = primaryLayer.getWidth() - 2 * BORDER_WITH;
        final int height = primaryLayer.getHeight() - 2 * BORDER_WITH;
        listener.resized(width, height);
      }
    });

    primaryLayer.setLayout(new BorderLayout());
    clear();

    // Layers must be transparent.
    handleLayer.setOpaque(false);

    layeredPanel.add(primaryLayer, Integer.valueOf(0));
    layeredPanel.add(handleLayer, Integer.valueOf(1));

    // Layers must cover the whole root panel.
    layeredPanel.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        final int width = layeredPanel.getWidth();
        final int height = layeredPanel.getHeight();
        for (Component child : layeredPanel.getComponents()) {
          child.setBounds(0, 0, width, height);
        }
      }
    });
  }

  /**
   * Return the Swing component of the area.
   */
  public JComponent getComponent() {
    return layeredPanel;
  }

  public void clear() {
    primaryLayer.removeAll();
    primaryLayer.setLayout(new BorderLayout());
    primaryLayer.add(new JLabel("Preview is not available", SwingConstants.CENTER), BorderLayout.CENTER);
    primaryLayer.revalidate();
    primaryLayer.repaint();
  }

  /**
   * A new outline was received, and we started rendering.
   * Until rendering is finished, the area is inconsistent with the new outline.
   * It should not ignore incoming events and should not send its events to the listener.
   */
  public void renderingStarted() {
    isBeingRendered = true;
  }

  /**
   * Rendering finished, the new outline and rendering information is available.
   * Show the rendered outlines.
   */
  public void show(@NotNull FlutterOutline unitOutline, @NotNull JsonObject renderObject) {
    isBeingRendered = false;

    idToOutline.clear();
    fillIdToOutline(unitOutline);

    fillIdToGlobalBounds(renderObject);

    primaryLayer.removeAll();
    primaryLayer.setLayout(null);

    final FlutterOutline rootOutline = idToOutline.get(rootWidgetId);
    if (rootOutline == null) {
      clear();
      return;
    }

    colorIndex = 0;
    outlineToComponent.clear();
    renderWidgetOutline(rootOutline);
    primaryLayer.repaint();
  }

  public void select(@NotNull List<FlutterOutline> outlines) {
    if (isBeingRendered) {
      return;
    }

    for (SelectionEditPolicy policy : selectionComponents) {
      policy.deactivate();
    }
    selectionComponents.clear();

    for (FlutterOutline outline : outlines) {
      final JComponent widget = outlineToComponent.get(outline);
      if (widget != null) {
        final SelectionEditPolicy selectionPolicy = new SelectionEditPolicy(handleLayer, widget);
        selectionComponents.add(selectionPolicy);
        selectionPolicy.activate();
      }
    }

    primaryLayer.repaint();
  }

  private void fillIdToOutline(@NotNull FlutterOutline outline) {
    if (outline.getId() != null) {
      idToOutline.put(outline.getId(), outline);
    }
    if (outline.getChildren() != null) {
      for (FlutterOutline child : outline.getChildren()) {
        fillIdToOutline(child);
      }
    }
  }

  private void fillIdToGlobalBounds(@NotNull JsonObject renderObject) {
    rootWidgetBounds = null;
    idToGlobalBounds.clear();
    for (Map.Entry<String, JsonElement> entry : renderObject.entrySet()) {
      try {
        final int id = Integer.parseInt(entry.getKey());

        final JsonObject widgetObject = (JsonObject)entry.getValue();

        final JsonObject boundsObject = widgetObject.getAsJsonObject("globalBounds");
        final int left = boundsObject.getAsJsonPrimitive("left").getAsInt();
        final int top = boundsObject.getAsJsonPrimitive("top").getAsInt();
        final int width = boundsObject.getAsJsonPrimitive("width").getAsInt();
        final int height = boundsObject.getAsJsonPrimitive("height").getAsInt();
        final Rectangle rect = new Rectangle(left, top, width, height);

        if (rootWidgetBounds == null) {
          rootWidgetId = id;
          rootWidgetBounds = rect;
        }

        idToGlobalBounds.put(id, rect);
      }
      catch (Throwable ignored) {
      }
    }
  }

  private void renderWidgetOutline(@NotNull FlutterOutline outline) {
    final Integer id = outline.getId();
    if (id != null) {
      Rectangle rect = idToGlobalBounds.get(id);
      if (rect != null) {
        final int x = BORDER_WITH + rect.x - rootWidgetBounds.x;
        final int y = BORDER_WITH + rect.y - rootWidgetBounds.y;
        rect = new Rectangle(x, y, rect.width, rect.height);

        final JPanel widget = new JPanel();
        widget.setBackground(pastelColors[(colorIndex++) % pastelColors.length]);
        widget.setBounds(rect);

        outlineToComponent.put(outline, widget);

        widget.addMouseListener(new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() >= 2) {
              myListener.doubleClicked(outline);
            }
          }

          @Override
          public void mousePressed(MouseEvent e) {
            myListener.clicked(outline);
          }
        });

        if (outline.getChildren() != null) {
          for (FlutterOutline child : outline.getChildren()) {
            renderWidgetOutline(child);
          }
        }

        primaryLayer.add(widget);
      }
    }
  }

  interface Listener {
    void clicked(FlutterOutline outline);

    void doubleClicked(FlutterOutline outline);

    void resized(int width, int height);
  }
}
