import 'dart:async';
import 'package:flutter/material.dart';
import '../data/api.dart';
import 'eye.dart';
import 'reveal.dart';
import 'theme.dart';

class StatsScreen extends StatefulWidget {
  const StatsScreen({super.key});
  @override
  State<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends State<StatsScreen> {
  int _days = 7;
  Map<String, Map<String, int>> _t = const {};
  List<int> _hours = List.filled(24, 0);
  int _dwell = 0;
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    _load();
    _sub = Api.counts().listen((_) => _load());
  }

  @override
  void dispose() { _sub?.cancel(); super.dispose(); }

  Future<void> _load() async {
    final t = await Api.dailyTotals(_days);
    final p = await Api.patterns(_days);
    if (mounted) setState(() { _t = t; _hours = p.hours; _dwell = p.dwellMs; });
  }

  String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final days = [for (var i = _days - 1; i >= 0; i--) DateTime(now.year, now.month, now.day).subtract(Duration(days: i))];
    final ig = [for (final d in days) _t[_iso(d)]?['instagram'] ?? 0];
    final yt = [for (final d in days) _t[_iso(d)]?['youtube'] ?? 0];
    final tot = [for (var i = 0; i < days.length; i++) ig[i] + yt[i]];
    final sum = tot.fold<int>(0, (a, b) => a + b);
    final avg = sum / _days;
    int best = 0, worst = 0;
    for (var i = 0; i < tot.length; i++) {
      if (tot[i] > tot[best]) best = i;
      if (tot[i] < tot[worst]) worst = i;
    }
    String lab(int i) => '${days[i].month}/${days[i].day}';
    final peak = _hours.fold<int>(0, (a, b) => a > b ? a : b);
    final peakHour = peak == 0 ? null : _hours.indexOf(peak);
    String hr(int h) => '${h % 12 == 0 ? 12 : h % 12}${h < 12 ? 'AM' : 'PM'}';

    return ListView(padding: const EdgeInsets.fromLTRB(20, 14, 20, 28), children: [
      Row(children: [
        Text('TRENDS', style: T.mono(13, color: T.ink, w: FontWeight.w500)),
        const Spacer(),
        for (final d in [7, 30])
          Padding(
            padding: const EdgeInsets.only(left: 8),
            child: ChoiceChip(
              label: Text('${d}D', style: T.mono(11, color: _days == d ? Colors.black : T.ink)),
              selected: _days == d, selectedColor: T.lime, backgroundColor: T.panel, side: BorderSide.none, showCheckmark: false,
              shape: const RoundedRectangleBorder(),
              onSelected: (_) { setState(() => _days = d); _load(); },
            ),
          ),
      ]),
      const SizedBox(height: 18),
      Reveal(delayMs: 0, child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
        Text('$sum', style: T.num(120, color: T.lime)),
        const SizedBox(width: 12),
        Expanded(child: Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('VIDEOS IN $_days DAYS', style: T.mono(11)),
            Text('${fmtDuration(_dwell)} ON SCREEN', style: T.mono(12, color: T.ink)),
          ]),
        )),
      ])),
      const SizedBox(height: 22),
      Reveal(delayMs: 100, child: _Card(title: 'PER DAY', child: SizedBox(height: 170, child: CustomPaint(painter: _Bars(ig, yt, days.map((d) => '${d.day}').toList(), _days), size: Size.infinite)))),
      const SizedBox(height: 8),
      Row(children: [_dot(T.igTint), Text(' REELS   ', style: T.mono(10)), _dot(T.ytTint), Text(' SHORTS', style: T.mono(10))]),
      const SizedBox(height: 22),
      Reveal(delayMs: 200, child: _Card(
        title: 'WHEN YOU SCROLL',
        trailing: peakHour == null ? null : 'PEAK ${hr(peakHour)}',
        child: SizedBox(height: 110, child: CustomPaint(painter: _Hours(_hours), size: Size.infinite)),
      )),
      const SizedBox(height: 22),
      _kv('DAILY AVERAGE', avg.toStringAsFixed(1)),
      _kv('WORST DAY', sum == 0 ? '—' : '${tot[best]}  (${lab(best)})'),
      _kv('QUIETEST DAY', sum == 0 ? '—' : '${tot[worst]}  (${lab(worst)})'),
    ]);
  }

  Widget _dot(Color c) => Container(width: 10, height: 10, color: c);
  Widget _kv(String k, String v) => Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: const BoxDecoration(border: Border(top: BorderSide(color: T.line))),
        child: Row(children: [Text(k, style: T.mono(12)), const Spacer(), Text(v, style: T.mono(15, color: T.ink, w: FontWeight.w500))]),
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.title, required this.child, this.trailing});
  final String title;
  final String? trailing;
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        color: T.panel,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Text(title, style: T.mono(11, color: T.lime)), const Spacer(), if (trailing != null) Text(trailing!, style: T.mono(11, color: T.ink))]),
          const SizedBox(height: 14),
          child,
        ]),
      );
}

