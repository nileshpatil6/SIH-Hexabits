/// The ten languages required by SIH 26173, keyed by ISO 639-1 code.
class Language {
  const Language(this.code, this.english, this.native, this.sample);
  final String code;
  final String english;
  final String native;

  /// A short phrase used for the TTS "test voice" button.
  final String sample;
}

const languages = <Language>[
  Language('hi', 'Hindi', 'हिन्दी', 'नमस्ते, यह एक परीक्षण संदेश है।'),
  Language('en', 'English', 'English', 'Hello, this is a test message.'),
  Language('bn', 'Bengali', 'বাংলা', 'নমস্কার, এটি একটি পরীক্ষামূলক বার্তা।'),
  Language('mr', 'Marathi', 'मराठी', 'नमस्कार, हा एक चाचणी संदेश आहे.'),
  Language('gu', 'Gujarati', 'ગુજરાતી', 'નમસ્તે, આ એક પરીક્ષણ સંદેશ છે.'),
  Language('ta', 'Tamil', 'தமிழ்', 'வணக்கம், இது ஒரு சோதனை செய்தி.'),
  Language('te', 'Telugu', 'తెలుగు', 'నమస్కారం, ఇది ఒక పరీక్ష సందేశం.'),
  Language('kn', 'Kannada', 'ಕನ್ನಡ', 'ನಮಸ್ಕಾರ, ಇದು ಒಂದು ಪರೀಕ್ಷಾ ಸಂದೇಶ.'),
  Language('ml', 'Malayalam', 'മലയാളം', 'നമസ്കാരം, ഇത് ഒരു പരീക്ഷണ സന്ദേശമാണ്.'),
  Language('or', 'Odia', 'ଓଡ଼ିଆ', 'ନମସ୍କାର, ଏହା ଏକ ପରୀକ୍ଷା ବାର୍ତ୍ତା।'),
];

Language languageFor(String code) =>
    languages.firstWhere((l) => l.code == code, orElse: () => languages.first);
