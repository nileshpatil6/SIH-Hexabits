import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/bridge/itantra_api.g.dart';
import '../../core/state/app_state.dart';

/// Numbers for judges and for tuning: STT/TTS real-time factor, memory,
/// packet counters, and the bandwidth saved versus sending audio.
class DiagnosticsScreen extends ConsumerStatefulWidget {
  const DiagnosticsScreen({super.key});

  @override
  ConsumerState<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends ConsumerState<DiagnosticsScreen> {
  Diagnostics? _d;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _poll();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _poll());
  }

  Future<void> _poll() async {
    final d = await ref.read(appProvider.notifier).diagnostics();
    if (mounted) setState(() => _d = d);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final d = _d;
    final feed = ref.watch(appProvider).feed;
    final received = feed.where((f) => !f.mine).toList();
    // Opus at 16 kbps is a common "low bitrate voice" baseline; compare what the
    // same utterances would have cost as audio.
    final spokenMs = feed.where((f) => f.mine).fold<int>(0, (a, f) => a + f.utteranceMs);
    final textBytes = feed.where((f) => f.mine).fold<int>(0, (a, f) => a + f.text.length * 3 + 40);
    final opusBytes = (spokenMs / 1000 * 16000 / 8).round();
    final avgLatency = received.isEmpty ? 0 : received.map((f) => f.latencyMs).reduce((a, b) => a + b) ~/ received.length;

    Widget row(String k, String v) => ListTile(dense: true, title: Text(k), trailing: Text(v, style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()])));

    return Scaffold(
      appBar: AppBar(title: const Text('Diagnostics')),
      body: d == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(children: [
              const _Header('Speech'),
              row('STT model loaded', d.sttLoaded.isEmpty ? 'none' : d.sttLoaded),
              row('STT real-time factor', d.sttRtf.toStringAsFixed(3)),
              row('TTS voices loaded', d.ttsLoaded.isEmpty ? 'none' : d.ttsLoaded),
              row('TTS real-time factor', d.ttsRtf.toStringAsFixed(3)),
              row('Native memory', '${d.nativeHeapMb.toStringAsFixed(0)} MB'),
              const _Header('Mesh'),
              row('Neighbours (BLE / Wi-Fi)', '${d.blePeers} / ${d.wifiPeers}'),
              row('Packets sent / received / relayed', '${d.packetsSent} / ${d.packetsReceived} / ${d.packetsRelayed}'),
              row('Bytes sent / received', '${d.bytesSent} / ${d.bytesReceived}'),
              row('Last end-to-end latency', '${d.lastE2eLatencyMs} ms'),
              row('Average latency (this session)', '$avgLatency ms'),
              const _Header('Bandwidth vs audio'),
              row('Speech sent', '${(spokenMs / 1000).toStringAsFixed(1)} s'),
              row('As text (approx.)', '$textBytes B'),
              row('As 16 kbps Opus audio', '$opusBytes B'),
              row('Saving', opusBytes == 0 ? '-' : '${(opusBytes / textBytes.clamp(1, 1 << 30)).toStringAsFixed(0)}x smaller'),
            ]),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Theme.of(context).colorScheme.primary)),
      );
}
