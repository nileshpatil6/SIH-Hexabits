// Run: dart run pigeon --input pigeons/itantra.dart
@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/core/bridge/itantra_api.g.dart',
  kotlinOut: 'android/app/src/main/kotlin/itantra/bridge/ItantraApi.g.kt',
  kotlinOptions: KotlinOptions(package: 'itantra.bridge'),
  dartPackageName: 'itantra',
))
library;

import 'package:pigeon/pigeon.dart';

/// How the microphone is gated.
enum TalkMode { pushToTalk, continuous }

/// Message priority. `alert` is spoken at max volume and cannot be muted.
enum Priority { normal, alert }

enum PackKind { stt, tts, vad }

/// Which radio carried the packet.
enum LinkKind { ble, wifiDirect, local }

class ModelPackInfo {
  ModelPackInfo({
    required this.lang,
    required this.kind,
    required this.engine,
    required this.installed,
    required this.sizeBytes,
    required this.version,
    required this.path,
  });
  String lang;
  PackKind kind;
  String engine; // nemo_ctc | vits
  bool installed;
  int sizeBytes;
  String version;
  String path;
}

class PeerInfo {
  PeerInfo({
    required this.peerId,
    required this.nickname,
    required this.lang,
    required this.rssi,
    required this.hops,
    required this.link,
    required this.lastSeenMs,
  });
  String peerId;
  String nickname;
  String lang;
  int rssi;
  int hops;
  LinkKind link;
  int lastSeenMs;
}

class IncomingMessage {
  IncomingMessage({
    required this.id,
    required this.senderId,
    required this.senderNick,
    required this.lang,
    required this.text,
    required this.priority,
    required this.sentAtMs,
    required this.receivedAtMs,
    required this.hops,
    required this.link,
    required this.bytesOnWire,
  });
  String id;
  String senderId;
  String senderNick;
  String lang;
  String text;
  Priority priority;
  int sentAtMs;
  int receivedAtMs;
  int hops;
  LinkKind link;
  int bytesOnWire;
}

class TranscriptEvent {
  TranscriptEvent({
    required this.text,
    required this.isFinal,
    required this.lang,
    required this.utteranceMs,
    required this.decodeMs,
  });
  String text;
  bool isFinal;
  String lang;
  int utteranceMs;
  int decodeMs;
}

class Diagnostics {
  Diagnostics({
    required this.sttLoaded,
    required this.ttsLoaded,
    required this.sttRtf,
    required this.ttsRtf,
    required this.nativeHeapMb,
    required this.packetsSent,
    required this.packetsReceived,
    required this.packetsRelayed,
    required this.bytesSent,
    required this.bytesReceived,
    required this.blePeers,
    required this.wifiPeers,
    required this.lastE2eLatencyMs,
  });
  String sttLoaded;
  String ttsLoaded;
  double sttRtf;
  double ttsRtf;
  double nativeHeapMb;
  int packetsSent;
  int packetsReceived;
  int packetsRelayed;
  int bytesSent;
  int bytesReceived;
  int blePeers;
  int wifiPeers;
  int lastE2eLatencyMs;
}

class Identity {
  Identity({required this.peerId, required this.nickname, required this.lang});
  String peerId;
  String nickname;
  String lang;
}

@HostApi()
abstract class SpeechApi {
  /// Loads STT for [lang] (unloading any other). Blocking, ~1-3 s.
  @async
  void loadStt(String lang);

  /// Ensures a TTS voice for [lang] is resident.
  @async
  void loadTts(String lang);

  void setTalkMode(TalkMode mode);

  /// Start capturing. In PTT mode the caller holds and releases.
  void startCapture();

  /// Stop capturing and flush any pending utterance through STT.
  void stopCapture();

  /// Speak locally (used for previews and the receive path fallback).
  @async
  void speak(String text, String lang, Priority priority);

  void stopSpeaking();

  /// Send text that was typed rather than spoken.
  void sendText(String text, Priority priority);

  bool isSpeaking();
}

@HostApi()
abstract class MeshApi {
  Identity getIdentity();
  void setNickname(String nickname);
  void setLanguage(String lang);
  void start();
  void stop();
  bool isRunning();
  List<PeerInfo> peers();
  Diagnostics diagnostics();
  /// Acknowledge (silence) the currently playing alert.
  void acknowledgeAlert();
}

@HostApi()
abstract class ModelApi {
  List<ModelPackInfo> listPacks();
  /// Import a `.itpack` zip from a content:// or file path. Returns the manifest.
  @async
  ModelPackInfo importPack(String uriOrPath);
  void deletePack(String lang, PackKind kind);
  /// Directory where packs live, for the file picker hint.
  String packsDir();
}

@FlutterApi()
abstract class EventsApi {
  void onTranscript(TranscriptEvent event);
  void onMessage(IncomingMessage message);
  void onPeersChanged(List<PeerInfo> peers);
  void onSpeakingChanged(bool speaking, String messageId);
  void onAlertState(bool active, String messageId);
  void onCaptureLevel(double rms);
  void onError(String code, String message);
  void onPackProgress(String lang, PackKind kind, double progress);
}

