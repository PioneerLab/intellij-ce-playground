/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.ui.laf;

import com.intellij.ide.ui.laf.darcula.DarculaLaf;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.mac.foundation.Foundation;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import java.awt.*;
import java.io.File;
import java.util.HashSet;

/**
 * @author Konstantin Bulenkov
 */
public class IntelliJLaf extends DarculaLaf {
  @Override
  public String getName() {
    return "IntelliJ";
  }

  @Override
  protected String getPrefix() {
    return "intellijlaf";
  }

  @Override
  protected DefaultMetalTheme createMetalTheme() {
    return new IdeaBlueMetalTheme();
  }

  @Override
  public UIDefaults getDefaults() {
    UIDefaults defaults = super.getDefaults();
    if (SystemInfo.isMacOSYosemite) {
      installMacOSXFonts(defaults);
    }
    return defaults;
  }

  private static void installMacOSXFonts(UIDefaults defaults) {
    String face = "HelveticaNeue-Regular";
    LafManagerImpl.initFontDefaults(defaults, face, 13);
    for (Object key : new HashSet<Object>(defaults.keySet())) {
      Object value = defaults.get(key);
      if (value instanceof FontUIResource) {
        FontUIResource font = (FontUIResource)value;
        if (font.getFamily().equals("Lucida Grande") || font.getFamily().equals("Serif")) {
          if (!key.toString().contains("Menu")) {
            defaults.put(key, new FontUIResource(face, font.getStyle(), font.getSize()));
          }
        }
      }
    }
    FontUIResource uiFont11 = new FontUIResource("HelveticaNeue-Medium", Font.PLAIN, 11);
    defaults.put("TableHeader.font", uiFont11);

    FontUIResource buttonFont = new FontUIResource("HelveticaNeue-Medium", Font.PLAIN, 13);
    defaults.put("Button.font", buttonFont);
    Font menuFont = new FontUIResource("Lucida Grande", Font.PLAIN, 14);
    defaults.put("Menu.font", menuFont);
    defaults.put("MenuItem.font", menuFont);
    defaults.put("MenuItem.acceleratorFont", menuFont);
    defaults.put("PasswordField.font", defaults.getFont("TextField.font"));
  }

  @NotNull
  private static FontUIResource getFont(String yosemite, String elCapitan, int size, int style) {
    if (SystemInfo.isMacOSElCapitan && elCapitan != null) {
      try {
        final Font sfFont = Font.createFont(Font.TRUETYPE_FONT, new File("/System/Library/Fonts/" + elCapitan + ".otf"));
        return new FontUIResource(sfFont.deriveFont(size + 0f));
      } catch (Exception ignored) {
      }
    }
    return new FontUIResource(yosemite, style, size);
  }

  public static boolean isGraphite() {
    try {
      // https://developer.apple.com/library/mac/documentation/Cocoa/Reference/ApplicationKit/Classes/NSCell_Class/index.html#//apple_ref/doc/c_ref/NSGraphiteControlTint
      // NSGraphiteControlTint = 6
      return Foundation.invoke("NSColor", "currentControlTint").intValue() == 6;
    } catch (Exception e) {
      return false;
    }
  }
}
