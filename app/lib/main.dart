import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/state/app_state.dart';
import 'features/alerts/alert_overlay.dart';
import 'features/onboarding/onboarding_screen.dart';
import 'features/peers/peers_screen.dart';
import 'features/settings/diagnostics_screen.dart';
import 'features/settings/models_screen.dart';
import 'features/settings/settings_screen.dart';
import 'features/talk/talk_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ProviderScope(child: ItantraApp()));
}

final _router = GoRouter(
  initialLocation: '/talk',
  routes: [
    GoRoute(path: '/onboarding', builder: (_, _) => const OnboardingScreen()),
    ShellRoute(
      builder: (context, state, child) => HomeShell(location: state.uri.path, child: child),
      routes: [
        GoRoute(path: '/talk', builder: (_, _) => const TalkScreen()),
        GoRoute(path: '/peers', builder: (_, _) => const PeersScreen()),
        GoRoute(path: '/settings', builder: (_, _) => const SettingsScreen()),
      ],
    ),
    GoRoute(path: '/models', builder: (_, _) => const ModelsScreen()),
    GoRoute(path: '/diagnostics', builder: (_, _) => const DiagnosticsScreen()),
  ],
);

class ItantraApp extends ConsumerWidget {
  const ItantraApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    const seed = Color(0xFF0B6E4F); // field-radio green
    return MaterialApp.router(
      title: 'iTantra',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: seed, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: seed, brightness: Brightness.dark, useMaterial3: true),
      routerConfig: _router,
      builder: (context, child) => AlertOverlay(child: child ?? const SizedBox()),
    );
  }
}

class HomeShell extends ConsumerWidget {
  const HomeShell({super.key, required this.location, required this.child});
  final String location;
  final Widget child;

  static const _tabs = ['/talk', '/peers', '/settings'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(appProvider);
    // First launch goes through onboarding (name, language, permissions).
    ref.listen(appProvider.select((s) => s.onboarded), (_, onboarded) {});
    if (!s.onboarded && s.peerId.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) => context.go('/onboarding'));
    }
    ref.listen(appProvider.select((s) => s.lastError), (_, err) {
      if (err == null) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(err)));
      ref.read(appProvider.notifier).clearError();
    });

    final index = _tabs.indexOf(location).clamp(0, 2);
    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => context.go(_tabs[i]),
        destinations: [
          const NavigationDestination(icon: Icon(Icons.record_voice_over_outlined), selectedIcon: Icon(Icons.record_voice_over), label: 'Talk'),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: s.peers.isNotEmpty,
              label: Text('${s.peers.length}'),
              child: const Icon(Icons.hub_outlined),
            ),
            selectedIcon: const Icon(Icons.hub),
            label: 'Mesh',
          ),
          const NavigationDestination(icon: Icon(Icons.tune_outlined), selectedIcon: Icon(Icons.tune), label: 'Settings'),
        ],
      ),
    );
  }
}