class _Bars extends CustomPainter {
  _Bars(this.ig, this.yt, this.labels, this.days);
  final List<int> ig, yt;
  final List<String> labels;
  final int days;

  @override
  void paint(Canvas c, Size s) {
    final totals = [for (var i = 0; i < ig.length; i++) ig[i] + yt[i]];
    final maxV = (totals.fold<int>(1, (a, b) => a > b ? a : b)).toDouble();
    final plotH = s.height - 22;
    final slot = s.width / ig.length;
    final bw = slot * (days > 7 ? 0.62 : 0.5);
    c.drawLine(Offset(0, plotH), Offset(s.width, plotH), Paint()..color = T.line..strokeWidth = 1);
    for (var i = 0; i < ig.length; i++) {
      final x = i * slot + (slot - bw) / 2;
      final hIg = ig[i] / maxV * (plotH - 14), hYt = yt[i] / maxV * (plotH - 14);
      c.drawRect(Rect.fromLTWH(x, plotH - hIg, bw, hIg), Paint()..color = T.igTint);
      c.drawRect(Rect.fromLTWH(x, plotH - hIg - hYt, bw, hYt), Paint()..color = T.ytTint);
      if (totals[i] > 0 && days <= 7) _text(c, '${totals[i]}', Offset(x + bw / 2, plotH - hIg - hYt - 13), T.ink, 10);
      if (days <= 7 || i % 5 == 0 || i == ig.length - 1) _text(c, labels[i], Offset(x + bw / 2, plotH + 4), T.mute, 10);
    }
  }

  void _text(Canvas c, String t, Offset o, Color col, double size) {
    final tp = TextPainter(text: TextSpan(text: t, style: T.mono(size, color: col)), textDirection: TextDirection.ltr)..layout();
    tp.paint(c, Offset(o.dx - tp.width / 2, o.dy));
  }

  @override
  bool shouldRepaint(_Bars o) => true;
}

/// 24 columns, midnight to 11PM. Late-night columns turn red: that is the point of this chart.
class _Hours extends CustomPainter {
  _Hours(this.h);
  final List<int> h;

  @override
  void paint(Canvas c, Size s) {
    final maxV = h.fold<int>(1, (a, b) => a > b ? a : b).toDouble();
    final plotH = s.height - 18;
    final slot = s.width / 24, bw = slot * 0.62;
    c.drawLine(Offset(0, plotH), Offset(s.width, plotH), Paint()..color = T.line..strokeWidth = 1);
    for (var i = 0; i < 24; i++) {
      final bh = h[i] / maxV * (plotH - 4);
      final late = i >= 23 || i < 5;
      final col = h[i] == 0 ? T.line : (late ? T.warn : T.lime);
      c.drawRect(Rect.fromLTWH(i * slot + (slot - bw) / 2, plotH - (h[i] == 0 ? 2 : bh), bw, h[i] == 0 ? 2 : bh), Paint()..color = col);
    }
    for (final i in [0, 6, 12, 18]) {
      final tp = TextPainter(text: TextSpan(text: i == 0 ? '12A' : i == 12 ? '12P' : '${i % 12}${i < 12 ? 'A' : 'P'}', style: T.mono(9)), textDirection: TextDirection.ltr)..layout();
      tp.paint(c, Offset(i * slot, plotH + 4));
    }
  }

  @override
  bool shouldRepaint(_Hours o) => true;
}
