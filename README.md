# 🔋 Battery Alarm — Android App

Ứng dụng theo dõi mức pin và cảnh báo tự động bằng **giọng nữ tiếng Việt (Google TTS)**.

---

## ✨ Tính năng

| Tính năng | Mô tả |
|---|---|
| 🔴 Cảnh báo pin thấp | Báo động khi pin xuống dưới ngưỡng tùy chỉnh |
| 🟢 Cảnh báo pin đầy | Báo động khi pin đạt ngưỡng đầy trong khi vẫn đang sạc |
| 🎙️ Giọng nữ TTS tiếng Việt | Phát câu _"CẢNH BÁO MỨC PIN"_ trong 5 giây |
| 🔁 Nhắc lại 10 phút/lần | Nếu chưa cắm/rút sạc sau cảnh báo |
| 🚀 Chạy nền liên tục | Foreground Service không bị hệ thống kill |
| ⚙️ Tự khởi động lại | Boot receiver + service restart receiver |
| 📱 Tùy chỉnh ngưỡng | Nhập % tùy ý từ 0–100 |

---

## 📁 Cấu trúc dự án

```
BatteryAlarm/
├── app/src/main/
│   ├── java/com/batteryalarm/
│   │   ├── MainActivity.kt           # Giao diện chính
│   │   ├── BatteryMonitorService.kt  # Foreground service theo dõi pin
│   │   ├── BootReceiver.kt           # Tự khởi động sau reboot
│   │   └── ServiceRestartReceiver.kt # Khởi động lại service khi bị kill
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   ├── drawable/input_bg.xml
│   │   ├── values/strings.xml
│   │   └── values/themes.xml
│   └── AndroidManifest.xml
├── .github/workflows/build.yml       # GitHub Actions → tự build APK
└── README.md
```

---

## 🚀 Build APK với GitHub Actions

1. **Push code lên GitHub:**
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/USERNAME/BatteryAlarm.git
   git push -u origin main
   ```

2. **GitHub Actions tự động chạy** → vào tab **Actions** trên GitHub

3. **Tải APK** từ tab **Artifacts** sau khi build xong

---

## 📋 Yêu cầu hệ thống

- Android 8.0+ (API 26+)
- Google TTS cài sẵn (mặc định trên hầu hết máy Android)
- Cho phép ứng dụng bỏ qua tối ưu hóa pin khi được hỏi

---

## 🔧 Cách dùng

1. Cài APK
2. Mở app → Nhập % pin thấp và % pin đầy
3. Bật/tắt từng loại cảnh báo theo ý muốn
4. Nhấn **LƯU CÀI ĐẶT**
5. App chạy nền tự động — có thể tắt màn hình bình thường
