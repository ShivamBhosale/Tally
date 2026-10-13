import 'dart:async';
import 'package:flutter/material.dart';
import '../data/api.dart';
import 'theme.dart';

/// Pushed from Today (when blind) and from Settings -> Tools.
class SetupPage extends StatelessWidget {
  const SetupPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(backgroundColor: T.bg, elevation: 0, title: Text('SETUP / HEALTH', style: T.mono(13, color: T.ink)), iconTheme: const IconThemeData(color: T.ink)),
        body: const SafeArea(child: SetupScreen()),
      );
}

class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});
  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> with WidgetsBindingObserver {
  Map<String, dynamic>? _s;
  Timer? _t;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
    _t = Timer.periodic(const Duration(seconds: 3), (_) => _load());
  }

  @override
  void dispose() { _t?.cancel(); WidgetsBinding.instance.removeObserver(this); super.dispose(); }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) { if (s == AppLifecycleState.resumed) _load(); }

  Future<void> _load() async {
    final s = await Api.state();
    if (mounted) setState(() => _s = s);
  }

  @override
  Widget build(BuildContext context) {
    final s = _s;
    if (s == null) return const Center(child: CircularProgressIndicator(color: T.lime));
    final brand = s['brand'] as String;
    final sdk = s['sdk'] as int;
    return ListView(padding: const EdgeInsets.all(20), children: [
      if (sdk >= 33 && s['accessibilityEnabled'] != true)
        _Note('Android 13+ blocks accessibility for sideloaded apps until you allow it: App info → ⋮ (top right) → "Allow restricted settings". Then enable Tally below.',
            action: 'OPEN APP INFO', onTap: Api.openAppInfo),
      _Check(ok: s['accessibilityEnabled'] == true, title: 'Accessibility service enabled', detail: 'Required. Settings → Accessibility → Installed apps → Tally.', action: 'OPEN SETTINGS', onTap: Api.openAccessibility),
      _Check(ok: s['serviceAlive'] == true, title: 'Service is alive', detail: s['serviceAlive'] == true ? 'Connected to this process.' : 'Not connected. If enabled above, toggle it off and on.'),
      _Check(ok: s['batteryExempt'] == true, title: 'Battery optimization exempt', detail: 'Stops Doze from killing the service.', action: 'EXEMPT', onTap: Api.requestBattery),
      _Check(ok: !('${s['rulesVersion']}'.startsWith('0-')), title: 'Detection rules: ${s['rulesVersion']}', detail: 'Source: ${s['rulesSource']}. Targets: ${(s['ruleTargets'] as Map).entries.map((e) => '${e.key == 'com.instagram.android' ? 'instagram' : e.key == 'com.google.android.youtube' ? 'youtube' : e.key} ${e.value}').join(', ')}'),
      const SizedBox(height: 20),
      Text('${brand.toUpperCase()} · ${s['model']} · Android API $sdk', style: T.mono(11)),
      const SizedBox(height: 8),
      ..._oem(brand).map((l) => Padding(padding: const EdgeInsets.only(bottom: 6), child: Text('· $l', style: T.mono(12, color: T.ink)))),
    ]);
  }

  List<String> _oem(String b) {
    if (b.contains('samsung')) return ['Settings → Battery → Background usage limits → Never sleeping apps → add Tally.', 'Recents: tap the Tally icon → "Keep open".'];
    if (b.contains('xiaomi') || b.contains('redmi') || b.contains('poco')) return ['Security → Permissions → Autostart → enable Tally.', 'Recents: long-press Tally card → lock.', 'App info → Battery saver → No restrictions.'];
    if (b.contains('oneplus') || b.contains('oppo') || b.contains('realme')) return ['Settings → Battery → Battery optimization → Tally → Don\'t optimize.', 'App info → Allow auto-launch / background activity.', 'Recents: lock Tally.'];
    if (b.contains('vivo') || b.contains('iqoo')) return ['i Manager → App manager → Autostart → enable Tally.', 'Battery → High background power consumption → allow Tally.'];
    if (b.contains('huawei') || b.contains('honor')) return ['App launch → Tally → manage manually → allow all three toggles.'];
    return ['Stock Android: the battery exemption above is usually enough.', 'If the service stops, re-check that Accessibility still lists Tally as On.'];
  }
}

class _Check extends StatelessWidget {
  const _Check({required this.ok, required this.title, required this.detail, this.action, this.onTap});
  final bool ok;
  final String title, detail;
  final String? action;
  final Future<void> Function()? onTap;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 10),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: T.panel, border: Border(left: BorderSide(color: ok ? T.lime : T.warn, width: 4))),
        child: Row(children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: T.mono(13, color: T.ink, w: FontWeight.w500)),
            const SizedBox(height: 4),
            Text(detail, style: T.mono(11)),
          ])),
          if (!ok && action != null) TextButton(onPressed: onTap, child: Text(action!, style: T.mono(11, color: T.lime))),
        ]),
      );
}

class _Note extends StatelessWidget {
  const _Note(this.text, {required this.action, required this.onTap});
  final String text, action;
  final Future<void> Function() onTap;
  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 14),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(border: Border.all(color: T.lime.withValues(alpha: 0.5))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(text, style: T.mono(12, color: T.ink)),
          TextButton(onPressed: onTap, child: Text(action, style: T.mono(11, color: T.lime))),
        ]),
      );
}
