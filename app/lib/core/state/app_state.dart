import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../bridge/itantra_api.g.dart';

final speechApiProvider = Provider((_) => SpeechApi());
final meshApiProvider = Provider((_) => MeshApi());
final modelApiProvider = Provider((_) => ModelApi());

/// One row in the conversation feed: either something we said or something we heard.
class FeedItem {
  FeedItem({
    required this.id,
    required this.text,
    required this.lang,
    required this.mine,
    required this.alert,
    required this.at,
    this.senderNick = '',
    this.hops = 0,
    this.link,
    this.bytesOnWire = 0,
    this.latencyMs = 0,
    this.decodeMs = 0,
    this.utteranceMs = 0,
  });

  final String id;
  final String text;
  final String lang;
  final bool mine;
  final bool alert;
  final DateTime at;
  final String senderNick;
  final int hops;
  final LinkKind? link;
  final int bytesOnWire;
  final int latencyMs;
  final int decodeMs;
  final int utteranceMs;
}

@immutable
class AppState {
  const AppState({
    this.onboarded = false,
    this.nickname = '',
    this.lang = 'hi',
    this.peerId = '',
    this.talkMode = TalkMode.pushToTalk,
    this.meshRunning = false,
    this.capturing = false,
    this.captureLevel = 0,
    this.speakingId,
    this.alertActiveId,
    this.feed = const [],
    this.peers = const [],
    this.packs = const [],
    this.sttReady = false,
    this.ttsReady = false,
    this.loading,
    this.lastError,
    this.downloads = const {},
  });

  final bool onboarded;
  final String nickname;
  final String lang;
  final String peerId;
  final TalkMode talkMode;
  final bool meshRunning;
  final bool capturing;
  final double captureLevel;
  final String? speakingId;
  final String? alertActiveId;
  final List<FeedItem> feed;
  final List<PeerInfo> peers;
  final List<ModelPackInfo> packs;
  final bool sttReady;
  final bool ttsReady;
  final String? loading;
  final String? lastError;

  /// In-flight pack downloads, key `lang-kind` (e.g. `hi-stt`) -> progress 0..1.
  final Map<String, double> downloads;

  bool hasPack(String lang, PackKind kind) =>
      packs.any((p) => p.lang == lang && p.kind == kind && p.installed);

  AppState copyWith({
    bool? onboarded,
    String? nickname,
    String? lang,
    String? peerId,
    TalkMode? talkMode,
    bool? meshRunning,
    bool? capturing,
    double? captureLevel,
    Object? speakingId = _sentinel,
    Object? alertActiveId = _sentinel,
    List<FeedItem>? feed,
    List<PeerInfo>? peers,
    List<ModelPackInfo>? packs,
    bool? sttReady,
    bool? ttsReady,
    Object? loading = _sentinel,
    Object? lastError = _sentinel,
    Map<String, double>? downloads,
  }) {
    return AppState(
      onboarded: onboarded ?? this.onboarded,
      nickname: nickname ?? this.nickname,
      lang: lang ?? this.lang,
      peerId: peerId ?? this.peerId,
      talkMode: talkMode ?? this.talkMode,
      meshRunning: meshRunning ?? this.meshRunning,
      capturing: capturing ?? this.capturing,
      captureLevel: captureLevel ?? this.captureLevel,
      speakingId: identical(speakingId, _sentinel) ? this.speakingId : speakingId as String?,
      alertActiveId: identical(alertActiveId, _sentinel) ? this.alertActiveId : alertActiveId as String?,
      feed: feed ?? this.feed,
      peers: peers ?? this.peers,
      packs: packs ?? this.packs,
      sttReady: sttReady ?? this.sttReady,
      ttsReady: ttsReady ?? this.ttsReady,
      loading: identical(loading, _sentinel) ? this.loading : loading as String?,
      lastError: identical(lastError, _sentinel) ? this.lastError : lastError as String?,
      downloads: downloads ?? this.downloads,
    );
  }
}

const _sentinel = Object();

class AppController extends StateNotifier<AppState> {
  AppController(this._speech, this._mesh, this._models) : super(const AppState()) {
    EventsApi.setUp(_NativeEvents(this));
    _init();
  }

