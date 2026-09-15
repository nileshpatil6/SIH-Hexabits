import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/bridge/itantra_api.g.dart';
import '../../core/languages.dart';
import '../../core/state/app_state.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  Future<void> _editName(BuildContext context, WidgetRef ref, String current) async {
    final ctrl = TextEditingController(text: current);
    final v = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Name or call sign'),
        content: TextField(controller: ctrl, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text.trim()), child: const Text('Save')),
        ],
      ),
    );
    if (v != null && v.isNotEmpty) await ref.read(appProvider.notifier).setNickname(v);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(appProvider);
    final c = ref.read(appProvider.notifier);
    final lang = languageFor(s.lang);

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(children: [
        ListTile(
          leading: const Icon(Icons.badge_outlined),
          title: const Text('Name'),
          subtitle: Text(s.nickname),
          onTap: () => _editName(context, ref, s.nickname),
        ),
        ListTile(
          leading: const Icon(Icons.translate),
          title: const Text('My language'),
          subtitle: Text('${lang.native} · ${lang.english}'),
          trailing: DropdownButton<String>(
            value: s.lang,
            underline: const SizedBox(),
            onChanged: (v) => v == null ? null : c.setLanguage(v),
            items: [for (final l in languages) DropdownMenuItem(value: l.code, child: Text(l.english))],
          ),
        ),
        ListTile(
          leading: const Icon(Icons.record_voice_over_outlined),
          title: const Text('Test my voice'),
          subtitle: Text(lang.sample),
          trailing: const Icon(Icons.play_arrow),
          enabled: s.hasPack(s.lang, PackKind.tts),
          onTap: () => c.previewVoice(s.lang, lang.sample),
        ),
        SwitchListTile(
          secondary: const Icon(Icons.call),
          title: const Text('Call mode (no push-to-talk)'),
          subtitle: const Text('Mic stays open; each sentence is sent when you pause.'),
          value: s.talkMode == TalkMode.continuous,
          onChanged: (v) => c.setTalkMode(v ? TalkMode.continuous : TalkMode.pushToTalk),
        ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.download_for_offline_outlined),
          title: const Text('Model packs'),
          subtitle: Text('${s.packs.length} installed'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => context.push('/models'),
        ),
        ListTile(
          leading: const Icon(Icons.speed),
          title: const Text('Diagnostics'),
          subtitle: const Text('Latency, bandwidth, model speed, memory'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => context.push('/diagnostics'),
        ),
        const Divider(),
        const AboutListTile(
          icon: Icon(Icons.info_outline),
          applicationName: 'iTantra',
          applicationVersion: '0.1.0',
          aboutBoxChildren: [
            Text('SIH 26173 · Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links.\n\n'
                'STT: AI4Bharat IndicConformer (MIT). TTS: AI4Bharat VITS Rasa13 (CC-BY-4.0) and Meta MMS-TTS (CC-BY-NC-4.0). '
                'Runtime: sherpa-onnx (Apache-2.0).'),
          ],
        ),
      ]),
    );
  }
}
