import 'package:flutter/material.dart';

/// Scoreboard: near-black, one acid-lime accent, condensed display numerals, mono labels.
class T {
  static const bg = Color(0xFF0A0A0B);
  static const panel = Color(0xFF141416);
  static const line = Color(0xFF26262A);
  static const ink = Color(0xFFECECE6);
  static const mute = Color(0xFF86868B);
  static const lime = Color(0xFFC8FF3C);
  static const warn = Color(0xFFFF5A3C);
  static const igTint = Color(0xFFFF7AB6);
  static const ytTint = Color(0xFFFF5A3C);

  static TextStyle num(double size, {Color color = ink}) => TextStyle(
      fontFamily: 'BigShoulders', fontSize: size, fontWeight: FontWeight.w800, height: 0.9, color: color,
      fontFeatures: const [FontFeature.tabularFigures()]);
  static TextStyle mono(double size, {Color color = mute, FontWeight w = FontWeight.w400}) =>
      TextStyle(fontFamily: 'DMMono', fontSize: size, color: color, fontWeight: w, letterSpacing: 0.4);

  static ThemeData theme() => ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: bg,
        fontFamily: 'DMMono',
        colorScheme: const ColorScheme.dark(primary: lime, surface: panel, onSurface: ink),
        splashFactory: NoSplash.splashFactory,
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: bg,
          indicatorColor: lime.withValues(alpha: 0.16),
          labelTextStyle: WidgetStatePropertyAll(mono(11, color: ink)),
        ),
      );
}
