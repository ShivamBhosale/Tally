import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../data/api.dart';
import 'eye.dart';
import 'reveal.dart';
import 'setup_screen.dart';
import 'tally_marks.dart';
import 'theme.dart';

class TodayScreen extends StatefulWidget {
  const TodayScreen({super.key});
  @override
  State<TodayScreen> createState() => _TodayScreenState();
}

class _TodayScreenState extends State<TodayScreen> {
  Map<String, int> _c = const {'instagram': 0, 'youtube': 0};
  Map<String, Map<String, int>> _sum = const {};
  int _every = 15;
  bool _alive = true, _enabled = true;
  int _pulse = 0;
  StreamSubscription? _sub;
  Timer? _t;

  @override
  void initState() {
    super.initState();
    _sub = Api.counts().listen((v) {
      final up = (v['instagram'] ?? 0) + (v['youtube'] ?? 0) > (_c['instagram'] ?? 0) + (_c['youtube'] ?? 0);
      setState(() { _c = v; if (up) _pulse++; });
      _loadSummary();
    });
    _loadAll();
    _t = Timer.periodic(const Duration(seconds: 3), (_) => _loadHealth());
  }

  @override
  void dispose() { _sub?.cancel(); _t?.cancel(); super.dispose(); }

  Future<void> _loadAll() async {
    final s = await Api.settings();
    final st = await Api.state();
    if (mounted) {
      setState(() {
        _every = (s['warnEvery'] as num).toInt();
        _c = (st['counts'] as Map).map((k, v) => MapEntry('$k', (v as num).toInt()));
      });
    }
    _loadSummary();
    _loadHealth();
  }

  Future<void> _loadSummary() async {
    final s = await Api.today();
    if (mounted) setState(() => _sum = s);
  }

  Future<void> _loadHealth() async {
    final s = await Api.state();
    if (mounted) setState(() { _alive = s['serviceAlive'] == true; _enabled = s['accessibilityEnabled'] == true; });
  }

  int _dwell(String app) => _sum[app]?['dwellMs'] ?? 0;

  @override
  Widget build(BuildContext context) {
    final ig = _c['instagram'] ?? 0, yt = _c['youtube'] ?? 0;
    final level = math.max(eyeLevel(ig, _every), eyeLevel(yt, _every));
    final tint = eyeColor(level);
    final total = ig + yt;
    final onScreen = _dwell('instagram') + _dwell('youtube');
    final now = DateTime.now();
    const wd = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
    const mo = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

    return Stack(children: [
      Positioned.fill(child: CustomPaint(painter: _LedgerBackdrop(tint, level))),
      ListView(padding: const EdgeInsets.fromLTRB(20, 14, 20, 28), children: [
        Reveal(delayMs: 0, child: Row(children: [
          Text('TALLY', style: T.mono(13, color: T.ink, w: FontWeight.w500)),
          Container(width: 6, height: 6, margin: const EdgeInsets.symmetric(horizontal: 8), decoration: BoxDecoration(color: _alive ? T.lime : T.warn, shape: BoxShape.circle)),
          Text(_alive ? 'WATCHING' : 'BLIND', style: T.mono(11, color: _alive ? T.mute : T.warn)),
          const Spacer(),
          Text('${wd[now.weekday - 1]} ${now.day} ${mo[now.month - 1]}', style: T.mono(11)),
        ])),
        if (!_alive) Padding(padding: const EdgeInsets.only(top: 14), child: _Blind(enabled: _enabled)),
        const SizedBox(height: 10),
        Reveal(delayMs: 80, child: SizedBox(
          height: 210,
          child: Center(child: AspectRatio(aspectRatio: 1.9, child: EyeHero(level: level, pulse: _pulse))),
        )),
        Reveal(delayMs: 200, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('YOUR EYES ARE', style: T.mono(11)),
          const SizedBox(height: 4),
          FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Text(eyeWord(level), style: T.num(96, color: tint))),
          const SizedBox(height: 8),
          Text('$total ${total == 1 ? 'VIDEO' : 'VIDEOS'}  ·  ${total == 0 ? 'NOTHING YET' : '${fmtDuration(onScreen)} ON SCREEN'}', style: T.mono(12, color: T.ink)),
        ])),
        const SizedBox(height: 26),
        Reveal(delayMs: 320, child: _Ledger(label: 'REELS', sub: 'INSTAGRAM', app: 'instagram', count: ig, dwellMs: _dwell('instagram'), every: _every, tint: T.igTint)),
        const SizedBox(height: 14),
        Reveal(delayMs: 420, child: _Ledger(label: 'SHORTS', sub: 'YOUTUBE', app: 'youtube', count: yt, dwellMs: _dwell('youtube'), every: _every, tint: T.ytTint)),
        const SizedBox(height: 18),
        Reveal(delayMs: 520, child: Text('Counts go up on their own while you watch. Use + / − only to fix a miss.', style: T.mono(10))),
      ]),
    ]);
  }
}

