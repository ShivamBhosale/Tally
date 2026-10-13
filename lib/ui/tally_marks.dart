import 'package:flutter/material.dart';
import 'theme.dart';

/// Real tally marks: four strokes, struck through every fifth. The newest stroke draws itself in.
class TallyMarks extends StatefulWidget {
  const TallyMarks({super.key, required this.count, required this.color, this.maxRows = 3});
  final int count;
  final Color color;
  final int maxRows;
  @override
  State<TallyMarks> createState() => _TallyMarksState();
}

class _TallyMarksState extends State<TallyMarks> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 380), value: 1);

  @override
  void didUpdateWidget(TallyMarks old) {
    super.didUpdateWidget(old);
    if (widget.count == old.count + 1) _c.forward(from: 0);
  }

  @override
  void dispose() { _c.dispose(); super.dispose(); }

  static const double _markH = 30, _gap = 9, _groupGap = 20, _rowGap = 14;

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, box) {
        const groupW = 4 * _gap + 6;
        final perRow = ((box.maxWidth + _groupGap) / (groupW + _groupGap)).floor().clamp(1, 99);
        final groups = (widget.count / 5).ceil();
        final capacity = perRow * widget.maxRows;
        final shownGroups = groups.clamp(1, capacity);
        final rows = (shownGroups / perRow).ceil().clamp(1, widget.maxRows);
        final hidden = groups > capacity ? widget.count - capacity * 5 : 0;
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AnimatedBuilder(
            animation: _c,
            builder: (_, _) => CustomPaint(
              size: Size(box.maxWidth, rows * (_markH + _rowGap) - _rowGap),
              painter: _TallyPainter(widget.count, widget.color, _c.value, perRow, capacity),
            ),
          ),
          if (hidden > 0) Padding(padding: const EdgeInsets.only(top: 6), child: Text('+$hidden MORE', style: T.mono(11, color: widget.color))),
        ]);
      });
}

class _TallyPainter extends CustomPainter {
  _TallyPainter(this.count, this.color, this.t, this.perRow, this.capacity);
  final int count, perRow, capacity;
  final Color color;
  final double t;

  @override
  void paint(Canvas c, Size s) {
    final stroke = Paint()..color = color..strokeWidth = 4..strokeCap = StrokeCap.round;
    final slash = Paint()..color = Colors.white..strokeWidth = 4..strokeCap = StrokeCap.round;
    final visible = count.clamp(0, capacity * 5);
    final firstShown = count > capacity * 5 ? count - capacity * 5 : 0; // oldest scroll off, newest stay
    for (var i = 0; i < visible; i++) {
      final n = firstShown + i;
      final g = i ~/ 5, k = i % 5;
      final row = g ~/ perRow, col = g % perRow;
      final x0 = col * (4 * _TallyMarksState._gap + 6 + _TallyMarksState._groupGap) + 2;
      final y0 = row * (_TallyMarksState._markH + _TallyMarksState._rowGap);
      final isNewest = n == count - 1;
      final p = isNewest ? Curves.easeOut.transform(t) : 1.0;
      if (k < 4) {
        final x = x0 + k * _TallyMarksState._gap;
        c.drawLine(Offset(x, y0 + 2), Offset(x, y0 + 2 + (_TallyMarksState._markH - 4) * p), stroke);
      } else {
        final a = Offset(x0 - 5, y0 + _TallyMarksState._markH - 6);
        final b = Offset(x0 + 3 * _TallyMarksState._gap + 5, y0 + 8);
        c.drawLine(a, Offset.lerp(a, b, p)!, slash);
      }
    }
    if (count == 0) {
      c.drawLine(Offset(2, _TallyMarksState._markH / 2), Offset(26, _TallyMarksState._markH / 2), Paint()..color = T.line..strokeWidth = 2);
    }
  }

  @override
  bool shouldRepaint(_TallyPainter o) => o.count != count || o.t != t || o.color != color || o.perRow != perRow;
}
