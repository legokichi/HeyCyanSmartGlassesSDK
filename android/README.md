# HeyCyan Glasses SDK - Android

Android SDK for controlling HeyCyan smart glasses via Bluetooth Low Energy (BLE).

## Files

- `glasses_sdk_20250723_v01.aar` - Android SDK library (AAR format)
- `GlassesSDKSample/` - Sample Android application demonstrating SDK usage
- `Android_SDK_Development_Guide_CN.pdf` - SDK documentation (Chinese)

## Quick Start

1. Add the AAR file to your Android project's `libs` directory
2. Add the dependency in your app's `build.gradle`:
   ```gradle
   implementation files('libs/glasses_sdk_20250723_v01.aar')
   ```
3. See the `GlassesSDKSample` project for implementation examples

## Requirements

- Android 5.0+ (API level 21)
- Bluetooth Low Energy support
- Android Studio

## Sample Application

The `GlassesSDKSample` directory contains a complete Android application demonstrating:
- Device scanning and connection
- Photo/video/audio capture controls
- Battery status monitoring
- AI image generation
- Device information retrieval

### メディアダウンロードフロー

サンプルアプリの `Download Media` は、メガネ内に残っているファイルをAndroidのメディアライブラリへ保存し、その後メガネ側へ削除要求を送ります。

1. アプリはBLEでメガネをスキャンして接続します。撮影、転送モード開始、状態取得、リモート削除要求などの制御コマンドはBLEで送ります。
2. ダウンロード時は、BLEでメガネへ転送モード開始を要求し、その後Wi-Fi P2Pの探索と接続を開始します。
3. メガネはWi-Fi P2Pの接続先IPを応答または通知し、スマートフォンはそのP2Pグループへ接続します。
4. スマートフォンは `http://<glasses-ip>/files/media.config` を読みます。
5. `media.config` に列挙された各ファイルを `http://<glasses-ip>/files/<file-name>` からHTTPでダウンロードします。
6. ダウンロードしたファイルは `MediaStore` に保存します。
   - 画像: `Pictures/HeyCyan`
   - 動画: `Movies/HeyCyan`
   - 音声: `Music/HeyCyan`
   - 種別不明のファイル: `Downloads/HeyCyan`
7. バッチ全体の保存が終わった後、アプリは転送モードを終了し、Wi-Fi P2Pを切断してから、保存済みリモートファイルに対してBLEで削除要求を送ります。

補足:
- Android OSのBluetooth設定でペアリング済みまたは接続済みに見えていても、それだけではSDKのBLE GATT接続は確立されません。このサンプルではアプリ内のBLE scan/connectで `BleOperateManager` の接続を作り、メガネ独自のGATTサービスに対してコマンド送信と通知受信を行います。
- Classic BluetoothのペアリングはHTTPファイル転送経路そのものには含まれません。サンプルはAndroidのClassic discovery結果を監視し、記憶している対象デバイスが見つかった場合に `createBondBluetoothJieLi(...)` を呼びますが、上記のダウンロード処理はClassic Bluetooth接続の完了を明示的には待ちません。
- 動作確認したメガネでは、リモートメディアが空のとき `media.config` がHTTP 500を返します。このサンプルでは空リストとして扱います。
- Wi-Fi転送後のリモート削除コールバックはタイムアウトすることがあります。そのためサンプルは削除完了を無期限に待たず、BLE削除要求を送出できたかをログに出します。
- 同期ありの定期撮影は、撮影画像に対して同じダウンロード・削除フローを使います。capture-onlyモードは意図的にダウンロードと削除を行わず、ファイルをメガネ側に残します。

## Support

For technical support or questions about the Android SDK, please see our GitHub issues or contact the HeyCyan development team.
