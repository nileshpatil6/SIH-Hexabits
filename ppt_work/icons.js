const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const sharp = require('sharp');
const fs = require('fs');
const md = require('react-icons/md');
const fa = require('react-icons/fa6');
const names = {
  mic: md.MdMic, stt: md.MdRecordVoiceOver, packet: md.MdMarkEmailRead, mesh: md.MdHub, speaker: md.MdVolumeUp,
  shield: md.MdVerifiedUser, alert: md.MdCampaign, offline: md.MdCloudOff, lang: md.MdTranslate, battery: md.MdBatteryChargingFull,
  memory: md.MdMemory, speed: md.MdSpeed, groups: md.MdGroups, access: md.MdAccessibilityNew, eco: md.MdEco, rupee: md.MdCurrencyRupee,
  satellite: md.MdSatelliteAlt, sos: md.MdSos, warning: md.MdWarningAmber, check: md.MdCheckCircle, book: md.MdMenuBook, code: md.MdCode,
  phone: md.MdPhoneAndroid, timer: md.MdTimer, lock: md.MdLock, route: md.MdRoute, storage: md.MdSdStorage, science: md.MdScience,
  link: md.MdLink, github: fa.FaGithub, public: md.MdPublic, wave: md.MdGraphicEq, bt: md.MdBluetooth, wifi: md.MdWifiTethering,
  hearing: md.MdHearing, layers: md.MdLayers, rocket: md.MdRocketLaunch, handshake: md.MdHandshake, flood: md.MdFlood, apps: md.MdApps,
  tune: md.MdTune, bolt: md.MdBolt, arrow: md.MdArrowForward, database: md.MdDataset, build: md.MdBuild, psychology: md.MdPsychology,
};
const colors = { w: '#FFFFFF', n: '#1F3B73', o: '#E8711A', g: '#1E8E3E', b: '#0070C0', s: '#5B6B7F' };
fs.mkdirSync('img/icons', { recursive: true });
(async () => {
  for (const [k, C] of Object.entries(names)) {
    for (const [ck, cv] of Object.entries(colors)) {
      const svg = renderToStaticMarkup(React.createElement(C, { color: cv, size: 256 }));
      await sharp(Buffer.from(svg)).resize(256, 256).png().toFile(`img/icons/${k}_${ck}.png`);
    }
  }
  console.log('icons', Object.keys(names).length * Object.keys(colors).length);
})();
