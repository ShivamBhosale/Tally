import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'theme.dart';

/// 0 = fresh, 1 = wrecked. Same formula as the overlay pill: count / (4 x warn interval).
double eyeLevel(int count, int warnEvery) => (count / (warnEvery * 4)).clamp(0.0, 1.0).toDouble();

String eyeWord(double l) => l < 0.15 ? 'FRESH' : l < 0.4 ? 'TIRED' : l < 0.65 ? 'STRAINED' : l < 0.9 ? 'BLOODSHOT' : 'FRIED';

Color eyeColor(double l) => Color.lerp(T.lime, T.warn, ((l - 0.2) / 0.7).clamp(0.0, 1.0))!;

String fmtDuration(int ms) {
  final s = ms ~/ 1000;
  if (s < 60) return '${s}S';
  final m = s ~/ 60;
  if (m < 60) return '${m}M';
  return '${m ~/ 60}H ${(m % 60).toString().padLeft(2, '0')}M';
}

/// Dart port of the overlay's EyeView so the app and the floating pill show the same eye.
class EyePainter extends CustomPainter {
  EyePainter({required this.level, this.blink = 0, this.alert = 0, this.look = 0});
  final double level, blink, alert, look; // look: -1..1 iris offset

  static const _veins = [
    [0.00, 0.52, 0.34, 0.48], [1.00, 0.50, 0.66, 0.54], [0.06, 0.30, 0.30, 0.44], [0.94, 0.70, 0.70, 0.56],
    [0.10, 0.74, 0.32, 0.58], [0.90, 0.28, 0.68, 0.42], [0.20, 0.18, 0.36, 0.40], [0.80, 0.84, 0.64, 0.60],
  ];

  @override
  void paint(Canvas c, Size s) {
    // The eye uses the top 86% of the box; the rest is reserved for the under-eye bag so nothing spills out.
    final w = s.width, h = s.height * 0.86, cx = w / 2, cy = h / 2;
    final sw = math.max(1.5, h * 0.05);
    final almond = Path()
      ..moveTo(sw, cy)
      ..quadraticBezierTo(cx, cy - h * 0.95, w - sw, cy)
      ..quadraticBezierTo(cx, cy + h * 0.95, sw, cy)
      ..close();
    c.save();
    c.clipPath(almond);

    final sclera = Color.lerp(Color.lerp(const Color(0xFFF6F5EF), const Color(0xFFE9948A), level), const Color(0xFFFF3B30), alert * 0.55)!;
    c.drawRect(Rect.fromLTWH(0, 0, w, h), Paint()..color = sclera);

    final vp = Paint()..style = PaintingStyle.stroke..strokeWidth = math.max(1.5, h * 0.03);
    final shown = math.min(_veins.length, (level * _veins.length + 0.4).floor());
    for (var i = 0; i < shown; i++) {
      final v = _veins[i];
      vp.color = const Color(0xFFC62828).withValues(alpha: ((110 + 120 * level) / 255).clamp(0.0, 1.0));
      c.drawLine(Offset(v[0] * w, v[1] * h), Offset(v[2] * w, v[3] * h), vp);
    }

    final ir = h * 0.36;
    final icx = cx + look * w * 0.07, icy = cy + level * h * 0.05;
    c.drawCircle(Offset(icx, icy), ir, Paint()..color = Color.lerp(const Color(0xFF2FB27A), const Color(0xFF7A7F58), level)!);
    c.drawCircle(Offset(icx, icy), ir * 0.78, Paint()..color = Colors.black.withValues(alpha: 0.10)..style = PaintingStyle.stroke..strokeWidth = ir * 0.12);
    c.drawCircle(Offset(icx, icy), ir * (0.48 + 0.12 * level), Paint()..color = const Color(0xFF101012));
    c.drawCircle(Offset(icx - ir * 0.32, icy - ir * 0.34), ir * 0.17, Paint()..color = Colors.white.withValues(alpha: 1 - level));

    final topY = cy - h * 0.475;
    final lidAmt = math.max(level * 0.62, blink);
    final lidY = topY + lidAmt * h * 0.95;
    if (lidAmt > 0.01) {
      final lid = Path()
        ..moveTo(0, 0)
        ..lineTo(w, 0)
        ..lineTo(w, lidY)
        ..quadraticBezierTo(cx, lidY + h * 0.14, 0, lidY)
        ..close();
      c.drawPath(lid, Paint()..color = Color.lerp(const Color(0xFF4A3F46), const Color(0xFF2B2126), level)!);
      c.drawPath(lid, Paint()..style = PaintingStyle.stroke..strokeWidth = sw..color = const Color(0xFF14100F));
    }
    c.restore();

    c.drawPath(almond, Paint()..style = PaintingStyle.stroke..strokeWidth = sw..color = Colors.white.withValues(alpha: 0.9));

    if (level > 0.25) {
      final bag = Rect.fromLTRB(w * 0.14, cy + h * 0.1, w * 0.86, s.height - 2);
      c.drawArc(bag, 25 * math.pi / 180, 130 * math.pi / 180, false,
          Paint()..style = PaintingStyle.stroke..strokeWidth = math.max(1.5, h * 0.04)..color = const Color(0xFF9B7FB5).withValues(alpha: ((200 * (level - 0.2)) / 255).clamp(0.0, 0.8)));
    }
  }

  @override
  bool shouldRepaint(EyePainter o) => o.level != level || o.blink != blink || o.alert != alert || o.look != look;
}

/// The hero eye: blinks at random, glances around, and flashes red briefly when [pulse] changes (a new count).
class EyeHero extends StatefulWidget {
  const EyeHero({super.key, required this.level, required this.pulse});
  final double level;
  final int pulse;
  @override
  State<EyeHero> createState() => _EyeHeroState();
}

class _EyeHeroState extends State<EyeHero> with TickerProviderStateMixin {
  late final AnimationController _blink = AnimationController(vsync: this, duration: const Duration(milliseconds: 220));
  late final AnimationController _alert = AnimationController(vsync: this, duration: const Duration(milliseconds: 600));
  late final AnimationController _look = AnimationController(vsync: this, duration: const Duration(milliseconds: 500));
  double _from = 0, _to = 0;
  final _rng = math.Random();
  Timer? _t;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  void _schedule() {
    // Heavier eyes blink slower and less often.
    final ms = 2200 + _rng.nextInt(2800) + (widget.level * 1800).round();
    _t = Timer(Duration(milliseconds: ms), () async {
      if (!mounted) return;
      if (_rng.nextInt(3) == 0) {
        _from = _look.value * 2 - 1;
        _to = _rng.nextDouble() * 1.6 - 0.8;
        _look.forward(from: 0);
      } else {
        await _blink.forward(from: 0);
        if (mounted) await _blink.reverse();
      }
      if (mounted) _schedule();
    });
  }

  @override
  void didUpdateWidget(EyeHero old) {
    super.didUpdateWidget(old);
    if (old.pulse != widget.pulse) _alert.forward(from: 0);
  }

  @override
  void dispose() { _t?.cancel(); _blink.dispose(); _alert.dispose(); _look.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: Listenable.merge([_blink, _alert, _look]),
        builder: (_, _) {
          final blink = Curves.easeInOut.transform(_blink.value);
          final alert = math.sin(_alert.value * math.pi);
          final look = _from + (_to - _from) * Curves.easeInOut.transform(_look.value);
          return CustomPaint(painter: EyePainter(level: widget.level, blink: blink, alert: alert, look: look));
        },
      );
}
