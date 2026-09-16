import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/bridge/itantra_api.g.dart';
import '../../core/languages.dart';
import '../../core/state/app_state.dart';

class TalkScreen extends ConsumerStatefulWidget {
  const TalkScreen({super.key});

  @override
  ConsumerState<TalkScreen> createState() => _TalkScreenState();
}

class _TalkScreenState extends ConsumerState<TalkScreen> {
  final _scroll = ScrollController();
  final _input = TextEditingController();

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
      }
    });
  }

  Future<void> _sendAlert() async {
    final ctrl = TextEditingController();
    final text = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.warning_amber_rounded, color: Colors.red, size: 40),
        title: const Text('Send emergency alert'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Every phone in range will announce this at full volume, even on silent, and relay it onward.'),
            const SizedBox(height: 12),
            TextField(controller: ctrl, autofocus: true, maxLines: 3, decoration: const InputDecoration(border: OutlineInputBorder(), hintText: 'e.g. Flood water rising near school, move to high ground')),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, ctrl.text),
            child: const Text('SEND ALERT'),
          ),
        ],
      ),
    );
    if (text != null && text.trim().isNotEmpty) {
      await ref.read(appProvider.notifier).sendTyped(text, alert: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(appProvider);
    final c = ref.read(appProvider.notifier);
    ref.listen(appProvider.select((s) => s.feed.length), (_, _) => _scrollToEnd());
    final lang = languageFor(s.lang);
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Text('Talk · ${lang.native}'),
        actions: [
          IconButton(
            tooltip: 'Emergency alert',
            onPressed: _sendAlert,
            icon: const Icon(Icons.campaign, color: Colors.red),
          ),
          PopupMenuButton<String>(
            onSelected: (v) {
              if (v == 'clear') c.clearFeed();
              if (v == 'diag') context.push('/diagnostics');
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'diag', child: Text('Diagnostics')),
              PopupMenuItem(value: 'clear', child: Text('Clear conversation')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          _StatusStrip(state: s),
          if (s.loading != null) LinearProgressIndicator(semanticsLabel: s.loading),
          Expanded(
            child: s.feed.isEmpty
                ? _EmptyFeed(state: s)
                : ListView.builder(
                    controller: _scroll,
                    padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
                    itemCount: s.feed.length,
                    itemBuilder: (_, i) => _Bubble(item: s.feed[i], speaking: s.speakingId == s.feed[i].id),
                  ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    minLines: 1,
                    maxLines: 3,
                    decoration: const InputDecoration(hintText: 'Or type a message', border: OutlineInputBorder(), isDense: true),
                    onSubmitted: (v) {
                      c.sendTyped(v);
                      _input.clear();
                    },
                  ),
                ),
                IconButton(
                  onPressed: () {
                    c.sendTyped(_input.text);
                    _input.clear();
                  },
                  icon: const Icon(Icons.send),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                SegmentedButton<TalkMode>(
                  segments: const [
                    ButtonSegment(value: TalkMode.pushToTalk, label: Text('PTT'), icon: Icon(Icons.touch_app)),
                    ButtonSegment(value: TalkMode.continuous, label: Text('Call'), icon: Icon(Icons.call)),
                  ],
                  selected: {s.talkMode},
                  onSelectionChanged: (v) => c.setTalkMode(v.first),
                  showSelectedIcon: false,
                ),
                const Spacer(),
                if (s.speakingId != null)
                  IconButton.filledTonal(onPressed: c.stopSpeaking, icon: const Icon(Icons.volume_off), tooltip: 'Stop playback'),
                const SizedBox(width: 8),
                _TalkButton(state: s, scheme: scheme),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _TalkButton extends ConsumerWidget {
  const _TalkButton({required this.state, required this.scheme});
  final AppState state;
  final ColorScheme scheme;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = ref.read(appProvider.notifier);
    final active = state.capturing;
    final level = (state.captureLevel * 8).clamp(0.0, 1.0);
    final color = active ? Colors.red : scheme.primary;

    final button = AnimatedContainer(
      duration: const Duration(milliseconds: 90),
      width: 88,
      height: 88,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: color,
        boxShadow: [BoxShadow(color: color.withValues(alpha: 0.45), blurRadius: 8 + 28 * level, spreadRadius: 2 + 10 * level)],
      ),
      child: Icon(active ? Icons.mic : Icons.mic_none, color: Colors.white, size: 40),
    );

    if (state.talkMode == TalkMode.pushToTalk) {
      return Semantics(
        button: true,
        label: 'Hold to talk',
        child: GestureDetector(
          onTapDown: (_) {
            HapticFeedback.mediumImpact();
            c.startTalking();
          },
          onTapUp: (_) => c.stopTalking(),
          onTapCancel: () => c.stopTalking(),
          child: button,
        ),
      );
    }
    return Semantics(
      button: true,
      label: active ? 'End call' : 'Start call',
      child: GestureDetector(
        onTap: () {
          HapticFeedback.mediumImpact();
          active ? c.stopTalking() : c.startTalking();
        },
        child: button,
      ),
    );
  }
}

class _StatusStrip extends StatelessWidget {
  const _StatusStrip({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme.labelMedium;
    Widget chip(IconData icon, String label, bool ok) => Padding(
          padding: const EdgeInsets.only(right: 12),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 16, color: ok ? Colors.green : Colors.orange),
            const SizedBox(width: 4),
            Text(label, style: t),
          ]),
        );
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: [
          chip(Icons.hub, '${state.peers.length} nearby', state.peers.isNotEmpty),
          chip(Icons.hearing, state.sttReady ? 'STT ready' : 'STT missing', state.sttReady),
          chip(Icons.record_voice_over, state.ttsReady ? 'Voice ready' : 'Voice missing', state.ttsReady),
          chip(Icons.cloud_off, 'Offline', true),
        ]),
      ),
    );
  }
}

