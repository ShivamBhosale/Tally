import 'package:flutter/services.dart';

/// Single entry point to the Kotlin side. Room-equivalent storage lives in Kotlin; Dart only talks over channels.
class Api {
  static const _m = MethodChannel('tally/api');
  static const _e = EventChannel('tally/counts');

  // ONE native subscription shared by every screen: the EventChannel allows a single listener, and a second
  // receiveBroadcastStream() would silently cancel the first.
  static final Stream<Map<String, int>> _counts = _e
      .receiveBroadcastStream()
      .map((v) => (v as Map).map((k, v) => MapEntry('$k', (v as num).toInt())))
      .asBroadcastStream();

  static Stream<Map<String, int>> counts() => _counts;

  static Future<Map<String, dynamic>> state() async => Map<String, dynamic>.from(await _m.invokeMethod('getState') as Map);
  static Future<void> adjust(String app, int delta) => _m.invokeMethod('adjust', {'app': app, 'delta': delta});
  static Future<void> openAccessibility() => _m.invokeMethod('openAccessibilitySettings');
  static Future<void> openAppInfo() => _m.invokeMethod('openAppInfo');
  static Future<void> requestBattery() => _m.invokeMethod('requestBatteryExemption');
  static Future<void> setCalibration(bool on) => _m.invokeMethod('setCalibration', {'on': on});
  static Future<Map<String, Map<String, int>>> dailyTotals(int days) async {
    final r = await _m.invokeMethod('getDailyTotals', {'days': days}) as Map;
    return r.map((d, v) => MapEntry('$d', (v as Map).map((a, c) => MapEntry('$a', (c as num).toInt()))));
  }

  static Future<Map<String, dynamic>> settings() async => Map<String, dynamic>.from(await _m.invokeMethod('getSettings') as Map);
  static Future<void> setSettings(Map<String, dynamic> m) => _m.invokeMethod('setSettings', m);
  static Future<void> resetToday() => _m.invokeMethod('resetToday');
  static Future<void> clearAll() => _m.invokeMethod('clearAll');
  static Future<void> shareCsv() => _m.invokeMethod('shareCsv');
  static Future<void> requestNotificationPermission() => _m.invokeMethod('requestNotificationPermission');

  /// {instagram: {count, dwellMs}, youtube: {...}} for today.
  static Future<Map<String, Map<String, int>>> today() async {
    final r = await _m.invokeMethod('getToday') as Map;
    return r.map((a, v) => MapEntry('$a', (v as Map).map((k, x) => MapEntry('$k', (x as num).toInt()))));
  }

  /// hours: 24 view counts by local hour; dwellMs: counted on-screen time. Over the last [days] days.
  static Future<({List<int> hours, int dwellMs})> patterns(int days) async {
    final r = await _m.invokeMethod('getPatterns', {'days': days}) as Map;
    return (hours: (r['hours'] as List).map((e) => (e as num).toInt()).toList(), dwellMs: (r['dwellMs'] as num).toInt());
  }
}
