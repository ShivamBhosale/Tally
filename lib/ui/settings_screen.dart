import 'package:flutter/material.dart';
import '../data/api.dart';
import 'calibration_screen.dart';
import 'setup_screen.dart';
import 'theme.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  Map<String, dynamic>? _s;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async { final s = await Api.settings(); if (mounted) setState(() => _s = s); }

  Future<void> _set(String k, Object v) async {
    setState(() => _s = {...?_s, k: v});
    await Api.setSettings({k: v});
  }

  Future<bool> _confirm(String title, String body) async =>
      await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          backgroundColor: T.panel, shape: const RoundedRectangleBorder(),
          title: Text(title, style: T.mono(14, color: T.ink, w: FontWeight.w500)),
          content: Text(body, style: T.mono(12)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text('CANCEL', style: T.mono(12))),
            TextButton(onPressed: () => Navigator.pop(c, true), child: Text('CONFIRM', style: T.mono(12, color: T.warn))),
          ],
        ),
      ) ?? false;

  @override
  Widget build(BuildContext context) {
    final s = _s;
    if (s == null) return const SizedBox();
    final dwell = (s['minDwellMs'] as num).toDouble() / 1000;
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text('SETTINGS', style: T.mono(13, color: T.ink, w: FontWeight.w500)),
      _h('TOOLS'),
      _btn('SETUP / HEALTH  ·  accessibility, battery, rules', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SetupPage()))),
      _btn('DEBUG / CALIBRATION  ·  record dumps', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const CalibrationPage()))),
      _h('COUNTING'),
      Text('Minimum dwell: ${dwell.toStringAsFixed(dwell % 1 == 0 ? 0 : 1)} s', style: T.mono(13, color: T.ink)),
      Slider(value: dwell.clamp(1, 10), min: 1, max: 10, divisions: 9, activeColor: T.lime, inactiveColor: T.line,
          onChanged: (v) => setState(() => _s = {...s, 'minDwellMs': (v * 1000).round()}),
          onChangeEnd: (v) => Api.setSettings({'minDwellMs': (v * 1000).round()})),
      _sw('Instagram Reels', 'instagramEnabled'),
      _sw('YouTube Shorts', 'youtubeEnabled'),
      _h('OVERLAY'),
      _sw('Show floating counter', 'overlayEnabled'),
      _sw('Only inside the Reels / Shorts player', 'overlayOnlyInPlayer'),
      Row(children: [
        Text('Size', style: T.mono(13, color: T.ink)), const Spacer(),
        SegmentedButton<String>(
          showSelectedIcon: false,
          style: SegmentedButton.styleFrom(shape: const RoundedRectangleBorder(), selectedBackgroundColor: T.lime, selectedForegroundColor: Colors.black, textStyle: T.mono(11)),
          segments: const [ButtonSegment(value: 'small', label: Text('SMALL')), ButtonSegment(value: 'medium', label: Text('MEDIUM'))],
          selected: {s['overlaySize'] as String},
          onSelectionChanged: (v) => _set('overlaySize', v.first),
        ),
      ]),
      const SizedBox(height: 8),
      Text('Opacity: ${((s['overlayOpacity'] as num) * 100).round()}%', style: T.mono(13, color: T.ink)),
      Slider(value: (s['overlayOpacity'] as num).toDouble().clamp(0.3, 1.0), min: 0.3, max: 1, activeColor: T.lime, inactiveColor: T.line,
          onChanged: (v) => setState(() => _s = {...s, 'overlayOpacity': v}), onChangeEnd: (v) => Api.setSettings({'overlayOpacity': v})),
      Text('Tap the pill: expand / dot.  Long-press: −1 / +1.  Drag to move.', style: T.mono(11)),
      _h('EYE & WARNING'),
      _sw('Eye icon that wears out as you scroll', 'eyeEnabled'),
      Text('Warn every ${s['warnEvery']} videos (per app, same number as the pill)', style: T.mono(13, color: T.ink)),
      Slider(value: (s['warnEvery'] as num).toDouble().clamp(5, 50), min: 5, max: 50, divisions: 9, activeColor: T.lime, inactiveColor: T.line,
          onChanged: (v) => setState(() => _s = {...s, 'warnEvery': v.round()}), onChangeEnd: (v) => Api.setSettings({'warnEvery': v.round()})),
      _sw('Vibrate on warning', 'warnVibrate'),
      _sw('Dance the counter on warning', 'warnDance'),
      Text('The eye is fully worn out at ${(s['warnEvery'] as num).round() * 4} videos.', style: T.mono(11)),
      _h('NOTIFICATION'),
      _sw('Ongoing notification with today\'s count', 'notificationEnabled', onTurnOn: Api.requestNotificationPermission),
      _h('DATA'),
      _btn('EXPORT CSV', () => Api.shareCsv()),
      _btn('RESET TODAY', () async { if (await _confirm('Reset today?', 'Deletes every entry for today.')) await Api.resetToday(); }, danger: true),
      _btn('CLEAR ALL DATA', () async { if (await _confirm('Clear all data?', 'Deletes the whole history. This cannot be undone.')) await Api.clearAll(); }, danger: true),
    ]);
  }

  Widget _h(String t) => Padding(padding: const EdgeInsets.only(top: 26, bottom: 6), child: Text(t, style: T.mono(11, color: T.lime)));

  Widget _sw(String label, String key, {Future<void> Function()? onTurnOn}) => SwitchListTile(
        contentPadding: EdgeInsets.zero, dense: true,
        title: Text(label, style: T.mono(13, color: T.ink)),
        value: _s![key] as bool, activeThumbColor: T.lime,
        onChanged: (v) async { if (v && onTurnOn != null) await onTurnOn(); _set(key, v); },
      );

  Widget _btn(String t, VoidCallback f, {bool danger = false}) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: OutlinedButton(
          onPressed: f,
          style: OutlinedButton.styleFrom(shape: const RoundedRectangleBorder(), side: BorderSide(color: danger ? T.warn : T.line), padding: const EdgeInsets.symmetric(vertical: 14), alignment: Alignment.centerLeft),
          child: Text(t, style: T.mono(12, color: danger ? T.warn : T.ink)),
        ),
      );
}
