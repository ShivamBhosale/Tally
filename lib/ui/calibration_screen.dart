import 'dart:async';
import 'package:flutter/material.dart';
import '../data/api.dart';
import 'theme.dart';

class CalibrationPage extends StatelessWidget {
  const CalibrationPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(backgroundColor: T.bg, elevation: 0, title: Text('DEBUG / CALIBRATION', style: T.mono(13, color: T.ink)), iconTheme: const IconThemeData(color: T.ink)),
        body: const SafeArea(child: CalibrationScreen()),
      );
}

class CalibrationScreen extends StatefulWidget {
  const CalibrationScreen({super.key});
  @override
  State<CalibrationScreen> createState() => _CalibrationScreenState();
}

class _CalibrationScreenState extends State<CalibrationScreen> {
  Map<String, dynamic>? _s;
  Timer? _t;

  @override
  void initState() { super.initState(); _load(); _t = Timer.periodic(const Duration(seconds: 2), (_) => _load()); }
  @override
  void dispose() { _t?.cancel(); super.dispose(); }

  Future<void> _load() async { final s = await Api.state(); if (mounted) setState(() => _s = s); }

  @override
  Widget build(BuildContext context) {
    final s = _s;
    if (s == null) return const SizedBox();
    final on = s['calibrationRunning'] == true;
    final alive = s['serviceAlive'] == true;
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text(on ? 'RECORDING' : 'IDLE', style: T.num(72, color: on ? T.warn : T.mute)),
      const SizedBox(height: 8),
      Text(alive ? 'While on, Tally logs accessibility events and tree snapshots from Instagram and YouTube only to a file on this phone. Text is truncated; no network.' : 'Enable the accessibility service first (SETUP tab).', style: T.mono(12, color: T.ink)),
      const SizedBox(height: 16),
      FilledButton(
        style: FilledButton.styleFrom(backgroundColor: on ? T.panel : T.lime, foregroundColor: on ? T.ink : Colors.black, shape: const RoundedRectangleBorder(), padding: const EdgeInsets.symmetric(vertical: 16)),
        onPressed: alive ? () async { await Api.setCalibration(!on); _load(); } : null,
        child: Text(on ? 'STOP RECORDING' : 'START RECORDING', style: T.mono(13, color: on ? T.ink : Colors.black, w: FontWeight.w500)),
      ),
      const SizedBox(height: 20),
      if (s['calibrationFile'] != null) Text('Current file:\n${s['calibrationFile']}', style: T.mono(11)),
      const SizedBox(height: 8),
      Text('Dump folder:\n${s['dumpDir']}', style: T.mono(11)),
      const SizedBox(height: 20),
      Text('Capture steps are in README → Calibration. Pull with:\nadb pull "${s['dumpDir']}" ./dumps', style: T.mono(11, color: T.ink)),
    ]);
  }
}
