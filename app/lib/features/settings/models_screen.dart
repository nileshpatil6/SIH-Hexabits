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
    final myLang = languageFor(s.lang);
    final myMissing = !s.hasPack(s.lang, PackKind.stt) || !s.hasPack(s.lang, PackKind.tts);
    final myBusy = s.downloads.keys.any((k) => k.startsWith('${s.lang}-'));

    return Scaffold(
      appBar: AppBar(
        title: const Text('Model packs'),
        actions: [
          IconButton(
            tooltip: 'Import a .itpack file',
            onPressed: s.loading != null ? null : () => _import(ref),
            icon: const Icon(Icons.file_open_outlined),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          if (s.loading != null) ...[const LinearProgressIndicator(), const SizedBox(height: 8), Text(s.loading!), const SizedBox(height: 16)],
          if (myMissing)
            Card(
              color: Theme.of(context).colorScheme.primaryContainer,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Set up ${myLang.english}', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 4),
                  const Text('Downloads speech recognition (~190 MB) and a voice (~110 MB). '
                      'Needs internet once; after that everything works offline.'),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: myBusy ? null : c.downloadMissingForMyLanguage,
                    icon: myBusy
                        ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.download),
                    label: Text(myBusy ? 'Downloading...' : 'Download ${myLang.english}'),
                  ),
                ]),
              ),
            ),
          const SizedBox(height: 8),
          const Text('Tap a download icon to fetch a pack. Long-press an installed pack to remove it.'),
          const SizedBox(height: 12),
          Card(
            child: Column(children: [
              const ListTile(
                dense: true,
                title: Text('Language'),
                trailing: SizedBox(width: 128, child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [Text('STT'), Text('Voice')])),
              ),
              const Divider(height: 1),
              for (final l in languages)
                ListTile(
                  selected: l.code == s.lang,
                  title: Text('${l.native} · ${l.english}'),
                  trailing: SizedBox(
                    width: 128,
                    child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
                      _PackCell(lang: l.code, kind: PackKind.stt, sizeLabel: _size),
                      _PackCell(lang: l.code, kind: PackKind.tts, sizeLabel: _size),
                    ]),
                  ),
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

/// One STT or voice cell: installed tick, download button, or live progress with cancel.
class _PackCell extends ConsumerWidget {
  const _PackCell({required this.lang, required this.kind, required this.sizeLabel});
  final String lang;
  final PackKind kind;
  final String Function(int) sizeLabel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(appProvider);
    final c = ref.read(appProvider.notifier);
    final key = '$lang-${kind.name}';
    final progress = s.downloads[key];
    final pack = s.packs.where((p) => p.lang == lang && p.kind == kind).firstOrNull;
    final what = kind == PackKind.stt ? 'speech recognition' : 'voice';

    if (progress != null) {
      return Tooltip(
        message: 'Downloading ${(progress * 100).toStringAsFixed(0)}%, tap to cancel',
        child: InkResponse(
          onTap: () => c.cancelDownload(lang, kind),
          radius: 24,
          child: SizedBox.square(
            dimension: 40,
            child: Stack(alignment: Alignment.center, children: [
              CircularProgressIndicator(value: progress <= 0 ? null : progress, strokeWidth: 3),
              Text((progress * 100).toStringAsFixed(0), style: const TextStyle(fontSize: 11)),
            ]),
          ),
        ),
      );
    }

    if (pack != null) {
      return Tooltip(
        message: '${pack.engine} · ${sizeLabel(pack.sizeBytes)} · v${pack.version}',
        child: InkResponse(
          radius: 24,
          onLongPress: () async {
            final ok = await showDialog<bool>(
              context: context,
              builder: (ctx) => AlertDialog(
                title: Text('Remove ${languageFor(lang).english} $what?'),
                actions: [
                  TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
                  FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
                ],
              ),
            );
            if (ok == true) await c.deletePack(lang, kind);
          },
          child: const SizedBox.square(dimension: 40, child: Icon(Icons.check_circle, color: Colors.green)),
        ),
      );
    }

    return IconButton(
      tooltip: 'Download ${languageFor(lang).english} $what',
      onPressed: () => c.downloadPack(lang, kind),
      icon: const Icon(Icons.download_for_offline_outlined),
    );
  }
}
