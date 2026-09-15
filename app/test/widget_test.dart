import 'package:flutter_test/flutter_test.dart';
import 'package:itantra/core/languages.dart';

void main() {
  test('all ten SIH 26173 languages are present', () {
    expect(languages.map((l) => l.code).toSet(), {'hi', 'en', 'bn', 'mr', 'gu', 'ta', 'te', 'kn', 'ml', 'or'});
  });

  test('unknown language code falls back to the first entry', () {
    expect(languageFor('xx').code, 'hi');
    expect(languageFor('ta').english, 'Tamil');
  });
}
