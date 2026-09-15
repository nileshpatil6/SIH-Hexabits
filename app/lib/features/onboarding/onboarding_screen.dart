import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/languages.dart';
import '../../core/permissions.dart';
import '../../core/state/app_state.dart';

class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({super.key});

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen> {
  final _name = TextEditingController();
  String _lang = 'hi';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final s = ref.read(appProvider);
    _name.text = s.nickname;
    _lang = s.lang;
  }

  Future<void> _continue() async {
    setState(() => _busy = true);
    final ok = await requestAllPermissions();
    if (!mounted) return;
    if (!ok) {
      setState(() => _busy = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Microphone and Bluetooth permissions are required to talk over the mesh.')),
      );
      return;
    }
    final name = _name.text.trim().isEmpty ? 'Phone' : _name.text.trim();
    await ref.read(appProvider.notifier).completeOnboarding(name, _lang);
    if (mounted) context.go('/talk');
  }

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 24),
            Icon(Icons.settings_input_antenna, size: 56, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 16),
            Text('iTantra', style: t.headlineLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            Text(
              'Talk to nearby phones without internet or mobile network. '
              'Your voice is turned into text on this phone, sent over Bluetooth or Wi-Fi Direct, '
              'and spoken aloud on the other phone.',
              style: t.bodyLarge,
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Your name or call sign', border: OutlineInputBorder()),
              textInputAction: TextInputAction.done,
            ),
            const SizedBox(height: 24),
            Text('Language you will speak', style: t.titleMedium),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final l in languages)
                  ChoiceChip(
                    label: Text('${l.native}  ·  ${l.english}'),
                    selected: _lang == l.code,
                    onSelected: (_) => setState(() => _lang = l.code),
                  ),
              ],
            ),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _busy ? null : _continue,
              icon: _busy
                  ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.arrow_forward),
              label: const Text('Allow permissions and start'),
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
            ),
          ],
        ),
      ),
    );
  }
}
