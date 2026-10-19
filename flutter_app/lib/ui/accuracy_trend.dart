import 'package:flutter/material.dart';

import 'app.dart';

class AccuracyTrend extends StatelessWidget {
  const AccuracyTrend({super.key, required this.values, required this.title});
  final List<double> values;
  final String title;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        title,
        style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700),
      ),
      const SizedBox(height: 12),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Semantics(
            label: '$title: ${(values.last * 100).round()}%',
            child: Row(
              children: [
                const SizedBox(
                  height: 140,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [Text('100%'), Text('50%'), Text('0%')],
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: SizedBox(
                    height: 140,
                    child: CustomPaint(painter: _TrendPainter(values)),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ],
  );
}

class _TrendPainter extends CustomPainter {
  _TrendPainter(this.values);
  final List<double> values;
  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = const Color(0xffdce6e2)
      ..strokeWidth = 1;
    for (final fraction in [0.0, .5, 1.0]) {
      final y = 4 + (size.height - 8) * fraction;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    if (values.isEmpty) return;
    final path = Path();
    Offset? last;
    for (var i = 0; i < values.length; i++) {
      final point = Offset(
        values.length == 1
            ? size.width / 2
            : i * size.width / (values.length - 1),
        4 + (1 - values[i]) * (size.height - 8),
      );
      if (i == 0) {
        path.moveTo(point.dx, point.dy);
      } else {
        path.lineTo(point.dx, point.dy);
      }
      last = point;
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = teal
        ..strokeWidth = 3
        ..style = PaintingStyle.stroke
        ..strokeJoin = StrokeJoin.round,
    );
    canvas.drawCircle(last!, 4, Paint()..color = teal);
  }

  @override
  bool shouldRepaint(covariant _TrendPainter old) => old.values != values;
}
