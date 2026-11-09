import fs from 'node:fs';
import path from 'node:path';

const manifestPath = path.resolve('android/app/src/main/AndroidManifest.xml');

if (fs.existsSync(manifestPath)) {
  let manifest = fs.readFileSync(manifestPath, 'utf8');

  const permissions = `
    <!-- 网络与通信权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <!-- 存储与文件读写特权 -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <!-- 系统级保活与常驻服务 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
`;

  if (!manifest.includes('android.permission.MANAGE_EXTERNAL_STORAGE')) {
    manifest = manifest.replace('<application', `${permissions}\n    <application`);
  }

  if (!manifest.includes('android:usesCleartextTraffic')) {
    manifest = manifest.replace(
      '<application',
      '<application\n        android:usesCleartextTraffic="true"',
    );
  }

  fs.writeFileSync(manifestPath, manifest, 'utf8');
  console.log('✅ AndroidManifest.xml permissions injected successfully!');
} else {
  console.log('⚠️ AndroidManifest.xml not found, skipping injection.');
}

// 让 APK 的版本号跟随 package.json，避免每次构建都显示 1.0
const gradlePath = path.resolve('android/app/build.gradle');
if (fs.existsSync(gradlePath)) {
  const pkg = JSON.parse(fs.readFileSync(path.resolve('package.json'), 'utf8'));
  const [major, minor, patch] = String(pkg.version || '1.0.0').split('.').map((n) => Number(n) || 0);
  const versionCode = major * 10000 + minor * 100 + patch;
  let gradle = fs.readFileSync(gradlePath, 'utf8');
  gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${versionCode}`);
  gradle = gradle.replace(/versionName\s+"[^"]*"/, `versionName "${pkg.version}"`);
  fs.writeFileSync(gradlePath, gradle, 'utf8');
  console.log(`✅ Version set to ${pkg.version} (code ${versionCode})`);
}
