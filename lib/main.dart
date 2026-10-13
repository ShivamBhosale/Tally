import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'ui/today_screen.dart';
import 'ui/settings_screen.dart';
import 'ui/stats_screen.dart';
import 'ui/theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent, statusBarIconBrightness: Brightness.light, systemNavigationBarColor: T.bg));
  runApp(const TallyApp());
}

class TallyApp extends StatelessWidget {
  const TallyApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(title: 'Tally', debugShowCheckedModeBanner: false, theme: T.theme(), home: const Shell());
}

class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _i = 0;
  static const _tabs = ['TODAY', 'TRENDS', 'SETTINGS'];

  @override
  Widget build(BuildContext context) {
    const pages = [TodayScreen(), StatsScreen(), SettingsScreen()];
    return Scaffold(
      body: SafeArea(bottom: false, child: IndexedStack(index: _i, children: pages)),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(color: T.bg, border: Border(top: BorderSide(color: T.line))),
        child: SafeArea(
          top: false,
          child: Row(children: [
            for (var n = 0; n < _tabs.length; n++)
              Expanded(
                child: InkWell(
                  onTap: () => setState(() => _i = n),
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    AnimatedContainer(duration: const Duration(milliseconds: 220), height: 3, color: _i == n ? T.lime : Colors.transparent),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: Text(_tabs[n], style: T.mono(12, color: _i == n ? T.ink : T.mute, w: _i == n ? FontWeight.w500 : FontWeight.w400)),
                    ),
                  ]),
                ),
              ),
          ]),
        ),
      ),
    );
  }
}
