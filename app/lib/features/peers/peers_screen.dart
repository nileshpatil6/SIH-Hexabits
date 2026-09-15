import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/bridge/itantra_api.g.dart';
import '../../core/languages.dart';
import '../../core/state/app_state.dart';

class PeersScreen extends ConsumerWidget {
  const PeersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(appProvider);
    final c = ref.read(appProvider.notifier);
    final t = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Mesh'),
        actions: [
          Row(children: [
            Text(s.meshRunning ? 'On' : 'Off'),
            Switch(value: s.meshRunning, onChanged: (v) => v ? c.startMesh() : c.stopMesh()),
          ]),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: const CircleAvatar(child: Icon(Icons.person)),
              title: Text(s.nickname),
              subtitle: Text('You · ${languageFor(s.lang).english} · ID ${s.peerId.isEmpty ? '' : s.peerId.substring(0, 8)}'),
            ),
          ),
          const SizedBox(height: 16),
          Text('Nearby phones', style: t.titleMedium),
          const SizedBox(height: 8),
          if (!s.meshRunning)
            const Padding(padding: EdgeInsets.all(24), child: Text('The mesh is off. Turn it on to find nearby phones.'))
          else if (s.peers.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Column(children: [
                CircularProgressIndicator(),
                SizedBox(height: 16),
                Text('Searching over Bluetooth and Wi-Fi Direct. Keep the other phone within ~30 m with iTantra open.', textAlign: TextAlign.center),
              ]),
            )
          else
            for (final p in s.peers) _PeerTile(peer: p),
          const SizedBox(height: 24),
          Text(
            'Messages hop through other iTantra phones up to 7 times, so people out of direct range can still hear you.',
            style: t.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _PeerTile extends StatelessWidget {
  const _PeerTile({required this.peer});
  final PeerInfo peer;

  @override
  Widget build(BuildContext context) {
    final direct = peer.hops <= 1;
    final ago = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(peer.lastSeenMs)).inSeconds;
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: direct ? Colors.green.shade100 : Colors.blueGrey.shade100,
          child: Icon(peer.link == LinkKind.wifiDirect ? Icons.wifi : Icons.bluetooth, color: direct ? Colors.green.shade800 : Colors.blueGrey.shade800),
        ),
        title: Text(peer.nickname),
        subtitle: Text([
          languageFor(peer.lang).english,
          direct ? 'direct' : '${peer.hops} hops',
          if (peer.rssi != 0) '${peer.rssi} dBm',
          'seen ${ago}s ago',
        ].join(' · ')),
      ),
    );
  }
}
