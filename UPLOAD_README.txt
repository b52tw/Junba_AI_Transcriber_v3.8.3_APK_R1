只更新 APK 的上傳方式：

1. 解壓縮本 ZIP。
2. 將 android 資料夾覆蓋 Repository 根目錄的 android 資料夾。
3. 將 .github/workflows/build-android-v3.8.2-r1.yml 一併上傳。
4. 不需要覆蓋 main.py、app/、Windows spec 或其他 Windows 檔案。
5. 到 GitHub > Actions > Build Junba Android APK v3.8.2 R1 > Run workflow。
6. 完成後下載 Artifact：Junba-v382-APK-R1。

注意：若原本的 combined workflow 也監看 android/**，上傳時它可能也被 GitHub 自動觸發；可直接忽略，這次指定下載 Android-only workflow 的 Junba-v382-APK-R1 即可。
