import 'package:permission_handler/permission_handler.dart';

/// Everything the radio + mic path needs. Location is only required on
/// Android 12 and lower for BLE scans; permission_handler ignores it elsewhere.
Future<bool> requestAllPermissions() async {
  final statuses = await [
    Permission.microphone,
    Permission.bluetoothScan,
    Permission.bluetoothAdvertise,
    Permission.bluetoothConnect,
    Permission.nearbyWifiDevices,
    Permission.locationWhenInUse,
    Permission.notification,
  ].request();
  final required = [
    Permission.microphone,
    Permission.bluetoothScan,
    Permission.bluetoothAdvertise,
    Permission.bluetoothConnect,
  ];
  return required.every((p) => statuses[p]?.isGranted ?? false);
}