class _Blind extends StatelessWidget {
  const _Blind({required this.enabled});
  final bool enabled;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: T.warn.withValues(alpha: 0.12), border: Border.all(color: T.warn.withValues(alpha: 0.6))),
        child: Row(children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('COUNTING IS OFF', style: T.mono(12, color: T.warn, w: FontWeight.w500)),
            const SizedBox(height: 4),
            Text(enabled ? 'The service is enabled but not running. Toggle it off and on in Accessibility.' : 'Turn on the Tally accessibility service, or nothing is counted.', style: T.mono(11, color: T.ink)),
          ])),
          TextButton(
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SetupPage())),
            child: Text('FIX', style: T.mono(12, color: T.warn, w: FontWeight.w500)),
          ),
        ]),
      );
}

class _Ledger extends StatelessWidget {
  const _Ledger({required this.label, required this.sub, required this.app, required this.count, required this.dwellMs, required this.every, required this.tint});
  final String label, sub, app;
  final int count, dwellMs, every;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    final toNext = every - (count % every);
    final progress = (count % every) / every;
    return Container(
      decoration: BoxDecoration(color: T.panel.withValues(alpha: 0.92), border: Border(left: BorderSide(color: tint, width: 4))),
      padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: T.mono(13, color: T.ink, w: FontWeight.w500)),
            Text(sub, style: T.mono(10)),
          ])),
          TweenAnimationBuilder<double>(
            tween: Tween(end: count.toDouble()), duration: const Duration(milliseconds: 350), curve: Curves.easeOut,
            builder: (_, v, __) => Text('${v.round()}', style: T.num(76)),
          ),
          const SizedBox(width: 4),
          Column(children: [
            _Nudge(icon: Icons.add, onTap: () => Api.adjust(app, 1)),
            _Nudge(icon: Icons.remove, onTap: count > 0 ? () => Api.adjust(app, -1) : null),
          ]),
        ]),
        const SizedBox(height: 14),
        Padding(padding: const EdgeInsets.only(right: 8), child: TallyMarks(count: count, color: tint)),
        const SizedBox(height: 14),
        Padding(
          padding: const EdgeInsets.only(right: 8),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Text(count == 0 ? 'ON SCREEN —' : 'ON SCREEN ${fmtDuration(dwellMs)}', style: T.mono(10)),
              const Spacer(),
              Text('EYE-CHECK IN $toNext', style: T.mono(10, color: toNext <= 3 ? T.warn : T.mute)),
            ]),
            const SizedBox(height: 6),
            ClipRect(child: LinearProgressIndicator(value: progress, minHeight: 3, backgroundColor: T.line, valueColor: AlwaysStoppedAnimation(toNext <= 3 ? T.warn : tint))),
          ]),
        ),
      ]),
    );
  }
}

class _Nudge extends StatelessWidget {
  const _Nudge({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: 40, height: 36,
        child: IconButton(padding: EdgeInsets.zero, onPressed: onTap, icon: Icon(icon, size: 20), color: onTap == null ? T.line : T.mute),
      );
}

/// Ledger rules plus a soft glow behind the eye that goes lime -> red as it wears out.
class _LedgerBackdrop extends CustomPainter {
  _LedgerBackdrop(this.tint, this.level);
  final Color tint;
  final double level;

  @override
  void paint(Canvas c, Size s) {
    final glow = Paint()..shader = RadialGradient(colors: [tint.withValues(alpha: 0.10 + 0.10 * level), Colors.transparent]).createShader(Rect.fromCircle(center: Offset(s.width / 2, 170), radius: s.width * 0.85));
    c.drawRect(Offset.zero & s, glow);
    final line = Paint()..color = Colors.white.withValues(alpha: 0.025)..strokeWidth = 1;
    for (double y = 28; y < s.height; y += 28) { c.drawLine(Offset(0, y), Offset(s.width, y), line); }
  }

  @override
  bool shouldRepaint(_LedgerBackdrop o) => o.tint != tint || o.level != level;
}