  final SpeechApi _speech;
  final MeshApi _mesh;
  final ModelApi _models;
  Timer? _peerPoll;

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    final id = await _mesh.getIdentity();
    state = state.copyWith(
      onboarded: prefs.getBool('onboarded') ?? false,
      nickname: id.nickname,
      lang: id.lang,
      peerId: id.peerId,
      meshRunning: await _mesh.isRunning(),
    );
    await refreshPacks();
    if (state.onboarded) {
      await startMesh();
      unawaited(prepareEngines());
    }
    _peerPoll = Timer.periodic(const Duration(seconds: 5), (_) async {
      if (!state.meshRunning) return;
      final peers = await _mesh.peers();
      state = state.copyWith(peers: peers);
    });
  }

  @override
  void dispose() {
    _peerPoll?.cancel();
    super.dispose();
  }

  // ---- settings ---------------------------------------------------------

  Future<void> completeOnboarding(String nickname, String lang) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('onboarded', true);
    await _mesh.setNickname(nickname);
    await _mesh.setLanguage(lang);
    state = state.copyWith(onboarded: true, nickname: nickname, lang: lang);
    await startMesh();
    unawaited(prepareEngines());
  }

  Future<void> setNickname(String nick) async {
    await _mesh.setNickname(nick);
    state = state.copyWith(nickname: nick);
  }

  Future<void> setLanguage(String lang) async {
    await _mesh.setLanguage(lang);
    state = state.copyWith(lang: lang, sttReady: false, ttsReady: false);
    unawaited(prepareEngines());
  }

  Future<void> setTalkMode(TalkMode mode) async {
    await _speech.setTalkMode(mode);
    if (state.capturing && mode == TalkMode.pushToTalk) await stopTalking();
    state = state.copyWith(talkMode: mode);
  }

  /// Loads STT and TTS for the current language if packs exist.
  Future<void> prepareEngines() async {
    final lang = state.lang;
    if (state.hasPack(lang, PackKind.stt)) {
      state = state.copyWith(loading: 'Loading $lang speech recognition');
      try {
        await _speech.loadStt(lang);
        state = state.copyWith(sttReady: true);
      } catch (e) {
        _error('stt_load', '$e');
      }
    }
    if (state.hasPack(lang, PackKind.tts)) {
      state = state.copyWith(loading: 'Loading $lang voice');
      try {
        await _speech.loadTts(lang);
        state = state.copyWith(ttsReady: true);
      } catch (e) {
        _error('tts_load', '$e');
      }
    }
    state = state.copyWith(loading: null);
  }

  // ---- mesh -------------------------------------------------------------

  Future<void> startMesh() async {
    await _mesh.start();
    state = state.copyWith(meshRunning: true);
  }

  Future<void> stopMesh() async {
    await _mesh.stop();
    state = state.copyWith(meshRunning: false, peers: const []);
  }

  Future<Diagnostics> diagnostics() => _mesh.diagnostics();

  // ---- talking ----------------------------------------------------------

  Future<void> startTalking() async {
    if (!state.sttReady) {
      _error('stt_missing', 'Install the ${state.lang} speech pack first (Settings > Models).');
      return;
    }
    await _speech.startCapture();
    state = state.copyWith(capturing: true);
  }

  Future<void> stopTalking() async {
    await _speech.stopCapture();
    state = state.copyWith(capturing: false, captureLevel: 0);
  }

  Future<void> sendTyped(String text, {bool alert = false}) async {
    if (text.trim().isEmpty) return;
    await _speech.sendText(text.trim(), alert ? Priority.alert : Priority.normal);
  }

  Future<void> previewVoice(String lang, String text) => _speech.speak(text, lang, Priority.normal);
  Future<void> stopSpeaking() => _speech.stopSpeaking();
  Future<void> acknowledgeAlert() => _mesh.acknowledgeAlert();

  // ---- models -----------------------------------------------------------

  Future<void> refreshPacks() async {
    final packs = await _models.listPacks();
    state = state.copyWith(packs: packs);
  }

  Future<void> importPack(String uriOrPath) async {
    state = state.copyWith(loading: 'Importing pack');
    try {
      await _models.importPack(uriOrPath);
      await refreshPacks();
      await prepareEngines();
    } catch (e) {
      _error('import', '$e');
    } finally {
      state = state.copyWith(loading: null);
    }
  }

  Future<void> downloadPack(String lang, PackKind kind) async {
    final key = '$lang-${kind.name}';
    if (state.downloads.containsKey(key)) return;
    state = state.copyWith(downloads: {...state.downloads, key: 0});
    try {
      await _models.downloadPack(lang, kind);
      await refreshPacks();
      if (lang == state.lang) await prepareEngines();
    } catch (e) {
      if (!'$e'.contains('cancel')) _error('download', 'Could not download $key: ${_short(e)}');
    } finally {
      state = state.copyWith(downloads: Map.of(state.downloads)..remove(key));
    }
  }

  /// Downloads whatever the current language is missing (STT and voice).
  Future<void> downloadMissingForMyLanguage() async {
    final lang = state.lang;
    await Future.wait([
      if (!state.hasPack(lang, PackKind.stt)) downloadPack(lang, PackKind.stt),
      if (!state.hasPack(lang, PackKind.tts)) downloadPack(lang, PackKind.tts),
    ]);
  }

  Future<void> cancelDownload(String lang, PackKind kind) => _models.cancelDownload(lang, kind);

  String _short(Object e) {
    final s = e is PlatformException ? (e.message ?? e.code) : '$e';
    return s.length > 160 ? '${s.substring(0, 160)}...' : s;
  }

  Future<void> deletePack(String lang, PackKind kind) async {
    await _models.deletePack(lang, kind);
    await refreshPacks();
  }

  Future<String> packsDir() => _models.packsDir();

  void clearError() => state = state.copyWith(lastError: null);
  void clearFeed() => state = state.copyWith(feed: const []);

  void _error(String code, String message) {
    debugPrint('[$code] $message');
    state = state.copyWith(lastError: message);
  }

  // ---- native events (routed through _NativeEvents) ------------------------

  void onTranscript(TranscriptEvent event) {
    if (!event.isFinal) return;
    final item = FeedItem(
      id: 'me-${DateTime.now().microsecondsSinceEpoch}',
      text: event.text,
      lang: event.lang,
      mine: true,
      alert: false,
      at: DateTime.now(),
      decodeMs: event.decodeMs,
      utteranceMs: event.utteranceMs,
    );
    state = state.copyWith(feed: [...state.feed, item]);
  }

  void onMessage(IncomingMessage m) {
    final item = FeedItem(
      id: m.id,
      text: m.text,
      lang: m.lang,
      mine: false,
      alert: m.priority == Priority.alert,
      at: DateTime.fromMillisecondsSinceEpoch(m.receivedAtMs),
      senderNick: m.senderNick,
      hops: m.hops,
      link: m.link,
      bytesOnWire: m.bytesOnWire,
      latencyMs: (m.receivedAtMs - m.sentAtMs).clamp(0, 1 << 30),
    );
    state = state.copyWith(feed: [...state.feed, item]);
  }

  void onPeersChanged(List<PeerInfo> peers) => state = state.copyWith(peers: peers);

  void onSpeakingChanged(bool speaking, String messageId) =>
      state = state.copyWith(speakingId: speaking ? messageId : null);

  void onAlertState(bool active, String messageId) =>
      state = state.copyWith(alertActiveId: active ? messageId : null);

  void onCaptureLevel(double rms) => state = state.copyWith(captureLevel: rms);

  void nativeError(String code, String message) => _error(code, message);

  void onPackProgress(String lang, PackKind kind, double progress) {
    final key = '$lang-${kind.name}';
    if (lang.isNotEmpty && state.downloads.containsKey(key)) {
      state = state.copyWith(downloads: {...state.downloads, key: progress});
    } else {
      state = state.copyWith(loading: 'Importing pack ${(progress * 100).toStringAsFixed(0)}%');
    }
  }
}

/// Kotlin -> Dart callbacks. Kept separate because StateNotifier already owns onError.
class _NativeEvents implements EventsApi {
  _NativeEvents(this._c);
  final AppController _c;

  @override
  void onTranscript(TranscriptEvent event) => _c.onTranscript(event);
  @override
  void onMessage(IncomingMessage message) => _c.onMessage(message);
  @override
  void onPeersChanged(List<PeerInfo> peers) => _c.onPeersChanged(peers);
  @override
  void onSpeakingChanged(bool speaking, String messageId) => _c.onSpeakingChanged(speaking, messageId);
  @override
  void onAlertState(bool active, String messageId) => _c.onAlertState(active, messageId);
  @override
  void onCaptureLevel(double rms) => _c.onCaptureLevel(rms);
  @override
  void onError(String code, String message) => _c.nativeError(code, message);
  @override
  void onPackProgress(String lang, PackKind kind, double progress) => _c.onPackProgress(lang, kind, progress);
}

final appProvider = StateNotifierProvider<AppController, AppState>((ref) {
  return AppController(ref.read(speechApiProvider), ref.read(meshApiProvider), ref.read(modelApiProvider));
});
