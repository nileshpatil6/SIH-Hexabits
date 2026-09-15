import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/bridge/itantra_api.g.dart';
import '../../core/languages.dart';
import '../../core/state/app_state.dart';

class ModelsScreen extends ConsumerWidget {
  const ModelsScreen({super.key});

  Future<void> _import(WidgetRef ref) async {
    final res = await FilePicker.platform.pickFiles(type: FileType.any, withData: false);
    final path = res?.files.single.path;
    if (path != null) await ref.read(appProvider.notifier).importPack(path);
  }

  String _size(int bytes) => bytes > 1 << 20 ? '${(bytes / (1 << 20)).toStringAsFixed(0)} MB' : '${(bytes / 1024).toStringAsFixed(0)} KB';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(appProvider);
    final c = ref.read(appProvider.notifier);

    ModelPackInfo? find(String lang, PackKind kind) =>
        s.packs.where((p) => p.lang == lang && p.kind == kind).firstOrNull;

    Widget cell(String lang, PackKind kind) {
      final p = find(lang, kind);
      if (p == null) return const Icon(Icons.remove_circle_outline, color: Colors.grey, size: 20);
      return Tooltip(
        message: '${p.engine} · ${_size(p.sizeBytes)} · v${p.version}',
        child: const Icon(Icons.check_circle, color: Colors.green, size: 20),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Model packs')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: s.loading != null ? null : () => _import(ref),
        icon: const Icon(Icons.file_open),
        label: const Text('Import .itpack'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
        children: [
          if (s.loading != null) ...[LinearProgressIndicator(), const SizedBox(height: 8), Text(s.loading!), const SizedBox(height: 16)],
          const Text(
            'Packs are copied to the phone once (from a laptop, SD card, or another phone) and then work fully offline. '
            'STT packs are about 130 MB each, voice packs 30 to 80 MB. Install only the languages you need.',
          ),
          const SizedBox(height: 16),
          Card(
            child: Column(children: [
              const ListTile(
                dense: true,
                title: Text('Language'),
                trailing: SizedBox(width: 120, child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [Text('STT'), Text('Voice')])),
              ),
              const Divider(height: 1),
              for (final l in languages)
                ListTile(
                  selected: l.code == s.lang,
                  title: Text('${l.native} · ${l.english}'),
                  trailing: SizedBox(
                    width: 120,
                    child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [cell(l.code, PackKind.stt), cell(l.code, PackKind.tts)]),
                  ),
                  onLongPress: () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: Text('Remove ${l.english} STT pack?'),
                        content: const Text('Voice packs shared by several languages are kept.'),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
                          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
                        ],
                      ),
                    );
                    if (ok == true) await c.deletePack(l.code, PackKind.stt);
                  },
                ),
            ]),
          ),
          const SizedBox(height: 16),
          FutureBuilder(
            future: c.packsDir(),
            builder: (_, snap) => Text('Storage: ${snap.data ?? ''}', style: Theme.of(context).textTheme.bodySmall),
          ),
        ],
      ),
    );
  }
}