class _EmptyFeed extends StatelessWidget {
  const _EmptyFeed({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final missing = !state.sttReady || !state.ttsReady;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(missing ? Icons.download_for_offline_outlined : Icons.settings_voice_outlined, size: 56),
          const SizedBox(height: 12),
          Text(
            missing
                ? 'Speech models for ${languageFor(state.lang).english} are not installed yet.'
                : state.talkMode == TalkMode.pushToTalk
                    ? 'Hold the mic button and speak. Each sentence is sent as soon as you pause.'
                    : 'Tap the mic to open a call. Everything you say is sent sentence by sentence.',
            textAlign: TextAlign.center,
          ),
          if (missing) ...[
            const SizedBox(height: 12),
            FilledButton.tonal(onPressed: () => context.push('/models'), child: const Text('Download speech models')),
          ],
        ]),
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.item, required this.speaking});
  final FeedItem item;
  final bool speaking;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final t = Theme.of(context).textTheme;
    final bg = item.alert
        ? Colors.red.shade100
        : item.mine
            ? scheme.primaryContainer
            : scheme.secondaryContainer;
    final fg = item.alert ? Colors.red.shade900 : (item.mine ? scheme.onPrimaryContainer : scheme.onSecondaryContainer);
    final meta = <String>[
      DateFormat.Hms().format(item.at),
      languageFor(item.lang).english,
      if (!item.mine) ...[
        '${item.hops} hop${item.hops == 1 ? '' : 's'}',
        item.link == LinkKind.wifiDirect ? 'Wi-Fi' : 'BLE',
        '${item.bytesOnWire} B',
        '${item.latencyMs} ms',
      ],
      if (item.mine && item.decodeMs > 0) 'STT ${item.decodeMs} ms',
    ].join(' · ');

    return Align(
      alignment: item.mine ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.82),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 8),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(16),
            border: speaking ? Border.all(color: scheme.primary, width: 2) : null,
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (!item.mine)
              Row(mainAxisSize: MainAxisSize.min, children: [
                if (item.alert) const Icon(Icons.warning_amber_rounded, size: 16, color: Colors.red),
                Text(item.senderNick, style: t.labelLarge?.copyWith(color: fg, fontWeight: FontWeight.w600)),
                if (speaking) ...[const SizedBox(width: 6), Icon(Icons.volume_up, size: 16, color: fg)],
              ]),
            Text(item.text, style: t.bodyLarge?.copyWith(color: fg, fontSize: 18)),
            const SizedBox(height: 4),
            Text(meta, style: t.labelSmall?.copyWith(color: fg.withValues(alpha: 0.7))),
          ]),
        ),
      ),
    );
  }
}
