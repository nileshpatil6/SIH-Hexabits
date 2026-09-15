import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/state/app_state.dart';

/// Full-screen flashing banner shown while an alert is being announced.
/// The only way to silence it is the Acknowledge button.
class AlertOverlay extends ConsumerStatefulWidget {
  const AlertOverlay({super.key, required this.child});
  final Widget child;

  @override
  ConsumerState<AlertOverlay> createState() => _AlertOverlayState();
}

class _AlertOverlayState extends ConsumerState<AlertOverlay> with SingleTickerProviderStateMixin {
  late final AnimationController _flash = AnimationController(vsync: this, duration: const Duration(milliseconds: 600))
    ..repeat(reverse: true);

  @override
  void dispose() {
    _flash.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(appProvider);
    final id = s.alertActiveId;
    final item = id == null ? null : s.feed.where((f) => f.id == id).lastOrNull;

    return Stack(children: [
      widget.child,
      if (id != null)
        Positioned.fill(
          child: AnimatedBuilder(
            animation: _flash,
            builder: (context, _) => Material(
              color: Color.lerp(Colors.red.shade900, Colors.red.shade600, _flash.value),
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(children: [
                    const Spacer(),
                    const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 96),
                    const SizedBox(height: 16),
                    Text('EMERGENCY ALERT', style: Theme.of(context).textTheme.headlineMedium?.copyWith(color: Colors.white, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 8),
                    if (item != null && !item.mine)
                      Text('from ${item.senderNick} · ${item.hops} hop${item.hops == 1 ? '' : 's'}', style: const TextStyle(color: Colors.white70)),
                    const SizedBox(height: 24),
                    Text(
                      item?.text ?? '',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall?.copyWith(color: Colors.white),
                    ),
                    const Spacer(),
                    FilledButton(
                      style: FilledButton.styleFrom(backgroundColor: Colors.white, foregroundColor: Colors.red.shade900, minimumSize: const Size.fromHeight(64)),
                      onPressed: () => ref.read(appProvider.notifier).acknowledgeAlert(),
                      child: const Text('ACKNOWLEDGE', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ),
    ]);
  }
}
